package com.example.piliai.data.repository

import com.example.piliai.PlayUrlNativeLib
import com.example.piliai.data.model.*
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.example.piliai.utils.toHttpsUrl

/**
 * 视频仓库 —— 视频详情、播放地址（wbi 签名 + Rust DASH 选流）、弹幕、写操作（点赞/投币/收藏/历史上报）
 */
class VideoRepository(private val apiClient: BiliApiClient = BiliApiClient()) {

    private val _videoInfo = MutableStateFlow<VideoItem?>(null)
    val videoInfo: StateFlow<VideoItem?> = _videoInfo.asStateFlow()

    private val _playUrl = MutableStateFlow<String?>(null)
    val playUrl: StateFlow<String?> = _playUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 获取视频信息（缓存到 videoInfo 流） */
    suspend fun getVideoInfo(bvid: String): Result<VideoItem> = withContext(Dispatchers.IO) {
        runCatching {
            _isLoading.value = true
            _error.value = null

            val resp = apiClient.getVideoInfo(bvid).getOrNull()
            val data = resp?.takeIf { it.code == 0 }?.data
            if (data != null) {
                val item = data.toVideoItem()
                _videoInfo.value = item
                item
            } else {
                _error.value = "获取视频信息失败"
                throw IllegalStateException("获取视频信息失败")
            }
        }.also { _isLoading.value = false }
    }

    /** 获取视频详情原始响应（含 cid / pages），供播放器取 cid */
    suspend fun getVideoDetail(bvid: String): Result<VideoInfoResponse> =
        withContext(Dispatchers.IO) { apiClient.getVideoInfo(bvid) }

    /**
     * 播放地址：wbi 签名请求 + Rust DASH 流选择，失败回退 Kotlin 解析。
     * @return Triple(完整响应, 选中的 video baseUrl, 选中的 audio baseUrl)
     */
    suspend fun getPlayUrl(
        bvid: String,
        cid: Long,
        qn: Int = 80
    ): Result<Triple<PlayUrlResponse, String?, String?>> = withContext(Dispatchers.IO) {
        apiClient.getPlayUrl(bvid, cid, qn).mapCatching { resp ->
            val streams = resolveStreams(resp.rawJson, qn)
            val video = streams?.first?.primary?.toHttpsUrl()
            val audio = streams?.second?.primary?.toHttpsUrl()
            Triple(resp, video, audio)
        }.onSuccess { (_, video, _) ->
            _playUrl.value = video
        }.onFailure {
            _error.value = it.message ?: "获取播放地址失败"
        }
    }

    /**
     * 审核290：带 CDN 候选列表的播放地址（主 URL + backupUrl 备用节点）。
     *
     * 实测发现 B 站分配的**首个 CDN 节点可能 TLS 握手超时**，而同一流的
     * backupUrl 节点完全正常。只取 baseUrl 的实现一遇到这种节点就整个播放失败
     * ——这是「普通视频也播放失败」的根因。播放器据此在主 URL 失败时切换节点。
     */
    suspend fun getPlayUrlWithCandidates(
        bvid: String,
        cid: Long,
        qn: Int = 80
    ): Result<Pair<PlayUrlResponse, Pair<PlayUrlCandidate?, PlayUrlCandidate?>>> =
        withContext(Dispatchers.IO) {
            apiClient.getPlayUrl(bvid, cid, qn).mapCatching { resp ->
                val streams = resolveStreams(resp.rawJson, qn)
                Pair(resp, Pair(streams?.first, streams?.second))
            }
        }

    /**
     * 从 playurl 原始 JSON 解析出选中的 video/audio 流（含 CDN 候选）。
     * Rust select_streams 输出与原始响应同构（baseUrl + backupUrl）。
     */
    private suspend fun resolveStreams(
        rawJson: String?,
        qn: Int
    ): Pair<PlayUrlCandidate?, PlayUrlCandidate?>? {
        if (rawJson.isNullOrBlank()) return null
        val selected = try {
            // 审核219/221：音质/画质/编码偏好经设置键传入 Rust 选流
            val ctx = com.example.piliai.AppContext.get()
            val storage = com.example.piliai.utils.StorageManager.getInstance(ctx)
            val cellular = com.example.piliai.utils.NetworkType.isCellular(ctx)
            val audioQaRaw = if (cellular) {
                storage.audioQaCellularFlow.first().ifBlank { storage.audioQaFlow.first() }
            } else storage.audioQaFlow.first()
            val audioQn = audioQaRaw.toIntOrNull() ?: 0
            val effectiveQn = if (cellular) {
                storage.videoQaCellularFlow.first().toIntOrNull()?.takeIf { it > 0 } ?: qn
            } else qn
            val preferCodec = if (cellular) {
                storage.preferCodecCellularFlow.first().ifBlank { storage.preferCodecFlow.first() }
            } else storage.preferCodecFlow.first()
            PlayUrlNativeLib.select(rawJson, effectiveQn, audioQn, preferCodec)
        } catch (_: Exception) {
            null
        }
        if (selected != null) {
            val arr = JSONObject(selected)
            val video = PlayUrlCandidate.fromJson(arr.optJSONObject("video"))
            val audio = PlayUrlCandidate.fromJson(arr.optJSONObject("audio"))
            if (video != null || audio != null) return Pair(video, audio)
        }
        // Rust 不可用时的 Kotlin 回退：直接读原始响应的 dash
        return try {
            val root = JSONObject(rawJson)
            val dash = root.optJSONObject("data")?.optJSONObject("dash")
                ?: root.optJSONObject("result")?.optJSONObject("dash")
            val video = dash?.optJSONArray("video")?.optJSONObject(0)?.let {
                PlayUrlCandidate.fromJson(it)
            }
            val audio = dash?.optJSONArray("audio")?.let { aud ->
                // 取带宽最高的一条
                var best: org.json.JSONObject? = null
                var bestBw = -1L
                for (i in 0 until aud.length()) {
                    val s = aud.optJSONObject(i) ?: continue
                    val bw = s.optLong("bandwidth", 0L)
                    if (bw > bestBw) {
                        bestBw = bw
                        best = s
                    }
                }
                best?.let { PlayUrlCandidate.fromJson(it) }
            }
            Pair(video, audio)
        } catch (_: Exception) {
            null
        }
    }

    /** 番剧播放地址（批次D）：wbi 签名 pgc playurl + Rust 选流（result.dash 同构，Rust 已兼容） */
    suspend fun getPgcPlayUrl(
        epId: Long,
        cid: Long,
        qn: Int = 80
    ): Result<Triple<PgcPlayUrlResponse, String?, String?>> = withContext(Dispatchers.IO) {
        apiClient.getPgcPlayUrl(epId, cid, qn).mapCatching { resp ->
            // Rust select_streams 已兼容 result.dash 路径；把响应序列化为 JSON 喂给它
            val bodyJson = kotlinx.serialization.json.Json.encodeToString(
                PgcPlayUrlResponse.serializer(), resp
            )
            val selected = PlayUrlNativeLib.select(bodyJson, qn)
            if (selected != null) {
                val arr = JSONObject(selected)
                val video = arr.optJSONObject("video")?.optString("baseUrl")?.toHttpsUrl()
                val audio = arr.optJSONObject("audio")?.optString("baseUrl")?.toHttpsUrl()
                Triple(resp, video, audio)
            } else {
                val video = resp.result?.dash?.video?.maxByOrNull { it.bandwidth }?.baseUrl?.toHttpsUrl()
                val audio = resp.result?.dash?.audio?.maxByOrNull { it.bandwidth }?.baseUrl?.toHttpsUrl()
                Triple(resp, video, audio)
            }
        }.onFailure {
            _error.value = it.message ?: "获取番剧播放地址失败"
        }
    }

    /** 获取弹幕 */
    suspend fun getDanmaku(cid: Long, oid: Long = 0L): Result<List<ParsedDanmaku>> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = apiClient.getDanmaku(cid, oid).getOrNull()
            if (resp != null && resp.code == 0) {
                resp.data.orEmpty().map { it.toParsed() }
            } else {
                emptyList()
            }
        }
    }

    /** 点赞（like=1 点赞 / 2 取消）—— 需登录（SESSDATA + csrf） */
    suspend fun likeVideo(aid: Long, like: Int = 1): Result<Boolean> = withContext(Dispatchers.IO) {
        apiClient.like(aid, like).map { isCodeZero(it) }
    }

    /** 投币（num=1/2）—— 需登录 */
    suspend fun coinVideo(aid: Long, num: Int = 1): Result<Boolean> = withContext(Dispatchers.IO) {
        apiClient.coin(aid, num).map { isCodeZero(it) }
    }

    /** 收藏到指定收藏夹（deal=1 收藏 / 2 取消）—— 需登录 */
    suspend fun favoriteVideo(aid: Long, mediaId: Long, deal: Int = 1): Result<Boolean> = withContext(Dispatchers.IO) {
        apiClient.favorite(aid, mediaId, deal).map { isCodeZero(it) }
    }

    /** 上报播放进度到历史 —— 需登录 */
    suspend fun reportHistory(aid: Long, cid: Long, progress: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        apiClient.reportHistory(aid, cid, progress).map { isCodeZero(it) }
    }

    private fun isCodeZero(m: Map<String, Any>): Boolean = (m["code"] as? Number)?.toInt() == 0

    /** 清除数据 */
    fun clear() {
        _videoInfo.value = null
        _playUrl.value = null
        _error.value = null
    }

}
