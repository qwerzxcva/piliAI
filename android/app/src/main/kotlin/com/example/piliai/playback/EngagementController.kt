package com.example.piliai.playback

import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.data.repository.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 审核312：互动控制器（从 VideoPlayerViewModel 提取）。
 *
 * 管：点赞 / 投币 / 收藏（默认夹 + 指定夹）/ 稍后再看 / 历史上报 / 收藏夹列表。
 * 原逻辑占 ViewModel 约 200 行，且每个操作都要 `viewModelScope.launch` +
 * 登录校验 + aid 兜底 + 失败提示，样板代码密集。
 *
 * ## 与原实现的差异
 * 原来直接写 `_state.value`，这里改为持有自己的 StateFlow + 通过
 * [onError] / [onToast] 回调把结果交回 ViewModel 展示。行为完全一致：
 * - 未登录：报「请先登录后再 XX」
 * - aid 取不到：报「无法获取视频 aid」
 * - 失败：报具体原因（接口非 0 / 网络异常）
 * - 收藏夹选择器固定 deal=1（审核295 修的「加不进别的收藏夹」）
 * - 默认收藏夹竞态兜底（审核294 修的「进播放器立刻点收藏报错」）
 */
class EngagementController(
    private val scope: CoroutineScope,
    /** 取当前 aid；0 表示未知（内部会尝试兜底拉取） */
    private val aidProvider: suspend () -> Long,
    /** 报错（ViewModel 侧会做 BiliErrorTranslator 翻译） */
    private val onError: (String) -> Unit,
    /** 轻提示（2 秒自动消失，不遮挡播放） */
    private val onToast: (String) -> Unit,
) {

    private val repo by lazy { VideoRepository(BiliApiClient()) }

    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited.asStateFlow()


    private val _likeCount = MutableStateFlow(0L)
    val likeCount: StateFlow<Long> = _likeCount.asStateFlow()

    private val _coinCountTotal = MutableStateFlow(0L)
    val coinCountTotal: StateFlow<Long> = _coinCountTotal.asStateFlow()

    private val _favCount = MutableStateFlow(0L)
    val favCount: StateFlow<Long> = _favCount.asStateFlow()

    /** 审核350：稍后再看是否已添加（防止重复点击重复入库） */
    private val _isInWatchLater = MutableStateFlow(false)
    val isInWatchLater: StateFlow<Boolean> = _isInWatchLater.asStateFlow()

    /** 默认收藏夹 id（0 = 未取到） */
    private var defaultFavFolderId: Long = 0L

    /** 收藏夹列表（id, 标题, 内容数） */
    private val _favFolders = MutableStateFlow<List<Triple<Long, String, Int>>>(emptyList())
    val favFolders: StateFlow<List<Triple<Long, String, Int>>> = _favFolders.asStateFlow()

    /**
     * 加载互动状态与计数。
     *
     * @param aid 视频 aid（0 表示跳过，番剧链路未取到时用）
     * @param bvid 视频 bvid，用于拿真实计数
     */
    fun load(aid: Long, bvid: String) {
        scope.launch {
            if (!AccountSession.isLogin) return@launch
            // 1) 交互状态（已赞/已投/已藏）
            BiliApiClient().getVideoRelation(aid).onSuccess { (liked, coined, fav) ->
                _isLiked.value = liked
                _isFavorited.value = fav
            }
            // 2) 默认收藏夹（第一个）
            runCatching {
                defaultFavFolderId = BiliApiClient().getMyFavFolders().getOrNull()
                    ?.firstOrNull()?.id ?: 0L
            }
            // 3) 真实计数（点赞/投币/收藏数）
            runCatching {
                repo.getVideoDetail(bvid).getOrNull()?.data?.stat?.let { s ->
                    _likeCount.value = s.like
                    _coinCountTotal.value = s.coin
                    _favCount.value = s.favorite
                }
            }
        }
    }

    /** 收藏夹列表供选择（收藏 Sheet 用） */
    fun loadFavFolders() {
        scope.launch {
            runCatching {
                BiliApiClient().getMyFavFolders().getOrNull().orEmpty()
            }.onSuccess { folders ->
                _favFolders.value = folders.mapNotNull { f ->
                    f.id.takeIf { it > 0 }?.let { Triple(it, f.title, f.media_count) }
                }
            }
        }
    }

    /** 点赞/取消点赞 */
    fun toggleLike() {
        scope.launch {
            if (!requireLogin("点赞")) return@launch
            val aid = aidProvider()
            if (aid == 0L) {
                onError("无法获取视频 aid")
                return@launch
            }
            val liked = _isLiked.value
            repo.likeVideo(aid, if (liked) 2 else 1)
                .onSuccess { ok ->
                    if (ok) {
                        _isLiked.value = !liked
                        _likeCount.value = _likeCount.value + if (liked) -1 else 1
                    } else {
                        onError("点赞失败（接口返回非 0）")
                    }
                }
                .onFailure { onError("点赞失败: ${it.message}") }
        }
    }

    /** 投币（1/2 枚） */
    fun coinOnce(multiply: Int = 1) {
        scope.launch {
            if (!requireLogin("投币")) return@launch
            val aid = aidProvider()
            if (aid == 0L) {
                onError("无法获取视频 aid")
                return@launch
            }
            repo.coinVideo(aid, multiply)
                .onSuccess { ok ->
                    if (ok) {
                        _coinCountTotal.value = _coinCountTotal.value + multiply
                    } else {
                        onError("投币失败（余额不足或已投满）")
                    }
                }
                .onFailure { onError("投币失败: ${it.message}") }
        }
    }

    /**
     * 收藏到指定收藏夹。
     *
     * 审核295（真 bug）：固定 deal=1。原来用全局 isFavorited 判断 deal，
     * 视频只要在任意收藏夹里 isFavorited 就是 true，用户选另一个收藏夹
     * 添加时会走 deal=2（取消收藏），结果什么都加不进去。
     */
    fun favoriteTo(mediaId: Long) {
        scope.launch {
            if (!requireLogin("收藏")) return@launch
            val aid = aidProvider()
            if (aid == 0L) {
                onError("无法获取视频 aid")
                return@launch
            }
            repo.favoriteVideo(aid, mediaId, 1)
                .onSuccess { ok ->
                    if (ok) {
                        _isFavorited.value = true
                        onToast("已收藏到所选收藏夹")
                    } else {
                        onError("收藏失败（可能已在该收藏夹中）")
                    }
                }
                .onFailure { onError("收藏失败: ${it.message}") }
        }
    }

    /** 收藏/取消收藏（默认收藏夹） */
    fun toggleFavorite() {
        scope.launch {
            if (!requireLogin("收藏")) return@launch
            val aid = aidProvider()
            if (aid == 0L) {
                onError("无法获取视频 aid")
                return@launch
            }
            val fav = _isFavorited.value
            var mediaId = defaultFavFolderId
            // 审核294：mediaId 还是 0 时补一次主动拉取。load() 里取收藏夹是
            // 异步的，用户进播放器立刻点收藏会撞上这个竞态。
            if (mediaId == 0L && !fav) {
                mediaId = BiliApiClient().getMyFavFolders().getOrNull()
                    ?.firstOrNull()?.id ?: 0L
                defaultFavFolderId = mediaId
            }
            if (mediaId == 0L && !fav) {
                onError("未获取到默认收藏夹，请到收藏页创建")
                return@launch
            }
            repo.favoriteVideo(aid, mediaId, if (fav) 2 else 1)
                .onSuccess { ok ->
                    if (ok) {
                        _isFavorited.value = !fav
                        _favCount.value = _favCount.value + if (fav) -1 else 1
                    } else {
                        onError("收藏失败（接口返回非 0）")
                    }
                }
                .onFailure { onError("收藏失败: ${it.message}") }
        }
    }

    /** 加入稍后再看 */
    fun addToWatchLater() {
        scope.launch {
            if (!AccountSession.isLogin) {
                onError("请先登录")
                return@launch
            }
            // 审核350：已添加就直接提示，不再重复调用接口
            if (_isInWatchLater.value) {
                onToast("已经在稍后再看里了")
                return@launch
            }
            val aid = aidProvider()
            if (aid == 0L) {
                onError("无法获取视频 aid")
                return@launch
            }
            BiliApiClient().addToView(aid)
                .onSuccess { ok ->
                    if (ok) {
                        _isInWatchLater.value = true
                        onToast("已加入稍后再看")
                    } else onError("添加失败")
                }
                .onFailure { onError("添加失败: ${it.message}") }
        }
    }

    /** 上报历史进度（播放中每 15 秒调一次；失败静默，不打扰用户） */
    fun reportProgress(aid: Long, cid: Long, positionSec: Long) {
        scope.launch {
            if (!AccountSession.isLogin || aid == 0L || cid == 0L) return@launch
            repo.reportHistory(aid, cid, positionSec)
        }
    }

    /** 未登录统一提示；返回 false 表示调用方应中止 */
    private fun requireLogin(action: String): Boolean {
        if (AccountSession.isLogin) return true
        onError("请先登录后再$action")
        return false
    }
}
