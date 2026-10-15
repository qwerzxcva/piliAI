package com.example.piliai.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.piliai.R

/**
 * 审核318：下载通知。
 *
 * DownloadWorker 以前台任务运行，必须提供 Notification（Android 8+ 强制）。
 * 只有一个「下载中」渠道：进行中的下载项通常只有一两个，逐条通知会造成
 * 通知栏刷屏，因此按渠道聚合（同 id 覆盖更新）。
 */
object DownloadNotifier {

    const val CHANNEL_ID = "download"
    private const val CHANNEL_NAME = "离线下载"

    fun ensureChannel(context: Context) {
        // 审核318：minSdk 36，NotificationChannel 自 API 26 起必有，无需版本判断
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "离线缓存下载进度"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 下载中进度通知（确定性进度，0..100） */
    fun progress(context: Context, title: String, percent: Int): Notification {
        ensureChannel(context)
        val pct = percent.coerceIn(0, 100)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("正在下载：$title")
            .setContentText("$pct%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 下载完成通知（可清除） */
    fun done(context: Context, title: String): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("下载完成：$title")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 取消通知（下载结束/失败时清理前台态） */
    fun cancel(context: Context, notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(notificationId)
    }
}
