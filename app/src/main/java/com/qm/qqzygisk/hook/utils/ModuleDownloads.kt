package com.qm.qqzygisk.hook.utils

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

internal data class ModuleDownload(
    val id: Long,
    val version: String,
    val versionCode: Long,
    val fileName: String,
)

internal enum class ModuleDownloadStatus { Pending, Running, Paused, Complete, Failed, Missing }

internal data class ModuleDownloadProgress(
    val download: ModuleDownload,
    val status: ModuleDownloadStatus,
    val receivedBytes: Long,
    val totalBytes: Long,
    val message: String,
) {
    val active: Boolean get() = status == ModuleDownloadStatus.Pending ||
        status == ModuleDownloadStatus.Running || status == ModuleDownloadStatus.Paused
}

private enum class ModuleDownloadPhase { Download, Publish }

private fun ModuleDownloadStatus.isActive(): Boolean =
    this == ModuleDownloadStatus.Pending || this == ModuleDownloadStatus.Running ||
        this == ModuleDownloadStatus.Paused

/**
 * 一个模块下载任务的持久状态。
 *
 * `received`/`total` 只用于展示与恢复判断，字节进度的真实来源始终是私有目录里的
 * `.part` 文件长度；该文件由本进程顺序写入、绝不预分配，所以文件长度就是真实字节数。
 */
private data class ModuleDownloadRecord(
    val id: Long,
    val version: String,
    val versionCode: Long,
    val fileName: String,
    val url: String,
    val status: ModuleDownloadStatus,
    val phase: ModuleDownloadPhase,
    val received: Long,
    val total: Long,
    val etag: String?,
    val lastModified: String?,
    val publishedUri: String?,
    val pendingUri: String?,
    val pendingPath: String?,
    /** API28 已持久化独占预留的公共最终路径；恢复只用它，不再另起名字。 */
    val publishPath: String? = null,
    val message: String,
    /** 迁移后仍需确认移除的旧 DownloadManager 任务；0 表示没有待清理项。 */
    val legacyId: Long = 0L,
) {
    fun toDownload(): ModuleDownload = ModuleDownload(id, version, versionCode, fileName)
}

/** worker 被用户取消或被新任务接管：不写任何状态。 */
private class WorkerCancelled : Exception()

/**
 * 模块 ZIP 的真实流式下载后端。
 *
 * 网络传输在本进程内的单线程执行器上进行，字节直接写入宿主私有目录
 * `files/qqzygisk/module-downloads/<id>.part`，进度是真实收到的字节数：关闭设置弹窗、
 * 销毁页面都不会影响它，只有 [cancel] 会关闭连接并删除本任务的临时文件。进程被强停后，
 * 记录仍在 [android.content.SharedPreferences] 中，[load] 会重新拉起 worker，凭 `.part`
 * 长度与持久化的 ETag/Last-Modified 发起 `Range` 续传。
 *
 * 下载完成后才把文件发布到公共“下载”目录（Android 10+ 走 MediaStore，Android 9 走
 * 独占文件 + [DownloadManager.addCompletedDownload]），`Complete` 只在长度校验与发布都
 * 成功之后出现。旧版本用 DownloadManager 下载留下的记录会被一次性迁移，失败也会留下
 * 可见记录，而不是静默消失。
 */
internal object ModuleDownloads {
    private const val PREFERENCES = "qqzygisk_module_download"
    private const val SCHEMA = 2
    private const val MIME_ZIP = "application/zip"
    private const val PROGRESS_INTERVAL_MS = 100L
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val LEGACY_POLL_INTERVAL_MS = 1_000L
    private const val WAIT_LEGACY_MESSAGE = "等待系统下载任务结束"
    private val unsafeFileName = Regex("[^A-Za-z0-9._-]")

    /** 任务记录占用的键；清理记录时必须保留 `nextId`。 */
    private val recordKeys = arrayOf(
        "schema", "id", "version", "versionCode", "fileName", "url", "status", "phase",
        "received", "total", "etag", "lastModified", "publishedUri", "pendingUri",
        "pendingPath", "publishPath", "message", "legacyId",
    )

    private val lock = Any()
    private val updates = MutableStateFlow<ModuleDownloadProgress?>(null)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qqzygisk-module-download").apply { isDaemon = true }
    }

    private var record: ModuleDownloadRecord? = null
    private var loaded = false
    @Volatile private var generation = 0L
    private var assignedGeneration = -1L
    @Volatile private var transfer: ModuleDownloadTransfer? = null
    @Volatile private var activeConnection: HttpURLConnection? = null

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 本任务独占的临时文件；由我们顺序写入，长度即真实字节数。 */
    internal fun partFile(context: Context, id: Long): File =
        File(File(context.applicationContext.filesDir, "qqzygisk/module-downloads"), "$id.part")

    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /**
     * 恢复最近保存的任务；进程重启后没有对应 worker 的活动任务会在这里自动续传。
     * 旧 DownloadManager 记录只在这里做一次性迁移；读取/迁移失败会抛出可见异常
     * （旧记录保持不动），绝不会静默当成“没有任务”。
     */
    fun load(context: Context): ModuleDownload? {
        val app = context.applicationContext
        val current = currentRecord(app) ?: return null
        val resolved = withLegacyResolved(app, current)
        val verified = verifyPublished(app, resolved)
        resumeIfNeeded(app, verified)
        return verified.toDownload()
    }

    /**
     * 订阅真实进度：先发出当前快照，之后只在任务终态时自动结束。
     * 取消订阅（页面销毁）不会影响 worker；只有 [cancel] 会中止下载。
     */
    fun observe(context: Context, download: ModuleDownload): Flow<ModuleDownloadProgress> {
        val app = context.applicationContext
        return callbackFlow {
            val first = query(app, download)
            trySend(first)
            if (!first.active) {
                close()
                return@callbackFlow
            }
            val watcher = launch {
                updates.filterNotNull()
                    .collect { snapshot ->
                        if (snapshot.download.id == download.id) {
                            trySend(snapshot)
                            if (!snapshot.active) close()
                        } else {
                            // 任务已被取消/替换：给旧 id 一个明确终态，观察者不能永远等待。
                            trySend(missing(download))
                            close()
                        }
                    }
            }
            awaitClose { watcher.cancel() }
        }.flowOn(Dispatchers.IO)
    }

    fun start(context: Context, release: ModuleRelease): ModuleDownload {
        val app = context.applicationContext
        require(!needsStoragePermission(app)) { "保存到下载目录需要存储权限" }
        require(release.zipUrl.startsWith("https://", ignoreCase = true)) { "下载链接必须使用 HTTPS" }
        val target = synchronized(lock) {
            val loaded = ensureLoadedLocked(app)
            val resolved = loaded?.let { withLegacyResolved(app, it) }
            if (resolved != null && resolved.legacyId > 0L) {
                // 旧系统下载任务尚未终态：迁移期间禁止任何 discard 或第二份下载。
                if (resolved.versionCode != release.versionCode) {
                    error("旧系统下载任务仍在进行，请稍后重试")
                }
                resumeIfNeededLocked(app, resolved)
                return resolved.toDownload()
            }
            val current = resolved?.let { verifyPublished(app, it) }
            if (current == null) {
                createRecord(app, release)
            } else {
                when {
                    current.status == ModuleDownloadStatus.Complete &&
                        current.versionCode == release.versionCode -> return current.toDownload()
                    current.status.isActive() -> {
                        if (current.versionCode != release.versionCode) {
                            error("已有模块正在下载，请等待完成或先取消")
                        }
                        // 同一版本已经在下载：继续原任务，不再创建第二个。
                        resumeIfNeededLocked(app, current)
                        return current.toDownload()
                    }
                    current.versionCode == release.versionCode && publishRetryReady(app, current) ->
                        preparePublishRetry(app, current)
                    current.versionCode == release.versionCode && resumeReady(app, current) ->
                        prepareResume(app, current)
                    else -> {
                        discardLocked(app, current)
                        createRecord(app, release)
                    }
                }
            }
        }
        resumeIfNeeded(app, target)
        return target.toDownload()
    }

    /**
     * 轻量的状态读取：命中内存快照时不做 I/O；首次调用会完成一次性旧任务迁移，
     * 活动任务若没有 worker 也会顺手拉起恢复。
     */
    fun query(context: Context, download: ModuleDownload): ModuleDownloadProgress {
        val app = context.applicationContext
        val current = currentRecord(app)
        if (current == null || current.id != download.id) return missing(download)
        val resolved = withLegacyResolved(app, current)
        val verified = verifyPublished(app, resolved)
        resumeIfNeeded(app, verified)
        return progress(verified)
    }

    /**
     * 立即停止本任务：关闭连接、删除本任务的 `.part` 与未发布的公共条目。
     * 已发布的公共文件属于用户，不会被删除；旧任务 id 也不会影响新任务。
     */
    fun cancel(context: Context, download: ModuleDownload) {
        val app = context.applicationContext
        val (cancelled, adopted) = synchronized(lock) {
            val loaded = ensureLoadedLocked(app) ?: return
            if (loaded.id != download.id) return
            val current = withLegacyResolved(app, loaded)
            if (current.legacyId > 0L) {
                // 旧系统下载任务仍在进行：没有原子“停止但保留”，必须保持所有权并如实报错，
                // 否则界面会假装已取消、旧任务却还在下载，随后 start 就会双开。
                throw IOException("旧系统下载仍在进行，请从系统下载通知中取消后重试")
            }
            // 公开状态无法确认时必须先抛错，绝不能在清记录之后丢掉所有权。
            val expected = expectedSize(app, current)
            val resolved = resolvePublished(app, current, expected)
            // 先持久清记录，成功后才改内存与取消标记：commit 失败则任务继续可见、也继续运行。
            if (!clearRecord(app)) throw IOException("无法保存下载状态，请重试")
            generation++
            assignedGeneration = -1L
            stopActiveTransfer()
            record = null
            updates.value = progress(current.copy(status = ModuleDownloadStatus.Missing, message = "已取消下载"))
            current to resolved
        }
        partFile(app, cancelled.id).delete()
        deleteOwnedPublication(app, cancelled, adopted?.toString())
        ModuleDownloadNotification.cancel(app)
    }

    /** 只有任务完成且公开文件真实可读时才返回可授权的打开意图。 */
    fun openIntent(context: Context, download: ModuleDownload): Intent {
        val app = context.applicationContext
        val loaded = currentRecord(app) ?: error("下载文件不存在，请重新下载")
        if (loaded.id != download.id) error("下载任务已变更，请重新下载")
        val current = withLegacyResolved(app, loaded)
        check(current.status == ModuleDownloadStatus.Complete) {
            current.message.ifBlank { "下载尚未完成" }
        }
        val uri = current.publishedUri?.takeIf { it.isNotBlank() }
            ?.let { value -> runCatching { Uri.parse(value) }.getOrNull() }
            ?: throw IOException("下载文件不存在，请重新下载")
        // provider 拒绝访问或暂时不可用时抛可见读取错误，不能当成文件丢失。
        if (probePublished(app, uri) < 0L) {
            markMissing(app, current)
            throw IOException("下载文件不存在，请重新下载")
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_ZIP)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("qqhook 模块 ZIP", uri)
        return intent
    }

    // ---- 任务记录 ----

    private fun currentRecord(app: Context): ModuleDownloadRecord? =
        synchronized(lock) { ensureLoadedLocked(app) }

    private fun ensureLoadedLocked(app: Context): ModuleDownloadRecord? {
        if (loaded) return record
        // 迁移失败必须每次都能重新报错，而不是被缓存成“没有任务”。
        val restored = readPersisted(app) ?: migrateLegacy(app)
        loaded = true
        record = restored
        restored?.let { updates.value = progress(it) }
        return restored
    }

    private fun readPersisted(app: Context): ModuleDownloadRecord? {
        val prefs = preferences(app)
        if (prefs.getInt("schema", 0) < SCHEMA) return null
        val id = prefs.getLong("id", -1L)
        if (id <= 0L) return null
        val url = prefs.getString("url", null).orEmpty()
        val stored = statusOf(prefs.getString("status", null))
        val status = if (url.isBlank() && stored.isActive()) ModuleDownloadStatus.Missing else stored
        val message = prefs.getString("message", "").orEmpty().ifBlank {
            if (status == ModuleDownloadStatus.Missing) "下载任务信息不完整，请重新下载" else ""
        }
        return ModuleDownloadRecord(
            id = id,
            version = prefs.getString("version", "").orEmpty(),
            versionCode = prefs.getLong("versionCode", 0L),
            fileName = prefs.getString("fileName", "").orEmpty(),
            url = url,
            status = status,
            phase = phaseOf(prefs.getString("phase", null)),
            received = prefs.getLong("received", 0L).coerceAtLeast(0L),
            total = prefs.getLong("total", -1L),
            etag = prefs.getString("etag", null),
            lastModified = prefs.getString("lastModified", null),
            publishedUri = prefs.getString("publishedUri", null),
            pendingUri = prefs.getString("pendingUri", null),
            pendingPath = prefs.getString("pendingPath", null),
            publishPath = prefs.getString("publishPath", null),
            message = message,
            legacyId = prefs.getLong("legacyId", 0L),
        )
    }

    private fun persist(app: Context, value: ModuleDownloadRecord): Boolean {
        return preferences(app).edit()
            .putInt("schema", SCHEMA)
            .putLong("id", value.id)
            .putString("version", value.version)
            .putLong("versionCode", value.versionCode)
            .putString("fileName", value.fileName)
            .putString("url", value.url)
            .putString("status", value.status.name)
            .putString("phase", value.phase.name)
            .putLong("received", value.received)
            .putLong("total", value.total)
            .putString("etag", value.etag)
            .putString("lastModified", value.lastModified)
            .putString("publishedUri", value.publishedUri)
            .putString("pendingUri", value.pendingUri)
            .putString("pendingPath", value.pendingPath)
            .putString("publishPath", value.publishPath)
            .putString("message", value.message)
            .putLong("legacyId", value.legacyId)
            .commit()
    }

    private fun persistQuietly(app: Context, value: ModuleDownloadRecord) {
        runCatching { persist(app, value) }
    }

    private fun clearRecord(app: Context): Boolean {
        val editor = preferences(app).edit()
        recordKeys.forEach { editor.remove(it) }
        return editor.commit()
    }

    private fun allocateId(app: Context): Long {
        val prefs = preferences(app)
        val candidate = maxOf(prefs.getLong("nextId", 0L), System.currentTimeMillis())
        prefs.edit().putLong("nextId", candidate + 1L).commit()
        return candidate
    }

    private fun statusOf(value: String?): ModuleDownloadStatus =
        value?.let { runCatching { ModuleDownloadStatus.valueOf(it) }.getOrNull() }
            ?: ModuleDownloadStatus.Missing

    private fun phaseOf(value: String?): ModuleDownloadPhase =
        value?.let { runCatching { ModuleDownloadPhase.valueOf(it) }.getOrNull() }
            ?: ModuleDownloadPhase.Download

    // ---- 旧 DownloadManager 任务的一次性迁移 ----

    /**
     * 旧 DownloadManager 任务的一次性迁移。
     *
     * 只有真正读到旧任务信息、并且新记录持久化成功之后才会移除旧任务；读取失败（查询异常、
     * 地址不是 HTTPS）时保留旧 prefs 与仍在运行的旧任务，并抛出可见错误，避免旧任务还在
     * 后台下载、界面却允许再起一份重复下载。
     */
    private fun migrateLegacy(app: Context): ModuleDownloadRecord? {
        val prefs = preferences(app)
        val legacyId = prefs.getLong("id", -1L)
        if (legacyId <= 0L) return null
        val version = prefs.getString("version", "").orEmpty()
        val versionCode = prefs.getLong("versionCode", 0L)
        val fileName = prefs.getString("fileName", "").orEmpty()
        val manager = app.getSystemService(DownloadManager::class.java)
            ?: throw IOException("系统下载服务不可用，无法迁移旧下载任务")

        var found = false
        var systemStatus = -1
        var sourceUrl: String? = null
        var total = -1L
        var received = 0L
        try {
            manager.query(DownloadManager.Query().setFilterById(legacyId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    found = true
                    systemStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    sourceUrl = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                    total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                }
            } ?: throw IOException("无法读取旧下载任务")
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("无法读取旧下载任务：${error.message ?: error.javaClass.simpleName}", error)
        }

        if (!found) {
            // 旧任务已经不在系统里，没有任何后台下载：留下可见的缺失记录即可。
            val missing = legacyRecord(app, version, versionCode, fileName,
                ModuleDownloadStatus.Missing, "旧下载任务已不存在，请重新下载")
            if (!persist(app, missing)) throw IOException("无法保存下载任务，请重试")
            return missing
        }

        if (systemStatus == DownloadManager.STATUS_SUCCESSFUL) {
            val uri = try {
                manager.getUriForDownloadedFile(legacyId)?.toString()
            } catch (_: Exception) {
                null
            }
            if (uri.isNullOrBlank()) {
                // 完成记录仍在，但文件已经读不到：不删除历史文件，只标记为缺失。
                val missing = legacyRecord(app, version, versionCode, fileName,
                    ModuleDownloadStatus.Missing, "旧下载文件已无法读取，请重新下载")
                if (!persist(app, missing)) throw IOException("无法保存下载任务，请重试")
                return missing
            }
            // 已完成：保留系统 content URI 与公共文件，不重新下载，也不删除历史。
            val migrated = ModuleDownloadRecord(
                id = allocateId(app),
                version = version,
                versionCode = versionCode,
                fileName = fileName,
                url = "",
                status = ModuleDownloadStatus.Complete,
                phase = ModuleDownloadPhase.Publish,
                received = if (received > 0L) received else total.coerceAtLeast(0L),
                total = if (total > 0L) total else -1L,
                etag = null,
                lastModified = null,
                publishedUri = uri,
                pendingUri = null,
                pendingPath = null,
                message = "下载完成",
            )
            if (!persist(app, migrated)) throw IOException("无法保存下载任务，请重试")
            return migrated
        }

        val url = sourceUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?: throw IOException("旧下载任务缺少 HTTPS 下载地址，无法继续；旧任务保持不变")
        // 未完成：用记录的原始 HTTPS 地址重新开始流式下载，不读取系统预分配的文件长度。
        val created = ModuleDownloadRecord(
            id = allocateId(app),
            version = version,
            versionCode = versionCode,
            fileName = fileName,
            url = url,
            status = ModuleDownloadStatus.Pending,
            phase = ModuleDownloadPhase.Download,
            received = 0L,
            total = -1L,
            etag = null,
            lastModified = null,
            publishedUri = null,
            pendingUri = null,
            pendingPath = null,
            message = "正在恢复旧下载任务",
            legacyId = legacyId,
        )
        // 同一个 commit 里带上待清理的旧任务 id：即使随后进程被杀，恢复时也会先移除旧任务
        // 再启动流式下载，不会留下两份下载。
        if (!persist(app, created)) throw IOException("无法保存下载任务，请重试")
        return created
    }

    private fun legacyRecord(
        app: Context,
        version: String,
        versionCode: Long,
        fileName: String,
        status: ModuleDownloadStatus,
        message: String,
    ): ModuleDownloadRecord = ModuleDownloadRecord(
        id = allocateId(app),
        version = version,
        versionCode = versionCode,
        fileName = fileName,
        url = "",
        status = status,
        phase = ModuleDownloadPhase.Download,
        received = 0L,
        total = -1L,
        etag = null,
        lastModified = null,
        publishedUri = null,
        pendingUri = null,
        pendingPath = null,
        message = message,
    )

    private class LegacyTask(val status: Int, val total: Long, val received: Long)

    /**
     * 读取旧 DownloadManager 任务。
     *
     * 游标为 null（provider 失败）必须抛可见异常并保留 `legacyId`；只有空游标才表示
     * 旧任务确实已经不存在。
     */
    private fun legacyTask(manager: DownloadManager, legacyId: Long): LegacyTask? {
        val cursor = try {
            manager.query(DownloadManager.Query().setFilterById(legacyId))
        } catch (error: Exception) {
            throw IOException("无法读取旧下载任务：${error.message ?: error.javaClass.simpleName}", error)
        } ?: throw IOException("无法读取旧下载任务")
        return try {
            cursor.use {
                if (!it.moveToFirst()) {
                    null
                } else {
                    LegacyTask(
                        status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        received = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    )
                }
            }
        } catch (error: Exception) {
            throw IOException("无法读取旧下载任务：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    /**
     * 解析一次性迁移的旧任务状态，**从不调用 remove**：公开 SDK 无法原子地“仅当仍在
     * 下载才移除”，remove 可能删掉刚刚完成的 ZIP。
     *
     * - 空游标：旧任务已不存在 → 清 `legacyId`
     * - SUCCESSFUL：接管其 content URI（保留文件与系统记录）
     * - FAILED：终态且无下载 → 清 `legacyId`，之后用原始 HTTPS 地址走流式
     * - PENDING/RUNNING/PAUSED：原样返回（仍带 `legacyId`），调用方必须继续等待
     *
     * @throws IOException 查询失败；此时不得丢弃任何所有权信息。
     */
    private fun refreshLegacy(app: Context, current: ModuleDownloadRecord): ModuleDownloadRecord {
        val legacyId = current.legacyId
        if (legacyId <= 0L) return current
        val manager = app.getSystemService(DownloadManager::class.java)
            ?: throw IOException("系统下载服务不可用，无法读取旧下载任务")
        val task = legacyTask(manager, legacyId) ?: return current.copy(legacyId = 0L)
        return when (task.status) {
            DownloadManager.STATUS_SUCCESSFUL -> adoptLegacyCompleted(manager, task, current)
            DownloadManager.STATUS_FAILED -> current.copy(legacyId = 0L)
            else -> current
        }
    }

    /** 读取时解析旧任务，切换状态后立刻持久化；查询失败抛可见错误且保留全部所有权。 */
    private fun withLegacyResolved(app: Context, current: ModuleDownloadRecord): ModuleDownloadRecord {
        if (current.legacyId <= 0L) return current
        synchronized(lock) {
            val live = record
            if (live == null || live.id != current.id) return current
            val resolved = refreshLegacy(app, live)
            if (resolved != live) {
                if (!persist(app, resolved)) throw IOException("无法保存下载任务，请重试")
                record = resolved
                updates.value = progress(resolved)
            }
            return resolved
        }
    }

    /** 旧任务在迁移期间完成：接管它的 content URI 与公共文件，不删除历史下载。 */
    private fun adoptLegacyCompleted(
        manager: DownloadManager,
        task: LegacyTask,
        current: ModuleDownloadRecord,
    ): ModuleDownloadRecord {
        val uri = try {
            manager.getUriForDownloadedFile(current.legacyId)?.toString()
        } catch (_: Exception) {
            null
        }
        if (uri.isNullOrBlank()) {
            return current.copy(
                status = ModuleDownloadStatus.Missing,
                message = "旧下载文件已无法读取，请重新下载",
                legacyId = 0L,
            )
        }
        val received = task.received.takeIf { it > 0L } ?: task.total.coerceAtLeast(0L)
        return current.copy(
            status = ModuleDownloadStatus.Complete,
            phase = ModuleDownloadPhase.Publish,
            received = received,
            total = task.total.takeIf { it > 0L } ?: -1L,
            publishedUri = uri,
            pendingUri = null,
            pendingPath = null,
            publishPath = null,
            message = "下载完成",
            legacyId = 0L,
        )
    }

    // ---- 任务创建/恢复 ----

    private fun createRecord(app: Context, release: ModuleRelease): ModuleDownloadRecord {
        val version = unsafeFileName.replace(release.version, "_").take(40)
        val fileName = "qqhook-$version-${System.currentTimeMillis()}.zip"
        val created = ModuleDownloadRecord(
            id = allocateId(app),
            version = release.version,
            versionCode = release.versionCode,
            fileName = fileName,
            url = release.zipUrl,
            status = ModuleDownloadStatus.Pending,
            phase = ModuleDownloadPhase.Download,
            received = 0L,
            total = -1L,
            etag = null,
            lastModified = null,
            publishedUri = null,
            pendingUri = null,
            pendingPath = null,
            message = "等待开始下载",
        )
        if (!persist(app, created)) error("无法保存下载任务，请重试")
        generation++
        assignedGeneration = -1L
        record = created
        updates.value = progress(created)
        return created
    }

    /**
     * `If-Range` 只接受 strong 验证器：strong ETag 或 Last-Modified。
     * 弱 ETag（`W/"…"`）不能用于续传，此时只能整包重下。
     */
    private fun hasResumableValidator(current: ModuleDownloadRecord): Boolean {
        val tag = current.etag?.trim()
        if (!tag.isNullOrEmpty() && !tag.startsWith("W/", ignoreCase = true)) return true
        return !current.lastModified.isNullOrBlank()
    }

    private fun resumeReady(app: Context, current: ModuleDownloadRecord): Boolean {
        val part = partFile(app, current.id)
        if (!part.isFile || part.length() <= 0L) return false
        // 没有可用验证器就无法证明服务器上是同一份文件，只能从头下载。
        if (!hasResumableValidator(current)) return false
        return current.total <= 0L || part.length() < current.total
    }

    private fun publishRetryReady(app: Context, current: ModuleDownloadRecord): Boolean {
        if (current.phase != ModuleDownloadPhase.Publish) return false
        val part = partFile(app, current.id)
        return part.isFile && part.length() > 0L
    }

    private fun prepareResume(app: Context, current: ModuleDownloadRecord): ModuleDownloadRecord =
        restart(app, current, "等待继续下载")

    private fun preparePublishRetry(app: Context, current: ModuleDownloadRecord): ModuleDownloadRecord =
        restart(app, current, "等待保存到下载目录")

    private fun restart(app: Context, current: ModuleDownloadRecord, message: String): ModuleDownloadRecord {
        generation++
        assignedGeneration = -1L
        val resumed = current.copy(status = ModuleDownloadStatus.Pending, message = message)
        if (!persist(app, resumed)) error("无法保存下载任务，请重试")
        record = resumed
        updates.value = progress(resumed)
        return resumed
    }

    private fun discardLocked(app: Context, current: ModuleDownloadRecord) {
        // 旧系统任务未终态时绝不能丢弃所有权信息。
        if (current.legacyId > 0L) error("旧系统下载任务仍在进行，请稍后重试")
        val expected = expectedSize(app, current)
        // 公开状态无法确认时抛错：此时记录与文件都必须原样保留。
        val adopted = resolvePublished(app, current, expected)
        // 先持久清记录，失败则中止替换，任务保持原样。
        if (!clearRecord(app)) throw IOException("无法保存下载状态，请重试")
        generation++
        assignedGeneration = -1L
        stopActiveTransfer()
        record = null
        updates.value = progress(current.copy(status = ModuleDownloadStatus.Missing, message = "下载任务已变更"))
        partFile(app, current.id).delete()
        deleteOwnedPublication(app, current, adopted?.toString())
    }

    private fun resumeIfNeeded(app: Context, current: ModuleDownloadRecord) {
        synchronized(lock) { resumeIfNeededLocked(app, current) }
    }

    private fun resumeIfNeededLocked(app: Context, current: ModuleDownloadRecord) {
        if (!current.status.isActive()) return
        val live = record ?: return
        if (live.id != current.id) return
        if (assignedGeneration == generation) return
        val claimed = generation
        assignedGeneration = claimed
        try {
            executor.execute { runWorker(app, claimed) }
        } catch (error: Throwable) {
            assignedGeneration = -1L
            throw error
        }
    }

    // ---- worker ----

    private fun isCurrent(gen: Long): Boolean = gen == generation

    private fun ensureCurrent(gen: Long) {
        if (!isCurrent(gen)) throw WorkerCancelled()
    }

    /**
     * 最终“公开”步骤必须与 [cancel] 使用同一把锁线性化：
     * 取消先拿锁则 generation 变化，旧 worker 在这里中止、绝不会再公开；
     * worker 先拿锁则先完成公开并持久化 URI，取消随后读到已公开状态，保留文件。
     * 只包住收尾（IS_PENDING=0 / DM 注册 / 状态写回），不锁网络与拷贝。
     */
    private fun <T> withPublicationLock(gen: Long, block: () -> T): T = synchronized(lock) {
        ensureCurrent(gen)
        block()
    }

    /**
     * 整包 200 前清空 `.part` 并落盘；必须先于持久化新验证器执行，避免“旧前缀 + 新
     * identity”在进程被杀后拼出同长度的坏 ZIP，或被当成已完成直接发布。
     */
    private fun truncatePart(part: File) {
        part.parentFile?.mkdirs()
        val output = FileOutputStream(part, false)
        try {
            output.flush()
            runCatching { output.fd.sync() }
        } finally {
            runCatching { output.close() }
        }
    }

    /**
     * 取消时同时断开“已经返回的传输”和“仍在连接/等待响应头”的连接句柄；
     * 后者的迟到结果会因 generation 不符被丢弃，绝不可能写到新任务上。
     */
    private fun stopActiveTransfer() {
        transfer?.close()
        transfer = null
        activeConnection?.let { connection -> runCatching { connection.disconnect() } }
        activeConnection = null
    }

    private fun runWorker(app: Context, gen: Long) {
        val current = synchronized(lock) {
            if (!isCurrent(gen)) return
            record
        } ?: return
        try {
            if (current.legacyId > 0L) {
                awaitLegacy(app, gen, current)
                return
            }
            when (current.phase) {
                ModuleDownloadPhase.Publish -> publish(app, gen, current)
                ModuleDownloadPhase.Download -> {
                    val downloaded = download(app, gen, current)
                    publish(app, gen, downloaded)
                }
            }
        } catch (_: WorkerCancelled) {
            // 已取消或被新任务替换：绝不回写任何状态。
        } catch (error: Throwable) {
            fail(app, gen, error)
        } finally {
            transfer = null
            activeConnection = null
            // 等待或异常退出后，下一次 load/query 仍能重新拉起检查（任务仍活动时）。
            synchronized(lock) {
                if (assignedGeneration == gen && record?.status?.isActive() == true) {
                    assignedGeneration = -1L
                }
            }
        }
    }

    /**
     * 一次性迁移的等待：旧 DownloadManager 任务仍在进行时只轮询它的状态，
     * 不发起网络、不启动流式下载，也绝不 remove（公开 SDK 无法原子“仅 running 才移除”，
     * 误删刚完成的 ZIP 不可逆）。
     */
    private fun awaitLegacy(app: Context, gen: Long, initial: ModuleDownloadRecord) {
        var current = initial
        if (current.status != ModuleDownloadStatus.Pending || current.message != WAIT_LEGACY_MESSAGE) {
            current = update(
                app, gen, current.copy(
                    status = ModuleDownloadStatus.Pending,
                    phase = ModuleDownloadPhase.Download,
                    message = WAIT_LEGACY_MESSAGE,
                ),
            )
        }
        while (true) {
            ensureCurrent(gen)
            val resolved = try {
                refreshLegacy(app, current)
            } catch (error: IOException) {
                // 保持 Pending（不写 Failed），下次 load/query 会重新解析。
                update(app, gen, current.copy(message = error.message ?: "无法读取旧下载任务，稍后重试"))
                return
            }
            if (!isCurrent(gen)) throw WorkerCancelled()
            if (resolved.legacyId <= 0L) {
                if (resolved.status == ModuleDownloadStatus.Complete) {
                    val done = update(app, gen, resolved.copy(message = "下载完成"))
                    resolved.publishedUri?.takeIf { it.isNotBlank() }?.let { value ->
                        runCatching { Uri.parse(value) }.getOrNull()?.let { uri ->
                            ModuleDownloadNotification.complete(app, progress(done), uri)
                        }
                    }
                    return
                }
                // 旧任务失败或已不存在：用持久化的原始 HTTPS 地址走流式下载。
                val streaming = update(
                    app, gen, resolved.copy(
                        status = ModuleDownloadStatus.Pending,
                        phase = ModuleDownloadPhase.Download,
                        message = "等待开始下载",
                    ),
                )
                val downloaded = download(app, gen, streaming)
                publish(app, gen, downloaded)
                return
            }
            try {
                Thread.sleep(LEGACY_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw WorkerCancelled()
            }
        }
    }

    private fun download(app: Context, gen: Long, initial: ModuleDownloadRecord): ModuleDownloadRecord {
        val part = partFile(app, initial.id)
        part.parentFile?.mkdirs()
        val lengthOnDisk = if (part.isFile) part.length() else 0L
        // 长度恰好等于总长度不足以证明内容完整（可能是上一份实体的旧前缀），
        // 只有 copyBody 在 EOF 校验并 fsync 后写入 phase=Publish 才算完成。
        val canResume = lengthOnDisk > 0L &&
            hasResumableValidator(initial) &&
            (initial.total <= 0L || lengthOnDisk < initial.total)
        var current = update(
            app, gen, initial.copy(
                status = ModuleDownloadStatus.Running,
                received = if (canResume) lengthOnDisk else 0L,
                message = if (canResume) "继续下载" else "正在下载",
            ),
        )
        val request: (Long) -> ModuleDownloadTransfer = { rangeOffset ->
            ModuleDownloadTransfer.open(
                url = current.url,
                offset = rangeOffset,
                etag = current.etag,
                lastModified = current.lastModified,
                expectedTotal = current.total,
                onConnection = { connection -> activeConnection = connection },
            )
        }
        val opened = try {
            request(if (canResume) lengthOnDisk else 0L)
        } catch (rejected: ModuleDownloadTransfer.ResumeRejected) {
            // 服务器不接受该断点：同一任务身份下从头重写，绝不在旧字节后追加。
            part.delete()
            current = update(
                app, gen, current.copy(
                    received = 0L,
                    total = -1L,
                    etag = null,
                    lastModified = null,
                    message = "正在重新下载",
                ),
            )
            request(0L)
        } catch (invalid: ModuleDownloadTransfer.InvalidRange) {
            // 服务器返回的断点数据不可信：丢弃本地部分文件，避免拼出损坏的 ZIP。
            part.delete()
            update(app, gen, current.copy(received = 0L, total = -1L, etag = null, lastModified = null))
            throw IOException("${invalid.message ?: "服务器返回的断点数据无效"}，请重新下载", invalid)
        }
        transfer = opened
        try {
            ensureCurrent(gen)
            val append = opened.partial
            // 整包 200 不能沿用旧实体身份：先清空并同步 `.part`，之后才提交新的
            // 验证器/总长度，这样持久化的新 identity 只可能对应空文件或新实体字节。
            if (!append) truncatePart(part)
            ensureCurrent(gen)
            val startOffset = if (append) lengthOnDisk else 0L
            current = update(
                app, gen, current.copy(
                    status = ModuleDownloadStatus.Running,
                    received = startOffset,
                    total = opened.totalBytes,
                    etag = opened.etag ?: if (append) current.etag else null,
                    lastModified = opened.lastModified ?: if (append) current.lastModified else null,
                    message = "正在下载",
                ),
            )
            return copyBody(app, gen, current, opened, part, append, startOffset)
        } finally {
            opened.close()
            transfer = null
        }
    }

    private fun copyBody(
        app: Context,
        gen: Long,
        initial: ModuleDownloadRecord,
        opened: ModuleDownloadTransfer,
        part: File,
        append: Boolean,
        startOffset: Long,
    ): ModuleDownloadRecord {
        var received = startOffset
        var lastPublishedAt = 0L
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        val output = FileOutputStream(part, append)
        try {
            while (true) {
                ensureCurrent(gen)
                val read = try {
                    opened.stream.read(buffer)
                } catch (error: IOException) {
                    val expected = if (initial.total > 0L) " / ${formatBytes(initial.total)}" else ""
                    throw IOException(
                        "下载连接中断：已接收 ${formatBytes(received)}$expected（${error.message ?: "连接已断开"}）",
                        error,
                    )
                }
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                received += read
                val now = System.currentTimeMillis()
                // 只在内存里合并进度（最多 100ms 一次），字节数不逐块写盘。
                if (now - lastPublishedAt >= PROGRESS_INTERVAL_MS) {
                    lastPublishedAt = now
                    publishProgressOnly(app, gen, initial.copy(received = received))
                }
            }
            output.flush()
            runCatching { output.fd.sync() }
        } finally {
            runCatching { output.close() }
        }
        val expected = initial.total
        if (expected > 0L && received != expected) {
            throw IOException("下载中断：已接收 ${formatBytes(received)} / ${formatBytes(expected)} 字节，请重试")
        }
        if (part.length() != received) throw IOException("下载文件写入不完整，请重试")
        return update(
            app, gen, initial.copy(
                received = received,
                total = if (expected > 0L) expected else received,
                status = ModuleDownloadStatus.Running,
                phase = ModuleDownloadPhase.Publish,
                message = "正在保存到下载目录",
            ),
        )
    }

    private fun publish(app: Context, gen: Long, initial: ModuleDownloadRecord): ModuleDownloadRecord {
        val part = partFile(app, initial.id)
        val expected = expectedSize(app, initial)
        // 先接管“已经公开但没来得及写回 Complete”的文件：用户文件绝不因恢复/清理被删。
        val settled = settlePublication(app, gen, initial, expected)
        settled.publishedUri?.takeIf { it.isNotBlank() }?.let { value ->
            // settlePublication 只会在 resolvePublished 已确认可读完整时保留这个 URI。
            return completeFromPublication(app, gen, settled, Uri.parse(value), expected)
        }
        if (!part.isFile || part.length() <= 0L) throw IOException("下载文件已丢失，请重新下载")
        val current = update(
            app, gen, settled.copy(
                status = ModuleDownloadStatus.Running,
                phase = ModuleDownloadPhase.Publish,
                received = part.length(),
                total = if (settled.total > 0L) settled.total else part.length(),
                message = "正在保存到下载目录",
            ),
        )
        ensureCurrent(gen)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(app, gen, current, part)
        } else {
            publishToDownloads(app, gen, current, part)
        }
        if (!isCurrent(gen)) {
            // 取消撞上发布：只有确实尚未公开的半成品才撤销，已公开的文件必须保留。
            if (canDeleteUnpublished(app, uri)) {
                runCatching { app.contentResolver.delete(uri, null, null) }
            }
            throw WorkerCancelled()
        }
        return completeFromPublication(app, gen, currentLive(gen), uri, part.length())
    }

    private fun currentLive(gen: Long): ModuleDownloadRecord = synchronized(lock) {
        ensureCurrent(gen)
        record ?: throw WorkerCancelled()
    }

    /** 本任务期望的完整长度：已知总长度优先，其次已接收字节与真实 `.part` 长度。 */
    private fun expectedSize(app: Context, current: ModuleDownloadRecord): Long {
        val part = partFile(app, current.id)
        val onDisk = if (part.isFile) part.length() else 0L
        return maxOf(current.total, current.received, onDisk)
    }

    private fun readableSize(app: Context, uri: Uri): Long = try {
        app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    /** MediaStore 行的真实状态：只有查询成功才能区分 Absent / Pending / Settled。 */
    private enum class MediaRowState { Settled, Pending, Absent, Unknown }

    private fun isMediaStoreUri(uri: Uri): Boolean =
        uri.authority?.equals(MediaStore.AUTHORITY, ignoreCase = true) == true

    private fun mediaRowState(app: Context, uri: Uri): MediaRowState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MediaRowState.Unknown
        if (!isMediaStoreUri(uri)) return MediaRowState.Unknown
        return try {
            val cursor = app.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null,
            ) ?: return MediaRowState.Unknown
            cursor.use {
                if (!it.moveToFirst()) return MediaRowState.Absent
                val column = it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                if (column < 0 || it.isNull(column)) return MediaRowState.Unknown
                if (it.getInt(column) == 0) MediaRowState.Settled else MediaRowState.Pending
            }
        } catch (_: Exception) {
            MediaRowState.Unknown
        }
    }

    /**
     * 只有明确 `IS_PENDING=1` 的 MediaStore 行才允许删除。查询失败、缺列、行不存在、
     * 非 MediaStore 行（API28 注册 URI）一律不删。
     */
    private fun canDeleteUnpublished(app: Context, uri: Uri): Boolean =
        mediaRowState(app, uri) == MediaRowState.Pending

    /**
     * 核实 Complete 记录的真实文件。
     *
     * @return 可读长度；-1 表示文件确实不存在（FileNotFoundException/无 descriptor）。
     * @throws IOException provider 拒绝访问或暂时不可用——这时不能当作文件丢失。
     */
    private fun probePublished(app: Context, uri: Uri): Long = try {
        val descriptor = app.contentResolver.openAssetFileDescriptor(uri, "r")
        descriptor?.use { it.length } ?: -1L
    } catch (_: FileNotFoundException) {
        -1L
    } catch (error: SecurityException) {
        throw IOException("无法读取下载文件：${error.message ?: "权限被拒绝"}", error)
    } catch (error: Exception) {
        throw IOException("无法读取下载文件：${error.message ?: error.javaClass.simpleName}", error)
    }

    private class DmRegistration(val registered: Boolean, val uri: String?)

    /** 按文件路径查 DownloadManager 里已注册的完成下载；null 表示无法判断（不得据此删除）。 */
    private fun findRegisteredDownload(app: Context, path: String?): DmRegistration? {
        if (path.isNullOrBlank()) return DmRegistration(false, null)
        val manager = app.getSystemService(DownloadManager::class.java) ?: return null
        val local = Uri.fromFile(File(path)).toString()
        return try {
            manager.query(DownloadManager.Query().setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL))
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use DmRegistration(false, null)
                    val localColumn = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
                    if (localColumn < 0 || idColumn < 0) return@use null
                    do {
                        if (cursor.getString(localColumn) == local) {
                            val id = cursor.getLong(idColumn)
                            return@use DmRegistration(true, manager.getUriForDownloadedFile(id)?.toString())
                        }
                    } while (cursor.moveToNext())
                    DmRegistration(false, null)
                }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 判定发布痕迹：
     * - 返回 URI：确认已公开、可读且长度完整；
     * - 返回 null：确认没有已公开文件（行不存在/IS_PENDING=1/DM 明确未注册），可安全重发并清标记；
     * - 抛 IOException：状态无法确认（查询失败、缺列、已公开但读不到），必须保留记录与文件。
     */
    private fun resolvePublished(
        app: Context,
        current: ModuleDownloadRecord,
        expected: Long,
    ): Uri? {
        val candidates = ArrayList<String>(4)
        current.publishedUri?.takeIf { it.isNotBlank() }?.let(candidates::add)
        current.pendingUri?.takeIf { it.isNotBlank() }?.let(candidates::add)
        registeredUri(app, current.pendingPath)?.let(candidates::add)
        registeredUri(app, current.publishPath)?.let(candidates::add)
        for (value in candidates) {
            val uri = classifyPublished(app, value, expected)
            if (uri != null) return uri
        }
        return null
    }

    /** 已注册路径的 DM URI；明确未注册返回 null；无法判断抛 IOException。 */
    private fun registeredUri(app: Context, path: String?): String? {
        if (path.isNullOrBlank()) return null
        val registration = findRegisteredDownload(app, path)
            ?: throw IOException("无法确认下载文件是否已注册，请稍后重试")
        return if (registration.registered) registration.uri else null
    }

    private fun classifyPublished(app: Context, value: String, expected: Long): Uri? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (isMediaStoreUri(uri)) {
            return when (mediaRowState(app, uri)) {
                // 明确未公开或行已不存在：可以清掉这个 URI 所有权标记并重发。
                MediaRowState.Pending, MediaRowState.Absent -> null
                MediaRowState.Unknown -> throw IOException("无法确认下载文件是否已公开，请稍后重试")
                MediaRowState.Settled -> {
                    val size = readableSize(app, uri)
                    // 已公开但读不到/长度不符：不接管，也不删除（删除只发生在 IS_PENDING=1），
                    // 让发布流程重新生成一份可用文件，而不是把用户卡在无法取消的状态。
                    if (size >= 0L && (expected <= 0L || size == expected)) uri else null
                }
            }
        }
        // 非 MediaStore：只可能来自 addCompletedDownload（注册即公开）。
        val size = readableSize(app, uri)
        if (size >= 0L && (expected <= 0L || size == expected)) return uri
        // 注册行存在但文件不可读：同样不接管、也不删除。
        return null
    }

    /**
     * 发布前清点：只删除确定未公开的新行与临时文件。`publishPath` 是后续注册要用的
     * 独占最终路径，这里绝不删除，交由发布流程继续使用。
     */
    private fun deleteUnpublishedArtifacts(
        app: Context,
        current: ModuleDownloadRecord,
        keepUri: String?,
    ) {
        current.pendingUri?.takeIf { it != keepUri }?.let { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull()
            if (uri != null && canDeleteUnpublished(app, uri)) {
                runCatching { app.contentResolver.delete(uri, null, null) }
            }
        }
        current.pendingPath?.let { path ->
            if (findRegisteredDownload(app, path)?.registered == false) {
                runCatching { File(path).delete() }
            }
        }
    }

    /**
     * 取消/替换任务时的清理：在 [deleteUnpublishedArtifacts] 之外，还可以删除我们
     * 独占但确认未注册的最终文件（`publishPath`）。已公开或无法判断的一律保留。
     */
    private fun deleteOwnedPublication(
        app: Context,
        current: ModuleDownloadRecord,
        keepUri: String?,
    ) {
        deleteUnpublishedArtifacts(app, current, keepUri)
        current.publishPath?.let { path ->
            if (findRegisteredDownload(app, path)?.registered == false) {
                runCatching { File(path).delete() }
            }
        }
    }

    /** 发布前先接管旧痕迹，返回清理后的记录；已公开的 URI 会保留在 `publishedUri`。 */
    private fun settlePublication(
        app: Context,
        gen: Long,
        current: ModuleDownloadRecord,
        expected: Long,
    ): ModuleDownloadRecord {
        if (current.publishedUri == null && current.pendingUri == null &&
            current.pendingPath == null && current.publishPath == null
        ) {
            return current
        }
        val adopted = resolvePublished(app, current, expected)
        deleteUnpublishedArtifacts(app, current, adopted?.toString())
        return update(
            app, gen, current.copy(
                publishedUri = adopted?.toString(),
                pendingUri = null,
                pendingPath = null,
                // 未接管时保留 API28 的独占最终路径，发布流程会继续使用它。
                publishPath = if (adopted != null) null else current.publishPath,
            ),
        )
    }

    private fun completeFromPublication(
        app: Context,
        gen: Long,
        current: ModuleDownloadRecord,
        uri: Uri,
        size: Long,
    ): ModuleDownloadRecord {
        val finalSize = if (size > 0L) size else expectedSize(app, current)
        val done = update(
            app, gen, current.copy(
                status = ModuleDownloadStatus.Complete,
                phase = ModuleDownloadPhase.Publish,
                received = finalSize,
                total = finalSize,
                publishedUri = uri.toString(),
                pendingUri = null,
                pendingPath = null,
                publishPath = null,
                message = "下载完成",
            ),
        )
        partFile(app, current.id).delete()
        ModuleDownloadNotification.complete(app, progress(done), uri)
        return done
    }

    private fun publishToMediaStore(
        app: Context,
        gen: Long,
        current: ModuleDownloadRecord,
        part: File,
    ): Uri {
        val resolver = app.contentResolver
        var created: Uri? = null
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, current.fileName)
                put(MediaStore.Downloads.MIME_TYPE, MIME_ZIP)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = try {
                resolver.insert(collection, values)
            } catch (error: Exception) {
                throw IOException("无法在下载目录创建文件：${error.message ?: error.javaClass.simpleName}", error)
            } ?: throw IOException("无法在下载目录创建文件")
            created = uri
            // 记录待发布条目与写盘同属一个事务：persist 失败也由 catch 撤销这个新行。
            update(app, gen, current.copy(pendingUri = uri.toString()))
            resolver.openOutputStream(uri)?.use { output -> writePartTo(output, part, gen) }
                ?: throw IOException("无法写入下载目录")
            ensureCurrent(gen)
            // 公开与取消在同一把锁内线性化：被取消的 worker 不得再公开任何文件。
            withPublicationLock(gen) {
                val settled = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                if (resolver.update(uri, settled, null, null) <= 0) throw IOException("无法完成下载文件")
                // 先把“已公开”写进记录：进程随后被杀也能接管，而不是当成未发布半成品。
                update(app, gen, currentLive(gen).copy(publishedUri = uri.toString()))
            }
            return uri
        } catch (error: Throwable) {
            // 只有仍处于 IS_PENDING 的半成品可以撤销；已公开或状态不明的文件必须保留。
            created?.let { uri ->
                if (canDeleteUnpublished(app, uri)) {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
            throw error
        }
    }

    /**
     * Android 9：在公共“下载”目录独占预留最终文件，并以可恢复的三步发布：
     * 1) 先把独占的最终路径（`publishPath`）持久化；
     * 2) 写入完整的临时文件（长度必须等于期望），再 `Os.rename` 原子替换预留位；
     * 3) 注册为已完成下载并持久化 content URI。
     *
     * `pendingPath` 只用于记录临时文件。恢复时只使用 `publishPath`，绝不另起名字；
     * 无法证明所有权或长度不符的文件不覆盖、不删除。
     */
    private fun publishToDownloads(
        app: Context,
        gen: Long,
        current: ModuleDownloadRecord,
        part: File,
    ): Uri {
        if (needsStoragePermission(app)) throw IOException("保存到下载目录需要存储权限")
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("下载目录不可用")
        if (!part.isFile) throw IOException("下载文件已丢失，请重新下载")
        val expected = part.length()
        if (expected <= 0L) throw IOException("下载文件已丢失，请重新下载")

        val temp = File(directory, "${current.fileName}.${current.id}.part")
        val existing = current.publishPath?.takeIf { it.isNotBlank() }
        val target: File
        if (existing != null) {
            target = File(existing)
            // 恢复：rename 已经完成、只差注册。
            if (target.isFile && target.length() == expected) {
                return registerAndClean(app, gen, target, temp)
            }
            if (target.isFile && target.length() > 0L) {
                throw IOException("下载目录中存在同名文件，无法覆盖")
            }
        } else {
            target = reserveTarget(directory, current.fileName)
            try {
                // 所有权先落盘：rename 之前记录最终路径，进程死亡后只认这个路径。
                update(app, gen, current.copy(publishPath = target.absolutePath))
            } catch (error: Throwable) {
                runCatching { target.delete() }
                throw error
            }
        }
        if (!target.isFile && !target.createNewFile()) throw IOException("无法在下载目录创建文件")
        if (target.length() != 0L) throw IOException("下载目录中的预留文件已被占用")

        update(app, gen, currentLive(gen).copy(pendingPath = temp.absolutePath))
        if (!(temp.isFile && temp.length() == expected)) {
            writePublicTemp(temp, part, gen, expected)
        }
        ensureCurrent(gen)
        renameAtomically(temp, target)
        if (!isCurrent(gen)) {
            // 改名期间被取消：这是我们独占且未注册的文件，撤销它避免孤儿。
            runCatching { target.delete() }
            throw WorkerCancelled()
        }
        return registerAndClean(app, gen, target, temp)
    }

    /** 注册成功后清理公共临时文件（rename 已消费时为空操作）。 */
    private fun registerAndClean(app: Context, gen: Long, target: File, temp: File): Uri {
        return try {
            registerTarget(app, gen, target)
        } finally {
            if (temp.isFile) runCatching { temp.delete() }
        }
    }

    /** 用 createNewFile 原子独占最终文件名，返回预留的空文件。 */
    private fun reserveTarget(directory: File, fileName: String): File {
        val hasExtension = fileName.contains('.')
        val base = if (hasExtension) fileName.substringBeforeLast('.') else fileName
        val extension = if (hasExtension) "." + fileName.substringAfterLast('.') else ""
        var index = 0
        while (index < 100) {
            val candidate = if (index == 0) {
                File(directory, fileName)
            } else {
                File(directory, "$base ($index)$extension")
            }
            if (candidate.createNewFile()) return candidate
            index++
        }
        throw IOException("下载目录中同名文件过多")
    }

    /** 从已校验长度的私有 `.part` 重建公共临时文件，长度必须与期望完全一致。 */
    private fun writePublicTemp(temp: File, part: File, gen: Long, expected: Long) {
        if (!part.isFile || part.length() != expected) throw IOException("下载文件不完整，请重新下载")
        val output = FileOutputStream(temp, false)
        try {
            writePartTo(output, part, gen)
            output.flush()
            runCatching { output.fd.sync() }
        } finally {
            runCatching { output.close() }
        }
        if (!temp.isFile || temp.length() != expected) throw IOException("下载文件写入不完整，请重试")
    }

    private fun renameAtomically(from: File, to: File) {
        try {
            Os.rename(from.absolutePath, to.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("无法保存下载文件（errno ${error.errno}）", error)
        }
    }

    /** 注册已完整的最终文件并持久化公开 URI；注册后即为公开，删除只能由用户进行。 */
    private fun registerTarget(app: Context, gen: Long, target: File): Uri {
        val service = app.getSystemService(DownloadManager::class.java)
            ?: throw IOException("系统下载服务不可用")
        // 注册是“公开”的线性化点：与 cancel 共用同一把锁，被取消的 worker 不能再登记。
        return withPublicationLock(gen) {
            val registeredId = try {
                service.addCompletedDownload(
                    target.name,
                    "qqhook 模块更新",
                    true,
                    MIME_ZIP,
                    target.absolutePath,
                    target.length(),
                    false,
                )
            } catch (error: Exception) {
                throw IOException("无法注册下载文件：${error.message ?: error.javaClass.simpleName}", error)
            }
            if (registeredId <= 0L) throw IOException("无法注册下载文件")
            val uri = service.getUriForDownloadedFile(registeredId)
                ?: throw IOException("无法读取已保存的下载文件")
            // 注册即公开：先写回记录，之后即使进程被杀也能按 URI 接管，绝不按半成品删除。
            update(
                app, gen, currentLive(gen).copy(
                    publishedUri = uri.toString(),
                    pendingUri = uri.toString(),
                ),
            )
            uri
        }
    }

    private fun writePartTo(output: OutputStream, part: File, gen: Long) {
        FileInputStream(part).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                ensureCurrent(gen)
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
            }
            output.flush()
        }
    }

    // ---- 状态写入 ----

    private fun progress(value: ModuleDownloadRecord): ModuleDownloadProgress =
        ModuleDownloadProgress(value.toDownload(), value.status, value.received, value.total, value.message)

    private fun update(app: Context, gen: Long, next: ModuleDownloadRecord): ModuleDownloadRecord {
        synchronized(lock) {
            ensureCurrent(gen)
            val live = record ?: throw WorkerCancelled()
            if (live.id != next.id) throw WorkerCancelled()
            // 与取消/替换共用同一把锁：旧 worker 绝不能在 clearRecord 之后把状态写回来。
            if (!persist(app, next)) throw IOException("无法保存下载状态，请重试")
            record = next
            updates.value = progress(next)
        }
        ModuleDownloadNotification.update(app, progress(next))
        return next
    }

    private fun publishProgressOnly(app: Context, gen: Long, snapshot: ModuleDownloadRecord) {
        val value = synchronized(lock) {
            ensureCurrent(gen)
            val live = record ?: throw WorkerCancelled()
            if (live.id != snapshot.id) throw WorkerCancelled()
            val next = live.copy(received = snapshot.received)
            record = next
            progress(next).also { updates.value = it }
        }
        ModuleDownloadNotification.update(app, value)
    }

    private fun fail(app: Context, gen: Long, error: Throwable) {
        if (error is WorkerCancelled) return
        val message = describeFailure(error)
        synchronized(lock) {
            if (!isCurrent(gen)) return
            val live = record ?: return
            val next = live.copy(status = ModuleDownloadStatus.Failed, message = message)
            persistQuietly(app, next)
            record = next
            updates.value = progress(next)
        }
        ModuleDownloadNotification.failed(app, message)
    }

    private fun describeFailure(error: Throwable): String = when (error) {
        is UnknownHostException -> "无法连接下载服务器：域名解析失败"
        is SocketTimeoutException -> "下载超时，请检查网络后重试"
        is ConnectException -> "无法连接下载服务器：${error.message ?: "连接被拒绝"}"
        is SSLException -> "下载连接不安全：${error.message ?: "TLS 握手失败"}"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "下载失败（${error.javaClass.simpleName}）"
    }

    private fun markMissing(app: Context, current: ModuleDownloadRecord): ModuleDownloadRecord {
        val next = current.copy(status = ModuleDownloadStatus.Missing, message = "下载文件不存在，请重新下载")
        synchronized(lock) {
            val live = record
            if (live == null || live.id != current.id) {
                // 任务已被替换：只给这个旧 id 返回缺失快照，不写坏新任务。
                return next
            }
            record = next
            updates.value = progress(next)
            persistQuietly(app, next)
        }
        return next
    }

    /**
     * Complete 只是快照：用户可能在系统里删除了公共 ZIP，也可能 provider 暂时不可读。
     * 文件确实不存在时持久标成 Missing；读取错误（权限/临时 provider 问题）抛可见异常，
     * 不能当成“文件丢失”而允许重复下载。
     */
    private fun verifyPublished(app: Context, current: ModuleDownloadRecord): ModuleDownloadRecord {
        if (current.status != ModuleDownloadStatus.Complete) return current
        val uri = current.publishedUri?.takeIf { it.isNotBlank() }
            ?.let { value -> runCatching { Uri.parse(value) }.getOrNull() }
            ?: return markMissing(app, current)
        val size = probePublished(app, uri)
        val expected = expectedSize(app, current)
        if (size < 0L || (expected > 0L && size != expected)) return markMissing(app, current)
        return current
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024L -> "$value B"
        value < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
    }

    private fun missing(download: ModuleDownload) = ModuleDownloadProgress(
        download, ModuleDownloadStatus.Missing, 0, -1, "下载文件或任务已不存在，请重新下载",
    )
}
