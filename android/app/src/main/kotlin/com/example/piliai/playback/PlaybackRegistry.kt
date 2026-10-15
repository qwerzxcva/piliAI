package com.example.piliai.playback

import android.content.Context
import androidx.media3.common.Player
import java.lang.ref.WeakReference

/**
 * 审核318：活跃播放器的进程内注册表。
 *
 * 让 [PlaybackService] 的 MediaSession 与系统媒体控制能拿到当前正在播放的
 * player，而无需把 player 所有权搬进 Service（那会牵连播放器 VM 上百处调用点）。
 *
 * 约束：
 * - 同一时刻只有一个活跃 player：进播放页时 register，离开时 unregister。
 *   竖屏流与详情页各自持有 player，但只有前台那个注册。
 * - 用 WeakReference 持有 Service：Service 被系统回收时不让注册表把它吊住。
 * - 用弱引用持有 player：异常情况下 VM 未反注册也不泄漏 player。
 */
object PlaybackRegistry {

    private var playerRef: WeakReference<Player>? = null
    private var serviceRef: WeakReference<PlaybackService>? = null

    /** 当前播放标题，供通知与 MediaSession 元数据展示 */
    private var title: String = ""

    /** 当前活跃 player（可能已被回收，返回 null 表示无活跃播放） */
    fun activePlayer(): Player? = playerRef?.get()

    /** 当前播放标题（未设置时为空串） */
    fun activeTitle(): String = title

    /** 注册活跃 player，并确保前台服务在跑 */
    fun register(context: Context, player: Player, title: String = "") {
        playerRef = WeakReference(player)
        this.title = title
        val service = serviceRef?.get()
        if (service != null) {
            service.refreshMetadata()
        } else {
            PlaybackService.start(context)
        }
    }

    /** 反注册（player release / 页面销毁时调用） */
    fun unregister() {
        playerRef = null
        title = ""
    }

    fun registerService(service: PlaybackService) {
        serviceRef = WeakReference(service)
    }

    fun unregisterService(service: PlaybackService) {
        if (serviceRef?.get() === service) serviceRef = null
    }

    /** 有活跃 player 且在播时才需要保活 */
    fun shouldKeepAlive(): Boolean =
        playerRef?.get()?.isPlaying == true

    /** 播放/暂停时刷新 MediaSession 状态（锁屏/通知栏据此显示播放中/已暂停） */
    fun refreshPlaybackState() {
        serviceRef?.get()?.refreshMetadata()
    }
}
