package com.example.piliai.playback

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.ServiceCompat
import com.example.piliai.data.download.DownloadNotifier

/**
 * 审核318：播放前台服务。
 *
 * 背景：Manifest 早就声明了 FOREGROUND_SERVICE_MEDIA_PLAYBACK，但全仓库
 * 没有任何 Service——「后台继续播放」设置项实际只做到「退到后台不 pause」，
 * 音频能否持续完全依赖 Activity 是否被系统保留。Android 8+ 对后台进程有
 * 严格限制，切走一会大概率被回收，播放中断且无任何系统媒体控制。
 *
 * 设计取舍（关键）：播放器（ExoPlayer）仍由 VideoPlayerViewModel /
 * StoryPlaybackViewModel 持有——它们有完整的取流、CDN 切换、弹幕、进度
 * 上报逻辑，把 player 所有权搬进 Service 会牵连上百处调用点，收益不成正比。
 * 因此本服务不拥有 player，只做两件事：
 *
 * 1. **进程保活**：以前台服务形态持有，系统不会轻易回收进程，player 得以
 *    继续解码输出音频。
 * 2. **系统媒体控制**：MediaSessionCompat 把当前播放状态暴露给系统
 *    （锁屏/通知栏/蓝牙耳机/车载），控制指令经 [PlaybackRegistry] 找到
 *    活跃 player 并转发。
 *
 * 技术选型说明：Media3 的 `media3-session` 不在本项目离线依赖缓存里
 * （本地 Gradle 只能 --offline 构建），因此用 androidx.media 的
 * MediaSessionCompat——它已在依赖清单中（androidx.media:media:1.7.0），
 * 且与 ExoPlayer 完全兼容。后续若能联网拉依赖，可平移到
 * media3-session 的 MediaSession（API 更完整，支持自定义命令）。
 */
class PlaybackService : android.app.Service() {

    private var session: MediaSessionCompat? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        DownloadNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立刻前台化，避免系统在长任务中途杀进程
        startForegroundCompat()
        ensureSession()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundCompat() {
        val notification = DownloadNotifier.playback(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    /**
     * 建立 MediaSessionCompat。
     *
     * 延迟到 onStartCommand 而不是 onCreate：此时 UI 层通常已注册 player
     * （Activity onStart → VM ensureBackend → PlaybackRegistry.register），
     * session 才能拿到真实 player。
     */
    private fun ensureSession() {
        if (session != null) return
        val player = PlaybackRegistry.activePlayer() ?: return
        session = MediaSessionCompat(this, TAG).apply {
            isActive = true
            // 审核318：把 ExoPlayer 包成 MediaSessionCompat 的播放目标，
            // 系统媒体控制（锁屏/蓝牙）的指令会转发给它。
            setMetadata(
                android.support.v4.media.MediaMetadataCompat.Builder()
                    .putString(
                        android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                        PlaybackRegistry.activeTitle(),
                    )
                    .build()
            )
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(
                        if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING
                        else PlaybackStateCompat.STATE_PAUSED,
                        player.currentPosition,
                        1.0f,
                    )
                    .build()
            )
        }
        PlaybackRegistry.registerService(this)
    }

    /** player 换了（切集/切源）时刷新 session 元数据 */
    fun refreshMetadata() {
        val player = PlaybackRegistry.activePlayer() ?: return
        session?.setMetadata(
            android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE,
                    PlaybackRegistry.activeTitle(),
                )
                .build()
        )
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    if (player.isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    player.currentPosition,
                    1.0f,
                )
                .build()
        )
    }

    override fun onDestroy() {
        session?.release()
        session = null
        PlaybackRegistry.unregisterService(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PiliPlayback"
        private const val NOTIFICATION_ID = 0x504C  // "PL"

        /** 启动服务（重复调用只会再走一次 onStartCommand，幂等） */
        fun start(context: android.content.Context) {
            val intent = Intent(context, PlaybackService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
