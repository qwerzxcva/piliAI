package com.example.piliai.todaywatch

import com.example.piliai.data.model.HistoryItem
import com.example.piliai.data.model.TopRcmdItem
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.database.PiliNaraDatabase
import com.example.piliai.data.remote.AccountSession
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「今日推荐」编排层：拉历史 → 读负反馈 → 拉候选 → 跑算法 → 出 Plan。
 *
 * 降级策略：
 * - 未登录 / 历史失败 → 空历史（算法退化为探索模式）
 * - 候选失败 → 空队列
 * - 负反馈读取失败 → 空信号
 */
class TodayWatchRepository(
    private val db: PiliNaraDatabase,
    private val api: BiliApiClient = BiliApiClient(),
) {
    private val gson = Gson()

    /** 生成今日推荐计划。mode/strategy 由调用方传入（MVP 用默认） */
    suspend fun buildPlan(
        mode: TodayWatchMode = TodayWatchMode.RELAX,
        strategy: TodayWatchStrategy = TodayWatchStrategy.BALANCED,
        historyLimit: Int = 80,
        queueLimit: Int = 20,
    ): Result<TodayWatchPlan> = withContext(Dispatchers.Default) {
        runCatching {
            val history = loadHistory(historyLimit)
            val candidates = loadCandidates()
            val penalty = loadPenaltySignals()
            buildTodayWatchPlan(
                historyVideos = history,
                candidateVideos = candidates,
                mode = mode,
                // 审核轮213：从近期历史聚合创作者信号（出现次数+观看亲和），
                // 使 buildTodayWatchPlan 的 max-merge 通路真正生效（原 MVP 恒空列表）
                creatorSignals = history
                    .filter { it.authorMid > 0 }
                    .groupBy { it.authorMid }
                    .map { (mid, items) ->
                        CreatorSignal(
                            mid = mid,
                            name = items.firstOrNull()?.authorName.orEmpty(),
                            score = items.size.toDouble() / 10.0,  // 出现频次归一（10 次封顶）
                            watchCount = items.size
                        )
                    },
                penaltySignals = penalty,
                strategy = strategy,
                queueLimit = queueLimit,
            )
        }
    }

    /** 观看历史（需登录；未登录/失败返回空表，算法降级为探索模式） */
    private suspend fun loadHistory(limit: Int): List<WatchHistoryItem> {
        if (!AccountSession.isLogin) return emptyList()
        val out = mutableListOf<WatchHistoryItem>()
        var max = 0L
        var viewAt = 0L
        var pages = 0
        while (out.size < limit && pages < 6) {
            val resp = api.getHistoryCursor(max, viewAt, 20).getOrNull() ?: break
            val items = resp.data?.list.orEmpty()
            if (items.isEmpty()) break
            items.forEach { out.add(it.toWatchHistoryItem()) }
            val cursor = resp.data?.cursor
            max = cursor?.max ?: 0L
            viewAt = cursor?.view_at ?: 0L
            if (max <= 0) break
            pages++
        }
        return out.take(limit)
    }

    /** 候选视频：首页推荐流（TopRcmdItem 缺 pubdate，freshness 恒为 0.5，见算法注释） */
    private suspend fun loadCandidates(): List<RcmdCandidate> {
        val resp = api.getTopRcmd().getOrNull() ?: return emptyList()
        return resp.data?.item.orEmpty().map { it.toRcmdCandidate() }
    }

    /** 负反馈快照（Room 单行 JSON） */
    private suspend fun loadPenaltySignals(): PenaltySignals = runCatching {
        val e = db.todayWatchFeedbackDao().getByKey("today_watch_feedback_v1") ?: return@runCatching PenaltySignals()
        PenaltySignals(
            dislikedBvids = e.dislikedBvidsJson.toStringSet(),
            dislikedCreatorMids = e.dislikedCreatorMidsJson.toLongSet(),
            dislikedKeywords = e.dislikedKeywordsJson.toStringSet(),
        )
    }.getOrDefault(PenaltySignals())

    /** 点踩一个视频（bvid + 可选作者） */
    suspend fun dislike(bvid: String, ownerMid: Long = 0L) = withContext(Dispatchers.IO) {
        runCatching {
            val dao = db.todayWatchFeedbackDao()
            val e = dao.getByKey("today_watch_feedback_v1")
            val bvids = (e?.dislikedBvidsJson.toStringSet() + bvid)
            val midSet: Set<Long> = e?.dislikedCreatorMidsJson.toLongSet()
            val mids = if (ownerMid > 0) midSet + ownerMid else midSet
            dao.upsert(com.example.piliai.database.TodayWatchFeedbackEntity(
                key = "today_watch_feedback_v1",
                dislikedBvidsJson = gson.toJson(bvids.take(200)),
                dislikedCreatorMidsJson = gson.toJson((mids ?: emptySet()).take(120)),
                dislikedKeywordsJson = e?.dislikedKeywordsJson,
                updatedAt = System.currentTimeMillis(),
            ))
        }
    }

    /** 审核349：读出「不感兴趣」的 bvid 集合，供首页过滤（与推荐流共用一份） */
    suspend fun dislikedBvids(): Set<String> = withContext(Dispatchers.IO) {
        runCatching {
            db.todayWatchFeedbackDao()
                .getByKey("today_watch_feedback_v1")
                ?.dislikedBvidsJson
                .toStringSet()
        }.getOrDefault(emptySet())
    }

    // ========== 模型转换 ==========

    private fun HistoryItem.toWatchHistoryItem() = WatchHistoryItem(
        bvid = bvid,
        title = title,
        tagName = "",
        authorMid = author_mid,
        authorName = author_name,
        kid = 0L,
        viewAt = view_at,
        progress = progress.toLong(),
        duration = duration.toLong(),
    )

    private fun TopRcmdItem.toRcmdCandidate() = RcmdCandidate(
        bvid = bvid,
        title = title,
        cover = pic.ifBlank { cover },
        duration = duration,
        pubdate = 0L,
        ownerMid = owner?.mid ?: 0L,
        ownerName = owner?.name ?: "",
        viewCount = stat?.view ?: 0L,
        likeCount = stat?.like ?: 0L,
        danmakuCount = stat?.danmaku ?: 0L,
    )

    private fun String?.toStringSet(): Set<String> = runCatching {
        if (isNullOrBlank()) emptySet() else gson.fromJson(this, Array<String>::class.java).toSet()
    }.getOrDefault(emptySet())

    private fun String?.toLongSet(): Set<Long> = runCatching {
        if (isNullOrBlank()) emptySet() else gson.fromJson(this, Array<Long>::class.java).toSet()
    }.getOrDefault(emptySet())
}
