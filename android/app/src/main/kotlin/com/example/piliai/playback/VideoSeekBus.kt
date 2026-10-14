package com.example.piliai.playback

/**
 * 审核295：空降助手的跨页面 seek 通道。
 *
 * 评论页是独立路由（Screen.Comment），不持有播放器实例；而播放器在
 * VideoPlayer 路由。要点评论里的时间戳跳到视频进度，需要跨页面通信。
 *
 * 用进程内单例：播放器注册回调，评论页（或任何页面）发请求。
 * 播放器销毁时注销，避免泄漏。
 */
object VideoSeekBus {
    @Volatile
    private var handler: ((Long) -> Unit)? = null

    /** 播放器注册（进播放器时调用） */
    fun register(onSeek: (Long) -> Unit) {
        handler = onSeek
    }

    /** 播放器销毁时调用 */
    fun unregister() {
        handler = null
    }

    /**
     * 请求跳转（毫秒）。
     * @return true = 有播放器接住了请求；false = 当前没有活跃播放器（调用方应提示）
     */
    fun seekTo(positionMs: Long): Boolean {
        val h = handler ?: return false
        h(positionMs)
        return true
    }

    /** 当前是否有活跃播放器 */
    fun hasActivePlayer(): Boolean = handler != null
}
