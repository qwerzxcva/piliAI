package com.example.piliai.playback

import com.example.piliai.danmaku.DanmakuNativeLib
import com.example.piliai.ui.settings.DanmakuBlockViewModel
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 审核311：弹幕控制器（从 VideoPlayerViewModel 提取）。
 *
 * VideoPlayerViewModel 原来 2117 行，弹幕只是它的关注点之一，却占了
 * 队列维护、屏蔽过滤、热力曲线、时间窗查询四块逻辑。提取后：
 * - 播放器只管播放，弹幕的增删查改归这里
 * - 弹幕逻辑不再依赖 PlayerState，可独立单测（不再需要 stub 播放器）
 * - 屏蔽规则/热力图这两个 native 下沉点集中在一处，便于排查
 *
 * 线程安全：队列用 CopyOnWriteArrayList（渲染线程与网络线程并发读写，
 * 与原实现一致，见审核13）。
 */
class DanmakuController {

    /** 弹幕事件（从 VideoPlayerViewModel.DanmakuEvent 原样搬来，避免牵连播放状态） */
    data class Event(
        val id: String,
        val timestamp: Long,
        val content: String,
        val color: Int,
        val fontSize: Int,
        val uid: Long = 0L,
        val mode: Int = 1,
    )

    private val queue = java.util.concurrent.CopyOnWriteArrayList<Event>()

    private val _heatCurve = MutableStateFlow<List<Float>>(emptyList())
    val heatCurve: StateFlow<List<Float>> = _heatCurve.asStateFlow()

    private val gson = Gson()

    /** 当前弹幕总数（UI 展示用） */
    val totalCount: Int get() = queue.size

    /**
     * 追加或替换弹幕。
     *
     * 屏蔽规则批量过滤优先走 Rust（万条×多规则 CPU 密集下沉），失败回退
     * Kotlin 逐条（原 addDanmakuEvents 逻辑，未改行为）。
     *
     * @param replace true 清空原队列（DanDan 补充源覆盖主弹幕时用）
     */
    fun addEvents(events: List<Event>, replace: Boolean = true) {
        val filtered = runCatching {
            val lib = DanmakuNativeLib()
            val entries = events.map { mapOf("content" to it.content, "uid" to it.uid) }
            val rules = mapOf(
                "keywords" to DanmakuBlockViewModel.cachedKeywords,
                "regexes" to DanmakuBlockViewModel.cachedRegexStrings,
                "uids" to DanmakuBlockViewModel.cachedUids.toList()
            )
            val out = lib.filterBlock(gson.toJson(entries), gson.toJson(rules))
                ?: return@runCatching null
            val obj = org.json.JSONObject(out)
            val kept = obj.getJSONArray("kept_indices")
            (0 until kept.length()).map { i -> events[kept.getInt(i)] }
        }.getOrNull()
            ?: events.filterNot { DanmakuBlockViewModel.shouldBlock(it.content, it.uid) }

        if (replace) queue.clear()
        queue.addAll(filtered)
        computeHeatCurve(queue.toList())
    }

    /** 清空（切 P / 切视频时） */
    fun clear() {
        queue.clear()
        _heatCurve.value = emptyList()
    }

    /** 取 [currentTimeMs] 前 3 秒内、尚未分发的弹幕 */
    fun eventsAt(currentTimeMs: Long): List<Event> =
        queue.filter { it.timestamp <= currentTimeMs && it.timestamp > currentTimeMs - 3000L }

    /** 本地插入一条（发弹幕后立即反馈） */
    fun addLocal(event: Event) {
        queue.add(event)
        queue.sortBy { it.timestamp }
    }

    /**
     * 批次L42：调用 Rust 计算高能进度条曲线。
     *
     * 弹幕加载早于播放器就绪时 duration 为 0 → 用弹幕最大时间兜底
     * （Rust 侧已处理，见审核36）。
     */
    private fun computeHeatCurve(events: List<Event>) {
        if (events.isEmpty()) {
            _heatCurve.value = emptyList()
            return
        }
        runCatching {
            val lib = DanmakuNativeLib()
            val pts = events.map { mapOf("t" to it.timestamp.toDouble(), "w" to 1.0) }
            val dur = durationMs.toDouble()
            val out = lib.heatMap(gson.toJson(pts), dur, 120) ?: return@runCatching
            val arr = org.json.JSONObject(out).getJSONArray("buckets")
            _heatCurve.value = (0 until arr.length()).map { arr.getDouble(it).toFloat() }
        }
    }

    /**
     * 当前视频时长（热力图横轴）。由播放器在 duration 变化时回填。
     * 0 表示未知，Rust 侧会用弹幕最大时间兜底。
     */
    var durationMs: Long = 0L
}
