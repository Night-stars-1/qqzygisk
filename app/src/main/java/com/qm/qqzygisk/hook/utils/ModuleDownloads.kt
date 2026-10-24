package com.qm.qqzygisk.hook.utils

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment

internal data class ModuleDownload(
    val id: Long,
    val version: String,
    val versionCode: Long,
    val fileName: String,
) {
    internal val query = DownloadManager.Query().setFilterById(id)
}

internal enum class ModuleDownloadStatus { Pending, Running, Paused, Complete, Failed, Missing }

internal data class ModuleDownloadProgress(
    val download: ModuleDownload,
    val status: ModuleDownloadStatus,
    val receivedBytes: Long,
    val totalBytes: Long,
    val message: String,
) {
    val active: Boolean get() = status == ModuleDownloadStatus.Pending ||
        status == ModuleDownloadStatus.Running || status == ModuleDownloadStatus.Paused
}

/** The system owns the transfer, so closing a settings dialog does not cancel the ZIP. */
internal object ModuleDownloads {
    private const val PREFERENCES = "qqzygisk_module_download"
    private val unsafeFileName = Regex("[^A-Za-z0-9._-]")

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun manager(context: Context): DownloadManager =
        requireNotNull(context.applicationContext.getSystemService(DownloadManager::class.java)) {
            "系统下载服务不可用"
        }

    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun load(context: Context): ModuleDownload? {
        val preferences = preferences(context)
        val id = preferences.getLong("id", -1)
        if (id <= 0) return null
        return ModuleDownload(id, preferences.getString("version", "") ?: "",
            preferences.getLong("versionCode", 0), preferences.getString("fileName", "") ?: "")
    }

    @Synchronized
    fun start(context: Context, release: ModuleRelease): ModuleDownload {
        require(!needsStoragePermission(context)) { "保存到下载目录需要存储权限" }
        val uri = Uri.parse(release.zipUrl)
        require(uri.scheme == "https") { "下载链接必须使用 HTTPS" }
        val service = manager(context)
        load(context)?.let { previous ->
            val progress = query(context, previous)
            check(!progress.active) { "已有模块正在下载，请等待完成或先取消" }
            if (progress.status == ModuleDownloadStatus.Complete && previous.versionCode == release.versionCode) return previous
            if (progress.status == ModuleDownloadStatus.Failed || progress.status == ModuleDownloadStatus.Missing) {
                service.remove(previous.id)
            }
        }
        val version = unsafeFileName.replace(release.version, "_").take(40)
        val fileName = "qqhook-$version-${System.currentTimeMillis()}.zip"
        val request = DownloadManager.Request(uri)
            .setTitle("qqhook v${release.version}")
            .setDescription("KSU 模块更新")
            .setMimeType("application/zip")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = service.enqueue(request)
        val download = ModuleDownload(id, release.version, release.versionCode, fileName)
        val saved = preferences(context).edit().putLong("id", id)
            .putString("version", release.version).putLong("versionCode", release.versionCode)
            .putString("fileName", fileName).commit()
        if (!saved) {
            service.remove(id)
            error("无法保存下载任务，请重试")
        }
        return download
    }

    fun query(context: Context, download: ModuleDownload): ModuleDownloadProgress {
        val service = manager(context)
        service.query(download.query)?.use { cursor ->
            if (!cursor.moveToFirst()) return missing(download)
            val received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            var status = ModuleDownloadStatus.Failed
            val message = when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> {
                    status = ModuleDownloadStatus.Pending
                    "等待开始下载"
                }
                DownloadManager.STATUS_RUNNING -> {
                    status = ModuleDownloadStatus.Running
                    "正在下载"
                }
                DownloadManager.STATUS_PAUSED -> {
                    status = ModuleDownloadStatus.Paused
                    when (reason) {
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "等待网络连接"
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "等待 Wi-Fi"
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> "等待系统重试"
                        else -> "下载已暂停，等待系统恢复"
                    }
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    try {
                        service.openDownloadedFile(download.id)?.use { }
                            ?: return missing(download)
                    } catch (_: java.io.FileNotFoundException) {
                        return missing(download)
                    }
                    status = ModuleDownloadStatus.Complete
                    "下载完成"
                }
                DownloadManager.STATUS_FAILED -> when (reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
                    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载目录不可用"
                    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "下载文件已存在"
                    DownloadManager.ERROR_CANNOT_RESUME -> "下载无法继续，请重新下载"
                    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "下载链接重定向过多"
                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "下载数据传输失败"
                    in 400..599 -> "下载失败：HTTP $reason"
                    else -> "下载失败（错误码 $reason）"
                }
                else -> "无法识别系统下载状态"
            }
            return ModuleDownloadProgress(download, status, received, total, message)
        }
        return missing(download)
    }

    fun cancel(context: Context, download: ModuleDownload) {
        manager(context).remove(download.id)
        val preferences = preferences(context)
        if (preferences.getLong("id", -1) == download.id) preferences.edit().clear().commit()
    }

    fun openIntent(context: Context, download: ModuleDownload): Intent {
        val progress = query(context, download)
        check(progress.status == ModuleDownloadStatus.Complete) { progress.message }
        val uri = manager(context).getUriForDownloadedFile(download.id)
            ?: error("下载文件不存在，请重新下载")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/zip")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("qqhook 模块 ZIP", uri)
        return intent
    }

    private fun missing(download: ModuleDownload) = ModuleDownloadProgress(
        download, ModuleDownloadStatus.Missing, 0, -1, "下载文件或任务已不存在，请重新下载",
    )
}
