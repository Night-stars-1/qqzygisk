package com.qm.qqzygisk.hook.app.host

import android.app.Activity
import android.view.View
import com.qm.qqzygisk.hook.app.chat.WeChatChatSession
import com.qm.qqzygisk.hook.app.chat.WeChatEmoticonSender
import com.qm.qqzygisk.hook.app.chat.WeChatImageSender
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.hooker.WeChatSettingHooker
import com.qm.qqzygisk.hook.app.hooker.WeChatEmoticonButtonHooker
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

    override fun send(file: File, kind: SendKind, session: ChatSessionRef?): Result<Unit> {
        val target = when (val resolved = session?.resolve() ?: SessionTarget.Unavailable) {
            is SessionTarget.Ready -> resolved.target as? String
            SessionTarget.Changed -> return Result.failure(IllegalStateException("聊天会话已切换，请重新打开面板"))
            SessionTarget.Unavailable -> null
        }
        return when (kind) {
            SendKind.IMAGE -> WeChatImageSender.send(
                file = file,
                talker = target?.takeIf { it.isNotBlank() },
            )

            SendKind.EMOTICON -> WeChatEmoticonSender.send(
                file = file,
                talker = target?.takeIf { it.isNotBlank() },
            )
        }
    }

    override fun installSettingsEntry(activity: Activity): Boolean = WeChatSettingHooker.install()
}
