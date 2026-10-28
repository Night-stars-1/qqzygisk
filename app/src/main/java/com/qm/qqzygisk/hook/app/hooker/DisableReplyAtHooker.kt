package com.qm.qqzygisk.hook.app.hooker

import android.os.Looper
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import com.v7878.vmtools.Hooks.EntryPointType
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.invoke.MethodHandle
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 禁止“回复引用消息”时自动 @ 被引用消息的发送者。
 *
 * 引用点击的完整链路（已按 QQ 9.3.50 / 9.3.75 / 9.3.70 的 APK 核对）：
 * 1. 回复 VM 的 intent 入口收到 `AIOMsgSendEvent$MsgOnClickReplyEvent`；
 * 2. 该入口先记录引用状态并发出引用 UI/键盘状态，再在群聊且对方可 @ 时向事件总线
 *    派发 `InputAtMsgIntent$InsertAtMemberSpan` 完成自动 @；
 * 3. @ 事件由 `InputAtVMDelegate` 的静态 intent 入口处理，把 at span 插进输入框。
 *
 * 所以这里只做两件事：
 * - reply 入口：仅当实参的**精确类型**是 `MsgOnClickReplyEvent` 时，在**原方法调用期间**
 *   打开线程本地作用域；原方法照常执行，引用状态、键盘、发送流程都不改；
 * - at 入口：仅当实参的**精确类型**是 `InsertAtMemberSpan`、当前线程处在上述作用域、
 *   开关开启时，跳过这个 void 方法（即不插入自动 @）。
 *
 * 作用域只表达“现在正处在引用处理的原方法里”，不按命名空间/堆栈判断：
 * 手动选择成员（`CreateAtSpan`）、@全体成员（`InsertAtAllMemberSpan`）与点头像/昵称
 * 触发的 `InsertAtMemberSpan` 都不在引用窗口内，因此不受影响。
 *
 * 异常安全：native 分支在 [Transformers.invokeExact] 外层用 try/finally 还原作用域，
 * 原方法抛异常也不会泄漏；Xposed 分支在 `afterHookedMethod` 里用 `before` 保存的深度
 * 精确还原（Xposed 即使原方法抛异常也会回调 after）。不使用时间窗口/TTL/stackTrace。
 *
 * 作用域只对主线程生效：其它线程（含未知调度）不打开作用域，对应调用一律 fail open。
 * 开关每次 at 事件读取 [HookSettings]，运行中切换立即生效；作用域只影响自动 @。
 *
 * 三个版本的混淆签名（方法名不写死，全部按形状解析）：
 * - 9.3.50：`reply.i#f(MsgIntent)` 实例 void；`InputAtVMDelegate#f(InputAtVMDelegate, MsgIntent)` 静态 void
 * - 9.3.75：`reply.i#g(MsgIntent)` 实例 void；`InputAtVMDelegate#g(InputAtVMDelegate, MsgIntent)` 静态 void
 * - 9.3.70：`reply.i#g(MsgIntent)` 实例 void；`InputAtVMDelegate#g(InputAtVMDelegate, MsgIntent)` 静态 void
 */
object DisableReplyAtHooker : BaseHooker() {
    override val key = "disable_reply_auto_at"
    override val name = "禁止回复自动 @"
    override val description =
        "回复并引用消息时不再自动 @ 对方。引用状态保留，手动 @、@全体成员以及点击头像/昵称的 @ 不受影响。"
    override val defaultEnabled = false

    private const val REPLY_DELEGATE_CLASS = "com.tencent.mobileqq.aio.input.reply.i"
    private const val INPUT_AT_DELEGATE_CLASS = "com.tencent.mobileqq.aio.input.at.InputAtVMDelegate"
    private const val MSG_INTENT_CLASS = "com.tencent.mvi.base.route.MsgIntent"
    private const val QUOTE_EVENT_CLASS =
        "com.tencent.mobileqq.aio.event.AIOMsgSendEvent\$MsgOnClickReplyEvent"
    private const val INSERT_AT_MEMBER_SPAN_CLASS =
        "com.tencent.mobileqq.aio.input.at.InputAtMsgIntent\$InsertAtMemberSpan"

    /** native frame 里实例方法的 receiver 占 0 号位，引用事件在 1 号位；静态 at 入口同样在 1 号位 */
    private const val FRAME_INTENT_INDEX = 1

    /** Xposed 的 `param.args` 不含 receiver：reply 入口的 intent 在 0 号位，静态 at 入口在 1 号位 */
    private const val XP_REPLY_INTENT_INDEX = 0
    private const val XP_AT_INTENT_INDEX = 1

    private const val QUOTE_WINDOW_EXTRA = "qqzygisk.disable_reply_auto_at.depth"

    private class Prepared(
        val replyMethod: Method,
        val inputAtMethod: Method,
        val quoteEventType: Class<*>,
        val insertAtMemberSpanType: Class<*>,
    )

    override fun initOnce() {
        // 类、方法、事件类型全部解析成功后才安装，避免只装上一处或装上错误入口。
        val prepared = prepare()
        if (ModuleUtils.isXpEnvironment) {
            installXposed(prepared)
        } else {
            installNative(prepared)
        }
        Log.info(
            "回复自动 @ 拦截已安装: " +
                "${prepared.replyMethod.declaringClass.name}.${prepared.replyMethod.name}, " +
                "${prepared.inputAtMethod.declaringClass.name}.${prepared.inputAtMethod.name}",
        )
    }

    private fun prepare(): Prepared {
        val msgIntentType = MSG_INTENT_CLASS.toAppClass()
        val quoteEventType = QUOTE_EVENT_CLASS.toAppClass()
        require(msgIntentType.isAssignableFrom(quoteEventType)) {
            "$QUOTE_EVENT_CLASS 不是 $MSG_INTENT_CLASS 的子类型"
        }
        val insertAtMemberSpanType = INSERT_AT_MEMBER_SPAN_CLASS.toAppClass()
        require(msgIntentType.isAssignableFrom(insertAtMemberSpanType)) {
            "$INSERT_AT_MEMBER_SPAN_CLASS 不是 $MSG_INTENT_CLASS 的子类型"
        }

        val replyDelegate = REPLY_DELEGATE_CLASS.toAppClass()
        val replyMethods = replyDelegate.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE &&
                it.parameterCount == 1 && it.parameterTypes[0] == msgIntentType
        }
        val replyMethod = replyMethods.singleOrNull()
            ?: error(
                "$REPLY_DELEGATE_CLASS 中 instance void($MSG_INTENT_CLASS) 匹配到 " +
                    "${replyMethods.size} 个方法，预期唯一",
            )

        val inputAtDelegate = INPUT_AT_DELEGATE_CLASS.toAppClass()
        val inputAtMethods = inputAtDelegate.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE &&
                it.parameterCount == 2 && it.parameterTypes[0] == inputAtDelegate &&
                it.parameterTypes[1] == msgIntentType
        }
        val inputAtMethod = inputAtMethods.singleOrNull()
            ?: error(
                "$INPUT_AT_DELEGATE_CLASS 中 static void($INPUT_AT_DELEGATE_CLASS, $MSG_INTENT_CLASS) " +
                    "匹配到 ${inputAtMethods.size} 个方法，预期唯一",
            )

        return Prepared(replyMethod, inputAtMethod, quoteEventType, insertAtMemberSpanType)
    }

    /**
     * 该 at 事件是否应被丢弃（即不插入自动 @）。
     *
     * 四个条件缺一不可：实参精确类型是 `InsertAtMemberSpan`、当前是主线程、正处于引用处理
     * 的原方法调用内、开关开启。任一条件不满足都 fail open，手动 @ / @全体成员不会命中。
     */
    private fun shouldDropAtMemberSpan(intent: Any?, insertAtMemberSpanType: Class<*>): Boolean =
        intent?.javaClass === insertAtMemberSpanType &&
            ReplyQuoteWindow.isMainThread &&
            ReplyQuoteWindow.isActive &&
            HookSettings.isEnabled(key, defaultEnabled)

    private fun installNative(prepared: Prepared) {
        Hooks.hook(
            prepared.replyMethod,
            EntryPointType.DIRECT,
            HookTransformer { original: MethodHandle, frame: EmulatedStackFrame ->
                val intent = frame.accessor().getValue(FRAME_INTENT_INDEX)
                if (ReplyQuoteWindow.isMainThread && intent?.javaClass === prepared.quoteEventType) {
                    val previous = ReplyQuoteWindow.enter()
                    try {
                        // 引用入口本身必须完整执行：引用状态、输入框、消息流程都由它负责。
                        Transformers.invokeExact(original, frame)
                    } finally {
                        ReplyQuoteWindow.restore(previous)
                    }
                } else {
                    Transformers.invokeExact(original, frame)
                }
            },
            EntryPointType.DIRECT,
        )
        Hooks.hook(
            prepared.inputAtMethod,
            EntryPointType.DIRECT,
            HookTransformer { original: MethodHandle, frame: EmulatedStackFrame ->
                val intent = frame.accessor().getValue(FRAME_INTENT_INDEX)
                if (!shouldDropAtMemberSpan(intent, prepared.insertAtMemberSpanType)) {
                    Transformers.invokeExact(original, frame)
                }
                // 命中时直接返回：at 入口是 void，不调用原方法即完成“跳过插入自动 @”。
            },
            EntryPointType.DIRECT,
        )
    }

    private fun installXposed(prepared: Prepared) {
        XposedBridge.hookMethod(
            prepared.replyMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.getOrNull(XP_REPLY_INTENT_INDEX)
                    if (!ReplyQuoteWindow.isMainThread || intent?.javaClass !== prepared.quoteEventType) {
                        return
                    }
                    param.setObjectExtra(QUOTE_WINDOW_EXTRA, ReplyQuoteWindow.enter())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    // 原方法抛异常时 Xposed 同样回调 after，这里精确还原 before 之前的状态。
                    val previous = param.getObjectExtra(QUOTE_WINDOW_EXTRA) as? Int ?: return
                    ReplyQuoteWindow.restore(previous)
                }
            },
        )
        XposedBridge.hookMethod(
            prepared.inputAtMethod,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.getOrNull(XP_AT_INTENT_INDEX) ?: return
                    if (shouldDropAtMemberSpan(intent, prepared.insertAtMemberSpanType)) {
                        // void 方法：设置结果即跳过原方法，不插入自动 @。
                        param.setResult(null)
                    }
                }
            },
        )
    }
}

/**
 * “正在处理引用点击”的线程本地作用域。
 *
 * 只记录进入次数，供 at 入口判断当前是否处在引用处理的原方法调用里。正常路径由 reply
 * hook 的 `try/finally`（native）或 `afterHookedMethod`（Xposed）还原，因此原方法抛异常
 * 不会把开关泄漏到之后的手动 @。作用域只在主线程打开，其它线程的调用一律 fail open。
 */
internal object ReplyQuoteWindow {
    private const val ABSENT = -1

    private val depth = ThreadLocal<Int>()

    val isMainThread: Boolean
        get() = Looper.myLooper() === Looper.getMainLooper()

    val isActive: Boolean
        get() = depth.get() != null

    /**
     * 进入作用域，返回进入前的深度；[ABSENT] 表示此前不在作用域内。返回值必须原样交给
     * [restore]，嵌套引用（极少数重入）时会逐层恢复。
     */
    fun enter(): Int {
        val previous = depth.get()
        return if (previous == null) {
            depth.set(1)
            ABSENT
        } else {
            depth.set(previous + 1)
            previous
        }
    }

    /** 精确还原到 [enter] 之前的状态；必须放在 `finally` / Xposed after 中执行。 */
    fun restore(previous: Int) {
        if (previous == ABSENT) {
            depth.remove()
        } else {
            depth.set(previous)
        }
    }
}
