package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.qm.qqzygisk.hook.app.chat.NtMsgAccess
import com.qm.qqzygisk.hook.app.chat.RecalledBadge
import com.qm.qqzygisk.hook.app.chat.RecalledMessageKey
import com.qm.qqzygisk.hook.app.chat.RecalledMessages
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在宿主 AIO 消息行上安装“已撤回”标记。
 *
 * 入口是 `AIOBubbleMsgItemVB.handleUIState(MviUIState)`：宿主每次把模型绑定到行、以及行
 * 滚进/滚出可见区都会经过它。before 先撤掉上一次的装饰并恢复气泡原约束，after 重新读取
 * 当前模型再绑定，保证行被复用到另一条消息时不会带着上一条消息的标记。只有真正的绑定状态
 * （`AIOMsgItemUIState$AIOMsgItemState`）才做 clear/rebind；ScrollIntoScreen / ScrollOutScreen
 * 等纯滚动状态不改 View，直接跳过，避免滚动时反复 detach/clone LP/重排。
 *
 * 所有宿主符号都按“契约形状”定位，不写死混淆名：
 *  - handleUIState：精确名 + 单参数为 `com.tencent.mvi.base.mvi.MviUIState`、返回 void；
 *  - 模型 getter：声明无参、返回 `com.tencent.mobileqq.aio.msg.AIOMsgItem` 且唯一；
 *  - 根 View getter：声明无参、返回 `android.view.View` 且唯一。
 *
 * 任一形状漂移只 warn 一次并跳过本 hook，异常不外抛，不影响其它模块 hook 的安装。
 */
internal object RecalledBadgeHooker {
    private const val VB_CLASS = "com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB"
    private const val MSG_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"
    private const val MVI_STATE_CLASS = "com.tencent.mvi.base.mvi.MviUIState"
    private const val BUBBLE_CLASS = "com.tencent.qqnt.aio.holder.template.BubbleLayoutCompatPress"
    private const val BUBBLE_SIMPLE_NAME = "BubbleLayoutCompatPress"

    /**
     * 真正的“把模型绑定到行”的状态。handleUIState 同时接收 ScrollIntoScreen / ScrollOutScreen
     * 等纯滚动状态，那些状态不重建 View，没必要 detach/clone LP/重排。
     */
    private const val MSG_ITEM_STATE_CLASS =
        "com.tencent.mobileqq.aio.msglist.holder.AIOMsgItemUIState\$AIOMsgItemState"

    private const val RECORD_GETTER = "getMsgRecord"

    private val lock = Any()

    /** SharedPreferences 载入与唯一的刷新 listener 只做一次。 */
    private val storeStarted = AtomicBoolean(false)

    /** 形状漂移的告警只输出一次，避免每个 Activity 生命周期刷屏。 */
    private val driftLogged = AtomicBoolean(false)

    /** 运行期绑定异常同样只报一次，避免滚动时刷日志。 */
    private val runtimeFailureLogged = AtomicBoolean(false)

    /** 绑定状态类缺失（版本漂移）只提示一次，随后退化为对所有状态生效。 */
    private val bindStateMissingLogged = AtomicBoolean(false)

    @Volatile
    private var installed = false

    @Volatile
    private var msgGetter: Method? = null

    @Volatile
    private var rootGetter: Method? = null

    @Volatile
    private var recordGetter: Method? = null

    @Volatile
    private var chatTypeGetter: Method? = null

    @Volatile
    private var peerGetter: Method? = null

    @Volatile
    private var seqGetter: Method? = null

    /** 只有该状态类实例才执行 clear/rebind；为 null 表示宿主没有该类，退化为全部状态。 */
    @Volatile
    private var bindStateType: Class<*>? = null

    /**
     * 初始化标记存储并安装 hook。
     *
     * 由 [com.qm.qqzygisk.hook.app.QQEntry] 在宿主首个 Activity、ClassLoader 对齐后调用。
     * 失败返回 false，允许后续 Activity 重试，且不影响其它 hook。
     */
    fun install(context: Context): Boolean {
        startStore(context)
        if (installed) return true
        synchronized(lock) {
            if (installed) return true
            val ok = runCatching { installHook() }.getOrElse { error ->
                drift("安装已撤回标记 hook 失败", error)
                false
            }
            installed = ok
            return ok
        }
    }

    /**
     * 载入模块专有标记并注册唯一的刷新 listener：撤回事件到达时立即刷新已绑定的可见行。
     */
    private fun startStore(context: Context) {
        if (!storeStarted.compareAndSet(false, true)) return
        runCatching {
            // 先挂 listener 再触发载入：首次载入完成的通知不会因为注册晚一步而丢掉。
            RecalledMessages.addListener {
                // 通知到达时现场读账号：切换账号后，旧账号的标记不能刷到新账号的会话行上。
                val account = runCatching { NtMsgAccess.selfUin() }.getOrDefault("")
                RecalledBadge.refresh(
                    recalled = { key ->
                        account.isNotEmpty() &&
                            key.accountUin == account &&
                            enabled() &&
                            RecalledMessages.contains(key)
                    },
                    bubbleOf = ::bubbleOf,
                )
            }
            RecalledMessages.initialize(context)
        }.onFailure { Log.warn("初始化已撤回标记存储失败", it) }
    }

    private fun installHook(): Boolean {
        val vbType = Class.forName(VB_CLASS, false, appClassLoader)
        val uiState = uiStateMethodOf(vbType) ?: return false
        val msg = msgGetterOf(vbType) ?: return false
        val root = rootGetterOf(vbType) ?: return false
        val record = recordGetterOf(msg.returnType) ?: return false
        val chatType = recordGetterOf(record.returnType, "getChatType", Int::class.javaPrimitiveType!!) ?: return false
        val peer = recordGetterOf(record.returnType, "getPeerUid", String::class.java) ?: return false
        val seq = recordGetterOf(record.returnType, "getMsgSeq", Long::class.javaPrimitiveType!!) ?: return false
        msgGetter = msg
        rootGetter = root
        recordGetter = record
        chatTypeGetter = chatType
        peerGetter = peer
        seqGetter = seq
        bindStateType = bindStateTypeOf()
        uiState.hook {
            before {
                runCatching {
                    // 只有真正的绑定状态才重建装饰；纯滚动状态保持现状，不做多余的布局写入。
                    if (!isBindState(args.firstOrNull())) return@runCatching
                    // 先撤掉旧装饰并恢复气泡原约束：这一行马上要显示新模型。
                    val row = rootGetter?.invoke(instance) as? View ?: return@runCatching
                    RecalledBadge.clear(row)
                }.onFailure { reportFailure("清除已撤回标记失败", it) }
            }
            after {
                runCatching {
                    if (!isBindState(args.firstOrNull())) return@runCatching
                    bind(instance)
                }.onFailure { reportFailure("绑定已撤回标记失败", it) }
            }
        }
        Log.info("已撤回标记 hook 安装完成")
        return true
    }

    private fun bindStateTypeOf(): Class<*>? =
        runCatching { Class.forName(MSG_ITEM_STATE_CLASS, false, appClassLoader) }.getOrNull()
            ?: run {
                if (bindStateMissingLogged.compareAndSet(false, true)) {
                    Log.warn("未找到 $MSG_ITEM_STATE_CLASS，已撤回标记对所有 handleUIState 状态生效")
                }
                null
            }

    private fun isBindState(state: Any?): Boolean {
        if (state == null) return false
        val type = bindStateType ?: return true
        // 也按类名兜底：状态类若来自另一份 ClassLoader 拷贝，isInstance 会失配。
        return type.isInstance(state) || state.javaClass.name == MSG_ITEM_STATE_CLASS
    }

    /** after：重新读当前模型，按现场账号与会话身份判断这行要不要标。 */
    private fun bind(binder: Any) {
        val row = rootGetter?.invoke(binder) as? View ?: return
        val bubble = bubbleOf(row)
        val key = keyOf(binder)
        val recalled = key != null && enabled() && RecalledMessages.contains(key)
        RecalledBadge.bind(row, bubble, key, recalled)
    }

    private fun keyOf(binder: Any): RecalledMessageKey? {
        val message = msgGetter?.invoke(binder) ?: return null
        val record = recordGetter?.invoke(message) ?: return null
        val chatType = chatTypeGetter?.let { invokeInt(it, record) } ?: return null
        val peerUid = peerGetter?.let { invokeString(it, record) } ?: return null
        val msgSeq = seqGetter?.let { invokeLong(it, record) } ?: return null
        if (peerUid.isEmpty() || msgSeq <= 0L) return null
        if (chatType != NtMsgAccess.CHAT_TYPE_C2C && chatType != NtMsgAccess.CHAT_TYPE_GROUP) return null
        // 账号每次现场读，避免同一进程切换账号后沿用旧 uin 造出错误的 key。
        val account = NtMsgAccess.selfUin()
        if (account.isEmpty()) return null
        return RecalledMessageKey(account, chatType, peerUid, msgSeq)
    }

    private fun invokeInt(
        method: Method,
        target: Any,
    ): Int? = runCatching { method.invoke(target) as? Int }.getOrNull()

    private fun invokeLong(
        method: Method,
        target: Any,
    ): Long? = runCatching { method.invoke(target) as? Long }.getOrNull()

    private fun invokeString(
        method: Method,
        target: Any,
    ): String? = runCatching { method.invoke(target) as? String }.getOrNull()

    private fun enabled(): Boolean = HookSettings.isEnabled(AntiRevokeHooker.key, AntiRevokeHooker.defaultEnabled)

    private fun uiStateMethodOf(type: Class<*>): Method? {
        // 只接受已核实的三版一致签名：handleUIState(MviUIState):void，且必须唯一。
        val candidates =
            type.declaredMethods.filter {
                it.name == "handleUIState" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0].name == MVI_STATE_CLASS &&
                    it.returnType == Void.TYPE
            }
        if (candidates.size != 1) {
            drift("$VB_CLASS.handleUIState(MviUIState) 未找到唯一入口 (候选=${candidates.size})")
            return null
        }
        return candidates[0].apply { isAccessible = true }
    }

    private fun msgGetterOf(type: Class<*>): Method? {
        val messageType = runCatching { Class.forName(MSG_ITEM_CLASS, false, appClassLoader) }.getOrNull()
        if (messageType == null) {
            drift("$MSG_ITEM_CLASS 不存在")
            return null
        }
        return uniqueNoArg(type, "模型 getter", messageType)
    }

    private fun rootGetterOf(type: Class<*>): Method? = uniqueNoArg(type, "根 View getter", View::class.java)

    private fun uniqueNoArg(
        type: Class<*>,
        what: String,
        returnType: Class<*>,
    ): Method? {
        val candidates =
            type.declaredMethods.filter {
                it.parameterCount == 0 && !it.isBridge && it.returnType == returnType
            }
        if (candidates.size != 1) {
            drift("$VB_CLASS 的${what}必须唯一返回 ${returnType.name} (候选=${candidates.size})")
            return null
        }
        return candidates[0].apply { isAccessible = true }
    }

    private fun recordGetterOf(type: Class<*>): Method? {
        val method =
            runCatching { type.getMethod(RECORD_GETTER) }.getOrNull()
                ?.takeIf { it.parameterCount == 0 }
        if (method == null) {
            drift("${type.name}.$RECORD_GETTER() 未找到")
            return null
        }
        return method.apply { isAccessible = true }
    }

    private fun recordGetterOf(
        type: Class<*>,
        name: String,
        returnType: Class<*>,
    ): Method? {
        val method =
            runCatching { type.getMethod(name) }.getOrNull()
                ?.takeIf { it.parameterCount == 0 && it.returnType == returnType }
        if (method == null) {
            drift("${type.name}.$name() 不再是 ${returnType.name}")
            return null
        }
        return method.apply { isAccessible = true }
    }

    /**
     * 气泡是 root 的直接子项（`BubbleLayoutCompatPress`，Vertical LinearLayout）。
     * 保留一层兜底深度，模板偶尔多包一层时标记仍然可用；灰条等没有气泡的行返回 null。
     */
    private fun bubbleOf(root: View): View? {
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (isBubble(child)) return child
        }
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) as? ViewGroup ?: continue
            for (inner in 0 until child.childCount) {
                val candidate = child.getChildAt(inner)
                if (isBubble(candidate)) return candidate
            }
        }
        return null
    }

    private fun isBubble(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == BUBBLE_CLASS || type.simpleName == BUBBLE_SIMPLE_NAME) return true
            type = type.superclass
        }
        return false
    }

    /** 运行期异常只报第一次，滚动/复用时的重复异常不刷日志。 */
    private fun reportFailure(
        message: String,
        error: Throwable?,
    ) {
        if (runtimeFailureLogged.compareAndSet(false, true)) {
            Log.warn(message, error)
        }
    }

    private fun drift(
        message: String,
        error: Throwable? = null,
    ) {
        if (driftLogged.compareAndSet(false, true)) {
            Log.warn("已撤回标记 hook 跳过: $message", error)
        }
    }
}
