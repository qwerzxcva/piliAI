package com.example.piliai.data.remote

import com.example.piliai.data.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * 审核276：B 站错误码 → 人话。
 *
 * API 实测报告确认用户会遇到的裸错误码（原来 message 直接透传给用户，
 * 看到 "-352" 完全不知道该怎么办）。
 */
object BiliErrorTranslator {

    /** 把响应 message / 异常 message 翻译成可行动的描述 */
    fun translate(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when {
            text == "-352" || text.contains("-352") ->
                "触发风控，请稍后重试或登录账号"
            text == "-403" || text.contains("-403") ->
                "需要登录后访问"
            text == "-101" || text.contains("-101") || text.contains("账号未登录") ->
                "请先登录 B 站账号"
            text == "-404" || text.contains("-404") ->
                "内容不存在或已失效"
            text == "-799" || text.contains("-799") || text.contains("请求过于频繁") ->
                "请求过于频繁，请稍后再试"
            text == "62012" || text.contains("62012") ->
                "评论区已关闭"
            text.contains("v_voucher") ->
                "需要通过验证，请稍后重试或登录"
            text.contains("访问权限不足") -> "需要登录后访问"
            text.contains("风控") -> "触发风控，请稍后重试或登录账号"
            // 审核289：写操作常见错误码（发评论/投币/收藏/稍再看）——
            // 原来这些接口只返回 Boolean，失败时统一显示「发送失败」，
            // 用户完全不知道是评论太频繁、硬币不足还是评论区已关闭。
            text == "-509" || text.contains("-509") ->
                "请求过于频繁，请稍后再试"
            text == "-104" || text.contains("-104") || text.contains("硬币") ->
                "硬币不足"
            text == "34002" || text.contains("34002") ->
                "不能给自己投币"
            text == "-111" || text.contains("-111") || text.contains("csrf") ->
                "登录状态已失效，请重新登录"
            else -> null
        }
    }
}

/**
 * 审核289：写操作统一结果校验。
 *
 * B 站写操作（发评论/点赞/投币/收藏/稍再看…）返回 `{code, message}`，
 * code=0 才是成功。原来这些接口写死
 * `requireWriteOk(resp)`，失败时只返回 false，
 * 上层只能显示「发送失败」这种无信息量的提示。
 *
 * 这个 helper 把 code/message 提出来：code=0 返回 true，否则抛出带
 * 可读 message 的异常（经 BiliErrorTranslator 翻译），让上层能给出
 * 「请求过于频繁」「硬币不足」这类可行动的反馈。
 */
internal fun requireWriteOk(resp: String): Boolean {
    // 风控页/非 JSON：别让 JSONObject 抛难懂的异常
    if (resp.trimStart().startsWith("<")) {
        error("B站返回风控页面（非 JSON），请稍后重试")
    }
    val obj = org.json.JSONObject(resp)
    val code = obj.optInt("code")
    if (code == 0) return true
    val raw = obj.optString("message").ifBlank { obj.optString("msg") }
    val detail = BiliErrorTranslator.translate("$code $raw".trim())
        ?: BiliErrorTranslator.translate(raw)
        ?: raw.ifBlank { "操作失败（code=$code）" }
    error(detail)
}

/**
 * 审核278：B 站风控时返回 **HTML 页面**（HTTP 200/412 + DOCTYPE），
 * 此时 `body()`/`decodeFromString` 会抛 "Unexpected JSON token ... had '<'"，
 * 而这个异常会**绕过** HomeRepository 的 popular 降级链（降级只在 code != 0 时触发），
 * 最终用户看到的就是截图里那个红色「加载失败」。
 *
 * 统一改为：先取文本 → 检测 HTML → 是 HTML 就返回 Result.failure（让上层降级）。
 */
/**
 * 审核278：B 站风控时返回 **HTML 风控页**（HTTP 200/412 + DOCTYPE），
 * decodeFromString 会抛 "Unexpected JSON token ... had '<'"，
 * 而这个异常会**绕过** HomeRepository 的 popular 降级链（降级只在
 * code != 0 时触发），最终用户看到截图里那个红色「加载失败」。
 *
 * 统一成「是 HTML 就抛可读错误」，让上层降级链能接住。
 */
internal fun requireJson(text: String): String {
    if (text.trimStart().startsWith("<")) {
        error("B站返回风控页面（非 JSON）")
    }
    return text
}

/**
 * Bilibili API Client using Ktor
 */
class BiliApiClient(private val client: HttpClient = BiliHttpClient.client) {
    
    companion object {
        /** 单例 Json（审核：避免每次请求重建，性能） */
        private const val API_BASE = "https://api.bilibili.com"
        private const val WBI_BASE = "https://api.bilibili.com/x/web-interface/nav"
        
        private val commonHeaders = mapOf(
            "Referer" to "https://www.bilibili.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    }
    
    // ========== Popular Videos ==========

    suspend fun popularVideos(page: Int = 1, pageSize: Int = 20): Result<PopularResponse> = runCatching {
        client.get("$API_BASE/x/web-interface/popular") {
            url {
                parameters.append("ps", pageSize.toString())
                parameters.append("pn", page.toString())
            }
            commonHeaders.forEach { (k, v) -> header(k, v) }
        }.bodyAsText().let { requireJson(it) }.let { text ->
            BiliHttpClient.json.decodeFromString<PopularResponse>(text)
        }
    }

    /** 批次L29：首页顶部大卡推荐（匿名可用） */
    suspend fun getTopRcmd(): Result<TopRcmdResponse> = runCatching {
        client.get("$API_BASE/x/web-interface/index/top/rcmd") {
            url {
                parameters.append("fresh_type", "3")
                parameters.append("version", "1")
                parameters.append("ps", "12")
            }
            commonHeaders.forEach { (k, v) -> header(k, v) }
        }.bodyAsText().let { requireJson(it) }.let { text ->
            BiliHttpClient.json.decodeFromString<TopRcmdResponse>(text)
        }
    }

    /**
     * 审核237：首页个性化推荐流（wbi 签名，fresh_idx 翻页）。
     * 移植自 bv `BiliHttpApi.getFeedRcmd`（fresh_type=4 / ps=30 / fresh_idx）。
     */
    suspend fun getFeedRcmd(freshIdx: Int, pageSize: Int = 30): Result<FeedRcmdResponse> = runCatching {
        // 匿名也需要 buvid3：B站风控对无 buvid 的推荐流直接 -352
        runCatching { AccountSession.ensureBuvid() }
        val params = mapOf(
            "fresh_type" to "4",
            "ps" to pageSize.toString(),
            "fresh_idx" to freshIdx.toString(),
            "fresh_idx_1h" to freshIdx.toString(),
        )
        // 审核270：签名失败时降级为无签名请求。
        // 实测（2026-10-13，匿名 + 桌面 UA + Referer）不带 w_rid 也返回 code 0；
        // 而 WbiSigner.getKeys() 依赖 nav 接口，匿名风控下可能取不到 key ——
        // 原来一失败就让首页整体降级到 popular，用户看到「刷不出推荐」。
        val signed = runCatching { WbiSigner.sign(params) }.getOrDefault(params)
        client.get("$API_BASE/x/web-interface/wbi/index/top/feed/rcmd") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://www.bilibili.com")
            commonHeaders.forEach { (k, v) -> header(k, v) }
        }.bodyAsText().let { requireJson(it) }.let { text ->
            BiliHttpClient.json.decodeFromString<FeedRcmdResponse>(text)
        }
    }

    /** 批次L30：番剧时间表（匿名可用） */
    suspend fun getBangumiTimeline(): Result<TimelineResponse> = runCatching {
        client.getSafe("$API_BASE/pgc/web/timeline") {
            url {
                parameters.append("types", "1")
                parameters.append("before", "6")
                parameters.append("after", "6")
            }
            header("Referer", "https://www.bilibili.com/anime/timeline/")
            commonHeaders.forEach { (k, v) -> header(k, v) }
        }
    }

    /** 批次L31：通用 GET（返回 JSON 文本） */
    suspend fun getRawJson(url: String): String =
        client.get(url) {
            header("Referer", "https://live.bilibili.com/")
            commonHeaders.forEach { (k, v) -> header(k, v) }
        }.bodyAsText().let { requireJson(it) }

    /** 批次L31：直播间大航海（舰长）列表，匿名可用 */
    suspend fun getGuardTopList(roomId: Long, ruid: Long): Result<GuardTopListResponse> = runCatching {
        client.getSafe("https://api.live.bilibili.com/xlive/app-room/v2/guardTab/topList") {
            url {
                parameters.append("roomid", roomId.toString())
                parameters.append("page", "1")
                parameters.append("ruid", ruid.toString())
                parameters.append("page_size", "29")
            }
            header("Referer", "https://live.bilibili.com/")
            commonHeaders.forEach { (k, v) -> header(k, v) }
        }
    }

    /** 批次L32：视频分区最新（匿名可用） */
    suspend fun getNewList(rid: Int, page: Int = 1, ps: Int = 20): Result<NewListResponse> = runCatching {
        client.getSafe("$API_BASE/x/web-interface/newlist") {
            url {
                parameters.append("rid", rid.toString())
                parameters.append("ps", ps.toString())
                parameters.append("pn", page.toString())
            }
            commonHeaders.forEach { (k, v) -> header(k, v) }
        }
    }

    // ========== Video Info ==========
    
    suspend fun getVideoInfo(bvid: String): Result<VideoInfoResponse> = runCatching {
        client.getSafe("$API_BASE/x/web-interface/view") {
            url {
                parameters.append("bvid", bvid)
            }
            header("Referer", "https://www.bilibili.com")
        }
    }
    
    // ========== Play URL ==========
    
    suspend fun getPlayUrl(bvid: String, cid: Long, qn: Int = 80): Result<PlayUrlResponse> = runCatching {
        // playurl 必须 wbi 签名，否则 -404/-352（见 docs/bilibili_api_checklist.md §1.2）
        val signed = WbiSigner.sign(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnval" to "4048", // DASH + HDR/杜比/8K 等全部位（对齐 PiliPala/PiliPlus）
                "fnver" to "0",
                "fourk" to "1",
                "try_look" to "1"  // 免登录试看（提升匿名可播概率）
            )
        )
        client.get("$API_BASE/x/player/wbi/playurl") {
            url {
                signed.forEach { (k, v) -> parameters.append(k, v) }
            }
            header("Referer", "https://www.bilibili.com")
        }.bodyAsText().let { text ->
            // 审核288：playurl 是关键接口，风控返回 HTML 时给可读错误
            // （否则报「Expected BEGIN_OBJECT」，用户不知道是风控）
            requireJson(text)
            // 审核：复用单例 Json，避免每次请求重建（性能）
            BiliHttpClient.json.decodeFromString<PlayUrlResponse>(text)
                .copy(rawJson = text)
        }
    }
    
    // ========== Write operations（需登录 + csrf） ==========

    /** 点赞/取消点赞（like=1 点赞, 2 取消；返回 code 0 成功） */
    suspend fun like(aid: Long, like: Int = 1): Result<Map<String, Any>> = runCatching {
        BiliHttpClient.postAuthForm<Map<String, Any>>(
            "https://api.bilibili.com/x/web-interface/archive/like",
            mapOf("aid" to aid.toString(), "like" to like.toString())
        )
    }

    /** 投币（multiply=1/2 个币，需先 like=1 一起勾选可传） */
    suspend fun coin(aid: Long, multiply: Int = 1): Result<Map<String, Any>> = runCatching {
        BiliHttpClient.postAuthForm<Map<String, Any>>(
            "https://api.bilibili.com/x/web-interface/coin/add",
            mapOf("aid" to aid.toString(), "multiply" to multiply.toString(), "select_like" to "0")
        )
    }

    /** 收藏/取消收藏到默认收藏夹（需要先知道 target mid 的默认夹 id；deal=1 收藏 2 取消） */
    suspend fun favorite(aid: Long, mediaId: Long, deal: Int = 1): Result<Map<String, Any>> = runCatching {
        BiliHttpClient.postAuthForm<Map<String, Any>>(
            "https://api.bilibili.com/x/v3/fav/resource/deal",
            mapOf(
                "rid" to aid.toString(),
                "type" to "2",
                "add_media_ids" to if (deal == 1) mediaId.toString() else "",
                "del_media_ids" to if (deal == 2) mediaId.toString() else ""
            )
        )
    }

    /** 审核轮206：用户经验/等级信息（PiliPlus exp_log 对齐；x/web-interface/acc/info 需登录） */
    /**
     * 用户经验/等级。
     *
     * 审核275：原路径 `/x/web-interface/acc/info` **已下线**（实测 HTTP 404）。
     * 改为明确返回错误而不是把 404 的 HTML 塞给 JSON 解析层
     * （原来会抛 "Expected BEGIN_OBJECT" 这种无意义错误）。
     */
    suspend fun getUserExpInfo(): Result<org.json.JSONObject> = runCatching {
        val resp: String = client.get("$API_BASE/x/web-interface/acc/info") {
            header("Referer", "https://www.bilibili.com")
        }.bodyAsText()
        if (resp.trimStart().startsWith("<")) {
            error("B站已下线该接口")
        }
        org.json.JSONObject(resp)
    }

    /** 审核轮205：投币记录（PiliPlus coin_log；需登录） */
    suspend fun getCoinLog(pn: Int = 1, ps: Int = 20): Result<org.json.JSONObject> = runCatching {
        val resp: String = client.get("$API_BASE/x/web-interface/coin/log") {
            parameter("pn", pn); parameter("ps", ps)
            header("Referer", "https://www.bilibili.com")
        }.bodyAsText()
        // 审核275：该路由实测已下线（HTTP 404 返回 HTML）。
        if (resp.trimStart().startsWith("<")) {
            error("B站已下线该接口")
        }
        org.json.JSONObject(resp)
    }

    /** 审核轮204：拉黑/取消拉黑用户（fid=目标 mid；act=1 拉黑 2 取消，需登录） */
    suspend fun modifyBlacklist(fid: Long, act: Int): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "$API_BASE/x/relation/modify",
            mapOf("fid" to fid.toString(), "act" to act.toString(), "re_src" to "11")
        )
        requireWriteOk(resp)
    }

    /** 审核轮204：黑名单列表（ps/pn 分页） */
    suspend fun getBlacklist(pn: Int = 1, ps: Int = 20): Result<org.json.JSONObject> = runCatching {
        val resp: String = client.get("$API_BASE/x/relation/blacks") {
            parameter("pn", pn); parameter("ps", ps)
            header("Referer", "https://www.bilibili.com")
        }.bodyAsText()
        // 审核288：风控/下线时返回 HTML，JSONObject 会抛「not a JSON object」
        if (resp.trimStart().startsWith("<")) {
            error("B站返回风控页面（非 JSON），请稍后重试")
        }
        org.json.JSONObject(resp)
    }

    /** 上报观看历史（progress 秒；sid/cid 可选） */
    suspend fun reportHistory(aid: Long, cid: Long, progress: Long): Result<Map<String, Any>> = runCatching {
        BiliHttpClient.postAuthForm<Map<String, Any>>(
            "https://api.bilibili.com/x/v2/history/report",
            mapOf(
                "aid" to aid.toString(),
                "cid" to cid.toString(),
                "progress" to progress.toString(),
                "type" to "3"
            )
        )
    }

    // ========== Search ==========

    /** 批次L23：专栏文章正文（匿名可用） */
    suspend fun getArticleView(id: Long): Result<ArticleViewResponse> = runCatching {
        client.getSafe("$API_BASE/x/article/view") {
            url { parameters.append("id", id.toString()) }
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 批次L24：UP 主专栏列表（匿名可用） */
    suspend fun getSpaceArticles(mid: Long, pn: Int = 1, ps: Int = 20): Result<SpaceArticleResponse> = runCatching {
        client.getSafe("$API_BASE/x/space/article") {
            url {
                parameters.append("mid", mid.toString())
                parameters.append("pn", pn.toString())
                parameters.append("ps", ps.toString())
                parameters.append("sort", "publish_time")
            }
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 批次L22：视频 AI 总结（wbi 签名，需登录，匿名 -403） */
    suspend fun getAiConclusion(bvid: String, cid: Long, upMid: Long = 0L): Result<AiConclusionResponse> = runCatching {
        val signed = WbiSigner.sign(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "up_mid" to upMid.toString()
            )
        )
        client.getSafe("$API_BASE/x/web-interface/view/conclusion/get") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://www.bilibili.com")
        }
    }

    suspend fun search(keyword: String, page: Int = 1, order: String = "totalrank"): Result<SearchResponse> = runCatching {
        // wbi 签名接口；缺 buvid3 会 -412 风控（见 docs/bilibili_api_checklist.md）
        AccountSession.ensureBuvid()
        val signed = WbiSigner.sign(
            mapOf("keyword" to keyword, "page" to page.toString(), "order" to order)
        )
        client.getSafe("$API_BASE/x/web-interface/wbi/search/all/v2") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://www.bilibili.com")
        }
    }
    
    suspend fun searchSuggest(keyword: String): Result<SearchSuggest> = runCatching {
        client.getSafe("$API_BASE/suggestion/search") {
            url {
                parameters.append("keyword", keyword)
            }
            header("Referer", "https://www.bilibili.com")
        }
    }
    
    // ========== Comments ==========
    
    suspend fun getComments(bvid: String, oid: Long = 0L, page: Int = 1, pageSize: Int = 20, mode: Int = 3): Result<CommentResponse> = runCatching {
        // 审核275（真 bug）：wbi 版评论接口已对匿名关闭。
        // 实测（2026-10-14，匿名+buvid3+桌面UA）：
        //   /x/v2/reply/wbi/main（签名正确）→ code -403 访问权限不足
        //   /x/v2/reply/main（同参数，无签名）  → code 0，all_count 89396
        // 即评论区整页不可用。改为先 wbi、-403 时自动降级到旧路径。
        // mode: 2=按时间 3=按热度（批次L35 排序切换）
        val params = mapOf(
            "oid" to oid.toString(),
            "type" to "1",
            "mode" to mode.toString(),
            "pn" to page.toString(),
            "ps" to pageSize.toString()
        )
        val signed = WbiSigner.sign(params)
        val wbi = client.get("$API_BASE/x/v2/reply/wbi/main") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://www.bilibili.com")
        }.body<CommentResponse>()
        if (wbi.code == 0) {
            wbi
        } else {
            // -403 或其它非 0：降级到旧路径（匿名仍可用）
            client.getSafe("$API_BASE/x/v2/reply/main") {
                url { params.forEach { (k, v) -> parameters.append(k, v) } }
                header("Referer", "https://www.bilibili.com")
            }
        }
    }

    /** 楼中楼回复 /x/v2/reply/reply（无需 wbi） */
    suspend fun getReplyList(oid: Long, rootRpid: Long, page: Int = 1, pageSize: Int = 20): Result<ReplyListResponse> = runCatching {
        client.getSafe("$API_BASE/x/v2/reply/reply") {
            url {
                parameters.append("oid", oid.toString())
                parameters.append("type", "1")
                parameters.append("root", rootRpid.toString())
                parameters.append("pn", page.toString())
                parameters.append("ps", pageSize.toString())
            }
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 评论点赞 /x/v2/reply/action（csrf）。like: 1 点赞 / 0 取消 */
    suspend fun likeComment(oid: Long, rpid: Long, like: Boolean): Result<Boolean> = runCatching {
        val form = linkedMapOf(
            "oid" to oid.toString(),
            "type" to "1",
            "rpid" to rpid.toString(),
            "action" to if (like) "1" else "0"
        )
        val resp: String = BiliHttpClient.postAuthForm("$API_BASE/x/v2/reply/action", form)
        requireWriteOk(resp)
    }

    /** 发评论 /x/v2/reply/add（csrf） */
    suspend fun addComment(
        oid: Long, message: String, rootRpid: Long = 0L, parentRpid: Long = 0L,
        atUid: Long = 0L, atName: String = ""
    ): Result<Boolean> = runCatching {
        val form = linkedMapOf(
            "oid" to oid.toString(),
            "type" to "1",
            "message" to message,
            "plat" to "1",
            "web_location" to "1315875"
        )
        if (rootRpid > 0L) form["root"] = rootRpid.toString()
        if (parentRpid > 0L) form["parent"] = parentRpid.toString()
        if (atUid > 0L && atName.isNotBlank()) {
            form["at_name_to_mid"] = org.json.JSONObject().put(atName, atUid).toString()
        }
        val resp: String = BiliHttpClient.postAuthForm("$API_BASE/x/v2/reply/add", form)
        requireWriteOk(resp)
    }

    /** @用户搜索（登录态，/x/v2/reply/at；oid/root/parent 参数从 Flutter 版对齐） */
    suspend fun searchAtUser(
        keyword: String, oid: Long, rootRpid: Long, parentRpid: Long
    ): Result<AtSearchResponse> = runCatching {
        val signed = WbiSigner.sign(
            mapOf(
                "keyword" to keyword,
                "oid" to oid.toString(),
                "type" to "1",
                "root" to rootRpid.toString(),
                "parent" to parentRpid.toString(),
                "platform" to "web",
                "web_location" to "1315875"
            )
        )
        client.getSafe("$API_BASE/x/v2/reply/at") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://www.bilibili.com/")
        }
    }
    
    // ========== Danmaku ==========
    
    suspend fun getDanmaku(cid: Long, oid: Long = 0L): Result<DanmakuResponse> = runCatching {
        client.getSafe("$API_BASE/x/v1/dm/list.so") {
            url {
                parameters.append("oid", oid.toString())
                parameters.append("pid", cid.toString())
                parameters.append("type", "1")
            }
            header("Referer", "https://www.bilibili.com")
        }
    }
    
    // ========== User Info ==========
    
    suspend fun getUserInfo(uid: Long): Result<UserInfoResponse> = runCatching {
        client.getSafe("$API_BASE/x/space/wbi/acc/info") {
            url {
                parameters.append("mid", uid.toString())
            }
            header("Referer", "https://space.bilibili.com")
        }
    }
    
    suspend fun getUserSpace(uid: Long): Result<UserInfoResponse> = runCatching {
        client.getSafe("$API_BASE/x/space/acc/info") {
            url {
                parameters.append("mid", uid.toString())
            }
            header("Referer", "https://space.bilibili.com")
        }
    }
    
    // ========== Dynamics ==========
    
    suspend fun getUserDynamics(uid: Long, offset: Long = 0L): Result<DynamicsResponse> = runCatching {
        client.getSafe("$API_BASE/x/polymer/web-dynamic/v1/feed/space") {
            url {
                parameters.append("host_uid", uid.toString())
                parameters.append("offset", offset.toString())
            }
            header("Referer", "https://t.bilibili.com")
        }
    }
    
    suspend fun getFollowingsFeed(offset: Long = 0L): Result<DynamicsResponse> = runCatching {
        client.getSafe("$API_BASE/x/polymer/web-dynamic/v1/feed/all") {
            url {
                parameters.append("offset", offset.toString())
            }
            header("Referer", "https://t.bilibili.com")
        }
    }
    
    // ========== Live ==========
    // getLiveRoomInfo / getLivePlayInfo / roomEntryAction（见下方 xlive 区域）
    
    // ========== Favorites ==========

    suspend fun getFavorites(uid: Long, pageSize: Int = 20, mediaType: String = "video"): Result<Map<String, Any>> = runCatching {
        client.getSafe("$API_BASE/x/v3/fav/folder/created/list-all") {
            url {
                parameters.append("up_mid", uid.toString())
                parameters.append("media_type", mediaType)
            }
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 当前用户收藏夹列表（需登录，用于取默认收藏夹 id） */
    suspend fun getMyFavFolders(): Result<List<FavFolder>> = runCatching {
        val mid = AccountSession.mid
        require(mid > 0L) { "未登录" }
        val resp: FavFolderListResponse = client.getSafe("$API_BASE/x/v3/fav/folder/created/list-all") {
            url { parameters.append("up_mid", mid.toString()) }
            header("Referer", "https://www.bilibili.com")
        }
        resp.data.orEmpty()
    }

    /** 查询视频交互状态（like=1 已赞, coin=1 已投币, favourite=1 已藏；需登录） */
    suspend fun getVideoRelation(aid: Long): Result<Triple<Boolean, Boolean, Boolean>> = runCatching {
        val resp: RelationResponse = client.getSafe("$API_BASE/x/web-interface/archive/relation") {
            url { parameters.append("aid", aid.toString()) }
            header("Referer", "https://www.bilibili.com")
        }
        val d = resp.data
        if (resp.code == 0 && d != null) {
            Triple(d.like == 1, d.coin == 1, d.favourite == 1)
        } else error(resp.message.ifEmpty { "查询交互状态失败" })
    }

    // ========== Dynamic（动态，需登录） ==========

    /**
     * 动态聚合流。首次传 offset=null，后续用上一页返回的 data.offset 翻页。
     * 需 SESSDATA（未登录返回 -101）。
     */
    suspend fun getDynamicFeed(offset: String? = null): Result<DynamicFeedResponse> = runCatching {
        client.getSafe("$API_BASE/x/polymer/web-dynamic/v1/feed/all") {
            url {
                parameters.append("type", "all")
                if (!offset.isNullOrEmpty()) parameters.append("offset", offset)
                parameters.append("web_location", "333.1369")
            }
            header("Referer", "https://www.bilibili.com")
        }
    }

    // ========== Video extras（批次A：播放器补全） ==========

    /** 相关视频推荐（无需登录） */
    suspend fun getRelatedVideos(bvid: String): Result<List<RelatedVideo>> = runCatching {
        val resp: RelatedVideoResponse = client.getSafe("$API_BASE/x/web-interface/archive/related") {
            url { parameters.append("bvid", bvid) }
            header("Referer", "https://www.bilibili.com")
        }
        if (resp.code == 0) resp.data.orEmpty() else error(resp.message.ifEmpty { "相关视频获取失败" })
    }

    // ========== Search extras（批次B） ==========

    /** 热搜榜（无需登录） */
    suspend fun getSearchTrending(): Result<List<TrendingItem>> = runCatching {
        val resp: SearchTrendingResponse = client.getSafe("$API_BASE/x/v2/search/trending/ranking") {
            url { parameters.append("limit", "20") }
            header("Referer", "https://search.bilibili.com")
        }
        if (resp.code == 0) {
            (resp.data?.top_list.orEmpty()) + resp.data?.list.orEmpty()
        } else error(resp.message.ifEmpty { "热搜获取失败" })
    }

    /**
     * 分类搜索：video（视频）/ bili_user（用户）/ live（直播）。
     * order: totalrank(默认)/click/pubdate/danmaku/stow
     * duration: 0全部/10分钟内/30分钟内/60分钟内
     * 需要 wbi 签名 + buvid3。
     */
    suspend fun searchByType(
        keyword: String,
        searchType: String = "video",
        page: Int = 1,
        order: String = "",
        duration: Int = 0
    ): Result<SearchResultResponse> = runCatching {
        AccountSession.ensureBuvid()
        val params = mutableMapOf(
            "search_type" to searchType,
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to "20"
        )
        if (order.isNotEmpty()) params["order"] = order
        if (duration > 0) params["duration"] = duration.toString()
        val signed = WbiSigner.sign(params)
        client.getSafe("$API_BASE/x/web-interface/wbi/search/type") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://search.bilibili.com")
        }
    }

    /**
     * 通用 GET：给路径 + 追加查询参数，反序列化为 reified T。
     * 用于收藏夹/历史等带具体模型的新接口，避免每个都加 wrapper。
     * 注意：inline 函数不能访问私有成员，故直接用公开的 BiliHttpClient.client。
     */
    suspend inline fun <reified T : Any> getFavoritesRaw(
        path: String,
        noinline params: io.ktor.http.ParametersBuilder.() -> Unit = {}
    ): T = BiliHttpClient.client.getSafe("https://api.bilibili.com/$path") {
        // URLBuilder.parameters 就是 ParametersBuilder，应用 lambda
        url.parameters.apply(params)
        header("Referer", "https://www.bilibili.com")
    }

    // ========== UP 主空间（批次C） ==========

    /** 空间主页信息（wbi 签名） */
    suspend fun getSpaceInfo(mid: Long): Result<SpaceInfoResponse> = runCatching {
        // 审核275：wbi 版匿名 -352 风控，旧版路径匿名可用但会被限频（-799）。
        // 两条都试：wbi 成功就用它，否则试旧路径；两条都失败返回 wbi 的结果
        // （让上层给出可读错误，而不是静默空列表）。
        val signed = WbiSigner.sign(mapOf("mid" to mid.toString()))
        val wbi = runCatching {
            client.get("$API_BASE/x/space/wbi/acc/info") {
                url { signed.forEach { (k, v) -> parameters.append(k, v) } }
                header("Referer", "https://space.bilibili.com/$mid")
            }.body<SpaceInfoResponse>()
        }.getOrNull()
        if (wbi != null && wbi.code == 0) return@runCatching wbi
        val legacy = runCatching {
            client.get("$API_BASE/x/space/acc/info") {
                url { parameters.append("mid", mid.toString()) }
                header("Referer", "https://space.bilibili.com/$mid")
            }.body<SpaceInfoResponse>()
        }.getOrNull()
        legacy ?: wbi ?: error("UP 主信息获取失败（风控或限频）")
    }

    /** 粉丝/关注数（无需签名） */
    suspend fun getRelationStat(mid: Long): Result<RelationStatResponse> = runCatching {
        client.get("$API_BASE/x/relation/stat") {
            url { parameters.append("vmid", mid.toString()) }
            header("Referer", "https://space.bilibili.com/$mid")
        }.body<RelationStatResponse>()
    }

    /** 投稿列表（wbi 签名 + 分页），order: pubdate 最新 / click 最多播放 */
    suspend fun getSpaceArchives(
        mid: Long,
        page: Int = 1,
        order: String = "pubdate",
        keyword: String = ""
    ): Result<SpaceArchiveResponse> = runCatching {
        val params = buildMap {
            put("mid", mid.toString())
            put("pn", page.toString())
            put("ps", "30")
            put("order", order)
            if (keyword.isNotEmpty()) put("keyword", keyword)
        }
        val signed = WbiSigner.sign(params)
        val resp: String = client.get("$API_BASE/x/space/wbi/arc/search") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://space.bilibili.com/$mid")
        }.bodyAsText()
        // 审核276：该接口匿名必被风控（实测 HTTP 412 + HTML 风控页，wbi 签名
        // 无法绕过）。原来 body() 直接反序列化 HTML 会抛 JSON 异常，
        // 用户看到的是「Expected BEGIN_OBJECT」而不是「需要登录」。
        if (resp.trimStart().startsWith("<")) {
            error("UP 主投稿列表需要登录后查看（匿名被风控）")
        }
        BiliHttpClient.json.decodeFromString<SpaceArchiveResponse>(resp)
    }

    /** 关注/取关 UP 主（csrf）。act: 1 关注 / 2 取关 */
    suspend fun modifyFollow(mid: Long, follow: Boolean): Result<Boolean> = runCatching {
        val form = linkedMapOf(
            "fid" to mid.toString(),
            "act" to if (follow) "1" else "2",
            "re_src" to "11"
        )
        val resp: String = BiliHttpClient.postAuthForm(
            "$API_BASE/x/relation/modify", form
        )
        requireWriteOk(resp)
    }

    /** 直播列表（分页，按人气） */
    suspend fun getLiveList(page: Int = 1, pageSize: Int = 30): Result<LiveListResponse> = runCatching {
        client.getSafe("https://api.live.bilibili.com/xlive/app-interface/v2/second/getList") {
             url {
                parameters.append("platform", "web")
                parameters.append("parent_area_id", "0")
                parameters.append("area_id", "0")
                parameters.append("sort_type", "")
                parameters.append("page", page.toString())
                parameters.append("page_size", pageSize.toString())
            }
            header("Referer", "https://live.bilibili.com")
        }
    }

    /** 批次L25：直播分区列表（room/v1/Area/getList，匿名可用） */
    suspend fun getLiveAreaList(): Result<LiveAreaListResponse> = runCatching {
        client.getSafe("https://api.live.bilibili.com/room/v1/Area/getList") {
            header("Referer", "https://live.bilibili.com")
        }
    }

    /** 批次L25：直播推荐流（webMain/getMoreRecList，匿名可用；分区过滤在前端做） */
    suspend fun getLiveRecList(): Result<LiveRecListResponse> = runCatching {
        client.getSafe("https://api.live.bilibili.com/xlive/web-interface/v1/webMain/getMoreRecList") {
            url { parameters.append("platform", "web") }
            header("Referer", "https://live.bilibili.com")
        }
    }

    /** 批次L26：入站必刷（匿名可用，一次返回全部 98 条） */
    suspend fun getPopularPrecious(): Result<PreciousResponse> = runCatching {
        client.getSafe("$API_BASE/x/web-interface/popular/precious") {
            url {
                parameters.append("page_size", "100")
                parameters.append("page", "1")
            }
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 批次L26：每周必看期数列表（匿名可用） */
    suspend fun getWeeklyList(): Result<WeeklyListResponse> = runCatching {
        client.getSafe("$API_BASE/x/web-interface/popular/series/list") {
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 批次L28：每周必看期数详情（需 buvid3 cookie 防 -352） */
    suspend fun getWeeklyDetail(number: Int): Result<WeeklyDetailResponse> = runCatching {
        AccountSession.ensureBuvid()
        client.getSafe("$API_BASE/x/web-interface/popular/series/one") {
            url { parameters.append("number", number.toString()) }
            header("Referer", "https://www.bilibili.com/v/popular/weekly")
        }
    }

    /** 批次L27：UP 主合集/系列列表（polymer web-space，匿名可用） */
    suspend fun getSeasonsSeries(mid: Long, pageNum: Int = 1, pageSize: Int = 20): Result<SeasonsSeriesResponse> = runCatching {
        client.getSafe("$API_BASE/x/polymer/web-space/seasons_series_list") {
            url {
                parameters.append("mid", mid.toString())
                parameters.append("page_num", pageNum.toString())
                parameters.append("page_size", pageSize.toString())
            }
            header("Referer", "https://space.bilibili.com")
        }
    }

    /** 批次L27：合集内视频列表（seasons_archives_list，匿名可用） */
    suspend fun getSeasonArchives(mid: Long, seasonId: Long, pageNum: Int = 1, pageSize: Int = 20): Result<SeasonArchivesResponse> = runCatching {
        client.getSafe("$API_BASE/x/polymer/web-space/seasons_archives_list") {
            url {
                parameters.append("mid", mid.toString())
                parameters.append("season_id", seasonId.toString())
                parameters.append("page_num", pageNum.toString())
                parameters.append("page_size", pageSize.toString())
            }
            header("Referer", "https://space.bilibili.com")
        }
    }

    /** 批次L27：系列内视频列表（/x/series/archives，匿名可用；archives 可能为 null） */
    suspend fun getSeriesArchives(mid: Long, seriesId: Long, pageNum: Int = 1, pageSize: Int = 20): Result<SeasonArchivesResponse> = runCatching {
        client.getSafe("$API_BASE/x/series/archives") {
            url {
                parameters.append("mid", mid.toString())
                parameters.append("series_id", seriesId.toString())
                parameters.append("only_normal", "true")
                parameters.append("sort", "desc")
                parameters.append("pn", pageNum.toString())
                parameters.append("ps", pageSize.toString())
            }
            header("Referer", "https://space.bilibili.com")
        }
    }

    // ========== 番剧/影视（批次D） ==========

    /** 番剧详情（season_id 或 ep_id）。注意：pgc 接口在 api.bilibili.com，返回字段直属 result */
    suspend fun getPgcSeason(seasonId: Long = 0L, epId: Long = 0L): Result<PgcSeasonResponse> = runCatching {
        client.getSafe("$API_BASE/pgc/view/web/season") {
            url {
                if (seasonId > 0) parameters.append("season_id", seasonId.toString())
                if (epId > 0) parameters.append("ep_id", epId.toString())
            }
            header("Referer", "https://www.bilibili.com/bangumi/")
        }
    }

    /** pgc 索引分类列表（批次L2）：season_type 1番剧 2电影 3纪录片 4国创 5电视剧 7综艺 */
    suspend fun getPgcIndex(
        seasonType: Int,
        page: Int,
        order: String = "update"
    ): Result<PgcIndexResponse> = runCatching {
        client.getSafe("$API_BASE/pgc/season/index/result") {
            url {
                parameters.append("season_type", seasonType.toString())
                parameters.append("type", "1")
                parameters.append("order", order)
                parameters.append("page", page.toString())
                parameters.append("pagesize", "20")
            }
            header("Referer", "https://www.bilibili.com/")
        }
    }

    /** 进度条缩略图雪碧图（批次L3 storyboard） */
    suspend fun getVideoShot(bvid: String, cid: Long): Result<VideoShotResponse> = runCatching {
        client.getSafe("$API_BASE/x/player/videoshot") {
            url {
                parameters.append("bvid", bvid)
                parameters.append("cid", cid.toString())
                parameters.append("index", "1")
            }
            header("Referer", "https://www.bilibili.com/")
        }
    }

    /** 番剧播放地址（pgc playurl，ep_id + cid） */
    suspend fun getPgcPlayUrl(epId: Long, cid: Long, qn: Int = 80): Result<PgcPlayUrlResponse> = runCatching {
        val signed = WbiSigner.sign(
            mapOf(
                "ep_id" to epId.toString(),
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnval" to "4048",
                "fnver" to "0",
                "fourk" to "1"
            )
        )
        client.getSafe("$API_BASE/pgc/player/web/playurl") {
            url { signed.forEach { (k, v) -> parameters.append(k, v) } }
            header("Referer", "https://www.bilibili.com/bangumi/")
        }
    }

    /** 追番/取消追番（csrf）。season_id 或 ep_id 二选一 */
    suspend fun followBangumi(seasonId: Long = 0L, epId: Long = 0L, follow: Boolean): Result<Boolean> = runCatching {
        val form = linkedMapOf(
            "season_id" to seasonId.toString(),
            "ep_id" to epId.toString()
        )
        val path = if (follow) "$API_BASE/pgc/app/follow/add" else "$API_BASE/pgc/app/follow/del"
        val resp: String = BiliHttpClient.postAuthForm(path, form)
        requireWriteOk(resp)
    }

    // ========== 关注/粉丝 + 消息（批次H） ==========

    /** 关注列表（需登录） */
    suspend fun getFollowings(mid: Long, page: Int = 1, pageSize: Int = 50): Result<FollowingsResponse> = runCatching {
        client.getSafe("$API_BASE/x/relation/followings") {
            url {
                parameters.append("vmid", mid.toString())
                parameters.append("pn", page.toString())
                parameters.append("ps", pageSize.toString())
            }
            header("Referer", "https://space.bilibili.com/$mid")
        }
    }

    /** 粉丝列表（需登录） */
    suspend fun getFollowers(mid: Long, page: Int = 1, pageSize: Int = 50): Result<FollowingsResponse> = runCatching {
        client.getSafe("$API_BASE/x/relation/followers") {
            url {
                parameters.append("vmid", mid.toString())
                parameters.append("pn", page.toString())
                parameters.append("ps", pageSize.toString())
            }
            header("Referer", "https://space.bilibili.com/$mid")
        }
    }

    /** 未读消息数（需登录） */
    suspend fun getMsgUnread(): Result<MsgUnreadResponse> = runCatching {
        client.getSafe("$API_BASE/x/msgfeed/unread") {
            url {
                parameters.append("platform", "web")
                parameters.append("build", "0")
                parameters.append("mobi_app", "web")
            }
            header("Referer", "https://message.bilibili.com")
        }
    }

    /** 回复我的（需登录） */
    suspend fun getMsgReply(page: Int = 1): Result<MsgFeedResponse> = runCatching {
        client.getSafe("$API_BASE/x/msgfeed/reply") {
            url {
                parameters.append("platform", "web")
                parameters.append("build", "0")
                parameters.append("mobi_app", "web")
                parameters.append("pn", page.toString())
            }
            header("Referer", "https://message.bilibili.com")
        }
    }

    /** @我的（需登录） */
    suspend fun getMsgAt(page: Int = 1): Result<MsgFeedResponse> = runCatching {
        client.getSafe("$API_BASE/x/msgfeed/at") {
            url {
                parameters.append("platform", "web")
                parameters.append("build", "0")
                parameters.append("mobi_app", "web")
                parameters.append("pn", page.toString())
            }
            header("Referer", "https://message.bilibili.com")
        }
    }

    /** 收到的赞（需登录） */
    suspend fun getMsgLike(page: Int = 1): Result<MsgFeedResponse> = runCatching {
        client.getSafe("$API_BASE/x/msgfeed/like") {
            url {
                parameters.append("platform", "web")
                parameters.append("build", "0")
                parameters.append("mobi_app", "web")
                parameters.append("pn", page.toString())
            }
            header("Referer", "https://message.bilibili.com")
        }
    }

    // ========== 直播（批次F） ==========

    /** 直播间播放信息（含 HLS/FLV 流地址）。roomId 支持短号 */
    suspend fun getLivePlayInfo(roomId: Long): Result<LivePlayInfoResponse> = runCatching {
        client.getSafe("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo") {
            url {
                parameters.append("room_id", roomId.toString())
                parameters.append("protocol", "0,1")     // 0=flv 1=hls
                parameters.append("format", "0,1,2")     // flv/ts/fmp4
                parameters.append("codec", "0,1")        // avc/hevc
                parameters.append("qn", "10000")         // 最高画质
            }
            header("Referer", "https://live.bilibili.com")
        }
    }

    /** 直播间信息（标题/分区/人气） */
    suspend fun getLiveRoomInfo(roomId: Long): Result<LiveRoomInfoResponse> = runCatching {
        client.getSafe("https://api.live.bilibili.com/xlive/web-interface/v1/index/getInfoByRoom") {
            url { parameters.append("room_id", roomId.toString()) }
            header("Referer", "https://live.bilibili.com")
        }
    }

    /** 直播人气入口上报（进入直播间必须调，否则风控） */
    suspend fun liveRoomEntryAction(roomId: Long): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/roomEntryAction",
            mapOf("room_id" to roomId.toString())
        )
        requireWriteOk(resp)
    }

    // ========== 直播弹幕 WebSocket（批次J） ==========

    /** 弹幕连接信息：token + host 列表（wss://host:wssPort/sub） */
    suspend fun getDanmuInfo(roomId: Long): Result<DanmuInfoResponse> = runCatching {
        client.getSafe("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo") {
            url {
                parameters.append("id", roomId.toString())
                parameters.append("type", "0")
            }
            header("Referer", "https://live.bilibili.com/")
        }
    }

    /**
     * 发送直播弹幕（csrf）。
     *
     * 审核288（真 bug）：直播弹幕接口的 CSRF 字段名是 `csrf_token`，而
     * postAuthForm 自动加的是 `csrf`。原来这里把 csrf_token 硬编码成空串，
     * 导致发弹幕必然返回 -111（csrf 校验失败）——用户看到「发送失败」但
     * 完全不知道原因。改为从登录态取 bili_jct 填入。
     */
    suspend fun sendLiveDanmaku(roomId: Long, message: String): Result<Boolean> = runCatching {
        val csrf = AccountSession.snapshot()["bili_jct"].orEmpty()
        if (csrf.isEmpty()) error("请先登录 B 站账号")
        val resp: String = BiliHttpClient.postAuthForm(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/SendMsg",
            linkedMapOf(
                "bubble" to "0",
                "msg" to message,
                "color" to "16777215",
                "mode" to "1",
                "fontsize" to "25",
                "rnd" to (System.currentTimeMillis() / 1000).toString(),
                "roomid" to roomId.toString(),
                "csrf_token" to csrf
            )
        )
        requireWriteOk(resp)
    }

    /** 发送视频弹幕 /x/v2/dm/post（oid=cid，需登录）+ csrf */
    suspend fun sendVideoDanmaku(
        cid: Long,
        bvid: String,
        msg: String,
        progressMs: Long = 0L,
        mode: Int = 1,
        color: Int = 16777215,
        fontSize: Int = 25
    ): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "https://api.bilibili.com/x/v2/dm/post",
            linkedMapOf(
                "type" to "1",
                "oid" to cid.toString(),
                "msg" to msg,
                "mode" to mode.toString(),
                "bvid" to bvid,
                "progress" to progressMs.toString(),
                "color" to color.toString(),
                "fontsize" to fontSize.toString(),
                "pool" to "0",
                "rnd" to (System.currentTimeMillis() * 1000000).toString(),
                "plat" to "1"
            )
        )
        requireWriteOk(resp)
    }

    // ========== 播放器字幕（批次K） ==========

    /** /x/player/v2 —— 字幕列表等播放器附加信息 */
    suspend fun getPlayerV2(bvid: String, cid: Long): Result<PlayerV2Response> = runCatching {
        client.getSafe("$API_BASE/x/player/v2") {
            url {
                parameters.append("bvid", bvid)
                parameters.append("cid", cid.toString())
            }
            header("Referer", "https://www.bilibili.com/")
        }
    }

    /** 拉取字幕内容 JSON（subtitle_url，协议相对 // 开头） */
    suspend fun fetchSubtitleBody(url: String): Result<SubtitleBody> = runCatching {
        val full = if (url.startsWith("//")) "https:$url" else url
        client.getSafe(full) {
            header("Referer", "https://www.bilibili.com/")
        }
    }

    // ========== History / 稍后再看（批次G） ==========

    /** 排行榜（批次L17）：需桌面 UA + rank referer，否则 -352 风控 */
    suspend fun getRanking(rid: Int = 0, type: String = "all"): Result<RankResponse> = runCatching {
        client.getSafe("$API_BASE/x/web-interface/ranking/v2") {
            url {
                parameters.append("rid", rid.toString())
                parameters.append("type", type)
            }
            header("Referer", "https://www.bilibili.com/v/popular/rank/all")
            header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        }
    }

    /** 批次L34：分区排行榜（ranking/region 匿名可用；rid=0 无效需传分区） */
    suspend fun getRegionRanking(rid: Int): Result<RegionRankResponse> = runCatching {
        client.getSafe("$API_BASE/x/web-interface/ranking/region") {
            url {
                parameters.append("rid", rid.toString())
                parameters.append("day", "3")
            }
            header("Referer", "https://www.bilibili.com/v/popular/rank/all")
            header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        }
    }

    /** 批次L45：UP主付费课程列表（cheese/pugv，匿名可用，无课程返回空） */
    suspend fun getMemberCheese(mid: Long): Result<CheesePageResponse> = runCatching {
        client.getSafe("$API_BASE/pugv/app/web/season/page") {
            url {
                parameters.append("mid", mid.toString())
                parameters.append("pn", "1")
                parameters.append("ps", "10")
            }
            header("Referer", "https://space.bilibili.com/$mid")
        }
    }

    /** 批次L43：UP主空间公告（匿名可用，可能为空字符串） */
    suspend fun getSpaceNotice(mid: Long): Result<String> = runCatching {
        val resp: SpaceNoticeResponse = client.getSafe("$API_BASE/x/space/notice") {
            url { parameters.append("mid", mid.toString()) }
            header("Referer", "https://space.bilibili.com/$mid")
        }
        if (resp.code == 0) resp.data.orEmpty() else error(resp.message.ifEmpty { "公告获取失败" })
    }

    /** 批次L40：播放页实时在线人数（匿名可用） */
    suspend fun getOnlineTotal(bvid: String, cid: Long): Result<Long> = runCatching {
        val resp: OnlineTotalResponse = client.getSafe("$API_BASE/x/player/online/total") {
            url {
                parameters.append("bvid", bvid)
                parameters.append("cid", cid.toString())
            }
            header("Referer", "https://www.bilibili.com")
        }
        if (resp.code == 0) (resp.data?.total ?: resp.data?.count ?: "0").toLongOrNull() ?: 0L
        else error(resp.message.ifEmpty { "在线人数获取失败" })
    }

    /** 批次L38：UP主代表作（匿名可用，精选置顶，最多3条） */
    suspend fun getMasterpiece(mid: Long): Result<List<MasterpieceArc>> = runCatching {
        val resp: MasterpieceResponse = client.getSafe("$API_BASE/x/space/masterpiece") {
            url { parameters.append("vmid", mid.toString()) }
            header("Referer", "https://space.bilibili.com/$mid")
        }
        if (resp.code == 0) resp.data else error(resp.message.ifEmpty { "代表作获取失败" })
    }

    /** 观看历史（cursor 分页，需登录）。max/view_at 取上一页 cursor 传回 */
    suspend fun getHistoryCursor(
        max: Long = 0L,
        viewAt: Long = 0L,
        ps: Int = 20
    ): Result<HistoryResponse> = runCatching {
        client.getSafe("$API_BASE/x/web-interface/history/cursor") {
            url {
                parameters.append("ps", ps.toString())
                if (max > 0) parameters.append("max", max.toString())
                if (viewAt > 0) parameters.append("view_at", viewAt.toString())
            }
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 稍后再看列表 /x/v2/history/toview（需登录） */
    suspend fun getToView(): Result<ToViewResponse> = runCatching {
        client.getSafe("$API_BASE/x/v2/history/toview") {
            header("Referer", "https://www.bilibili.com")
        }
    }

    /** 添加稍后再看（csrf） */
    suspend fun addToView(aid: Long): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "$API_BASE/x/v2/history/toview/add",
            mapOf("aid" to aid.toString())
        )
        requireWriteOk(resp)
    }

    /** 删除稍后再看（csrf） */
    suspend fun delToView(aid: Long): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "$API_BASE/x/v2/history/toview/del",
            mapOf("aid" to aid.toString())
        )
        requireWriteOk(resp)
    }

    /**
     * 审核268：清空稍后再看（需登录）。
     * 接口 /x/v2/history/toview/clear（匿名访问返回 200 但 code != 0）。
     */
    suspend fun clearToView(): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "$API_BASE/x/v2/history/toview/clear",
            emptyMap()
        )
        requireWriteOk(resp)
    }

    /** 审核轮146：一键三连（点赞+投币+收藏，需登录） */
    suspend fun likeCoinFav(aid: Long): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "$API_BASE/x/web-interface/archive/like/triple",
            mapOf("aid" to aid.toString())
        )
        requireWriteOk(resp)
    }

    /** 删除单条历史（csrf）。kid = business:oid，如 archive:123456 */
    suspend fun delHistory(kid: String): Result<Boolean> = runCatching {
        val resp: String = BiliHttpClient.postAuthForm(
            "$API_BASE/x/v2/history/delete",
            mapOf("kid" to kid)
        )
        requireWriteOk(resp)
    }
    
    // ========== 私聊会话（批次：私聊） ==========

    /** 私聊会话列表（Web API，需登录） */
    suspend fun getMySessions(endTs: Long = 0L): Result<SessionListResponse> = runCatching {
        val resp: String = client.get("$API_BASE/bili-api/v1/web/session/list") {
            url {
                parameters.append("session_type", "1")
                parameters.append("group_id", "0")
                parameters.append("build", "0")
                parameters.append("mobi_app", "web")
                if (endTs > 0) parameters.append("end_ts", endTs.toString())
            }
            header("Referer", "https://message.bilibili.com/")
        }.bodyAsText()
        // 审核275：该路由实测已下线（HTTP 404 返回 HTML）。
        // 原来 body() 直接反序列化 HTML 会抛 "Expected BEGIN_OBJECT"。
        if (resp.trimStart().startsWith("<")) {
            error("B站已下线私信网页接口")
        }
        BiliHttpClient.json.decodeFromString<SessionListResponse>(resp)
    }

    /** 私聊消息记录（talker = 对方 mid） */
    suspend fun fetchSessionMsgs(talkerId: Long, sessionTs: Long = 0L): Result<SessionMsgsResponse> = runCatching {
        client.getSafe("$API_BASE/bili-api/v1/web/session/poll") {
            url {
                parameters.append("talker_id", talkerId.toString())
                parameters.append("session_type", "1")
                parameters.append("build", "0")
                parameters.append("web_location", "0")
                if (sessionTs > 0) parameters.append("session_ts", sessionTs.toString())
            }
            header("Referer", "https://message.bilibili.com/")
        }
    }

    /** 发送私聊消息（csrf）；sender_uid = 自己 mid */
    suspend fun sendPrivateMsg(senderUid: Long, receiverId: Long, content: String): Result<Boolean> = runCatching {
        val payload = org.json.JSONObject().put("content", content).toString()
        val resp: String = BiliHttpClient.postAuthForm(
            "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg",
            linkedMapOf(
                "msg[sender_uid]" to senderUid.toString(),
                "msg[receiver_id]" to receiverId.toString(),
                "msg[receiver_type]" to "1",
                "msg[msg_type]" to "1",
                "msg[msg_status]" to "0",
                "msg[content]" to payload,
                "msg[timestamp]" to (System.currentTimeMillis() / 1000).toString(),
                "msg[new_device_token]" to "",
                "from_firework" to "0",
                "build" to "0",
                "mobi_app" to "web"
            )
        )
        requireWriteOk(resp)
    }

    /** 发送私聊图片消息（msg_type=2，content 为 JSON {"url":...,"width":...,"height":...,"image_type":0}） */
    suspend fun sendPrivateImage(senderUid: Long, receiverId: Long, url: String, width: Int, height: Int): Result<Boolean> = runCatching {
        val payload = org.json.JSONObject()
            .put("url", url)
            .put("width", width)
            .put("height", height)
            .put("image_type", 0)
            .toString()
        val resp: String = BiliHttpClient.postAuthForm(
            "https://api.vc.bilibili.com/web_im/v1/web_im/send_msg",
            linkedMapOf(
                "msg[sender_uid]" to senderUid.toString(),
                "msg[receiver_id]" to receiverId.toString(),
                "msg[receiver_type]" to "1",
                "msg[msg_type]" to "2",
                "msg[msg_status]" to "0",
                "msg[content]" to payload,
                "msg[timestamp]" to (System.currentTimeMillis() / 1000).toString(),
                "msg[new_device_token]" to "",
                "from_firework" to "0",
                "build" to "0",
                "mobi_app" to "web"
            )
        )
        requireWriteOk(resp)
    }

    // ========== 表情包（批次：表情包） ==========

    /** 大表情包详情 /x/emote/package（1=小黄脸，244=小黄脸动态） */
    suspend fun getEmotePackage(id: Long): Result<EmotePackageResponse> = runCatching {
        client.getSafe("$API_BASE/x/emote/package") {
            url { parameters.append("id", id.toString()) }
            header("Referer", "https://www.bilibili.com")
        }
    }

    // ========== WBI Signature ==========
    // WBI 签名统一走 WbiSigner.sign()（nav 取 key 缓存 4h + MIXIN_KEY_ENC_TAB 置换 + md5）
}
