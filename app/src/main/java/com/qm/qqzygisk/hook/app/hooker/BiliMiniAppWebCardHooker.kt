package com.qm.qqzygisk.hook.app.hooker

import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.chat.BiliReceivedCardBridge
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.utils.Log

/**
 * 收到的 B 站小程序卡片：只替换聊天行里的内容视图，消息、记录与发送都不参与。
 *
 * 安装时机由 [com.qm.qqzygisk.hook.app.QQEntry] 在宿主 ClassLoader 对齐后独立调用。
 * 这里只按“实际宿主 ClassLoader”解析类并安装；安装期间 ClassLoader 被替换时抛错，
 * 让 [BaseHooker] 不置位成功状态，下一次宿主 Activity 时机重试，避免把旧类副本锁存成已安装。
 */
internal object BiliMiniAppWebCardHooker : BaseHooker() {
    override val key = "bilibili_miniapp_to_web"
    override val name = "哔哩哔哩小程序转网页"
    override val description = "仅将收到的 B 站小程序在本地显示为宿主原生网页卡片，原始消息保持不变"
    override val defaultEnabled = true

    override fun initOnce() {
        val loader = HostData.appClassLoader
        val bridge = BiliReceivedCardBridge(loader, key, defaultEnabled)
        bridge.install()
        check(HostData.appClassLoader === loader) {
            "安装期间宿主 ClassLoader 已切换，等待下一次宿主 Activity 时机"
        }
        Log.info("B 站小程序网页卡片 hook 已安装：${bridge.target}")
    }
}
