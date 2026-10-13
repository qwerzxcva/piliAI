package com.example.piliai.data.repository

import com.example.piliai.data.model.FeedRcmdItem
import com.example.piliai.data.model.FeedRcmdResponse
import com.example.piliai.data.model.PopularResponse
import com.example.piliai.data.model.TopRcmdResponse
import com.example.piliai.data.model.VideoItem
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 首页视频仓库 - 实现真实 Bilibili API 数据加载
 *
 * 审核237：首页数据源重构（用户反馈「首页视频刷新推送有问题」）。
 *
 * 原实现的三个真实缺陷：
 * 1. 只用 `/x/web-interface/popular`（全站热门，与用户无关、内容更新极慢），
 *    下拉刷新拿到的是同一批数据 → 用户感知「刷新没用」。
 * 2. `loadMore` 的页码用 `current.size / 20 + 1` 反推：一旦开启推荐过滤
 *    （RecommendFilter）列表变短，页码就会回退 → 重复加载同一页、永远刷不出新内容。
 * 3. `refresh()` 直接以 repo.refresh() 的结果覆盖列表，请求被取消/失败时
 *    旧数据也被清空。
 *
 * 现在：主数据源是 wbi 签名推荐流（fresh_idx 翻页，见 [BiliApiClient.getFeedRcmd]），
 * popular 仅在推荐流不可用（风控 -352 / 签名失败）时兜底；翻页状态由仓库自己维护，
 * 不再从列表长度反推。
 */
class HomeRepository(private val apiClient: BiliApiClient = BiliApiClient()) {

    /** 当前生效的数据源，供 UI 展示「推荐 / 热门」来源 */
    enum class FeedSource { RECOMMEND, POPULAR }

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    val videos: StateFlow<List<VideoItem>> = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _feedSource = MutableStateFlow(FeedSource.RECOMMEND)
    val feedSource: StateFlow<FeedSource> = _feedSource.asStateFlow()

    /** 是否还有更多（推荐流返回空列表即视为到底） */
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 推荐流翻页游标：fresh_idx 从 1 开始，每次 +1（不可从列表长度反推） */
    private var freshIdx = 1

    /** popular 兜底页码 */
    private var popularPage = 1

    private var loading = false

    /**
     * 刷新：重新从第一页开始。
     * @return 成功时返回新列表；失败时抛异常（调用方决定是否保留旧列表）。
     */
    suspend fun refresh(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        runCatching {
            freshIdx = 1
            popularPage = 1
            _hasMore.value = true
            loadFeed(freshIdx)
                .getOrElse { throw it }
        }
    }

    /**
     * 加载更多。内部按当前数据源选择翻页参数。
     * 并发保护：loading 为 true 时直接返回当前列表（不重复请求）。
     */
    suspend fun loadMore(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        if (loading || !_hasMore.value) return@withContext Result.success(_videos.value)
        runCatching {
            when (_feedSource.value) {
                FeedSource.RECOMMEND -> {
                    val next = freshIdx + 1
                    val appended = loadFeed(next).getOrElse { throw it }
                    if (appended.isNotEmpty()) freshIdx = next
                    appended
                }
                FeedSource.POPULAR -> {
                    val next = popularPage + 1
                    val appended = loadPopular(next).getOrElse { throw it }
                    if (appended.isNotEmpty()) popularPage = next
                    appended
                }
            }
        }
    }

    /**
     * 拉一页推荐流并**追加**到列表（去重按 bvid）。
     * 返回本次新增的条目（为空说明到底或全部重复）。
     */
    private suspend fun loadFeed(idx: Int): Result<List<VideoItem>> {
        return runCatching {
            loading = true
            _isLoading.value = true
            _error.value = null
            try {
                val resp = apiClient.getFeedRcmd(idx).getOrThrow()
                if (resp.code != 0) {
                    // 风控/签名失败：降级到 popular，并保留已有列表
                    _feedSource.value = FeedSource.POPULAR
                    _error.value = resp.message ?: "推荐流不可用，已切换到热门"
                    return@runCatching loadPopular(1).getOrThrow()
                }
                val items = resp.data?.item.orEmpty()
                    .filter { it.playable }
                    .map { it.toVideoItem() }
                val added = appendUnique(items)
                if (added.isEmpty()) {
                    // 连续空页视为到底，避免无限翻页
                    _hasMore.value = idx == 1
                }
                added
            } finally {
                _isLoading.value = false
                loading = false
            }
        }
    }

    /** popular 兜底：整页替换第一页，后续页追加 */
    private suspend fun loadPopular(page: Int): Result<List<VideoItem>> {
        return runCatching {
            loading = true
            _isLoading.value = true
            _error.value = null
            try {
                val resp: PopularResponse = apiClient.popularVideos(page, 20).getOrThrow()
                if (resp.code != 0) {
                    error(resp.message ?: "获取数据失败")
                }
                val items = resp.data?.list.orEmpty()
                if (page == 1) _videos.value = items else appendUnique(items)
                _hasMore.value = items.isNotEmpty()
                items
            } finally {
                _isLoading.value = false
                loading = false
            }
        }
    }

    /** 追加去重（bvid 为空时退化为按 aid），返回实际新增的条目 */
    private fun appendUnique(incoming: List<VideoItem>): List<VideoItem> {
        if (incoming.isEmpty()) return emptyList()
        val existing = _videos.value
        val seen = HashSet<String>(existing.size + incoming.size)
        existing.forEach {
            seen.add(if (it.bvid.isNotEmpty()) "b:${it.bvid}" else "a:${it.aid}")
        }
        val added = incoming.filter { item ->
            val key = if (item.bvid.isNotEmpty()) "b:${item.bvid}" else "a:${item.aid}"
            key !in "null" && seen.add(key)
        }
        if (added.isNotEmpty()) _videos.value = existing + added
        return added
    }

    /** 首页顶部大卡轮播（Hero）数据 */
    /**
     * 首页顶部大卡轮播（HeroCarousel）数据。
     *
     * 审核277：原接口 `/x/web-interface/index/top/rcmd` 匿名实测 **HTTP 412
     * 风控页**（连 JSON 都不是，body() 会抛异常），而 `body()` 抛错会让
     * HomeViewModel.refresh 里的 launch 静默失败 → HeroCarousel 永远不显示。
     * 改用已实测可用的推荐流第一页（feed/rcmd 匿名 code 0）取前几条当大卡。
     */
    suspend fun topRcmd(): Result<TopRcmdResponse> = runCatching {
        apiClient.getTopRcmd().getOrElse { fallbackTopRcmd() }
    }

    /** 推荐流第一页的前 6 条 → TopRcmdResponse 结构（复用 HeroCarousel 渲染） */
    private suspend fun fallbackTopRcmd(): TopRcmdResponse {
        val resp = apiClient.getFeedRcmd(1, 12).getOrThrow()
        val items = resp.data?.item.orEmpty()
            .filter { it.playable }
            .take(6)
            .map { feed ->
                com.example.piliai.data.model.TopRcmdItem(
                    id = feed.id,
                    bvid = feed.bvid,
                    cid = feed.cid,
                    goto = feed.goto,
                    uri = feed.uri,
                    title = feed.title,
                    pic = feed.pic,
                    duration = feed.duration.toLong(),
                    owner = com.example.piliai.data.model.TopRcmdOwner(
                        mid = feed.owner?.mid ?: 0L,
                        name = feed.owner?.name.orEmpty(),
                        face = feed.owner?.face.orEmpty()
                    ),
                    stat = com.example.piliai.data.model.TopRcmdStat(
                        view = feed.stat?.view ?: 0L,
                        like = feed.stat?.like ?: 0L,
                        danmaku = feed.stat?.danmaku ?: 0L
                    )
                )
            }
        return com.example.piliai.data.model.TopRcmdResponse(
            code = 0,
            data = com.example.piliai.data.model.TopRcmdData(items)
        )
    }

    /** 确保 buvid3 就绪（推荐流风控依赖） */
    suspend fun ensureBuvid(): Boolean = runCatching { AccountSession.ensureBuvid() }.getOrDefault(false)

    fun clear() {
        _videos.value = emptyList()
        _error.value = null
        freshIdx = 1
        popularPage = 1
        _hasMore.value = true
    }
}

/** FeedRcmdItem → VideoItem（与 popular 的 item 字段对齐，供同一套卡片渲染） */
private fun FeedRcmdItem.toVideoItem(): VideoItem = VideoItem(
    aid = id,
    bvid = bvid,
    cid = cid,
    title = title,
    pic = pic,
    duration = duration,
    pubdate = pubdate,
    author = owner?.name.orEmpty(),
    authorMid = owner?.mid ?: 0L,
    authorFace = owner?.face.orEmpty(),
    owner = com.example.piliai.data.model.Owner(
        mid = owner?.mid ?: 0L,
        name = owner?.name.orEmpty(),
        face = owner?.face.orEmpty()
    ),
    stat = com.example.piliai.data.model.Stat(
        view = stat?.view ?: 0L,
        danmaku = stat?.danmaku ?: 0L,
        like = stat?.like ?: 0L
    )
)
