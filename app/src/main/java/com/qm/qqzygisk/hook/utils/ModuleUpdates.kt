package com.qm.qqzygisk.hook.utils

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class ModuleRelease(
    val version: String,
    val versionCode: Long,
    val zipUrl: String,
    val changelogUrl: String?,
)

/** Blocking network operations: callers run these on Dispatchers.IO, never the UI thread. */
internal object ModuleUpdates {
    private const val UPDATE_URL = "https://raw.githubusercontent.com/Night-stars-1/qqzygisk/master/update.json"
    private val releaseHeading = Regex("^#\\s+[^\\r\\n]+$", RegexOption.MULTILINE)

    fun check(): ModuleRelease {
        val json = JSONObject(request(UPDATE_URL))
        val version = json.getString("version").trim()
        val code = json.getLong("versionCode")
        require(version.isNotEmpty() && code > 0) { "更新源中的版本信息无效" }
        val changelog = if (json.isNull("changelog")) null else json.optString("changelog").takeIf { it.isNotBlank() }
        return ModuleRelease(version, code, httpsUrl(json.getString("zipUrl")), changelog)
    }

    fun releaseNotes(release: ModuleRelease): String {
        val url = release.changelogUrl ?: return ""
        val text = request(httpsUrl(url))
        // 弹窗已有模块更新版本标题，正文仅保留本版本内容，不重复文件标题或历史条目。
        val first = releaseHeading.find(text) ?: return text.trim()
        val next = releaseHeading.find(text, first.range.last + 1)
        return text.substring(first.range.last + 1, next?.range?.first ?: text.length).trim()
    }

    private fun httpsUrl(value: String): String {
        val url = URL(value)
        require(url.protocol == "https") { "更新链接必须使用 HTTPS" }
        return url.toExternalForm()
    }

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "qqhook-module-updater")
            val status = connection.responseCode
            check(status in 200..299) { "请求更新信息失败：HTTP $status" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
