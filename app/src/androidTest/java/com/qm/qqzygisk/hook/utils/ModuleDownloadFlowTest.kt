package com.qm.qqzygisk.hook.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.qm.qqzygisk.ui.setting.ModuleDownloadControls
import com.qm.qqzygisk.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * 模块 ZIP 下载的完整业务流回归：真实本机回环 HTTPS 字节，不用 mock 或文本回声。
 *
 * 覆盖：
 *  1. 已知长度慢速 HTTPS：真实中间进度、关闭 observer 不取消 worker、`load` 恢复同一
 *     身份、EOF 后从公开 Downloads 打开 ZIP 且 SHA-256 与源字节一致；
 *  2. 未知长度（chunked）：运行中 `totalBytes == -1`；取消后连接被及时关闭、`.part`
 *     清理、不发布也不可打开；随后新任务不被旧 worker 晚到写入覆盖；
 *  3. 真实截断与 HTTP 500：一律 Failed（绝不伪完成），重试是完整新任务且字节正确。
 *
 * 隔离：只操作本测试创建的任务/公开条目，finally 取消并删除；开始时快照
 * [ModuleDownloads] 的偏好设置，结束时恢复，故同套件里既有的完成下载不会被删除。
 * TLS 只在本测试进程临时信任回环测试证书，finally 恢复默认值。
 */
@RunWith(AndroidJUnit4::class)
class ModuleDownloadFlowTest {

    private lateinit var instrumentation: android.app.Instrumentation
    private lateinit var context: Context
    private lateinit var testContext: Context
    private var server: LoopbackHttpsServer? = null
    private var restoreTrust: Closeable? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val observers = CopyOnWriteArrayList<Job>()
    private val started = CopyOnWriteArrayList<ModuleDownload>()
    private val publishedUris = CopyOnWriteArrayList<Uri>()
    private val probePids = CopyOnWriteArrayList<Int>()
    private var probeRunToken: String? = null
    private var preferencesSnapshot: Map<String, Any?> = emptyMap()

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        testContext = instrumentation.context
        grantLegacyStoragePermissionIfNeeded()
        preferencesSnapshot = preferences().all.toMap()
        restoreTrust = LoopbackTls.installProcessDefaults(testContext)
    }

    @After
    fun tearDown() {
        val runToken = probeRunToken
        val probeSnapshot = readProbeSnapshot()
        val snapshotPid = probeSnapshot
            ?.takeIf { runToken != null && it.optString("run") == runToken }
            ?.optInt("pid", 0)
            ?: 0
        if (snapshotPid > 0 && snapshotPid != Process.myPid()) probePids.add(snapshotPid)
        probePids.forEach { pid ->
            if (pid > 0 && pid != Process.myPid()) {
                runCatching { Process.killProcess(pid) }
            }
        }
        probePids.forEach { pid -> runCatching { awaitProcessGone(pid) } }
        probePids.clear()
        // 只删除已发布的 base 快照；`.new` 归 writer 所有，读者/清理者绝不触碰。
        File(context.filesDir, DOWNLOAD_PROBE_SNAPSHOT).delete()
        probeSnapshot?.takeIf { runToken != null && it.optString("run") == runToken }?.let { snapshot ->
            val probeId = snapshot.optLong("id", 0L)
            if (probeId > 0L) ModuleDownloads.partFile(context, probeId).delete()
            val probeUri = snapshot.optString("uri")
            if (probeUri.isNotBlank() && snapshot.optString("status") == "Complete") {
                runCatching { context.contentResolver.delete(Uri.parse(probeUri), null, null) }
            }
        }
        started.forEach { download -> runCatching { ModuleDownloads.cancel(context, download) } }
        awaitUntil(10_000) {
            started.all { download ->
                runCatching { !ModuleDownloads.query(context, download).active }.getOrDefault(true)
            }
        }
        publishedUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        publishedUris.clear()
        started.forEach { download -> partFile(download.id).delete() }
        started.clear()
        restorePreferences(preferencesSnapshot)
        preferencesSnapshot = emptyMap()
        probeRunToken = null
        scope.cancel()
        cancelObservers()
        server?.close()
        server = null
        restoreTrust?.close()
        restoreTrust = null
    }

    @Test
    fun slowHttpsStreamsRealProgressAndObserverCloseKeepsWorkerRunning() {
        val payload = moduleZip(seed = 11L, randomSize = 512 * 1024)
        val server = startServer { request ->
            LoopbackHttpsServer.Response(
                code = 200,
                headers = mapOf(
                    "Content-Type" to "application/zip",
                    "ETag" to "\"flow-known-length\"",
                ),
                body = payload,
                writeChunkBytes = 8 * 1024,
                delayPerChunkMs = 35L,
            )
        }
        val release = ModuleRelease(
            version = "9.9.1",
            versionCode = 9901L,
            zipUrl = server.url("/module.zip"),
            changelogUrl = null,
        )
        val download = ModuleDownloads.start(context, release)
        started.add(download)
        assertEquals("9.9.1", download.version)
        assertEquals(9901L, download.versionCode)
        assertTrue(download.fileName, download.fileName.startsWith("qqhook-"))

        val emissions = CopyOnWriteArrayList<ModuleDownloadProgress>()
        val observer = scope.launch {
            ModuleDownloads.observe(context, download).collect { progress -> emissions.add(progress) }
        }
        observers.add(observer)

        awaitUntil(20_000) {
            val live = ModuleDownloads.query(context, download)
            (live.active && live.receivedBytes in 1L until payload.size.toLong()) ||
                emissions.any { it.active && it.receivedBytes in 1L until payload.size.toLong() }
        }
        val midway = ModuleDownloads.query(context, download)
        assertTrue("必须产生真实中间字节，实际 received=${midway.receivedBytes}", midway.receivedBytes > 0)
        assertTrue("此时不应已经完成", midway.receivedBytes < payload.size)
        assertEquals("已知长度的真实总量", payload.size.toLong(), midway.totalBytes)
        assertTrue(midway.active)

        val loaded = ModuleDownloads.load(context)
        assertNotNull("运行中的任务必须能通过 load 恢复", loaded)
        assertEquals("load 必须恢复同一任务身份", download, loaded)
        assertEquals(download.fileName, loaded!!.fileName)

        runBlocking { observer.cancelAndJoin() }
        observers.remove(observer)
        assertTrue(
            "observer 应该至少看到过非终态",
            emissions.any {
                it.status == ModuleDownloadStatus.Pending ||
                    it.status == ModuleDownloadStatus.Running ||
                    it.status == ModuleDownloadStatus.Paused
            },
        )

        // 关闭 observer 不取消 worker：传输继续到 Complete。
        awaitUntil(60_000) {
            ModuleDownloads.query(context, download).status == ModuleDownloadStatus.Complete
        }
        val completed = ModuleDownloads.query(context, download)
        assertEquals(payload.size.toLong(), completed.receivedBytes)
        assertEquals(payload.size.toLong(), completed.totalBytes)
        assertFalse(completed.active)

        val intent = ModuleDownloads.openIntent(context, download)
        assertEquals("application/zip", intent.type)
        assertTrue(
            "打开意图必须带读取授权",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertNotNull(intent.clipData)
        val uri = requireNotNull(intent.data)
        publishedUris.add(uri)
        val publishedBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals("公开 Downloads 中的长度必须与源 ZIP 一致", payload.size, publishedBytes.size)
        assertArrayEquals("公开 ZIP 必须与真实源字节完全一致", payload, publishedBytes)
        assertEquals(FlowZip.sha256(payload), FlowZip.sha256(publishedBytes))
        assertZipEntriesMatch(payload, publishedBytes)
    }

    @Test
    fun cancelDuringUnknownLengthStopsWorkerAndNextTaskOwnsItsBytes() {
        val slowPayload = moduleZip(seed = 21L, randomSize = 256 * 1024)
        val nextPayload = moduleZip(seed = 22L, randomSize = 192 * 1024)
        val server = startServer { request ->
            when (request.path) {
                "/unknown.zip" -> LoopbackHttpsServer.Response(
                    body = slowPayload,
                    chunked = true,
                    writeChunkBytes = 8 * 1024,
                    delayPerChunkMs = 20L,
                )
                "/next.zip" -> LoopbackHttpsServer.Response(
                    body = nextPayload,
                    chunked = true,
                    writeChunkBytes = 8 * 1024,
                    delayPerChunkMs = 25L,
                )
                else -> LoopbackHttpsServer.Response(code = 404, body = "missing".toByteArray())
            }
        }

        val firstRelease = ModuleRelease("9.9.2", 9902L, server.url("/unknown.zip"), null)
        val first = ModuleDownloads.start(context, firstRelease)
        started.add(first)

        val firstEmissions = CopyOnWriteArrayList<ModuleDownloadProgress>()
        val firstObserver = scope.launch {
            ModuleDownloads.observe(context, first).collect { progress -> firstEmissions.add(progress) }
        }
        observers.add(firstObserver)

        awaitUntil(20_000) {
            val live = ModuleDownloads.query(context, first)
            live.receivedBytes > 0 || firstEmissions.any { it.receivedBytes > 0 }
        }
        val running = ModuleDownloads.query(context, first)
        assertEquals("未知总长度必须真实报告 -1", -1L, running.totalBytes)
        assertTrue(running.active)
        assertTrue("取消前应收到部分字节", running.receivedBytes > 0)

        ModuleDownloads.cancel(context, first)

        awaitUntil(10_000) { server.abortedConnections > 0 }
        assertTrue("cancel 必须关闭真实连接以停止 worker", server.abortedConnections > 0)
        awaitUntil(10_000) {
            runCatching { !ModuleDownloads.query(context, first).active }.getOrDefault(true)
        }
        assertNotEquals(
            "取消不能伪装成完成",
            ModuleDownloadStatus.Complete,
            ModuleDownloads.query(context, first).status,
        )
        awaitUntil(10_000) { !partFile(first.id).exists() }
        assertFalse("cancel 必须清理 .part 临时文件", partFile(first.id).exists())
        assertTrue(
            "未完成的任务不得提供打开意图",
            runCatching { ModuleDownloads.openIntent(context, first) }.isFailure,
        )

        runBlocking { firstObserver.cancelAndJoin() }
        observers.remove(firstObserver)

        val nextRelease = ModuleRelease("9.9.3", 9903L, server.url("/next.zip"), null)
        val next = ModuleDownloads.start(context, nextRelease)
        started.add(next)
        assertNotEquals("新任务必须使用独立身份", first.id, next.id)

        val nextEmissions = CopyOnWriteArrayList<ModuleDownloadProgress>()
        val nextObserver = scope.launch {
            ModuleDownloads.observe(context, next).collect { progress -> nextEmissions.add(progress) }
        }
        observers.add(nextObserver)

        awaitUntil(20_000) {
            nextEmissions.any {
                it.status == ModuleDownloadStatus.Running &&
                    it.totalBytes == -1L &&
                    it.receivedBytes > 0
            }
        }
        assertTrue(
            "未知长度任务在运行时必须对观察者保持 -1，不能伪造总量",
            nextEmissions.any {
                it.status == ModuleDownloadStatus.Running &&
                    it.totalBytes == -1L &&
                    it.receivedBytes > 0
            },
        )

        awaitUntil(60_000) {
            ModuleDownloads.query(context, next).status == ModuleDownloadStatus.Complete
        }
        runBlocking { nextObserver.cancelAndJoin() }
        observers.remove(nextObserver)

        val intent = ModuleDownloads.openIntent(context, next)
        val uri = requireNotNull(intent.data)
        publishedUris.add(uri)
        val publishedBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals(
            "旧 worker 的晚到写入不得混入新任务",
            nextPayload,
            publishedBytes,
        )
        assertEquals(FlowZip.sha256(nextPayload), FlowZip.sha256(publishedBytes))
        assertZipEntriesMatch(nextPayload, publishedBytes)
    }

    @Test
    fun interruptedDownloadsNeverCompleteFromPartialChangedOrUnprovableBytes() {
        val flakyPayload = moduleZip(seed = 31L, randomSize = 320 * 1024)
        val flakyEtag = "\"flaky-entity\""
        val truncatedAfter = 64 * 1024
        val changedOldPayload = moduleZip(seed = 61L, randomSize = 384 * 1024)
        val changedNewPayload = moduleZip(seed = 62L, randomSize = 288 * 1024)
        val changedOldEtag = "\"changed-old\""
        val changedNewEtag = "\"changed-new\""
        val unsafeOldPayload = moduleZip(seed = 63L, randomSize = 384 * 1024)
        val unsafeNewPayload = moduleZip(seed = 64L, randomSize = 512 * 1024)
        val unsafeOldEtag = "\"unsafe-old\""
        val unsafeNewEtag = "\"unsafe-new\""
        val flakyRequests = AtomicInteger(0)
        val changedRequests = AtomicInteger(0)
        val unsafeRequests = AtomicInteger(0)
        val servedUnprovableRange = AtomicBoolean(false)
        val unsafeResumeOffsets = CopyOnWriteArrayList<Long>()

        val server = startServer { request ->
            when (request.path) {
                "/flaky.zip" -> if (flakyRequests.getAndIncrement() == 0) {
                    LoopbackHttpsServer.Response(
                        code = 200,
                        headers = mapOf("ETag" to flakyEtag),
                        body = flakyPayload,
                        writeChunkBytes = 8 * 1024,
                        abortAfterBytes = truncatedAfter,
                    )
                } else {
                    LoopbackHttpsServer.Response(
                        code = 200,
                        headers = mapOf("ETag" to flakyEtag),
                        body = flakyPayload,
                    )
                }

                "/changed.zip" -> if (changedRequests.getAndIncrement() == 0) {
                    LoopbackHttpsServer.Response(
                        code = 200,
                        headers = mapOf("ETag" to changedOldEtag),
                        body = changedOldPayload,
                        writeChunkBytes = 8 * 1024,
                        delayPerChunkMs = 5L,
                        abortAfterBytes = 128 * 1024,
                    )
                } else {
                    // 同一 URL 的实体已变化：服务端忽略 Range/If-Range，返回 chunked 200
                    // （无 Content-Length）与新的 ETag；旧 total/旧 prefix 都不可沿用。
                    LoopbackHttpsServer.Response(
                        code = 200,
                        headers = mapOf("ETag" to changedNewEtag),
                        body = changedNewPayload,
                        chunked = true,
                        writeChunkBytes = 8 * 1024,
                        delayPerChunkMs = 10L,
                    )
                }

                "/unsafe-range.zip" -> when (unsafeRequests.getAndIncrement()) {
                    0 -> LoopbackHttpsServer.Response(
                        code = 200,
                        headers = mapOf("ETag" to unsafeOldEtag),
                        body = unsafeOldPayload,
                        writeChunkBytes = 8 * 1024,
                        delayPerChunkMs = 5L,
                        abortAfterBytes = 128 * 1024,
                    )
                    1 -> {
                        // 206 但总长度是 `*`：无法证明这是同一完整 entity 的剩余部分。
                        val offset = request.range
                            ?.let { range -> Regex("^bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toLongOrNull() }
                            ?: 0L
                        val start = offset.toInt()
                        // 故意按“旧实体剩余长度”返回 206：若把任意 range 当完整 entity，
                        // 正好会拼出旧 total 长度但内容错误的文件。
                        val length = (unsafeOldPayload.size - start)
                            .coerceAtMost(unsafeNewPayload.size - start)
                            .coerceAtLeast(1)
                        unsafeResumeOffsets.add(offset)
                        servedUnprovableRange.set(true)
                        LoopbackHttpsServer.Response(
                            code = 206,
                            headers = mapOf(
                                "Content-Range" to "bytes $offset-${start + length - 1}/*",
                                // 验证器与请求一致，唯一不合法之处就是 total=*，避免误判成
                                // “实体已变化”的 InvalidRange。
                                "ETag" to unsafeOldEtag,
                            ),
                            body = unsafeNewPayload.copyOfRange(start, start + length),
                        )
                    }
                    else -> LoopbackHttpsServer.Response(
                        code = 200,
                        headers = mapOf("ETag" to unsafeNewEtag),
                        body = unsafeNewPayload,
                    )
                }

                "/broken.zip" -> LoopbackHttpsServer.Response(
                    code = 500,
                    body = "backend exploded".toByteArray(),
                )
                else -> LoopbackHttpsServer.Response(code = 404, body = "missing".toByteArray())
            }
        }

        // 1) 真实截断 → Failed → 同版本重试（服务端忽略 Range 返回完整 200）不得拼接旧字节。
        val flakyRelease = ModuleRelease("9.9.4", 9904L, server.url("/flaky.zip"), null)
        val truncated = ModuleDownloads.start(context, flakyRelease)
        started.add(truncated)
        awaitUntil(60_000) {
            ModuleDownloads.query(context, truncated).status == ModuleDownloadStatus.Failed
        }
        val failure = ModuleDownloads.query(context, truncated)
        assertNotEquals("截断绝不能标成完成", ModuleDownloadStatus.Complete, failure.status)
        assertTrue("失败需要真实原因，实际=${failure.message}", failure.message.isNotBlank())
        assertTrue(
            "截断的传输不得提供打开意图",
            runCatching { ModuleDownloads.openIntent(context, truncated) }.isFailure,
        )

        val flakyRetry = ModuleDownloads.start(context, flakyRelease)
        started.add(flakyRetry)
        awaitUntil(60_000) {
            ModuleDownloads.query(context, flakyRetry).status == ModuleDownloadStatus.Complete
        }
        if (flakyRetry.id == truncated.id) {
            assertTrue(
                "同一身份重试必须带 Range/If-Range 探测，实际=${server.requests.map { it.range to it.ifRange }}",
                server.requests.any { it.range?.startsWith("bytes=") == true && it.ifRange != null },
            )
        }
        assertEquals(flakyPayload.size.toLong(), ModuleDownloads.query(context, flakyRetry).receivedBytes)
        readPublished(flakyRetry).let { bytes ->
            assertArrayEquals("重试后的公开 ZIP 必须与源字节一致", flakyPayload, bytes)
            assertEquals(FlowZip.sha256(flakyPayload), FlowZip.sha256(bytes))
        }

        // 2) 中断后实体变化：Range/If-Range 得到 200 + chunked + 新 ETag + 不同大小。
        //    旧 prefix 不得拼入、Running.total 必须回到 -1、最终字节必须是新实体。
        val changedRelease = ModuleRelease("9.9.8", 9908L, server.url("/changed.zip"), null)
        val changedFirst = ModuleDownloads.start(context, changedRelease)
        started.add(changedFirst)
        awaitUntil(60_000) {
            ModuleDownloads.query(context, changedFirst).status == ModuleDownloadStatus.Failed
        }
        val changedFailure = ModuleDownloads.query(context, changedFirst)
        assertNotEquals(
            "改变实体前的截断不能是完成",
            ModuleDownloadStatus.Complete,
            changedFailure.status,
        )
        assertTrue("真实截断必须留下部分文件用于验证续传", partFile(changedFirst.id).length() > 0L)

        val changedRetry = ModuleDownloads.start(context, changedRelease)
        started.add(changedRetry)
        val changedEmissions = CopyOnWriteArrayList<ModuleDownloadProgress>()
        val changedObserver = scope.launch {
            ModuleDownloads.observe(context, changedRetry).collect { changedEmissions.add(it) }
        }
        observers.add(changedObserver)
        awaitUntil(60_000) {
            changedEmissions.any {
                it.status == ModuleDownloadStatus.Running && it.totalBytes == -1L
            }
        }
        awaitUntil(60_000) {
            ModuleDownloads.query(context, changedRetry).status == ModuleDownloadStatus.Complete
        }
        runBlocking { changedObserver.cancelAndJoin() }
        observers.remove(changedObserver)
        val changedCompleted = ModuleDownloads.query(context, changedRetry)
        assertEquals(changedNewPayload.size.toLong(), changedCompleted.receivedBytes)
        assertEquals(changedNewPayload.size.toLong(), changedCompleted.totalBytes)
        assertNotEquals(
            "实体大小不同，旧 total 不能被复用",
            changedOldPayload.size.toLong(),
            changedNewPayload.size.toLong(),
        )
        readPublished(changedRetry).let { bytes ->
            assertArrayEquals("旧 prefix 不得拼进新实体", changedNewPayload, bytes)
            assertEquals(FlowZip.sha256(changedNewPayload), FlowZip.sha256(bytes))
        }
        val changedRequestsSeen = server.requests.filter { it.path == "/changed.zip" }
        assertTrue("必须真实发生续传尝试，实际=${changedRequestsSeen.map { it.range }}", changedRequestsSeen.size >= 2)
        assertEquals(changedOldEtag, changedRequestsSeen[1].ifRange)
        assertTrue(changedRequestsSeen[1].range?.startsWith("bytes=") == true)

        // 3) 206 的 Content-Range total 为 `*`：无法证明完整 entity，必须拒绝该断点并从 0
        //    重新请求完整 200，绝不能把任意 range 当成完成。
        val unsafeRelease = ModuleRelease("9.9.9", 9909L, server.url("/unsafe-range.zip"), null)
        val unsafeFirst = ModuleDownloads.start(context, unsafeRelease)
        started.add(unsafeFirst)
        awaitUntil(60_000) {
            ModuleDownloads.query(context, unsafeFirst).status == ModuleDownloadStatus.Failed
        }
        assertTrue(partFile(unsafeFirst.id).length() > 0L)

        val unsafeRetry = ModuleDownloads.start(context, unsafeRelease)
        started.add(unsafeRetry)
        awaitUntil(60_000) {
            ModuleDownloads.query(context, unsafeRetry).status == ModuleDownloadStatus.Complete
        }
        val unsafeCompleted = ModuleDownloads.query(context, unsafeRetry)
        assertEquals(unsafeNewPayload.size.toLong(), unsafeCompleted.receivedBytes)
        readPublished(unsafeRetry).let { bytes ->
            assertArrayEquals("不可证明的 206 不能拼出完整文件", unsafeNewPayload, bytes)
            assertEquals(FlowZip.sha256(unsafeNewPayload), FlowZip.sha256(bytes))
        }
        assertTrue("必须真实返回过 total=* 的 206", servedUnprovableRange.get())
        val unsafeRequestsSeen = server.requests.filter { it.path == "/unsafe-range.zip" }
        assertTrue(
            "拒绝不可证明的 206 后必须重新发完整请求，实际=${unsafeRequestsSeen.map { it.range }}",
            unsafeRequestsSeen.size >= 3,
        )
        assertTrue(
            "重新下载必须是无 Range 的完整请求",
            unsafeRequestsSeen.last().range == null,
        )
        assertTrue(
            "断点偏移必须真实来自本地 .part，实际=$unsafeResumeOffsets",
            unsafeResumeOffsets.firstOrNull()?.let { it in 1L until unsafeOldPayload.size.toLong() } == true,
        )

        // 4) HTTP 500 必须 Failed，且不提供打开意图。
        val brokenRelease = ModuleRelease("9.9.5", 9905L, server.url("/broken.zip"), null)
        val broken = ModuleDownloads.start(context, brokenRelease)
        started.add(broken)
        awaitUntil(60_000) {
            ModuleDownloads.query(context, broken).status == ModuleDownloadStatus.Failed
        }
        val brokenProgress = ModuleDownloads.query(context, broken)
        assertTrue(
            "HTTP 500 必须以真实状态失败，实际=${brokenProgress.message}",
            brokenProgress.message.contains("500"),
        )
        assertTrue(
            "HTTP 错误不得提供打开意图",
            runCatching { ModuleDownloads.openIntent(context, broken) }.isFailure,
        )
    }

    /**
     * 真实控件 + 真实后端：在 debug 探针 Activity 上渲染 production 的
     * [ModuleDownloadControls]，由 [ModuleDownloads.observe] 驱动，UiAutomator 读取一次
     * 0<百分比<100；点击“关闭”只关闭 Dialog，worker 继续并把公开 ZIP 完成。
     */
    @Test
    fun actualModuleDownloadControlsShowIntermediatePercentAndClosingKeepsWorkerRunning() {
        val intent = Intent().setClassName(context.packageName, PROBE_ACTIVITY_CLASS)
        assumeTrue(
            "真实控件回归需要 debug 变体里的 $PROBE_ACTIVITY_CLASS",
            intent.resolveActivity(context.packageManager) != null,
        )
        // 冷启动 Compose 可能需要数秒，保持足够长的真实传输窗口以观察中间百分比。
        val payload = moduleZip(seed = 41L, randomSize = 1024 * 1024)
        val server = startServer {
            LoopbackHttpsServer.Response(
                code = 200,
                headers = mapOf("ETag" to "\"flow-ui\""),
                body = payload,
                writeChunkBytes = 8 * 1024,
                delayPerChunkMs = 50L,
            )
        }
        val release = ModuleRelease("9.9.6", 9906L, server.url("/ui.zip"), null)
        val launch = Intent()
            .setClassName(context.packageName, PROBE_ACTIVITY_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(launch)
        var download: ModuleDownload? = null
        try {
            // 先把探针 Activity 冷启动完成，再开始真实下载，避免 Compose 首次组合吃掉进度窗口。
            val created = ModuleDownloads.start(context, release)
            download = created
            started.add(created)
            instrumentation.runOnMainSync {
                (activity as ComponentActivity).setContent {
                    AppTheme(dynamicColor = false) {
                        var progress by remember { mutableStateOf<ModuleDownloadProgress?>(null) }
                        var visible by remember { mutableStateOf(true) }
                        LaunchedEffect(created.id) {
                            ModuleDownloads.observe(context, created).collect { progress = it }
                        }
                        if (visible) {
                            Dialog(onDismissRequest = { visible = false }) {
                                Surface {
                                    ModuleDownloadControls(
                                        release = release,
                                        download = created,
                                        progress = progress,
                                        starting = false,
                                        readFailed = false,
                                        error = null,
                                        onDownload = {},
                                        onCancel = {},
                                        onRetryRead = {},
                                        onOpenZip = null,
                                        onClose = { visible = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val device = UiDevice.getInstance(instrumentation)
            val percentNode = device.wait(
                Until.findObject(By.text(Pattern.compile("^(?:[1-9]|[1-9][0-9])%$"))),
                20_000,
            )
            assertNotNull("真实控件上未出现 0<percent<100 的下载百分比", percentNode)
            val percent = percentNode!!.text.removeSuffix("%").toInt()
            assertTrue("百分比必须来自真实中间字节，实际=$percent", percent in 1..99)
            val screenshot = File(context.getExternalFilesDir(null) ?: context.filesDir, "module-download-progress.png")
            device.takeScreenshot(screenshot)
            instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("downloadProgress", "$percent%")
                putString("progressScreenshot", screenshot.absolutePath)
            })

            val close = device.wait(Until.findObject(By.text("关闭")), 10_000)
            assertNotNull("真实下载控件缺少关闭按钮", close)
            close!!.click()
            assertTrue(
                "关闭 Dialog 后下载操作区应消失",
                device.wait(Until.gone(By.text("取消下载")), 10_000),
            )
        } finally {
            runCatching { activity.finish() }
        }

        val completedDownload = requireNotNull(download) { "真实控件流程未创建下载任务" }
        awaitUntil(60_000) {
            ModuleDownloads.query(context, completedDownload).status == ModuleDownloadStatus.Complete
        }
        val intentView = ModuleDownloads.openIntent(context, completedDownload)
        val uri = requireNotNull(intentView.data)
        publishedUris.add(uri)
        val publishedBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals("关闭 Dialog 后必须发布与源字节一致的 ZIP", payload, publishedBytes)
        assertEquals(FlowZip.sha256(payload), FlowZip.sha256(publishedBytes))
    }

    /**
     * 真实进程强停/重启：下载 worker 在独立进程 `:module_download_probe` 中运行，主进程杀掉
     * 该进程后由新进程的 [ModuleDownloads.load] 从 `.part` 使用 Range/If-Range 续传，最终
     * 公开 ZIP 与源字节逐字节一致。不是同进程 reload。
     */
    @Test
    fun killedDownloadProcessResumesWithRangeAndPublishesExactZip() {
        val probeCheck = Intent().setClassName(context.packageName, DOWNLOAD_PROBE_CLASS)
        assumeTrue(
            "进程重启流程需要 debug 变体里的 $DOWNLOAD_PROBE_CLASS",
            probeCheck.resolveActivity(context.packageManager) != null,
        )
        val payload = moduleZip(seed = 51L, randomSize = 1024 * 1024)
        val etag = "\"restart-etag\""
        val servedPartial = AtomicBoolean(false)
        val server = startServer { request ->
            val offset = request.range
                ?.let { range -> Regex("^bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toLongOrNull() }
            if (offset != null && offset > 0L && request.ifRange == etag) {
                servedPartial.set(true)
                LoopbackHttpsServer.Response(
                    code = 206,
                    headers = mapOf(
                        "Content-Range" to "bytes $offset-${payload.size - 1}/${payload.size}",
                        "Accept-Ranges" to "bytes",
                        "ETag" to etag,
                    ),
                    body = payload.copyOfRange(offset.toInt(), payload.size),
                    writeChunkBytes = 16 * 1024,
                    delayPerChunkMs = 10L,
                )
            } else {
                LoopbackHttpsServer.Response(
                    code = 200,
                    headers = mapOf("Accept-Ranges" to "bytes", "ETag" to etag),
                    body = payload,
                    writeChunkBytes = 16 * 1024,
                    delayPerChunkMs = 30L,
                )
            }
        }
        val runToken = java.util.UUID.randomUUID().toString()
        probeRunToken = runToken
        File(context.filesDir, DOWNLOAD_PROBE_SNAPSHOT).delete()
        val keystore = Base64.encodeToString(
            testContext.assets.open(KEYSTORE_ASSET).readBytes(),
            Base64.NO_WRAP,
        )
        val url = server.url("/restart.zip")
        val firstLaunch = Intent()
            .setClassName(context.packageName, DOWNLOAD_PROBE_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(DOWNLOAD_PROBE_EXTRA_MODE, DOWNLOAD_PROBE_MODE_START)
            .putExtra(DOWNLOAD_PROBE_EXTRA_ZIP_URL, url)
            .putExtra(DOWNLOAD_PROBE_EXTRA_VERSION, "9.9.7")
            .putExtra(DOWNLOAD_PROBE_EXTRA_VERSION_CODE, 9907L)
            .putExtra(DOWNLOAD_PROBE_EXTRA_KEYSTORE, keystore)
            .putExtra(DOWNLOAD_PROBE_EXTRA_KEYSTORE_PASSWORD, KEYSTORE_PASSWORD)
            .putExtra(DOWNLOAD_PROBE_EXTRA_RUN_TOKEN, runToken)
        // startActivitySync 明确拒绝跨进程 Activity；探针进程由 context.startActivity 冷启动，
        // 后续一切以快照 pid 为准，不依赖同进程 Activity 对象。
        context.startActivity(firstLaunch)

        val partial = awaitSnapshot(60_000) { snapshot ->
            snapshot.optString("run") == runToken &&
                snapshot.optString("mode") == DOWNLOAD_PROBE_MODE_START &&
                snapshot.optString("status") == ModuleDownloadStatus.Running.name &&
                snapshot.optLong("received") in 128L until payload.size.toLong()
        }
        val firstPid = partial.optInt("pid")
        assertTrue("副进程快照必须带真实 pid，实际=$firstPid", firstPid > 0)
        assertTrue(
            "下载必须跑在独立进程，而不是测试/主进程（pid=$firstPid）",
            firstPid != Process.myPid(),
        )
        probePids.add(firstPid)

        Process.killProcess(firstPid)
        awaitProcessGone(firstPid)
        probePids.remove(firstPid)
        val requestsBeforeRestore = server.requests.size

        val restoreLaunch = Intent()
            .setClassName(context.packageName, DOWNLOAD_PROBE_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(DOWNLOAD_PROBE_EXTRA_MODE, DOWNLOAD_PROBE_MODE_RESTORE)
            .putExtra(DOWNLOAD_PROBE_EXTRA_KEYSTORE, keystore)
            .putExtra(DOWNLOAD_PROBE_EXTRA_KEYSTORE_PASSWORD, KEYSTORE_PASSWORD)
            .putExtra(DOWNLOAD_PROBE_EXTRA_RUN_TOKEN, runToken)
        context.startActivity(restoreLaunch)

        val completed = awaitSnapshot(120_000) { snapshot ->
            val pid = snapshot.optInt("pid")
            pid > 0 && pid != firstPid &&
                snapshot.optString("run") == runToken &&
                snapshot.optString("mode") == DOWNLOAD_PROBE_MODE_RESTORE &&
                snapshot.optString("status") == ModuleDownloadStatus.Complete.name &&
                !snapshot.isNull("uri")
        }
        val secondPid = completed.optInt("pid")
        assertTrue("恢复必须发生在新进程，实际=$secondPid", secondPid > 0 && secondPid != firstPid)
        probePids.add(secondPid)
        assertTrue("恢复请求必须是真实 Range 续传", servedPartial.get())
        val resumedRequests = server.requests.drop(requestsBeforeRestore)
        assertTrue(
            "新进程必须带 Range/If-Range 续传，实际=${resumedRequests.map { it.range to it.ifRange }}",
            resumedRequests.any {
                it.range?.startsWith("bytes=") == true && it.ifRange == etag
            },
        )

        val uri = Uri.parse(completed.getString("uri"))
        publishedUris.add(uri)
        val publishedBytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals("强停后重启续传的公开 ZIP 必须与源字节一致", payload, publishedBytes)
        assertEquals(FlowZip.sha256(payload), FlowZip.sha256(publishedBytes))
    }

    private fun startServer(
        responder: (LoopbackHttpsServer.Request) -> LoopbackHttpsServer.Response,
    ): LoopbackHttpsServer {
        val value = LoopbackHttpsServer(testContext, responder)
        server = value
        return value
    }

    private fun moduleZip(seed: Long, randomSize: Int): ByteArray = FlowZip.build(
        listOf(
            "module.prop" to
                "id=qqhook\nname=flow-$seed\nversion=1\nversionCode=$seed\n".toByteArray(),
            "system/random.bin" to FlowZip.randomBytes(seed = seed, size = randomSize),
        ),
    )

    private fun readPublished(download: ModuleDownload): ByteArray {
        val intent = ModuleDownloads.openIntent(context, download)
        val uri = requireNotNull(intent.data) { "完成的任务必须返回公开 URI" }
        publishedUris.add(uri)
        return context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    }

    /**
     * 快照由探针用 AtomicFile 原子 rename 发布，base 永远是一份完整内容。
     * 读者只读 base 字节：绝不调用 AtomicFile.openRead（它在 base 存在时会删除并发
     * writer 的 `.new`，让 rename 失败），也绝不在这里恢复/清理 `.new`。
     */
    private fun readProbeSnapshot(): JSONObject? {
        return runCatching {
            val file = File(context.filesDir, DOWNLOAD_PROBE_SNAPSHOT)
            if (!file.isFile || file.length() == 0L) return@runCatching null
            val bytes = file.inputStream().use { it.readBytes() }
            if (bytes.isEmpty()) return@runCatching null
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun awaitSnapshot(timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last: JSONObject? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val snapshot = readProbeSnapshot()
            if (snapshot != null) {
                last = snapshot
                if (snapshot.optString("status") == "Error") {
                    throw AssertionError(
                        "下载探针失败：${snapshot.optString("message")} " +
                            "(${snapshot.optString("error")})",
                    )
                }
                if (predicate(snapshot)) return snapshot
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("等待下载探针快照超时，最后状态=$last")
    }

    private fun awaitProcessGone(pid: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (!File("/proc/$pid").exists()) return
            SystemClock.sleep(25)
        }
    }

    private fun partFile(id: Long): File = ModuleDownloads.partFile(context, id)

    private fun assertZipEntriesMatch(expectedZip: ByteArray, actualZip: ByteArray) {
        val expected = FlowZip.readEntries(expectedZip)
        val actual = FlowZip.readEntries(actualZip)
        assertEquals("ZIP 条目集合", expected.keys, actual.keys)
        expected.forEach { (name, bytes) ->
            assertArrayEquals("ZIP 条目 $name 的内容", bytes, actual.getValue(name))
        }
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue("等待条件超时（${timeoutMs}ms）", condition())
    }

    private fun preferences() =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 完整快照（含未知键与 nextId），保证测试不改动既有的完成下载记录。 */
    private fun restorePreferences(snapshot: Map<String, Any?>) {
        // 副进程已被回收；先清理其磁盘写入和本进程缓存，避免 clear() 因旧空缓存跳过落盘。
        check(context.deleteSharedPreferences(PREFERENCES_NAME))
        val editor = preferences().edit().clear()
        snapshot.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
    }

    private fun grantLegacyStoragePermissionIfNeeded() {
        if (!ModuleDownloads.needsStoragePermission(context)) return
        runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
        assumeTrue(
            "API 28 设备需要 WRITE_EXTERNAL_STORAGE 才能写入公共下载目录",
            !ModuleDownloads.needsStoragePermission(context),
        )
    }

    private fun cancelObservers() {
        observers.forEach { it.cancel() }
        observers.clear()
    }

    companion object {
        const val PREFERENCES_NAME = "qqzygisk_module_download"
        /** Debug-only Activity reused as a lifecycle owner for the real download controls. */
        const val PROBE_ACTIVITY_CLASS = "com.qm.qqzygisk.testing.SafGrantProbeActivity"
        /** Debug-only Activity in its own process for the real worker-restart flow. */
        const val DOWNLOAD_PROBE_CLASS = "com.qm.qqzygisk.testing.ModuleDownloadProbeActivity"
        const val DOWNLOAD_PROBE_SNAPSHOT = "qqzygisk-download-probe.json"
        const val KEYSTORE_ASSET = "downloadtls/loopback-test.p12"
        const val KEYSTORE_PASSWORD = "loopback"

        const val DOWNLOAD_PROBE_EXTRA_MODE =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_MODE"
        const val DOWNLOAD_PROBE_EXTRA_ZIP_URL =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_ZIP_URL"
        const val DOWNLOAD_PROBE_EXTRA_VERSION =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_VERSION"
        const val DOWNLOAD_PROBE_EXTRA_VERSION_CODE =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_VERSION_CODE"
        const val DOWNLOAD_PROBE_EXTRA_KEYSTORE =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_KEYSTORE"
        const val DOWNLOAD_PROBE_EXTRA_KEYSTORE_PASSWORD =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_KEYSTORE_PASSWORD"
        const val DOWNLOAD_PROBE_EXTRA_RUN_TOKEN =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_RUN_TOKEN"
        const val DOWNLOAD_PROBE_MODE_START = "start"
        const val DOWNLOAD_PROBE_MODE_RESTORE = "restore"
    }
}
