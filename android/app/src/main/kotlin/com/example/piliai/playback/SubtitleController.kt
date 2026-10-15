package com.example.piliai.playback

import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 审核311：字幕控制器（从 VideoPlayerViewModel 提取）。
 *
 * 管：CC 字幕轨道列表、选中项、字幕正文 cues、看点（viewPoints）、
 * 以及按播放进度更新当前字幕文本。
 *
 * 提取收益：
 * - VideoPlayerViewModel 少约 80 行，播放状态机更聚焦
 * - 幕幕相关的 4 个 StateFlow 集中一处，UI 只需订阅本类
 * - 网络请求的生命周期（subtitleListJob / subtitleBodyJob）由 scope 托管，
 *   不再占用播放器的 Job 字段
 *
 * 行为与原实现完全一致（未改逻辑，只搬位置）：
 * - 番剧 bvid 形如 "ep123"，getPlayerV2 需要真实 bvid → 反查（审核244）
 * - 取不到字幕/正文时静默，不打断播放
 */
class SubtitleController(
    private val scope: CoroutineScope,
) {

    /** 轨道三元组：id / 语言描述 / 字幕文件 url */
    private val _tracks = MutableStateFlow<List<Triple<Long, String, String>>>(emptyList())
    val tracks: StateFlow<List<Triple<Long, String, String>>> = _tracks.asStateFlow()

    private val _viewPoints = MutableStateFlow<List<com.example.piliai.data.model.ViewPoint>>(emptyList())
    val viewPoints: StateFlow<List<com.example.piliai.data.model.ViewPoint>> = _viewPoints.asStateFlow()

    private val _current = MutableStateFlow("")
    val current: StateFlow<String> = _current.asStateFlow()

    private val _selectedId = MutableStateFlow(-1L)
    val selectedId: StateFlow<Long> = _selectedId.asStateFlow()

    private var cues: List<com.example.piliai.data.model.SubtitleCue> = emptyList()

    private var listJob: Job? = null
    private var bodyJob: Job? = null

    /** 当前加载代次（切 P / 切视频时由播放器 +1，用于丢弃过期响应） */
    @Volatile
    var generation: Long = 0L

    fun reset() {
        listJob?.cancel()
        bodyJob?.cancel()
        cues = emptyList()
        _tracks.value = emptyList()
        _selectedId.value = -1L
        _current.value = ""
        _viewPoints.value = emptyList()
    }

    /**
     * 拉取字幕轨道与看点，并默认选中第一条未上锁的。
     *
     * @param bvid 视频 bvid；番剧为 "ep123" 形式，内部会反查真实 bvid
     */
    fun load(bvid: String, cid: Long) {
        listJob?.cancel()
        // 审核244（P1-8）：去掉 bvid.startsWith("ep") 短路。
        // 该短路本意是防 getPlayerV2 收到 "ep123" 报错，但番剧通常有 CC 字幕，
        // 结果是番剧播放永不加载字幕。改为反查该集真实 bvid 再请求。
        if (bvid.isEmpty() || cid <= 0L) return
        val myGen = generation
        listJob = scope.launch {
            try {
                // 番剧的 bvid 形如 "ep123"，getPlayerV2 需要真实 bvid，
                // 传 "ep..." 会 400。用 getPgcSeason(epId) 反查该集 bvid
                // （复用已有接口，不新增网络层）。
                val effectiveBvid = if (bvid.startsWith("ep")) {
                    val epIdNum = bvid.removePrefix("ep").toLongOrNull() ?: 0L
                    runCatching {
                        BiliApiClient()
                            .getPgcSeason(epId = epIdNum)
                            .getOrNull()?.result?.episodes
                            ?.firstOrNull { it.id == epIdNum }?.bvid
                    }.getOrNull().orEmpty()
                } else {
                    bvid
                }
                if (effectiveBvid.isEmpty()) return@launch
                val resp = BiliApiClient().getPlayerV2(effectiveBvid, cid).getOrNull()?.data
                if (myGen != generation) return@launch
                val subs = resp?.subtitle?.subtitles.orEmpty()
                _tracks.value = subs.map { Triple(it.id, it.langDoc, it.subtitleUrl) }
                _viewPoints.value = resp?.viewPoints.orEmpty()
                val pick = subs.firstOrNull { !it.isLock } ?: return@launch
                select(pick.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Optional subtitles must not interrupt video playback.
            }
        }
    }

    /** 选中指定轨道（id < 0 表示关闭字幕） */
    fun select(id: Long) {
        val url = if (id < 0L) null else
            _tracks.value.firstOrNull { it.first == id }?.third ?: return
        bodyJob?.cancel()
        _selectedId.value = id
        cues = emptyList()
        _current.value = ""
        if (url == null) return
        val myGen = generation
        bodyJob = scope.launch {
            try {
                val body = BiliApiClient().fetchSubtitleBody(url).getOrNull()
                if (myGen != generation || _selectedId.value != id || body == null) return@launch
                cues = body.body.sortedBy { it.from }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep failed subtitle requests out of the active timeline.
            }
        }
    }

    /** 播放器每帧/每秒调：更新当前字幕文本 */
    fun updateAt(currentTimeMs: Long) {
        val sec = currentTimeMs / 1000f
        val cue = cues.firstOrNull { sec >= it.from && sec <= it.to }
        val text = cue?.content.orEmpty().replace("<br/>", "\n")
        if (text != _current.value) _current.value = text
    }
}
