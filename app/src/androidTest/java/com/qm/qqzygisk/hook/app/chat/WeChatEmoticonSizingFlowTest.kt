package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 发送前尺寸处理 + 原生导入/提交的完整流程回归。
 *
 * 测试只替换微信原生边界（[WeChatEmoticonApi]）：导入读取 helper 实际给出的文件字节并
 * 返回真实 MD5/大小，提交按原生终态异步回调。真实微信送达不在本测试范围内。
 */
@RunWith(AndroidJUnit4::class)
class WeChatEmoticonSizingFlowTest {
    @Test
    fun oversizedJpegBecomesFittingPngThenImportsSubmitsAndReleasesCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = temporaryRoot(context)
        val api = NativeEmoticonApi()
        var prepared: PreparedWeChatEmoticon? = null
        try {
            val originalFile = File(root, "original-jpeg")
            writeJpeg(originalFile, 640, 480)
            val originalBytes = originalFile.readBytes()
            val original = WeChatEmoticonFile.inspect(originalFile)
            assertEquals(WeChatEmoticonFormat.JPEG, original.format)

            val directory = File(root, "prepared")
            prepared = onMain { WeChatEmoticonSendImage.prepare(original, 64, directory) }
            val sent = checkNotNull(prepared)

            assertTrue("超限 JPEG 必须生成发送副本", sent.scaled)
            assertNotSame(original, sent.source)
            assertEquals(WeChatEmoticonFormat.PNG, sent.source.format)
            val copyBytes = sent.source.file.readBytes()
            assertEquals(md5(copyBytes), sent.source.md5)
            assertEquals(copyBytes.size.toLong(), sent.source.size)
            assertNotEquals(original.md5, sent.source.md5)
            assertFits(640, 480, sent.source.file, 64)

            val outcomes = CopyOnWriteArrayList<Result<Unit>>()
            val completed = CountDownLatch(1)
            val launched = WeChatEmoticonSubmission(api).send(
                sent.source, "wxid_flow_recipient", { "wxid_flow_recipient" },
            ) { result ->
                outcomes += result
                completed.countDown()
            }
            assertTrue(launched.isSuccess)
            assertEquals(1, api.imports)
            assertEquals(1, api.submits)
            assertEquals("wxid_flow_recipient", api.submittedTalker)
            val imported = checkNotNull(api.lastImported)
            assertEquals("native import must hash the bytes it read", sent.source.md5, imported.md5)
            assertEquals(sent.source.size, imported.size)
            assertArrayEquals(copyBytes, api.receivedBytes)
            assertEquals(sent.source.md5, api.submittedMd5)

            assertTrue("提交返回普通成功时不能已有终态", outcomes.isEmpty())
            assertFalse(completed.await(150, TimeUnit.MILLISECONDS))

            // 真实 Sender 在原生导入复制字节后即可释放副本；终态不得依赖发送副本仍在。
            sent.close()
            assertFalse("原生导入后必须能立即删除自有副本", sent.source.file.exists())
            sent.close()
            assertFalse("关闭必须幂等", sent.source.file.exists())
            assertNullOrEmpty(directory.listFiles())
            assertTrue("副本释放不得影响已启动的提交", outcomes.isEmpty())

            api.finishOnWorker(Result.success(Unit))
            assertTrue("原生终态必须通知完成", completed.await(10, TimeUnit.SECONDS))
            assertEquals(1, outcomes.size)
            assertTrue(outcomes.single().isSuccess)

            api.finishOnWorker(Result.failure(IllegalStateException("duplicate terminal")))
            api.awaitIdle()
            assertEquals("重复终态只能回调一次", 1, outcomes.size)

            assertEquals(original.md5, WeChatEmoticonFile.inspect(originalFile).md5)
            assertArrayEquals(originalBytes, originalFile.readBytes())
        } finally {
            prepared?.close()
            api.close()
            clean(root)
        }
    }

    @Test
    fun transparentPngScalingKeepsAlphaWhileInLimitPngStaysOriginalAndImportFailureRemovesCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = temporaryRoot(context)
        try {
            val inLimitFile = File(root, "in-limit.png")
            writeTransparentPng(inLimitFile, 40, 30)
            val inLimitBytes = inLimitFile.readBytes()
            val inLimit = WeChatEmoticonFile.inspect(inLimitFile)
            val inLimitDirectory = File(root, "in-limit-prepared")
            val untouched = onMain { WeChatEmoticonSendImage.prepare(inLimit, 64, inLimitDirectory) }
            try {
                assertFalse("尺寸内不得创建副本", untouched.scaled)
                assertSame("尺寸内必须直接使用调用方文件", inLimit, untouched.source)
                assertArrayEquals(inLimitBytes, untouched.source.file.readBytes())
                assertNullOrEmpty(inLimitDirectory.listFiles())

                val successApi = NativeEmoticonApi()
                try {
                    val outcomes = CopyOnWriteArrayList<Result<Unit>>()
                    val completed = CountDownLatch(1)
                    val launched = WeChatEmoticonSubmission(successApi).send(
                        untouched.source, "wxid_flow_recipient", { "wxid_flow_recipient" },
                    ) { result ->
                        outcomes += result
                        completed.countDown()
                    }
                    assertTrue(launched.isSuccess)
                    assertEquals(1, successApi.submits)
                    assertArrayEquals(inLimitBytes, successApi.receivedBytes)
                    assertEquals(inLimit.md5, successApi.lastImported?.md5)
                    assertEquals(inLimit.size, successApi.lastImported?.size)
                    assertEquals(inLimit.md5, successApi.submittedMd5)
                    assertTrue(outcomes.isEmpty())

                    successApi.finishOnWorker(Result.success(Unit))
                    assertTrue(completed.await(10, TimeUnit.SECONDS))
                    assertEquals(1, outcomes.size)
                    assertTrue(outcomes.single().isSuccess)
                } finally {
                    successApi.close()
                }

                untouched.close()
                assertTrue("尺寸内关闭不得删除原文件", inLimitFile.exists())
                assertArrayEquals(inLimitBytes, inLimitFile.readBytes())
                assertEquals(inLimit.md5, WeChatEmoticonFile.inspect(inLimitFile).md5)
            } finally {
                untouched.close()
            }

            val transparentFile = File(root, "transparent.png")
            writeTransparentPng(transparentFile, 320, 240)
            val transparentBytes = transparentFile.readBytes()
            val transparent = WeChatEmoticonFile.inspect(transparentFile)
            val directory = File(root, "scaled")
            val scaled = onMain { WeChatEmoticonSendImage.prepare(transparent, 64, directory) }
            try {
                assertTrue("超限透明 PNG 必须缩放", scaled.scaled)
                assertNotSame(transparent, scaled.source)
                assertEquals(WeChatEmoticonFormat.PNG, scaled.source.format)
                assertFits(320, 240, scaled.source.file, 64)
                assertScaledAlphaKept(scaled.source.file)
                val copyBytes = scaled.source.file.readBytes()
                assertEquals(md5(copyBytes), scaled.source.md5)

                val failureApi = NativeEmoticonApi().apply {
                    importFailure = IOException("native import failed")
                }
                try {
                    val outcomes = CopyOnWriteArrayList<Result<Unit>>()
                    val failed = WeChatEmoticonSubmission(failureApi).send(
                        scaled.source, "wxid_flow_recipient", { "wxid_flow_recipient" },
                    ) { outcomes += it }
                    assertTrue("导入异常必须作为启动失败返回", failed.isFailure)
                    assertEquals(1, failureApi.imports)
                    assertEquals(0, failureApi.submits)
                    assertTrue("导入异常不得伪造终态", outcomes.isEmpty())
                    assertArrayEquals("原生读到的是缩放后的真实字节", copyBytes, failureApi.receivedBytes)

                    scaled.close()
                    assertFalse("导入异常路径也必须清理副本", scaled.source.file.exists())
                    assertNullOrEmpty(directory.listFiles())
                } finally {
                    failureApi.close()
                    scaled.close()
                }

                assertEquals(transparent.md5, WeChatEmoticonFile.inspect(transparentFile).md5)
                assertArrayEquals(transparentBytes, transparentFile.readBytes())
            } finally {
                scaled.close()
            }
        } finally {
            clean(root)
        }
    }

    @Test
    fun oversizedGifIsRejectedWithBoundsWhileInLimitGifKeepsOriginalDynamicBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = temporaryRoot(context)
        val api = NativeEmoticonApi()
        var prepared: PreparedWeChatEmoticon? = null
        try {
            val oversizedFile = File(root, "oversized.gif")
            oversizedFile.writeBytes(gifBytes(screenWidth = 600, screenHeight = 600))
            val oversizedBytes = oversizedFile.readBytes()
            val oversized = WeChatEmoticonFile.inspect(oversizedFile)
            assertEquals(WeChatEmoticonFormat.GIF, oversized.format)

            val rejectedDirectory = File(root, "rejected")
            val rejection = assertThrows(IllegalStateException::class.java) {
                onMain { WeChatEmoticonSendImage.prepare(oversized, 512, rejectedDirectory) }
            }
            val message = checkNotNull(rejection.message)
            assertTrue("错误必须带上实际尺寸: $message", message.contains("600x600"))
            assertTrue("错误必须带上尺寸上限: $message", message.contains("512x512"))
            assertNullOrEmpty(rejectedDirectory.listFiles())
            assertArrayEquals("拒绝后 GIF 原字节不得变化", oversizedBytes, oversizedFile.readBytes())

            val gifFile = File(root, "dynamic.gif")
            gifFile.writeBytes(gifBytes(screenWidth = 2, screenHeight = 2))
            val dynamicBytes = gifFile.readBytes()
            val gif = WeChatEmoticonFile.inspect(gifFile)
            assertEquals(WeChatEmoticonFormat.GIF, gif.format)
            val directory = File(root, "gif-prepared")
            prepared = onMain { WeChatEmoticonSendImage.prepare(gif, 512, directory) }
            val dynamic = checkNotNull(prepared)

            assertFalse("尺寸内 GIF 不得重编码", dynamic.scaled)
            assertSame("尺寸内 GIF 必须保持原文件", gif, dynamic.source)
            assertArrayEquals(dynamicBytes, dynamic.source.file.readBytes())
            assertNullOrEmpty(directory.listFiles())

            val outcomes = CopyOnWriteArrayList<Result<Unit>>()
            val completed = CountDownLatch(1)
            val launched = WeChatEmoticonSubmission(api).send(
                dynamic.source, "wxid_flow_recipient", { "wxid_flow_recipient" },
            ) { result ->
                outcomes += result
                completed.countDown()
            }
            assertTrue(launched.isSuccess)
            assertEquals(WeChatEmoticonFormat.GIF, api.receivedFormat)
            assertEquals("GIF 必须按动态表情导入", 2, api.lastImported?.nativeType)
            assertArrayEquals("GIF 不得被静图化", dynamicBytes, api.receivedBytes)
            assertEquals(gif.md5, api.lastImported?.md5)
            assertEquals(gif.size, api.lastImported?.size)
            assertEquals(gif.md5, api.submittedMd5)
            assertTrue(outcomes.isEmpty())

            api.finishOnWorker(Result.success(Unit))
            assertTrue(completed.await(10, TimeUnit.SECONDS))
            assertEquals(1, outcomes.size)
            assertTrue(outcomes.single().isSuccess)

            dynamic.close()
            assertTrue("关闭尺寸内准备结果不得删除原 GIF", gifFile.exists())
            assertArrayEquals(dynamicBytes, gifFile.readBytes())
            assertEquals(gif.md5, WeChatEmoticonFile.inspect(gifFile).md5)
        } finally {
            prepared?.close()
            api.close()
            clean(root)
        }
    }

    /**
     * 最小原生边界：导入直接读取 helper 交出的真实文件并计算 MD5/大小；
     * 提交立即返回“已启动”，完成回调只在测试显式触发原生终态后异步到达。
     */
    private inner class NativeEmoticonApi : WeChatEmoticonApi {
        var importFailure: Throwable? = null
        var imports = 0
        var submits = 0
        var receivedBytes: ByteArray? = null
        var receivedFormat: WeChatEmoticonFormat? = null
        var lastImported: ImportedWeChatEmoticon? = null
        var submittedTalker: String? = null
        var submittedMd5: String? = null

        private val terminal = Executors.newSingleThreadExecutor()
        private var completion: ((Result<Unit>) -> Unit)? = null

        override fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon {
            imports++
            val bytes = file.file.readBytes()
            receivedBytes = bytes
            receivedFormat = file.format
            importFailure?.let { throw it }
            val imported = ImportedWeChatEmoticon(
                md5 = md5(bytes),
                size = bytes.size.toLong(),
                nativeType = if (file.format == WeChatEmoticonFormat.GIF) 2 else 0,
                readable = true,
            )
            lastImported = imported
            return imported
        }

        override fun submit(talker: String, md5: String, complete: (Result<Unit>) -> Unit): Result<Unit> {
            submits++
            submittedTalker = talker
            submittedMd5 = md5
            completion = complete
            return Result.success(Unit)
        }

        fun finishOnWorker(result: Result<Unit>) {
            val callback = checkNotNull(completion) { "原生提交尚未启动" }
            terminal.execute {
                callback(result)
            }
        }

        fun awaitIdle() {
            val idle = CountDownLatch(1)
            terminal.execute { idle.countDown() }
            assertTrue("原生回调线程未结束", idle.await(10, TimeUnit.SECONDS))
        }

        fun close() {
            terminal.shutdownNow()
        }
    }

    private fun temporaryRoot(context: Context): File =
        File(context.cacheDir, "wx-emoji-sizing-${System.nanoTime()}-${UUID.randomUUID()}").apply {
            check(mkdirs()) { "无法创建测试临时目录: $path" }
        }

    private fun clean(root: File) {
        root.deleteRecursively()
        assertFalse("测试临时目录必须清理: $root", root.exists())
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<Result<T>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }

    private fun writeJpeg(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(18, 52, 86))
            canvas.drawRect(
                width / 4f, height / 4f, width * 3 / 4f, height * 3 / 4f,
                Paint().apply { color = Color.rgb(240, 200, 30) },
            )
            file.outputStream().use { output ->
                assertTrue("JPEG fixture must encode", bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 四角完全透明、中央完全不透明，缩放后仍可据此验证 alpha 未被丢弃。 */
    private fun writeTransparentPng(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawRect(
                width / 4f, height / 4f, width * 3 / 4f, height * 3 / 4f,
                Paint().apply { color = Color.argb(255, 20, 200, 60) },
            )
            file.outputStream().use { output ->
                assertTrue("PNG fixture must encode", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertFits(originalWidth: Int, originalHeight: Int, file: File, limit: Int) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        assertTrue("宽度 $width 必须落在上限 $limit 内", width in 1..limit)
        assertTrue("高度 $height 必须落在上限 $limit 内", height in 1..limit)
        assertEquals("宽高比必须保持", originalWidth.toDouble() / originalHeight, width.toDouble() / height, 0.02)
    }

    private fun assertScaledAlphaKept(file: File) {
        val decoded = checkNotNull(
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ),
        )
        try {
            assertTrue("缩放后的 PNG 必须保留 alpha 通道", decoded.hasAlpha())
            assertEquals("四角应保持透明", 0, Color.alpha(decoded.getPixel(0, 0)))
            assertEquals("中央不透明区域必须保留", 255, Color.alpha(decoded.getPixel(decoded.width / 2, decoded.height / 2)))
        } finally {
            decoded.recycle()
        }
    }

    /**
     * 真实可解码的单帧 GIF89a：给定逻辑屏幕尺寸，帧本身为 2x2。
     * 修改逻辑尺寸只影响 bounds，解码仍是真实 GIF 而不是伪造头部。
     */
    private fun gifBytes(screenWidth: Int, screenHeight: Int): ByteArray {
        require(screenWidth in 1..0xffff && screenHeight in 1..0xffff) { "GIF 尺寸无效" }
        return byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
            (screenWidth and 0xff).toByte(), ((screenWidth shr 8) and 0xff).toByte(),
            (screenHeight and 0xff).toByte(), ((screenHeight shr 8) and 0xff).toByte(),
            0x80.toByte(), 0x00, 0x00, // 2 色全局色表
            0x00, 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0x2c, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, // 2x2 图像描述
            0x02, 0x02, 0x44, 0x08, 0x00, 0x3b, // LZW 数据 + 结束符
        )
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun assertNullOrEmpty(files: Array<File>?) {
        assertTrue("不应残留临时文件: ${files?.joinToString()}", files.isNullOrEmpty())
    }
}
