package com.qm.qqzygisk.hook.app.chat

import java.util.concurrent.atomic.AtomicBoolean

internal data class ImportedWeChatEmoticon(
    val md5: String,
    val size: Long,
    val nativeType: Int,
    val readable: Boolean,
)

/**
 * 微信原生表情的业务接口。
 *
 * 返回的 [Result] 只表示是否启动：failure 时不得调用完成回调；
 * success 后完成回调只会在原生终态、拒绝、异常或超时后恰好调用一次。
 */
internal interface WeChatEmoticonApi {
    fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon?

    /**
     * @param complete 原生终态/拒绝/异常/超时后恰好回调一次；返回 failure 时不得调用。
     */
    fun submit(talker: String, md5: String, complete: (Result<Unit>) -> Unit): Result<Unit>
}

internal class WeChatEmoticonSubmission(private val api: WeChatEmoticonApi) {
    /**
     * 导入调用方已 [WeChatEmoticonFile.inspect] 的实际发送文件后只提交一次原生发送；
     * MD5/大小校验针对这个文件，成功必须等到原生上传与消息发送的终态。
     */
    fun send(
        source: WeChatEmoticonFile,
        talker: String?,
        currentTalker: () -> String,
        complete: (Result<Unit>) -> Unit,
    ): Result<Unit> = runCatching {
        require(!talker.isNullOrBlank()) { "没有可用的聊天会话，请重新进入聊天页面" }
        val imported = checkNotNull(api.importFile(source)) { "微信未能导入这个表情文件" }
        check(imported.md5.equals(source.md5, ignoreCase = true)) {
            "表情文件导入前后不一致，请重新打开面板后重试"
        }
        check(imported.readable && imported.size == source.size) {
            "微信表情缓存不可用，请检查微信存储权限"
        }
        if (source.format == WeChatEmoticonFormat.GIF) {
            check(imported.nativeType == 2) { "微信没有将此 GIF 识别为动态表情，已取消发送" }
        }
        // 导入可能耗时；Fj 之前必须确认面板仍指向同一个会话，切换后不提交。
        check(currentTalker() == talker) { "聊天会话已切换，请重新打开面板" }

        val once = AtomicBoolean()
        val completed: (Result<Unit>) -> Unit = { result ->
            if (once.compareAndSet(false, true)) complete(result)
        }
        val started = api.submit(talker, imported.md5, completed)
        if (started.isFailure && once.get()) {
            // 原生已经回调终态，本次发送实际已启动；外层不能再重复上报。
            return@runCatching
        }
        started.getOrThrow()
    }
}
