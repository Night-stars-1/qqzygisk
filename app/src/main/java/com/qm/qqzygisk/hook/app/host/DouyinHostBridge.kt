package com.qm.qqzygisk.hook.app.host

import android.app.Activity
import android.view.View
import com.qm.qqzygisk.hook.app.chat.DouyinChatSession
import com.qm.qqzygisk.hook.app.chat.DouyinMediaSender
import com.qm.qqzygisk.hook.app.chat.DouyinSessionTarget
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.hooker.DouyinSettingHooker
import com.qm.qqzygisk.hook.utils.Log
import java.io.File

internal object DouyinHostBridge : HostBridge {
    override val packageName: String = HostType.DOUYIN.packageName
    override val supportedKinds: List<SendKind> = listOf(SendKind.IMAGE)
    override val panelActionPreferenceKey: String = "douyin_native_image_panel_send_type"

    override val proxyActivityClass: Class<*>
        get() = Class.forName("com.ss.android.ugc.aweme.setting.ui.AboutActivity", false, appClassLoader)

    override fun matchesEmoticonButton(view: View): Boolean = DouyinChatSession.matches(view)
    override fun installBeforeChat() = DouyinChatSession.install()
    override fun sessionFor(view: View): ChatSessionRef? = DouyinChatSession.sessionFor(view)
    override fun installSettingsEntry(activity: Activity): Boolean = DouyinSettingHooker.inject(activity)

    override fun send(file: File, kind: SendKind, session: ChatSessionRef?): Result<Unit> = runCatching {
        check(kind == SendKind.IMAGE) { "抖音面板当前使用普通图片发送，请重新打开面板" }
        val target = currentTarget(session)
        DouyinMediaSender.send(file, target, { currentTarget(session) }) { result ->
            result.onFailure { Log.error("抖音媒体发送失败", it) }
        }.getOrThrow()
    }

    /** 抖音面板只有普通图片，同步终态由 [complete] 承担；图片没有独立入队事件。 */
    override fun sendAsync(
        file: File,
        kind: SendKind,
        session: ChatSessionRef?,
        onEnqueued: () -> Unit,
        complete: (Result<Unit>) -> Unit,
    ) {
        runCatching {
            check(kind == SendKind.IMAGE) { "抖音面板当前使用普通图片发送，请重新打开面板" }
            val target = currentTarget(session)
            DouyinMediaSender.send(file, target, { currentTarget(session) }, complete).getOrThrow()
        }.onFailure { complete(Result.failure(it)) }
    }

    private fun currentTarget(session: ChatSessionRef?): DouyinSessionTarget =
        when (val resolved = session?.resolve() ?: SessionTarget.Unavailable) {
            is SessionTarget.Ready -> resolved.target as? DouyinSessionTarget
                ?: error("抖音聊天会话类型不匹配")
            SessionTarget.Changed -> error("聊天会话已切换，请重新打开面板")
            SessionTarget.Unavailable -> error("没有可用的抖音聊天会话，请重新打开面板")
        }
}
