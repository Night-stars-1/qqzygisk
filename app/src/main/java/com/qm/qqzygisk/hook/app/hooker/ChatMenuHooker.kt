package com.qm.qqzygisk.hook.app.hooker

import android.content.Context
import android.widget.Toast
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.base.SettingData
import com.qm.qqzygisk.hook.app.chat.ChatMenu
import com.qm.qqzygisk.hook.app.chat.ChatMenuPosition
import com.qm.qqzygisk.hook.app.chat.ChatMenuType
import com.qm.qqzygisk.hook.app.chat.NtImageRkeyProvider
import com.qm.qqzygisk.hook.app.chat.SaveImagePanel
import com.qm.qqzygisk.hook.app.chat.MiniAppCards
import com.qm.qqzygisk.hook.app.chat.MiniAppInfoDialog
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log

object ChatMenuHooker : BaseHooker() {
    override val key = "chat_menu_entry"
    override val name = "聊天长按保存图片"
    override val description = "在含图片的消息长按菜单中保存图片"
    override val defaultEnabled = false

    private const val MINIAPP_INFO_KEY = "miniapp_info"
    override val extraSettings = listOf(
        SettingData(
            key = MINIAPP_INFO_KEY,
            name = "小程序信息",
            description = "在小程序卡片的长按菜单中查看和复制原始信息",
            defaultEnabled = false,
        ),
    )

    private val enabled get() = HookSettings.isEnabled(key, defaultEnabled)

    override fun initOnce() {
        ChatMenu.addMenuItem(
            title = "保存",
            type = ChatMenuType.Pic,
            icon = R.drawable.ic_save,
            visible = { enabled },
            position = ChatMenuPosition.Front,
        ) { context, element ->
            openSavePanel(context, element)
        }
        // 信息查看有独立开关，不受“保存图片”或 B 站卡片转换开关控制。
        ChatMenu.addMenuItem(
            title = "小程序信息",
            type = ChatMenuType.Ark,
            icon = R.drawable.ic_miniapp_info,
            visible = { HookSettings.isEnabled(MINIAPP_INFO_KEY, false) },
            matches = MiniAppCards::isMiniApp,
        ) { context, element ->
            try {
                MiniAppInfoDialog.show(context, MiniAppCards.read(element))
            } catch (_: Exception) {
                // 原始 JSON 可能含私有参数，不把解析异常或卡片内容写入日志。
                Toast.makeText(context, "无法读取小程序信息", Toast.LENGTH_SHORT).show()
            }
        }
        runCatching {
            NtImageRkeyProvider.installHook()
        }.onFailure {
            Log.error("安装 NT 图片 rkey hook 失败", it)
        }
    }

    private fun openSavePanel(
        context: Context,
        picElement: Any,
    ) {
        runCatching {
            SaveImagePanel.show(context, resolveImageUrls(picElement))
        }.onFailure {
            Log.error("打开图片面板失败", it)
            Toast.makeText(context, "无法打开图片面板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveImageUrls(picElement: Any): List<String> {
        val originUrl = invokeStringGetter(picElement, "getOriginImageUrl").orEmpty()
        val candidates =
            when {
                originUrl.startsWith("https://") || originUrl.startsWith("http://") -> {
                    listOf(originUrl)
                }

                originUrl.startsWith("/download") -> {
                    val ntUrl = "https://multimedia.nt.qq.com.cn$originUrl"
                    val signedUrl =
                        if (originUrl.contains("rkey=")) {
                            ntUrl
                        } else {
                            NtImageRkeyProvider.get(originUrl)?.let { appendRkey(ntUrl, it) }
                        }
                    listOfNotNull(signedUrl, ntUrl)
                }

                originUrl.startsWith("/") -> {
                    listOf("https://gchat.qpic.cn$originUrl")
                }

                else -> {
                    emptyList()
                }
            }
        return candidates.distinct()
    }

    private fun appendRkey(
        imageUrl: String,
        rkey: String,
    ): String {
        val separator =
            when {
                imageUrl.endsWith('?') || imageUrl.endsWith('&') -> ""
                imageUrl.contains('?') -> "&"
                else -> "?"
            }
        return imageUrl + separator + rkey.removePrefix("?").removePrefix("&")
    }

    private fun invokeStringGetter(
        instance: Any,
        methodName: String,
    ): String? =
        runCatching {
            instance.javaClass.getMethod(methodName).invoke(instance) as? String
        }.getOrNull()
}
