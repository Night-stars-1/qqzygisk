package com.qm.qqzygisk.hook.app.chat

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * 把 B 站（AppID 1109937557）的 QQ 小程序卡片改写成普通网页 Ark 卡片。
 *
 * 纯 JSON 映射：只读入参字符串，不接触宿主消息对象、不写日志、不联网、
 * 不展开或请求短链。返回 null 表示调用方应保持原卡片。
 */
internal object BiliMiniAppWebCard {
    private const val SOURCE_APP = "com.tencent.miniapp_01"
    private const val SOURCE_APP_ID = "1109937557"
    private const val SOURCE_APP_ID_NUMBER = 1109937557.0

    private const val WEB_APP = "com.tencent.structmsg"
    private const val WEB_VIEW = "news"
    private const val WEB_VER = "0.0.0.1"
    private const val WEB_TAG = "哔哩哔哩"
    private const val WEB_SOURCE_URL = "https://bilibili.com"

    private const val HOST_B23 = "b23.tv"
    private const val HOST_BILIBILI = "bilibili.com"

    /**
     * @param rawJson ArkElement bytesData 原文
     * @param isSelf 本人发送的消息，一律不转换
     * @return 新的网页卡片 JSON；null 表示保持原卡片
     */
    fun convert(rawJson: String, isSelf: Boolean): String? {
        if (isSelf) return null
        if (rawJson.isBlank()) return null
        return runCatching { convertOrNull(rawJson) }.getOrNull()
    }

    /** 任何结构不符或字段非法都返回 null；异常文本不记录。 */
    private fun convertOrNull(rawJson: String): String? {
        val root = JSONObject(rawJson)
        if (root.opt("app") != SOURCE_APP) return null

        val meta = objectOrNull(root.opt("meta")) ?: return null
        val detail = objectOrNull(meta.opt("detail_1")) ?: return null
        if (!isTargetAppId(detail.opt("appid"))) return null

        val jumpUrl = detail.opt("qqdocurl") as? String ?: return null
        if (!isBilibiliHttpsUrl(jumpUrl)) return null

        val contentTitle = (detail.opt("desc") as? String)?.takeIf { it.isNotBlank() }
            ?: (detail.opt("title") as? String)?.takeIf { it.isNotBlank() }
            ?: return null
        val preview = (detail.opt("preview") as? String).orEmpty()
        val sourceIcon = (detail.opt("icon") as? String)?.takeIf { it.isNotBlank() }

        val news = JSONObject()
            .put("title", contentTitle)
            .put("desc", "")
            .put("tag", WEB_TAG)
            .put("preview", preview)
            .put("jumpUrl", jumpUrl)
            .put("appid", 0)
            .put("app_type", 1)
            .put("source_url", WEB_SOURCE_URL)
        if (sourceIcon != null) news.put("source_icon", sourceIcon)

        val config = JSONObject()
            .put("type", "normal")
            .put("forward", true)

        return JSONObject()
            .put("app", WEB_APP)
            .put("view", WEB_VIEW)
            .put("ver", WEB_VER)
            .put("prompt", contentTitle)
            .put("meta", JSONObject().put("news", news))
            .put("config", config)
            .toString()
    }

    /** appid 接受同值的 JSON 字符串或 JSON 数字，其他形式一律不匹配。 */
    private fun isTargetAppId(value: Any?): Boolean = when (value) {
        is String -> value == SOURCE_APP_ID
        is Number -> value.toDouble().let { it.isFinite() && it == SOURCE_APP_ID_NUMBER }
        else -> false
    }

    /** 仅接受 HTTPS 且 host 为 b23.tv / bilibili.com 或其真实子域。 */
    private fun isBilibiliHttpsUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.isAbsolute || uri.rawUserInfo != null) return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.isEmpty()) return false
        return host == HOST_B23 || host.endsWith(".$HOST_B23") ||
            host == HOST_BILIBILI || host.endsWith(".$HOST_BILIBILI")
    }

    /** 小程序元数据通常为对象，宿主偶尔给出 JSON 字符串，两者都按原结构读取。 */
    private fun objectOrNull(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
}
