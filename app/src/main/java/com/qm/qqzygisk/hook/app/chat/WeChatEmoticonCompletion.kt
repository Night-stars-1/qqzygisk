package com.qm.qqzygisk.hook.app.chat

import android.os.Handler
import android.os.Looper
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微信原生表情上传完成观察器。队列、消息查询和可选的发送任务都来自
 * [WeChatEmojiProtocol] 的形状定位，不写死混淆后的类名。
 *
 * 原生发送返回 true 不代表发送成功。这里只观察，不修改原生参数、返回值
 * 或宿主状态；非本次提交产生的消息不会回调。协议无法定位时在原生发送之前
 * 返回失败，不做启动后的假成功。
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
    private val taskPending = IdentityHashMap<Any, Pending>()
    private val initiating = ThreadLocal<Pending>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 在 [start] 前快照队列，并捕获同步调用创建的发送任务；只绑定本次提交的唯一身份。
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
        val pending = Pending(talker, md5, complete)
        val timeout = Runnable { onTimeout(pending) }
        pending.timeout = timeout
        mainHandler.postDelayed(timeout, TIMEOUT_MS)

        val accepted = try {
            initiating.set(pending)
            try { start() } finally { initiating.remove() }
        } catch (error: Throwable) {
            if (synchronized(lock) { pending.task != null && pending.taskAccepted }) return Result.success(Unit)
            // 原生方法抛错时可能已经入队：能唯一定位就继续等终态，否则按未启动上报。
            val bound = findUnique(owner, before, talker, md5)
            if (bound == null) {
                // Nj 抛错时宿主可能已经提交任务，但尚未返回接受结果；继续等待，不重复发送。
                if (synchronized(lock) { pending.task != null }) return Result.success(Unit)
                abandon(pending)
                throw error
            }
            clearTask(pending)
            Log.warn("微信原生表情提交抛出异常，继续等待原生终态")
            bind(bound, pending)
            return Result.success(Unit)
        }
        if (!accepted) {
            abandon(pending)
            return Result.failure(IllegalStateException("微信拒绝了此次表情发送"))
        }
        if (synchronized(lock) { pending.task != null && pending.taskAccepted }) return Result.success(Unit)
        // 宿主会捕获 Nj 异常并回退传统队列；未得到 true 的候选不能抢占该队列的终态。
        clearTask(pending)

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
     * 只在队列终态方法移除队首之前读取本次完成的 msgId/talker，并把确认投递到主线程。
     * 主线程消息在终态方法（含状态写入）之后执行，再读宿主消息状态；原方法抛错时
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

    /** 只认本次同步提交期间 Nj 接收的参数对象；J 完成后再按其消息 ID 查库。 */
    private fun installTask(protocol: WeChatEmojiTask) {
        protocol.start.hook {
            before {
                runCatching {
                    val pending = initiating.get() ?: return@runCatching
                    val params = args.firstOrNull() ?: return@runCatching
                    if (protocol.taskTalker.get(params) != pending.talker) return@runCatching
                    val info = protocol.taskEmoji.get(params) ?: return@runCatching
                    if (md5Method.invoke(info) != pending.md5) return@runCatching
                    synchronized(lock) {
                        if (!pending.finished.get() && pending.task == null) {
                            pending.task = params
                            taskPending[params] = pending
                        }
                    }
                }
            }
            after {
                val params = args.firstOrNull() ?: return@after
                synchronized(lock) {
                    val pending = taskPending[params] ?: return@synchronized
                    if (result == true) {
                        pending.taskAccepted = true
                    } else {
                        clearTask(pending)
                    }
                }
            }
        }
        protocol.finish.hook {
            after {
                runCatching {
                    val params = protocol.taskParameters.get(instance) ?: return@runCatching
                    val pending = synchronized(lock) { taskPending[params] } ?: return@runCatching
                    val info = protocol.taskInfo.invoke(instance) ?: return@runCatching
                    val identity = protocol.infoMessage.get(info) ?: return@runCatching
                    val id = protocol.messageId.getLong(identity)
                    val talker = protocol.messageTalker.get(identity) as? String ?: return@runCatching
                    if (id <= 0 || talker != pending.talker) return@runCatching
                    mainHandler.post {
                        if (synchronized(lock) { taskPending[params] !== pending }) return@post
                        bind(id, pending)
                        confirm(id, talker, null, null)
                    }
                }
            }
        }
    }

    private fun confirm(id: Long, talker: String, errType: Int?, errCode: Int?) {
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

    /** 只认本次原生提交之后新入队、talker 与 md5 都精确匹配且唯一的 i0。 */
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

    private fun onTerminal(id: Long, status: Int, errType: Int?, errCode: Int?) {
        val result = when (status) {
            STATUS_SUCCESS -> Result.success(Unit)
            STATUS_FAILURE -> Result.failure(
                IllegalStateException(if (errType == null) "微信表情发送失败" else "微信表情发送失败（errType=$errType，errCode=$errCode）"),
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
        clearTask(pending)
        pending.timeout?.let { mainHandler.removeCallbacks(it) }
        runCatching { pending.complete(result) }
            .onFailure { Log.warn("微信表情完成回调异常") }
        return true
    }

    private fun clearTask(pending: Pending) {
        synchronized(lock) {
            pending.task?.let { taskPending.remove(it) }
            pending.task = null
            pending.taskAccepted = false
        }
    }

    private fun abandon(pending: Pending) {
        pending.finished.set(true)
        pending.timeout?.let { mainHandler.removeCallbacks(it) }
        synchronized(lock) {
            clearTask(pending)
            val iterator = active.entries.iterator()
            while (iterator.hasNext()) if (iterator.next().value === pending) iterator.remove()
        }
    }

    private class Pending(
        val talker: String,
        val md5: String,
        val complete: (Result<Unit>) -> Unit,
    ) {
        val finished = AtomicBoolean()
        var timeout: Runnable? = null
        var task: Any? = null
        var taskAccepted = false
    }

    companion object {
        private const val STATUS_SUCCESS = 2
        private const val STATUS_FAILURE = 5
        private const val TIMEOUT_MS = 60_000L
        private const val UNCONFIRMED = "结果未确认，请先检查聊天记录，勿重复发送"

        private val cache = WeakHashMap<ClassLoader, WeChatEmoticonCompletion>()

        /** 每个 ClassLoader 只安装一次；安装失败不缓存，仍可重试。 */
        @Synchronized
        fun forLoader(loader: ClassLoader, protocol: WeChatEmojiProtocol): Result<WeChatEmoticonCompletion> {
            cache[loader]?.let { return Result.success(it) }
            return runCatching { create(protocol) }
                .recoverCatching { error ->
                    val cause = generateSequence(error) { it.cause }.last()
                    throw IllegalStateException(
                        "当前微信版本的原生表情上传结果无法确认：$cause（未发送）",
                        error,
                    )
                }
                .onSuccess { cache[loader] = it }
        }

        private fun create(protocol: WeChatEmojiProtocol): WeChatEmoticonCompletion =
            WeChatEmoticonCompletion(
                protocol.queueOwner,
                protocol.lookup,
                protocol.itemClass,
                protocol.queueField,
                protocol.msgIdField,
                protocol.talkerField,
                protocol.emojiField,
                protocol.emojiMd5,
                protocol.messageLookup,
                protocol.statusMethod,
            ).also { completion ->
                completion.install(protocol.terminal)
                protocol.task?.let(completion::installTask)
            }

        private fun unconfirmed() = IllegalStateException(UNCONFIRMED)
    }
}
