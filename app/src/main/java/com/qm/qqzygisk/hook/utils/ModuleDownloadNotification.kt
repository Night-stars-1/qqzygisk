package com.qm.qqzygisk.hook.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock

/**
 * 下载通知：使用宿主进程自己的 [NotificationManager] 和独立通道，不依赖宿主的清单声明，
 * 也不使用前台服务（模块寄生在 QQ/微信/抖音进程内，无法申请前台服务类型）。
 *
 * 通知只是尽力而为：宿主关闭通知、Android 13+ 未授予 POST_NOTIFICATIONS、ROM 拦截等情况下
 * 一律安静跳过，下载本身照常进行，进度仍由 [ModuleDownloads.observe] 提供。
 */
internal object ModuleDownloadNotification {
    private const val CHANNEL_ID = "qqzygisk_module_download"
    private const val CHANNEL_NAME = "模块更新下载"
    private const val NOTIFICATION_ID = 0x5151
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val MIME_ZIP = "application/zip"

    private var lastPostedAt = 0L

    fun update(context: Context, progress: ModuleDownloadProgress) {
        if (!progress.active) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostedAt < PROGRESS_INTERVAL_MS) return
        lastPostedAt = now
        val manager = manager(context) ?: return
        try {
            val builder = builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载模块更新")
                .setContentText(progress.message.ifBlank { "正在下载" })
                .setProgress(100, percent(progress), progress.totalBytes <= 0L)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Throwable) {
        }
    }

    fun complete(context: Context, progress: ModuleDownloadProgress, uri: Uri) {
        lastPostedAt = 0L
        val manager = manager(context) ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_ZIP)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.clipData = ClipData.newRawUri("qqhook 模块 ZIP", uri)
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = builder(context)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("模块更新已下载")
                .setContentText(progress.download.fileName)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Throwable) {
        }
    }

    fun failed(context: Context, message: String) {
        lastPostedAt = 0L
        val manager = manager(context) ?: return
        try {
            val builder = builder(context)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("模块更新下载失败")
                .setContentText(message)
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Throwable) {
        }
    }

    fun cancel(context: Context) {
        lastPostedAt = 0L
        try {
            manager(context)?.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }

    private fun builder(context: Context): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)

    /** 主播/宿主没有通知权限时返回 null，调用方静默跳过。 */
    private fun manager(context: Context): NotificationManager? {
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java)
            ?: return null
        return try {
            if (!manager.areNotificationsEnabled()) return null
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                )
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
            manager
        } catch (_: Throwable) {
            null
        }
    }

    private fun percent(progress: ModuleDownloadProgress): Int {
        if (progress.totalBytes <= 0L) return 0
        val received = progress.receivedBytes.coerceIn(0L, progress.totalBytes)
        return ((received * 100) / progress.totalBytes).toInt().coerceIn(0, 100)
    }
}
