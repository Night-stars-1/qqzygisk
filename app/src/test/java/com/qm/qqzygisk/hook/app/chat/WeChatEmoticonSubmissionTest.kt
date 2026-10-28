package com.qm.qqzygisk.hook.app.chat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class WeChatEmoticonSubmissionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun gif(): File = temporary.newFile("extensionless-${System.nanoTime()}").apply {
        writeBytes("GIF89a".toByteArray() + ByteArray(100) { it.toByte() })
    }

    private class NativeApi : WeChatEmoticonApi {
        var importedBytes: ByteArray? = null
        var imports = 0
        var submissions = 0
        var sentTalker: String? = null
        var sentMd5: String? = null
        var transform: (ImportedWeChatEmoticon) -> ImportedWeChatEmoticon = { it }
        var submitResult: Result<Unit> = Result.success(Unit)
        var importFailure: Throwable? = null
        private var complete: ((Result<Unit>) -> Unit)? = null

        override fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon {
            imports++
            importFailure?.let { throw it }
            importedBytes = file.file.readBytes()
            return transform(ImportedWeChatEmoticon(file.md5, file.size, if (file.format == WeChatEmoticonFormat.GIF) 2 else 1, true))
        }

        override fun submit(talker: String, md5: String, complete: (Result<Unit>) -> Unit): Result<Unit> {
            submissions++
            sentTalker = talker
            sentMd5 = md5
            this.complete = complete
            return submitResult
        }

        fun finish(result: Result<Unit>) {
            complete?.invoke(result)
        }
    }

    @Test
    fun `native upload terminal success is the only success`() {
        val file = gif()
        val original = file.readBytes()
        val api = NativeApi()
        val outcomes = mutableListOf<Result<Unit>>()
        var talkerChecks = 0
        val launched = WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(file), "test-recipient", {
            talkerChecks++
            "test-recipient"
        }) { outcomes += it }

        assertTrue(launched.isSuccess)
        assertEquals(1, talkerChecks)
        assertArrayEquals(original, api.importedBytes)
        assertArrayEquals(original, file.readBytes())
        assertEquals(1, api.imports)
        assertEquals(1, api.submissions)
        assertEquals("test-recipient", api.sentTalker)
        assertEquals(WeChatEmoticonFile.inspect(file).md5, api.sentMd5)
        // Fj/提交返回时没有终态，不能提前成功。
        assertTrue(outcomes.isEmpty())

        api.finish(Result.success(Unit))
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isSuccess)
    }

    @Test
    fun `native upload failure is never reported as success`() {
        val api = NativeApi()
        val outcomes = mutableListOf<Result<Unit>>()
        val launched = WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", { "test-recipient" }) { outcomes += it }

        assertTrue(launched.isSuccess)
        assertTrue(outcomes.isEmpty())
        api.finish(Result.failure(IllegalStateException("upload failed")))
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isFailure)
    }

    @Test
    fun `duplicate native completion is forwarded only once`() {
        val api = NativeApi()
        val outcomes = mutableListOf<Result<Unit>>()
        WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", { "test-recipient" }) { outcomes += it }

        api.finish(Result.success(Unit))
        api.finish(Result.success(Unit))
        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single().isSuccess)
    }

    @Test
    fun `switching session after import never sends`() {
        val api = NativeApi()
        val outcomes = mutableListOf<Result<Unit>>()
        var importsWhenChecked = -1
        val result = WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", {
            importsWhenChecked = api.imports
            "another-recipient"
        }) { outcomes += it }

        assertTrue(result.isFailure)
        assertEquals(1, importsWhenChecked)
        assertEquals(1, api.imports)
        assertEquals(0, api.submissions)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `native rejection returns failure without completing`() {
        val api = NativeApi().apply {
            submitResult = Result.failure(IllegalStateException("rejected"))
        }
        val outcomes = mutableListOf<Result<Unit>>()
        val result = WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", { "test-recipient" }) { outcomes += it }

        assertTrue(result.isFailure)
        assertEquals(1, api.submissions)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `changed import bytes abort before native submission`() {
        val api = NativeApi().apply { transform = { it.copy(md5 = "00000000000000000000000000000000") } }
        val outcomes = mutableListOf<Result<Unit>>()
        assertTrue(WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", { "test-recipient" }) { outcomes += it }.isFailure)
        assertEquals(0, api.submissions)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `GIF incorrectly registered as a static image aborts`() {
        val api = NativeApi().apply { transform = { it.copy(nativeType = 1) } }
        assertTrue(WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", { "test-recipient" }) {}.isFailure)
        assertEquals(0, api.submissions)
    }

    @Test
    fun `missing or incomplete native cache never sends`() {
        for (change in listOf<(ImportedWeChatEmoticon) -> ImportedWeChatEmoticon>(
            { it.copy(size = it.size - 1) }, { it.copy(readable = false) },
        )) {
            val api = NativeApi().apply { transform = change }
            assertTrue(WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", { "test-recipient" }) {}.isFailure)
            assertEquals(0, api.submissions)
        }
    }

    @Test
    fun `unavailable session never imports or sends`() {
        val api = NativeApi()
        val outcomes = mutableListOf<Result<Unit>>()
        var talkerChecks = 0
        val result = WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), null, {
            talkerChecks++
            error("会话检查不应执行")
        }) { outcomes += it }

        assertTrue(result.isFailure)
        assertEquals(0, talkerChecks)
        assertEquals(0, api.imports)
        assertEquals(0, api.submissions)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `import exception follows the normal panel failure path`() {
        val error = IOException("native cache unavailable")
        val api = NativeApi().apply { importFailure = error }
        val outcomes = mutableListOf<Result<Unit>>()
        val result = WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(gif()), "test-recipient", { "test-recipient" }) { outcomes += it }

        assertSame(error, result.exceptionOrNull())
        assertEquals(0, api.submissions)
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `WebP bytes are not converted to a bitmap before import`() {
        val bytes = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + ByteArray(30)
        val file = temporary.newFile("webp").apply { writeBytes(bytes) }
        val api = NativeApi()
        val outcomes = mutableListOf<Result<Unit>>()
        val launched = WeChatEmoticonSubmission(api).send(WeChatEmoticonFile.inspect(file), "test-recipient", { "test-recipient" }) { outcomes += it }

        assertTrue(launched.isSuccess)
        assertArrayEquals(bytes, api.importedBytes)
        assertEquals(1, api.submissions)
        assertTrue(outcomes.isEmpty())
        api.finish(Result.success(Unit))
        assertTrue(outcomes.single().isSuccess)
    }
}
