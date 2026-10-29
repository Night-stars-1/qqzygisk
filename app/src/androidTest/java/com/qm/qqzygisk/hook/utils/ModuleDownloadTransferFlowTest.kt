package com.qm.qqzygisk.hook.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * 纯 HTTP 传输层（[ModuleDownloadTransfer]）的真实 TLS 断点恢复流程。
 *
 * 与本机回环 TLS 服务器交互，验证：
 *  - 正常整包：totalBytes / validator / finalUrl 来自真实响应；
 *  - 中断后 Range + If-Range：206 只返回剩余字节，与服务端 Content-Range 一致；
 *  - 服务端忽略 Range 返回 200：调用方看到 `partial=false` 并从 0 开始读取，不会把整包
 *    当作剩余字节拼接；
 *  - 416：抛出带真实 HTTP 状态码的 IOException；
 *  - 未知长度（chunked）：totalBytes/contentRangeTotal 真实为 -1。
 *
 * 只走进程内临时 trust（[LoopbackTls.installProcessDefaults]），finally 恢复默认值；
 * 不使用 mock 回声或反射改状态。
 */
@RunWith(AndroidJUnit4::class)
class ModuleDownloadTransferFlowTest {

    private lateinit var testContext: Context
    private var server: LoopbackHttpsServer? = null
    private var restoreTrust: Closeable? = null

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        testContext = instrumentation.context
        restoreTrust = LoopbackTls.installProcessDefaults(testContext)
    }

    @After
    fun tearDown() {
        server?.close()
        server = null
        restoreTrust?.close()
        restoreTrust = null
    }

    @Test
    fun interruptedTransferResumesWithRangeAndIfRangeBytes() {
        val payload = FlowZip.build(
            listOf(
                "module.prop" to "id=qqhook\nname=test\nversion=1\n".toByteArray(),
                "system/payload.bin" to FlowZip.randomBytes(seed = 71L, size = 320 * 1024),
            ),
        )
        val etag = "\"flow-etag-1\""
        val lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
        val resumeAt = 100 * 1024
        val server = LoopbackHttpsServer(testContext) { request ->
            if (request.range == "bytes=$resumeAt-" && request.ifRange == etag) {
                LoopbackHttpsServer.Response(
                    code = 206,
                    headers = mapOf(
                        "Content-Range" to "bytes $resumeAt-${payload.size - 1}/${payload.size}",
                        "Accept-Ranges" to "bytes",
                        "ETag" to etag,
                    ),
                    body = payload.copyOfRange(resumeAt, payload.size),
                )
            } else {
                LoopbackHttpsServer.Response(
                    code = 200,
                    headers = mapOf(
                        "Accept-Ranges" to "bytes",
                        "ETag" to etag,
                        "Last-Modified" to lastModified,
                    ),
                    body = payload,
                )
            }
        }.also { this.server = it }

        val url = server.url("/module.zip")
        var prefix: ByteArray? = null
        ModuleDownloadTransfer.open(url).use { transfer ->
            assertEquals(url, transfer.finalUrl)
            assertFalse(transfer.partial)
            assertEquals(-1L, transfer.contentRangeStart)
            assertEquals(payload.size.toLong(), transfer.totalBytes)
            assertEquals(etag, transfer.etag)
            assertEquals(lastModified, transfer.lastModified)
            prefix = readExactly(transfer.stream, resumeAt)
            assertEquals(resumeAt, prefix!!.size)
        }

        ModuleDownloadTransfer.open(url, offset = resumeAt.toLong(), etag = etag).use { resumed ->
            assertTrue(resumed.partial)
            assertEquals(url, resumed.finalUrl)
            assertEquals(resumeAt.toLong(), resumed.contentRangeStart)
            assertEquals(payload.size.toLong(), resumed.contentRangeTotal)
            assertEquals(payload.size.toLong(), resumed.totalBytes)
            assertEquals(etag, resumed.etag)
            val rest = resumed.stream.readBytes()
            assertEquals(payload.size - resumeAt, rest.size)
            assertArrayEquals(payload, prefix!! + rest)
        }

        val resumeRequest = server.requests.last()
        assertEquals("bytes=$resumeAt-", resumeRequest.range)
        assertEquals(etag, resumeRequest.ifRange)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun serverIgnoringRangeStreamsWholeBodyFromZero() {
        val payload = FlowZip.build(
            listOf("module.prop" to "id=qqhook\ntest=200\n".toByteArray()),
        )
        val etag = "\"flow-etag-2\""
        val server = LoopbackHttpsServer(testContext) {
            LoopbackHttpsServer.Response(
                code = 200,
                headers = mapOf("Accept-Ranges" to "none", "ETag" to etag),
                body = payload,
            )
        }.also { this.server = it }

        ModuleDownloadTransfer.open(server.url("/module.zip"), offset = 64L, etag = etag).use { transfer ->
            assertFalse("服务端返回 200 时不能宣称 partial", transfer.partial)
            assertEquals(-1L, transfer.contentRangeStart)
            assertEquals(payload.size.toLong(), transfer.totalBytes)
            assertArrayEquals(
                "整包 200 必须从文件起点读取，且不与旧 .part 拼接",
                payload,
                transfer.stream.readBytes(),
            )
        }
        assertNotNull("有 offset+validator 时必须发出 Range 探测", server.requests.last().range)
        assertEquals(etag, server.requests.last().ifRange)

        // 没有 validator 时不得发送 Range：无法校验的续传会让服务端按偏移返回字节。
        ModuleDownloadTransfer.open(server.url("/module.zip"), offset = 64L).use { transfer ->
            assertNull(server.requests.last().range)
            assertFalse(transfer.partial)
            assertArrayEquals(payload, transfer.stream.readBytes())
        }
    }

    @Test
    fun unsatisfiableRangeThrowsRealHttpStatus() {
        val payload = FlowZip.build(listOf("module.prop" to "id=qqhook\n".toByteArray()))
        val etag = "\"flow-etag-3\""
        val server = LoopbackHttpsServer(testContext) { request ->
            assertEquals("bytes=${payload.size + 10L}-", request.range)
            LoopbackHttpsServer.Response(
                code = 416,
                headers = mapOf(
                    "Content-Range" to "bytes */${payload.size}",
                    "ETag" to etag,
                ),
                body = "range not satisfiable".toByteArray(),
            )
        }.also { this.server = it }

        try {
            ModuleDownloadTransfer.open(
                server.url("/module.zip"),
                offset = payload.size + 10L,
                etag = etag,
            ).close()
            fail("416 必须以 IOException 失败，而不是当作可恢复的部分内容")
        } catch (expected: IOException) {
            assertTrue(
                "异常需要携带真实 HTTP 状态码，实际=${expected.message}",
                expected.message.orEmpty().contains("416"),
            )
        }
    }

    @Test
    fun chunkedResponseKeepsUnknownLengthUntilEof() {
        val payload = FlowZip.build(
            listOf("module.prop" to "id=qqhook\n".toByteArray()),
        )
        val server = LoopbackHttpsServer(testContext) {
            LoopbackHttpsServer.Response(
                body = payload,
                chunked = true,
                writeChunkBytes = 4 * 1024,
            )
        }.also { this.server = it }

        ModuleDownloadTransfer.open(server.url("/module.zip")).use { transfer ->
            assertFalse(transfer.partial)
            assertEquals(-1L, transfer.totalBytes)
            assertEquals(-1L, transfer.contentRangeTotal)
            assertArrayEquals(payload, transfer.stream.readBytes())
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            if (count < 0) throw IOException("流提前结束：需要 $size 字节，实际 $read")
            read += count
        }
        return bytes
    }
}
