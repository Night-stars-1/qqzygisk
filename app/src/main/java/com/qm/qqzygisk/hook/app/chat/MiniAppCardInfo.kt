package com.qm.qqzygisk.hook.app.chat

import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** 概览中的一行原始 meta 字段。 */
internal data class MiniAppCardField(
    val key: String,
    val label: String,
    val value: String,
)

/**
 * 从 Ark 卡片 bytesData 解析出的只读快照。
 *
 * [rawJson] 保留宿主返回的原始文本，复制使用它；
 * [prettyJson] 只是重新缩进后的完整展示副本。
 */
internal data class MiniAppCardInfo(
    val app: String,
    val view: String?,
    val prompt: String?,
    val metadataPath: String?,
    val fields: List<MiniAppCardField>,
    val rawJson: String,
    val prettyJson: String,
)

/**
 * QQ 小程序 Ark 卡片的只读解析入口。
 *
 * 只调用 host 的 getBytesData，不修改消息对象、不联网、不写日志或磁盘。
 * 普通网页 Ark、缺少卡片数据或 JSON 损坏都返回 false，不把其他 Ark 当作小程序。
 */
internal object MiniAppCards {
    private val MINI_APP_APPS = setOf("com.tencent.miniapp_01", "com.tencent.miniapp", "com.tencent.miniapp.lua")
    private val METADATA_KEYS = arrayOf("detail_1", "miniapp")
    private const val UNVERIFIED_LINK_LABEL = "卡片原始链接（未验证网页兼容）"
    private const val COMPLEX_VALUE = "（复杂字段，请查看原始 JSON）"

    /** 这些字段的 URL 指向图片，不按“卡片原始链接”提示网页兼容性。 */
    private val imageKeys = setOf(
        "icon",
        "iconurl",
        "icon_url",
        "preview",
        "previewurl",
        "preview_url",
        "cover",
        "coverurl",
        "cover_url",
    )

    /** getBytesData 是 ArkElement 的公开无参 getter；按实际 element 类缓存。 */
    private val bytesDataGetters = ConcurrentHashMap<Class<*>, Method>()

    private val knownLabels = mapOf(
        "appid" to "AppID",
        "appId" to "AppID",
        "app_id" to "AppID",
        "title" to "标题",
        "desc" to "描述",
        "path" to "页面路径",
        "pagepath" to "页面路径",
        "pagePath" to "页面路径",
        "page_path" to "页面路径",
        "icon" to "图标",
        "preview" to "预览图",
        "url" to UNVERIFIED_LINK_LABEL,
        "qqdocurl" to UNVERIFIED_LINK_LABEL,
        "scene" to "场景值",
        "showid" to "展示 ID",
        "nickname" to "昵称",
        "name" to "名称",
        "appType" to "应用类型",
        "shareTemplateId" to "分享模板 ID",
        "shareTemplateData" to "分享模板数据",
        "tag" to "标签",
        "button" to "按钮",
        "source" to "来源",
    )

    /** 当前消息元素是否是小程序卡片；任何读取/解析失败都视为否。 */
    fun isMiniApp(element: Any): Boolean {
        val raw = rawJson(element) ?: return false
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return root.optString("app") in MINI_APP_APPS
    }

    /**
     * 读取并解析小程序卡片，失败时抛出明确异常（不携带卡片原文）。
     * 调用方应先用 [isMiniApp] 过滤。
     */
    fun read(element: Any): MiniAppCardInfo {
        val raw = rawJson(element) ?: error("无法从消息元素读取 Ark 卡片数据")
        return parse(raw) ?: error("该 Ark 卡片不是 QQ 小程序卡片")
    }

    /** 纯 JSON 入口：只有 app 精确等于小程序标识时才返回结果。 */
    fun parse(raw: String): MiniAppCardInfo? {
        if (raw.isEmpty()) return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val app = root.opt("app") as? String ?: return null
        if (app !in MINI_APP_APPS) return null
        var metadataPath: String? = null
        var detail: JSONObject? = null
        val meta = root.optJSONObject("meta")
        if (meta != null) {
            for (key in METADATA_KEYS) {
                val value = metadataObject(meta.opt(key)) ?: continue
                metadataPath = "meta.$key"
                detail = value
                break
            }
        }
        return MiniAppCardInfo(
            app = app,
            view = root.textOrNull("view"),
            prompt = root.textOrNull("prompt"),
            metadataPath = metadataPath,
            fields = detail?.let(::detailFields).orEmpty(),
            rawJson = raw,
            prettyJson = runCatching { root.toString(2) }.getOrDefault(raw),
        )
    }

    private fun rawJson(element: Any): String? {
        val type = element.javaClass
        val getter = bytesDataGetters[type] ?: type.methods.singleOrNull {
            it.name == "getBytesData" && it.parameterCount == 0 &&
                it.returnType == String::class.java && !Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }?.also { bytesDataGetters[type] = it }
        return getter?.let { runCatching { it.invoke(element) as? String }.getOrNull() }
    }

    /** 仅解析卡片中实际存在的对象或 JSON 字符串，不补齐任何字段。 */
    private fun metadataObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun detailFields(detail: JSONObject): List<MiniAppCardField> {
        val fields = ArrayList<MiniAppCardField>(detail.length())
        detail.keys().forEach { key ->
            val value = detail.opt(key)
            if (value == null || value === JSONObject.NULL) return@forEach
            val text = displayValue(value)
            if (text.isEmpty()) return@forEach
            fields += MiniAppCardField(
                key = key,
                label = knownLabels[key] ?: linkLabel(key) ?: key,
                value = text,
            )
        }
        return fields
    }

    private fun displayValue(value: Any): String = when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        is JSONObject, is JSONArray -> COMPLEX_VALUE
        else -> value.toString()
    }

    private fun linkLabel(key: String): String? =
        if (isLinkKey(key)) UNVERIFIED_LINK_LABEL else null

    /** url/qqdocurl 及同类字段只按原文展示，绝不补齐或猜测可直接打开的网页地址。 */
    private fun isLinkKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        if (normalized in imageKeys) return false
        return normalized == "url" || normalized.endsWith("url")
    }

    private fun JSONObject.textOrNull(key: String): String? {
        val value = opt(key) ?: return null
        if (value === JSONObject.NULL) return null
        val text = (value as? String) ?: value.toString()
        return text.takeIf { it.isNotEmpty() }
    }
}
