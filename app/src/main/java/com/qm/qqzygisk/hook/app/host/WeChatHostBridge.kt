package com.qm.qqzygisk.hook.app.host

import android.app.Activity
import android.view.View
import com.qm.qqzygisk.hook.app.chat.WeChatChatSession
import com.qm.qqzygisk.hook.app.chat.WeChatEmoticonSender
import com.qm.qqzygisk.hook.app.chat.WeChatImageSender
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.hooker.WeChatSettingHooker
import com.qm.qqzygisk.hook.app.hooker.WeChatEmoticonButtonHooker
import com.qm.qqzygisk.hook.utils.Log
import java.io.File

/**
 * 微信的宿主实现。
 *
 * - 会话：聊天页 Intent extra `Chat_User`（见 [WeChatChatSession]）
 * - 表情发送：微信原生 EmojiMgrImpl 的本地导入与 sendEmoji 接口（见 [WeChatEmoticonSender]）
 * - 图片发送：用户手动选择图片模式时使用 PhotoEditProxy（见 [WeChatImageSender]）
 * - 设置入口：微信 `setting_new` 设置页是 UIComponent + 设置项树的模型，插入点见
 *   [com.qm.qqzygisk.hook.app.hooker.WeChatSettingHooker]。
 */
internal object WeChatHostBridge : HostBridge {
    override val packageName: String = "com.tencent.mm"

    override val supportedKinds: List<SendKind> = listOf(SendKind.EMOTICON, SendKind.IMAGE)
    override val panelActionPreferenceKey: String = "wechat_native_emoticon_panel_send_type"

    override val proxyActivityClass: Class<*>?
        get() = listOf(
            "com.tencent.mm.ui.EmptyActivity",
            "com.tencent.mm.ui.container.MMCommonActivity",
        ).asSequence().mapNotNull { name ->
            runCatching { Class.forName(name, false, appClassLoader) }.getOrNull()
        }.firstOrNull { Activity::class.java.isAssignableFrom(it) }

    override fun installBeforeChat() {
        WeChatEmoticonButtonHooker.install()
    }

    override fun sessionFor(view: View): ChatSessionRef? = WeChatChatSession.sessionFor(view)

    /** 返回值仅表示启动结果；面板走 sendAsync，成功历史仍以原生上传和发送终态为准。 */
    override fun send(file: File, kind: SendKind, session: ChatSessionRef?): Result<Unit> = runCatching {
        val target = currentTalker(session)
        when (kind) {
            SendKind.IMAGE -> WeChatImageSender.send(file, target).getOrThrow()
            SendKind.EMOTICON -> WeChatEmoticonSender.send(
                file, target, { currentTalker(session) },
            ) { result ->
                result.onFailure { Log.warn("微信原生表情发送未完成（${it.javaClass.simpleName}）") }
            }.getOrThrow()
        }
    }

    /**
     * 原生表情的返回值只表示启动（入队）结果：成功后面板立即关闭，上传/发送终态仍走
     * [complete]。同步先到的失败终态与随后的入队回调由面板按一次终态 + 关闭处理。
     */
    override fun sendAsync(
        file: File,
        kind: SendKind,
        session: ChatSessionRef?,
        onEnqueued: () -> Unit,
        complete: (Result<Unit>) -> Unit,
    ) {
        runCatching {
            val target = currentTalker(session)
            when (kind) {
                SendKind.IMAGE -> complete(WeChatImageSender.send(file, target))
                SendKind.EMOTICON -> WeChatEmoticonSender.send(
                    file, target, { currentTalker(session) }, complete,
                ).onSuccess { runCatching { onEnqueued() } }.getOrThrow()
            }
        }.onFailure { complete(Result.failure(it)) }
    }

    private fun currentTalker(session: ChatSessionRef?): String =
        when (val resolved = session?.resolve() ?: SessionTarget.Unavailable) {
            is SessionTarget.Ready -> (resolved.target as? String)?.takeIf { it.isNotBlank() }
                ?: error("没有可用的聊天会话，请重新进入聊天页面")
            SessionTarget.Changed -> error("聊天会话已切换，请重新打开面板")
            SessionTarget.Unavailable -> error("没有可用的聊天会话，请重新进入聊天页面")
        }

    override fun installSettingsEntry(activity: Activity): Boolean = WeChatSettingHooker.install()
}
