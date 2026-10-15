package com.example.piliai.ui.audio

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.FavMedia
import com.example.piliai.data.model.FavFolder
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.data.repository.LibraryRepository
import com.example.piliai.data.repository.VideoRepository
import com.example.piliai.playback.PlaybackRequestHeaders
import com.example.piliai.playback.PlaybackRegistry
import com.example.piliai.playback.SubtitleController
import com.example.piliai.playback.backend.Media3Backend
import com.example.piliai.playback.backend.PlaybackBackend
import com.example.piliai.playback.backend.PlaybackEventListener
import com.example.piliai.playback.backend.PlaybackRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 审核319：听视频模式 ViewModel（移植 BiliPai feature/audio）。
 *
 * 设计取舍——没有照搬 BiliPai 的 1690 行歌词系统：
 * BiliPai 用多个第三方歌词 provider 搜索匹配，因为 YouTube Music 类视频没有
 * 内嵌字幕。但 B 站音乐区/MMD/翻唱视频普遍带 CC 字幕轨，而 piliai 已有完整的
 * [SubtitleController]（getPlayerV2 → subtitle.subtitles → fetchSubtitleBody），
 * 因此歌词直接用 B 站 CC 字幕，零新依赖、零新增网络层。
 *
 * 播放链路复用 [Media3Backend] + [VideoRepository]，与竖屏流/详情页同一套
 * 取流逻辑（playurl + Rust 选流 + CDN 候选 + buvid3 cookie）。
 *
 * 数据源用收藏夹：与 BiliPai 一致（它的「播放列表/专辑/歌手」三个 section
 * 全部由收藏夹映射而来）。收藏夹是用户主动整理的，比推荐流更适合当歌单。
 */
class ListenVideoViewModel(
    private val repo: LibraryRepository = LibraryRepository(),
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val error: String? = null,
        val notLoggedIn: Boolean = false,
        val folders: List<FavFolder> = emptyList(),
        val tracks: List<ListenTrack> = emptyList(),
        val selectedFolderTitle: String = "",
        val playingIndex: Int = -1,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val playError: String? = null,
    )

    /** 播放列表里的一首 */
    data class ListenTrack(
        val bvid: String,
        val title: String,
        val cover: String,
        val ownerName: String,
        val durationSec: Long,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 歌词（B 站 CC 字幕复用）：轨道列表、当前 cue、当前行索引 */
    val subtitle = SubtitleController(viewModelScope)

    private var backend: PlaybackBackend? = null
    private var loadJob: Job? = null
    private var clockJob: Job? = null

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
            _state.value = _state.value.copy(isPlaying = false)
        }
        override fun onError(message: String) {
            _state.value = _state.value.copy(playError = message, isBuffering = false)
        }
        override fun onVideoSizeChanged(width: Int, height: Int) = Unit
        override fun onDecoderOverloaded() = Unit
    }

    init { }

    /**
     * 加载收藏夹列表。
     *
     * @param mid 必须由 UI 传入（与 FavoritesScreen 同一来源）。收藏夹接口
     *   x/v3/fav/folder/created/list-all 需要 up_mid，而 AccountSession 只存
     *  cookie 不存 mid，VM 自己猜不可靠。
     */
    fun loadFolders(mid: Long) {
        if (mid <= 0L) {
            _state.value = _state.value.copy(notLoggedIn = true, loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, notLoggedIn = false)
            repo.favFolders(mid)
                .onSuccess { resp ->
                    _state.value = _state.value.copy(
                        loading = false,
                        folders = resp.data.orEmpty(),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: "收藏夹加载失败",
                    )
                }
        }
    }

    /** 打开某个收藏夹作为播放列表 */
    fun openFolder(folder: FavFolder) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repo.favResources(folder.id, pn = 1, ps = 50)
                .onSuccess { resp ->
                    val tracks = resp.data?.medias.orEmpty().mapNotNull { it.toListenTrack() }
                    _state.value = _state.value.copy(
                        loading = false,
                        tracks = tracks,
                        selectedFolderTitle = folder.title,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        loading = false,
                        error = e.message ?: "收藏夹内容加载失败",
                    )
                }
        }
    }

    /** 播放列表中第 index 首 */
    fun playAt(index: Int) {
        val tracks = _state.value.tracks
        if (index < 0 || index >= tracks.size) return
        val track = tracks[index]
        _state.value = _state.value.copy(playingIndex = index, playError = null, isBuffering = true)
        loadJob?.cancel()
        // 切歌重置歌词
        subtitle.reset()
        loadJob = viewModelScope.launch {
            try {
                runCatching { AccountSession.ensureBuvid() }
                // 收藏夹列表不返回 cid，先用详情反查（单P 视频取首页 cid）
                val detail = VideoRepository(BiliApiClient()).getVideoDetail(track.bvid).getOrNull()?.data
                val cid = detail?.cid ?: run {
                    _state.value = _state.value.copy(
                        playError = "无法获取视频 cid（详情接口失败）", isBuffering = false,
                    )
                    return@launch
                }
                val qn = 64
                val result = VideoRepository(BiliApiClient())
                    .getPlayUrlWithCandidates(track.bvid, cid, qn = qn)
                    .getOrElse {
                        _state.value = _state.value.copy(
                            playError = "取流失败：${it.message}", isBuffering = false,
                        )
                        return@launch
                    }
                val videoUrl = result.second.first?.primary
                val audioUrl = result.second.second?.primary
                if (videoUrl.isNullOrEmpty()) {
                    _state.value = _state.value.copy(
                        playError = "未解析到播放地址", isBuffering = false,
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
                // 歌词：B 站 CC 字幕轨（无轨时静默，不打断播放）
                subtitle.load(track.bvid, cid)
                startClock()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    playError = e.message ?: "播放失败", isBuffering = false,
                )
            }
        }
    }

    /** 下一首 */
    fun next() {
        val s = _state.value
        if (s.tracks.isEmpty()) return
        val nextIndex = if (s.playingIndex + 1 < s.tracks.size) s.playingIndex + 1 else 0
        playAt(nextIndex)
    }

    /** 上一首 */
    fun previous() {
        val s = _state.value
        if (s.tracks.isEmpty()) return
        val prevIndex = if (s.playingIndex - 1 >= 0) s.playingIndex - 1 else s.tracks.size - 1
        playAt(prevIndex)
    }

    fun togglePlayPause() {
        val b = backend ?: return
        if (b.isPlaying()) b.pause() else b.play()
        PlaybackRegistry.refreshPlaybackState()
    }

    fun seekTo(positionMs: Long) {
        backend?.seekTo(positionMs)
        subtitle.updateAt(positionMs)
    }

    private fun startClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (true) {
                val b = backend ?: break
                val pos = b.positionMs()
                val dur = b.durationMs().takeIf { it > 0L } ?: _state.value.durationMs
                _state.value = _state.value.copy(positionMs = pos, durationMs = dur)
                // 歌词跟随播放进度
                subtitle.updateAt(pos)
                delay(250)
            }
        }
    }

    private fun ensureBackend(): PlaybackBackend {
        val existing = backend
        if (existing != null) return existing
        val created = Media3Backend(appContext(), listener)
        backend = created
        created.nativePlayer()?.let {
            val title = _state.value.tracks.getOrNull(_state.value.playingIndex)?.title.orEmpty()
            PlaybackRegistry.register(appContext(), it, title)
        }
        return created
    }

    private fun appContext(): Context = com.example.piliai.AppContext.get()

    override fun onCleared() {
        clockJob?.cancel()
        loadJob?.cancel()
        PlaybackRegistry.unregister()
        backend?.release()
        backend = null
    }
}

/**
 * 收藏夹条目 → 播放列表项。
 *
 * 注意：收藏夹列表接口（x/v3/fav/resource/list）的 FavMedia **不返回 cid**，
 * 因此这里只带 bvid；播放前由 [ListenVideoViewModel.playAt] 调
 * getVideoDetail 反查 cid。这是 B 站接口的客观限制——多P 视频的 cid 与
 * 分P 绑定，列表层只给 bvid。
 */
private fun FavMedia.toListenTrack(): ListenVideoViewModel.ListenTrack? {
    if (bvid.isBlank()) return null
    return ListenVideoViewModel.ListenTrack(
        bvid = bvid,
        title = title,
        cover = cover,
        ownerName = upper?.name.orEmpty(),
        durationSec = duration.toLong(),
    )
}
