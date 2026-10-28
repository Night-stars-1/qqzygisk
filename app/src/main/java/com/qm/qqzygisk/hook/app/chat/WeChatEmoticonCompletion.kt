package com.qm.qqzygisk.hook.app.chat

import android.os.Handler
import android.os.Looper
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信 8.0.79 原生表情上传的完成观察器，覆盖两个已核对分发。
 *
 * 真实 DEX（androguard 核对，不使用 JADX 的 f 数字别名）：
 * - `b0.m` 是唯一发送队列 `java.util.List`，`Pj` 在 `Fj` 内同步 `add(new i0(...))`
 * - `i0` 字段真名 `a:long`(msgId)、`b:String`(talker)、`c:EmojiInfo`，两版同名同形；
 *   队列项由微信创建，观察器不依赖其构造签名
 * - 上传场景结束回调同步调用 `b0.Gj(errType, errCode)`：code3182 是 `f82/b4` 结束
 *   `o82/y`，code3200 是 `i72/b4` 结束 `r72/y`
 * - `Gj` 先移除队首，再按 `errType==0 && errCode==0` 把消息状态写成 2，否则写成 5
 * - 消息状态由消息查询取回后读 `M0()`：code3182 `oy0.k0.Zi(talker, msgId)` + `hn/c8.M0()`，
 *   code3200 `ox0.k0.Vi(talker, msgId)` + `im/c8.M0()`
 *
 * `Fj` 返回 true 只代表入队，不代表发送成功。这里只观察，不修改原生参数、返回值
 * 或宿主状态；非本次提交产生的消息不会回调。注册表不在 [WeChatEmojiVariant] 内时
 * 在 `Fj` 之前返回失败，不做启动后的假成功。
 */
internal class WeChatEmoticonCompletion private constructor(
    private val ownerClass: Class<*>,
    private val lookup: Method,
    private val itemClass: Class<*>,
    private val queueField: Field,
    private val msgIdField: Field,
    private val talkerField: Field,
    private val emojiField: Field,
    private val md5Method: Method,
    private val messageLookup: Method,
    private val statusMethod: Method,
) {
    private val lock = Any()
    private val active = HashMap<Long, Pending>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 在 [start] 前快照队列；[start] 返回 true 时绑定本次新入队的唯一消息。
     *
     * 返回值只表示启动：
     * - failure：未启动（拒绝/队列不可用/快照失败），不会调用 [complete]；
     * - success：已启动，[complete] 只会在原生终态、异常或超时后恰好回调一次。
     */
    fun submit(
        talker: String,
        md5: String,
        complete: (Result<Unit>) -> Unit,
        start: () -> Boolean,
    ): Result<Unit> {
        check(Looper.myLooper() === Looper.getMainLooper()) { "微信表情上传队列必须在主线程提交" }
        val owner = runCatching { queueOwner() }.getOrElse {
            return Result.failure(IllegalStateException("微信表情上传队列尚未就绪，已取消发送", it))
        }
        val before = snapshot(owner) ?: return Result.failure(
            IllegalStateException("微信表情上传队列尚不可用，已取消发送"),
        )
        val pending = Pending(complete)
        val timeout = Runnable { onTimeout(pending) }
        pending.timeout = timeout
        mainHandler.postDelayed(timeout, TIMEOUT_MS)

        val accepted = try {
            start()
        } catch (error: Throwable) {
            // 原生方法抛错时可能已经入队：能唯一定位就继续等终态，否则按未启动上报。
            val bound = findUnique(owner, before, talker, md5)
            if (bound == null) {
                abandon(pending)
                throw error
            }
            Log.warn("微信原生表情提交抛出异常，继续等待原生终态")
            bind(bound, pending)
            return Result.success(Unit)
        }
        if (!accepted) {
            abandon(pending)
            return Result.failure(IllegalStateException("微信拒绝了此次表情发送"))
        }

        val bound = findUnique(owner, before, talker, md5)
        if (bound == null) {
            Log.warn("微信原生表情上传队列未能唯一定位本次消息")
            finish(pending, Result.failure(unconfirmed()))
            return Result.success(Unit)
        }
        bind(bound, pending)
        return Result.success(Unit)
    }

    /**
     * 只在 `Gj` 移除队首之前读取本次完成的 msgId/talker，并把确认投递到主线程。
     * 主线程消息在 `Gj`（含状态写入）之后执行，再读宿主消息状态；原方法抛错时
     * 状态仍未变为 2/5，本次提交留到超时，不会伪成功。
     */
    private fun install(terminal: Method) {
        terminal.hook {
            before {
                runCatching {
                    val list = queueField.get(instance) as? List<*> ?: return@runCatching
                    val head = list.firstOrNull() ?: return@runCatching
                    if (!itemClass.isInstance(head)) return@runCatching
                    val id = msgIdField.getLong(head)
                    if (id <= 0L || synchronized(lock) { id !in active }) return@runCatching
                    val completedTalker = talkerField.get(head) as? String ?: return@runCatching
                    val errType = args[0] as Int
                    val errCode = args[1] as Int
                    mainHandler.post { confirm(id, completedTalker, errType, errCode) }
                }
            }
        }
    }

    private fun confirm(id: Long, talker: String, errType: Int, errCode: Int) {
        if (synchronized(lock) { active[id] == null }) return
        val status = runCatching {
            val message = messageLookup.invoke(null, talker, id) ?: return@runCatching null
            statusMethod.invoke(message) as? Int
        }.getOrNull() ?: return
        onTerminal(id, status, errType, errCode)
    }

    private fun queueOwner(): Any =
        checkNotNull(lookup.invoke(null, ownerClass)) { "微信表情上传队列服务尚未就绪" }

    private fun snapshot(owner: Any): Set<Any>? {
        return try {
            val list = queueField.get(owner) as? List<*> ?: return null
            val items = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            for (item in list) if (item != null) items.add(item)
            items
        } catch (_: Throwable) {
            null
        }
    }

    /** 只认本次 Fj 之后新入队、talker 与 md5 都精确匹配且唯一的 i0。 */
    private fun findUnique(owner: Any, before: Set<Any>, talker: String, md5: String): Long? {
        return try {
            val list = queueField.get(owner) as? List<*> ?: return null
            var found: Long? = null
            for (item in list) {
                if (item == null || !itemClass.isInstance(item) || before.contains(item)) continue
                if (talkerField.get(item) != talker) continue
                val info = emojiField.get(item) ?: continue
                if (md5Method.invoke(info) != md5) continue
                val id = msgIdField.getLong(item)
                if (id <= 0L || found != null) return null
                found = id
            }
            found
        } catch (_: Throwable) {
            null
        }
    }

    private fun bind(id: Long, pending: Pending) {
        runCatching {
            synchronized(lock) {
                if (!pending.finished.get()) active[id] = pending
            }
        }.onFailure {
            if (finish(pending, Result.failure(unconfirmed()))) {
                Log.warn("微信原生表情终态登记失败")
            }
        }
    }

    private fun onTerminal(id: Long, status: Int, errType: Int, errCode: Int) {
        val result = when (status) {
            STATUS_SUCCESS -> Result.success(Unit)
            STATUS_FAILURE -> Result.failure(
                IllegalStateException("微信表情发送失败（errType=$errType，errCode=$errCode）"),
            )

            else -> return
        }
        val pending = synchronized(lock) { active.remove(id) } ?: return
        if (!finish(pending, result)) return
        if (status == STATUS_SUCCESS) {
            Log.info("微信原生表情上传与消息发送完成")
        } else {
            Log.warn("微信原生表情上传失败 status=$status errType=$errType errCode=$errCode")
        }
    }

    private fun onTimeout(pending: Pending) {
        synchronized(lock) {
            val iterator = active.entries.iterator()
            while (iterator.hasNext()) if (iterator.next().value === pending) iterator.remove()
        }
        if (finish(pending, Result.failure(unconfirmed()))) {
            Log.warn("微信原生表情上传结果超时未确认")
        }
    }

    private fun finish(pending: Pending, result: Result<Unit>): Boolean {
        if (!pending.finished.compareAndSet(false, true)) return false
        pending.timeout?.let { mainHandler.removeCallbacks(it) }
        runCatching { pending.complete(result) }
            .onFailure { Log.warn("微信表情完成回调异常") }
        return true
    }

    private fun abandon(pending: Pending) {
        pending.finished.set(true)
        pending.timeout?.let { mainHandler.removeCallbacks(it) }
        synchronized(lock) {
            val iterator = active.entries.iterator()
            while (iterator.hasNext()) if (iterator.next().value === pending) iterator.remove()
        }
    }

    private class Pending(
        val complete: (Result<Unit>) -> Unit,
    ) {
        val finished = AtomicBoolean()
        var timeout: Runnable? = null
    }

    companion object {
        private const val EMOJI_API_CLASS = "com.tencent.mm.api.IEmojiInfo"
        private const val OWNER_CLASS = "com.tencent.mm.feature.emoji.b0"
        private const val ITEM_CLASS = "com.tencent.mm.feature.emoji.i0"
        private const val MESSAGE_CLASS = "com.tencent.mm.storage.e9"
        private const val QUEUE_FIELD = "m"
        private const val TERMINAL_METHOD = "Gj"
        private const val MESSAGE_STATUS_METHOD = "M0"
        private const val STATUS_SUCCESS = 2
        private const val STATUS_FAILURE = 5
        private const val TIMEOUT_MS = 60_000L
        private const val UNCONFIRMED = "结果未确认，请先检查聊天记录，勿重复发送"

        private val cache = WeakHashMap<ClassLoader, WeChatEmoticonCompletion>()

        /** 每个 ClassLoader 只安装一次；安装失败不缓存，仍可重试。 */
        @Synchronized
        fun forLoader(loader: ClassLoader, lookup: Method): Result<WeChatEmoticonCompletion> {
            cache[loader]?.let { return Result.success(it) }
            return runCatching { create(loader, lookup) }
                .recoverCatching { error ->
                    val cause = generateSequence(error) { it.cause }.last()
                    throw IllegalStateException(
                        "当前微信版本的原生表情上传结果无法确认：$cause（未发送）",
                        error,
                    )
                }
                .onSuccess { cache[loader] = it }
        }

        private fun create(loader: ClassLoader, lookup: Method): WeChatEmoticonCompletion {
            val variant = WeChatEmojiVariant.require(lookup.declaringClass)
            WeChatEmojiVariant.verifyLookup(lookup, variant)

            val ownerClass = Class.forName(OWNER_CLASS, false, loader)
            val itemClass = Class.forName(ITEM_CLASS, false, loader)
            val emojiApi = Class.forName(EMOJI_API_CLASS, false, loader)
            val messageClass = Class.forName(MESSAGE_CLASS, false, loader)

            val queue = ownerClass.getDeclaredField(QUEUE_FIELD).apply {
                check(!Modifier.isStatic(modifiers) && List::class.java.isAssignableFrom(type)) {
                    "微信 ${ownerClass.name}.$QUEUE_FIELD 不是发送队列"
                }
                isAccessible = true
            }
            val terminal = ownerClass.getMethod(TERMINAL_METHOD, Integer.TYPE, Integer.TYPE).apply {
                check(!Modifier.isStatic(modifiers) && returnType == Void.TYPE) {
                    "微信 ${ownerClass.name}.$TERMINAL_METHOD(int,int) 形状已变化"
                }
                isAccessible = true
            }
            val msgId = itemClass.getDeclaredField("a").apply {
                check(type == java.lang.Long.TYPE) { "微信 ${itemClass.name}.a 不是 long" }
                isAccessible = true
            }
            val talker = itemClass.getDeclaredField("b").apply {
                check(type == String::class.java) { "微信 ${itemClass.name}.b 不是 String" }
                isAccessible = true
            }
            val emoji = itemClass.getDeclaredField("c").apply {
                check(emojiApi.isAssignableFrom(type)) { "微信 ${itemClass.name}.c 不是 IEmojiInfo" }
                isAccessible = true
            }
            // 队列项由微信创建；观察器只读取字段，不依赖宿主构造器的参数形状。
            val md5 = emojiApi.getMethod("getMd5").apply {
                check(returnType == String::class.java) { "微信 IEmojiInfo.getMd5 形状已变化" }
            }
            val messageLookup = Class.forName(variant.messageLookupClass, false, loader)
                .getMethod(variant.messageLookupMethod, String::class.java, java.lang.Long.TYPE)
                .apply {
                    check(Modifier.isStatic(modifiers) && returnType == messageClass) {
                        "微信 ${variant.messageLookupClass}.${variant.messageLookupMethod} 形状已变化"
                    }
                }
            val status = messageClass.getMethod(MESSAGE_STATUS_METHOD).apply {
                check(!Modifier.isStatic(modifiers) && returnType == java.lang.Integer.TYPE) {
                    "微信 $MESSAGE_CLASS.$MESSAGE_STATUS_METHOD 形状已变化"
                }
                isAccessible = true
            }

            return WeChatEmoticonCompletion(
                ownerClass,
                lookup,
                itemClass,
                queue,
                msgId,
                talker,
                emoji,
                md5,
                messageLookup,
                status,
            ).also { it.install(terminal) }
        }

        private fun unconfirmed() = IllegalStateException(UNCONFIRMED)
    }
}
