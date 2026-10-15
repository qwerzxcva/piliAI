package com.example.piliai.ui.story

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.VideoItem
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.data.repository.HomeRepository
import com.example.piliai.data.repository.VideoRepository
import com.example.piliai.playback.PlaybackRequestHeaders
import com.example.piliai.playback.backend.Media3Backend
import com.example.piliai.playback.backend.PlaybackBackend
import com.example.piliai.playback.backend.PlaybackEventListener
import com.example.piliai.playback.backend.PlaybackRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 审核318：竖屏流播放 ViewModel。
 *
 * 背景：原 StoryScreen 只有封面图 + 「全屏播放」按钮，点一下跳到详情页播放器，
 * 竖屏流本身不播放任何内容。BiliPai 的 PortraitVideoPager（4524 行）自带
 * ExoPlayer 与 WiFi 预加载队列，做到上下滑直接切视频播放。
 *
 * 移植取舍：不重造播放器。本项目已有完整的 [Media3Backend]（DASH 双流合并、
 * CDN 候选、丢帧检测、缓冲策略）+ [VideoRepository]（playurl + Rust 选流），
 * 因此这里只做「播放列表 + 起播编排」，切页时取消旧请求、起新播放。
 *
 * 与详情页播放器的关系：详情页的 VideoPlayerViewModel 由 NavBackStackEntry
 * 各自持有，本 VM 是 StoryScreen 自己的一份，两者互不干扰（同一时间只有
 * 一个 Screen 在前台，退到后台时 ON_STOP 会 pause）。
 */
class StoryPlaybackViewModel(
    private val repo: HomeRepository = HomeRepository(),
) : ViewModel() {

    data class UiState(
        val items: List<VideoItem> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val endReached: Boolean = false,
        /** 当前播放页的 bvid；空表示还没起播 */
        val playingBvid: String = "",
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        /** 播放失败原因（起播失败时展示重试） */
        val playError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var backend: PlaybackBackend? = null
    private var loadJob: Job? = null
    private var clockJob: Job? = null

    /** 当前已起播的 bvid，避免重复请求 */
    private var currentBvid: String = ""

    private val listener = object : PlaybackEventListener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            _state.value = _state.value.copy(isBuffering = isLoading)
        }

        override fun onBuffering(isBuffering: Boolean) {
            _state.value = _state.value.copy(isBuffering = isBuffering)
        }

        override fun onDurationChanged(durationMs: Long) {
            _state.value = _state.value.copy(durationMs = durationMs)
        }

        override fun onPositionChanged(positionMs: Long) {
            _state.value = _state.value.copy(positionMs = positionMs)
        }

        override fun onPlaybackEnded() {
            // 竖屏流播完自动进下一条（由 UI 层观察本状态翻页）
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun onError(message: String) {
            _state.value = _state.value.copy(playError = message, isBuffering = false)
        }

        override fun onVideoSizeChanged(width: Int, height: Int) = Unit
        override fun onDecoderOverloaded() = Unit
    }

    init { loadMore() }

    fun loadMore() {
        if (_state.value.loading || _state.value.endReached) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val isFirst = _state.value.items.isEmpty()
            val result = if (isFirst) repo.refresh() else repo.loadMore()
            result.onSuccess { added ->
                val list = if (isFirst) added else repo.videos.value
                _state.value = _state.value.copy(
                    items = list,
                    loading = false,
                    endReached = list.isEmpty() || added.isEmpty(),
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "视频流加载失败",
                )
            }
        }
    }

    /**
     * 起播指定视频。已在播同一个 bvid 时忽略。
     *
     * 与详情页 startPlayback 相同的请求头策略：桌面 Chrome UA + web Referer +
     * buvid3/SESSDATA cookie（DASH CDN 强制，审核215 实测无 cookie 一律 403）。
     */
    fun play(item: VideoItem) {
        if (item.bvid.isBlank()) return
        if (item.bvid == currentBvid && _state.value.playingBvid == item.bvid) return
        currentBvid = item.bvid
        loadJob?.cancel()
        _state.value = _state.value.copy(
            playingBvid = item.bvid, isBuffering = true, playError = null, durationMs = 0L,
        )
        loadJob = viewModelScope.launch {
            try {
                // DASH CDN 风控：确保 buvid3 已就绪
                runCatching { AccountSession.ensureBuvid() }
                val qn = 64 // 竖屏流默认 720p：列表里视频多、以流畅为先
                val playResult = VideoRepository(BiliApiClient())
                    .getPlayUrlWithCandidates(item.bvid, item.cid, qn = qn)
                    .getOrElse {
                        _state.value = _state.value.copy(
                            playError = "取流失败：${it.message}", isBuffering = false,
                        )
                        return@launch
                    }
                val videoCand = playResult.second.first
                val audioCand = playResult.second.second
                val videoUrl = videoCand?.primary
                val audioUrl = audioCand?.primary
                if (videoUrl.isNullOrEmpty()) {
                    _state.value = _state.value.copy(
                        playError = "未解析到播放地址（可能需要登录）", isBuffering = false,
                    )
                    return@launch
                }
                val cookie = runCatching { AccountSession.cookieHeader() }.getOrNull().orEmpty()
                val referer = PlaybackRequestHeaders.refererFor("https://www.bilibili.com", videoUrl, audioUrl)
                val headers = buildMap {
                    referer?.let {
                        put("Referer", it)
                        put("Origin", "https://www.bilibili.com")
                    }
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }
                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                val b = ensureBackend()
                b.prepare(
                    PlaybackRequest(
                        videoUri = videoUrl.toUri(),
                        audioUri = audioUrl?.takeIf { it.isNotEmpty() }?.toUri(),
                        headers = headers,
                        userAgent = userAgent,
                        playWhenReady = true,
                    )
                )
                b.play()
                startPositionClock()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    playError = e.message ?: "播放失败", isBuffering = false,
                )
            }
        }
    }

    private fun ensureBackend(): PlaybackBackend {
        val existing = backend
        if (existing != null) return existing
        val created = Media3Backend(appContext(), listener)
        backend = created
        // 审核318：注册到 PlaybackRegistry，系统媒体控制才能控制竖屏流播放。
        // 标题取 state 里当前播放项——ensureBackend 由 play() 调起，
        // 那时 playingBvid 已更新为即将播放的视频。
        created.nativePlayer()?.let {
            val title = _state.value.items.firstOrNull { v -> v.bvid == _state.value.playingBvid }
                ?.title.orEmpty()
            com.example.piliai.playback.PlaybackRegistry.register(appContext(), it, title)
        }
        return created
    }

    /**
     * Surface 生命周期回调（StoryScreen 的 SurfaceView）。
     *
     * detach 时传 null：ExoPlayer 的 surface 置空后仍继续解码/缓冲，
     * 回到该页重新 attach 时无需重新取流（比 clearMediaItems 便宜）。
     */
    fun attachSurface(surface: android.view.Surface?) {
        val b = backend ?: return
        if (surface != null) b.attachSurface(surface) else b.detachSurface()
    }

    /** 播放器需要 Context；用 AppContext 避免 VM 持 Activity 引用 */
    private fun appContext(): Context =
        com.example.piliai.AppContext.get()

    private fun startPositionClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (true) {
                val b = backend ?: break
                _state.value = _state.value.copy(
                    positionMs = b.positionMs(),
                    durationMs = b.durationMs().takeIf { it > 0L } ?: _state.value.durationMs,
                )
                kotlinx.coroutines.delay(500)
            }
        }
    }

    fun togglePlayPause() {
        val b = backend ?: return
        if (b.isPlaying()) b.pause() else b.play()
        // 审核318：同步播放态到 MediaSession
        com.example.piliai.playback.PlaybackRegistry.refreshPlaybackState()
    }

    /** 应用退到后台：暂停（与详情页 pauseOnMinimize 语义一致，省电 + 不占音频焦点） */
    fun onAppBackground() {
        backend?.takeIf { it.isPlaying() }?.pause()
    }

    override fun onCleared() {
        clockJob?.cancel()
        loadJob?.cancel()
        // 审核318：先反注册再 release（与详情页播放器同一顺序）
        com.example.piliai.playback.PlaybackRegistry.unregister()
        backend?.release()
        backend = null
    }
}
