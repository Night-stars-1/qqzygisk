package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.qm.qqzygisk.hook.utils.Log
import java.util.concurrent.Executors

/**
 * 一条本模块保留下来、可以在列表里标出“已撤回”的消息。
 *
 * 键必须带账号和会话，seq 只是会话内序号：不同账号、不同群可能有同一个 seq。
 */
internal data class RecalledMessageKey(
    val accountUin: String,
    val chatType: Int,
    val peerUid: String,
    val msgSeq: Long,
)

/**
 * 防撤回保留的消息标记，供 AIO 每次重新绑定消息时查询。
 *
 * 标记只落在模块自己的 SharedPreferences 里，不碰 QQ 的消息库：重启后重新绑定
 * 原消息仍然能标出“已撤回”。磁盘读取/写入全部在 [io] 单线程上串行执行，
 * [contains] 只读内存快照；[addListener] 的回调在主线程派发。
 */
internal object RecalledMessages {
    private const val PREFERENCES_NAME = "qqzygisk_recalled_messages"

    /** 唯一的落盘键，值是下面 [encode] 出来的字符串集合。 */
    private const val STORE_KEY = "messages"

    /** 账号 uin、chatType 都是纯数字，peerUid 里理论上可能出现 '|'，用首尾定位解码。 */
    private const val SEPARATOR = '|'

    private val io =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "qh-recalled").apply { isDaemon = true }
        }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val startLock = Any()

    /** 首次载入任务只会入队一次，保证它排在所有写入之前。 */
    @Volatile
    private var started = false

    /** 线程安全的内存快照，整体替换，[contains] 不加锁也不做 IO。 */
    @Volatile
    private var snapshot: Set<RecalledMessageKey> = emptySet()

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var listener: (() -> Unit)? = null

    /** 唯一的生产 listener（已绑定消息行），重复注册只是替换。 */
    fun addListener(listener: () -> Unit) {
        this.listener = listener
    }

    /**
     * 异步载入磁盘上的标记。宿主 Activity 就绪时调用；重复调用不会重复读取，
     * 也不会在调用线程（可能是主线程）做任何磁盘操作。
     */
    fun initialize(context: Context) {
        ensureStarted(appContext(context))
    }

    /**
     * 记录一批本模块保留的消息。加载未完成时写入任务排在载入之后，因此不会用
     * 空快照覆盖磁盘上的历史记录；非法账号、非法 chatType、空 peer 和非正 seq
     * 一律丢弃。[context] 仅用于拿到模块 prefs，可以传任意宿主 Context。
     */
    fun remember(
        context: Context,
        accountUin: String,
        chatType: Int,
        peerUid: String,
        messageSeqs: List<Long>,
    ) {
        if (!isValid(accountUin, chatType, peerUid)) return
        val keys =
            messageSeqs
                .asSequence()
                .filter { it > 0 }
                .map { seq -> RecalledMessageKey(accountUin, chatType, peerUid, seq) }
                .toSet()
        if (keys.isEmpty()) return
        val appContext = appContext(context)
        // 先确保载入任务已入队，再排写入任务，串行执行天然“先载入后写入”。
        ensureStarted(appContext)
        io.execute { record(appContext, keys) }
    }

    /** 只查内存快照，供绑定路径调用；不存在或尚未载入都返回 false。 */
    fun contains(key: RecalledMessageKey): Boolean = key in snapshot

    private fun ensureStarted(context: Context) {
        if (started) return
        synchronized(startLock) {
            if (started) return
            started = true
            io.execute { load(context) }
        }
    }

    private fun load(context: Context) {
        try {
            val prefs = prefs(context)
            preferences = prefs
            val stored = prefs.getStringSet(STORE_KEY, emptySet()).orEmpty()
            snapshot = decodeAll(stored)
        } catch (t: Throwable) {
            Log.warn("读取已撤回标记失败", t)
        }
        notifyListener()
    }

    private fun record(
        context: Context,
        keys: Set<RecalledMessageKey>,
    ) {
        val prefs =
            try {
                preferences ?: prefs(context).also { preferences = it }
            } catch (t: Throwable) {
                // 读不到磁盘时仍保留本次进程内的标记，不能因为存储故障丢标记。
                Log.warn("打开已撤回标记存储失败", t)
                snapshot = snapshot + keys
                notifyListener()
                return
            }
        val stored =
            try {
                prefs.getStringSet(STORE_KEY, emptySet()).orEmpty()
            } catch (t: Throwable) {
                // 未读到旧集合时不能用空集合回写，否则会覆盖磁盘上的历史。
                snapshot = snapshot + keys
                notifyListener()
                Log.warn("读取已撤回标记存储失败，本次仅保留进程内标记", t)
                return
            }
        // 载入异常时磁盘上可能还有快照里没有的记录，合并后再判断新增，
        // 任何一笔历史都不能因为一次写入被丢掉。
        val known = decodeAll(stored) + snapshot
        val added = keys.filterNot { it in known }
        if (added.isEmpty()) return
        snapshot = known + added
        try {
            val merged = HashSet<String>(stored.size + snapshot.size)
            merged.addAll(stored)
            snapshot.forEach { merged.add(encode(it)) }
            if (!prefs.edit().putStringSet(STORE_KEY, merged).commit()) {
                Log.warn("保存已撤回标记未写入磁盘")
            }
        } catch (t: Throwable) {
            // 落盘失败不影响本次进程内的标记与提示，后续撤回会整批重写。
            Log.warn("保存已撤回标记失败", t)
        }
        notifyListener()
    }

    private fun notifyListener() {
        val callback = listener ?: return
        mainHandler.post {
            runCatching { callback() }
                .onFailure { Log.warn("已撤回标记通知失败", it) }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun appContext(context: Context): Context = context.applicationContext ?: context

    private fun isValid(
        accountUin: String,
        chatType: Int,
        peerUid: String,
    ): Boolean =
        accountUin.isNotEmpty() &&
            peerUid.isNotEmpty() &&
            (chatType == NtMsgAccess.CHAT_TYPE_C2C || chatType == NtMsgAccess.CHAT_TYPE_GROUP)

    private fun decodeAll(stored: Set<String>): Set<RecalledMessageKey> =
        stored.mapNotNullTo(HashSet(stored.size), ::decode)

    private fun encode(key: RecalledMessageKey): String =
        buildString {
            append(key.accountUin).append(SEPARATOR)
            append(key.chatType).append(SEPARATOR)
            append(key.peerUid).append(SEPARATOR)
            append(key.msgSeq)
        }

    private fun decode(raw: String): RecalledMessageKey? {
        val accountEnd = raw.indexOf(SEPARATOR)
        if (accountEnd <= 0) return null
        val typeEnd = raw.indexOf(SEPARATOR, accountEnd + 1)
        if (typeEnd <= accountEnd + 1) return null
        val seqStart = raw.lastIndexOf(SEPARATOR)
        if (seqStart <= typeEnd + 1) return null
        val accountUin = raw.substring(0, accountEnd)
        val chatType = raw.substring(accountEnd + 1, typeEnd).toIntOrNull() ?: return null
        val peerUid = raw.substring(typeEnd + 1, seqStart)
        val msgSeq = raw.substring(seqStart + 1).toLongOrNull() ?: return null
        if (!isValid(accountUin, chatType, peerUid) || msgSeq <= 0) return null
        return RecalledMessageKey(accountUin, chatType, peerUid, msgSeq)
    }
}
