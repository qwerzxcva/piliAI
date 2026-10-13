package com.example.piliai.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bangumi 公开资料接口，不附带 Bilibili Cookie 或账号令牌。 */
object BangumiApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = HttpClient(OkHttp) {
        expectSuccess = true
        followRedirects = false
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
    }

    @Serializable
    private data class SearchFilter(val type: List<Int> = listOf(2))

    @Serializable
    private data class SearchRequest(
        val keyword: String,
        val sort: String,
        val filter: SearchFilter
    )

    @Serializable
    data class Subject(
        val id: Long = 0,
        val name: String = "",
        @SerialName("name_cn") val nameCn: String = "",
        val summary: String = "",
        val date: String = "",
        @SerialName("eps") val episodeCount: Int = 0,
        /** 审核240：数据库章节数（追番「最新集数」首选；eps 是 wiki 解析值，可能为 0） */
        @SerialName("total_episodes") val totalEpisodes: Int = 0,
        val platform: String = "",
        val type: Int = 0,
        val images: Images = Images(),
        val rating: Rating = Rating()
    ) {
        val title: String get() = nameCn.ifBlank { name }
        val cover: String get() = images.large.ifBlank { images.common }

        /** 追番最新集数：优先 DB 章节数，回退 wiki 集数 */
        val bestEpisodeCount: Int get() = if (totalEpisodes > 0) totalEpisodes else episodeCount
    }

    @Serializable
    data class Images(val large: String = "", val common: String = "")

    @Serializable
    data class Rating(val score: Double = 0.0, val rank: Int = 0, val total: Int = 0)

    @Serializable
    private data class SearchResponse(val data: List<Subject> = emptyList())

    /** 按关键字找最匹配的条目，找不到返回 null。 */
    suspend fun searchSubject(keyword: String): Subject? = searchSubjects(keyword).firstOrNull()

    /** 动画类型候选，供订阅条目挑 Bangumi 介绍。 */
    suspend fun searchSubjects(keyword: String, limit: Int = 8): List<Subject> = withContext(Dispatchers.IO) {
        require(keyword.isNotBlank())
        val text = client.post("https://api.bgm.tv/v0/search/subjects") {
            parameter("limit", limit.coerceIn(1, 20))
            contentType(ContentType.Application.Json)
            header("User-Agent", "QWERZXCVA/piliAI (https://github.com/qwerzxcva/piliai)")
            setBody(json.encodeToString(SearchRequest(keyword.trim(), "match", SearchFilter())))
        }.bodyAsText()
        json.decodeFromString<SearchResponse>(text).data.filter { it.id > 0L }
    }

    /** 条目详情：封面、评分、简介、开播日期。 */
    suspend fun subject(id: Long): Subject = withContext(Dispatchers.IO) {
        require(id > 0L)
        val text = client.get("https://api.bgm.tv/v0/subjects/$id") {
            header("User-Agent", "QWERZXCVA/piliAI (https://github.com/qwerzxcva/piliai)")
        }.bodyAsText()
        json.decodeFromString(text)
    }

    // ==================== 章节（剧集）接口，审核261 ====================

    @Serializable
    data class EpisodePage(
        val total: Int = 0,
        val limit: Int = 0,
        val offset: Int = 0,
        val data: List<Episode> = emptyList()
    )

    /**
     * 章节（剧集）条目。
     *
     * 契约来自 bangumi openapi v0.yaml 的 /v0/episodes，并用 api.bgm.tv 实测
     * （subject_id=265 返回 30 条 EVA 剧集，字段齐全）。
     * - [ep] / [sort]：集数与排序（sort 更可靠，部分条目 ep 为 0）
     * - [type]：0=正片 1=SP 2=OP 3=ED 4=预告/宣传片（官方 subject_type 枚举）
     */
    @Serializable
    data class Episode(
        val id: Long = 0L,
        val name: String = "",
        @SerialName("name_cn") val nameCn: String? = null,
        val sort: Int = 0,
        val ep: Int = 0,
        val airdate: String? = null,
        val duration: String? = null,
        val type: Int = 0,
        val desc: String? = null,
        @SerialName("duration_seconds") val durationSeconds: Int = 0
    ) {
        val displayTitle: String get() = nameCn?.takeIf { it.isNotBlank() } ?: name

        /** 排序号优先用 sort，回退 ep */
        val order: Int get() = if (sort > 0) sort else ep

        /** 类型标签（官方枚举） */
        val typeLabel: String
            get() = when (type) {
                1 -> "SP"
                2 -> "OP"
                3 -> "ED"
                4 -> "预告"
                else -> ""
            }
    }

    /**
     * 条目的章节列表。
     *
     * @param subjectId Bangumi subject_id
     * @param limit 单页条数（上限 200，见 openapi；0 表示服务端默认 100）
     * @param offset 分页偏移
     */
    suspend fun episodes(
        subjectId: Long,
        limit: Int = 100,
        offset: Int = 0
    ): EpisodePage = withContext(Dispatchers.IO) {
        require(subjectId > 0L)
        val text = client.get("https://api.bgm.tv/v0/episodes") {
            parameter("subject_id", subjectId)
            parameter("limit", limit.coerceIn(0, 200))
            parameter("offset", offset.coerceAtLeast(0))
            header("User-Agent", "QWERZXCVA/piliAI (https://github.com/qwerzxcva/piliai)")
        }.bodyAsText()
        json.decodeFromString(text)
    }

    // ==================== 收藏（追番）接口，审核240 ====================
    // 契约依据：bangumi openapi v0.yaml + api.bgm.tv 实测（见子代理调研报告）。
    // 关键实测结论：
    //  - `GET/POST/PATCH /v0/users/-/collections...` 的 `-` 形态**必须带 Bearer**；
    //  - 修改收藏用 **POST**（不存在则创建）；PUT 路由不存在（实测 404）；
    //  - 列表接口 limit 上限 100（101 → 400），offset 越界返回 400，只能靠 total 收手；
    //  - 收藏类型编码 1=想看 2=看过 3=在看 4=搁置 5=抛弃（顺序反常，勿按直觉排）；
    //  - 动画条目不要传 ep_status（官方明确只用于书籍）。

    @Serializable
    data class CollectionPage(
        val total: Int = 0,
        val limit: Int = 0,
        val offset: Int = 0,
        val data: List<UserCollection> = emptyList()
    )

    @Serializable
    data class UserCollection(
        @SerialName("subject_id") val subjectId: Long = 0L,
        @SerialName("subject_type") val subjectType: Int = 0,
        val type: Int = 0,
        val rate: Int = 0,
        @SerialName("ep_status") val epStatus: Int = 0,
        val comment: String? = null,
        val tags: List<String> = emptyList(),
        @SerialName("private") val isPrivate: Boolean = false,
        @SerialName("updated_at") val updatedAt: String = "",
        val subject: SlimSubject? = null
    )

    @Serializable
    data class SlimSubject(
        val id: Long = 0L,
        val type: Int = 0,
        val name: String = "",
        @SerialName("name_cn") val nameCn: String? = null,
        @SerialName("short_summary") val shortSummary: String? = null,
        val date: String? = null,
        val eps: Int = 0,
        @SerialName("collection_total") val collectionTotal: Int = 0,
        val score: Double = 0.0,
        val rank: Int = 0,
        val images: Images? = null
    ) {
        val displayTitle: String get() = nameCn?.takeIf { it.isNotBlank() } ?: name
    }

    @Serializable
    private data class CollectionModifyRequest(
        val type: Int,
        val rate: Int = 0,
        val comment: String? = null,
        @SerialName("private") val isPrivate: Boolean = false
    )

    @Serializable
    private data class EpisodeCollectionPatchRequest(
        @SerialName("episode_id") val episodeIds: List<Long>,
        val type: Int
    )

    /**
     * 我的收藏列表（`-` = 当前用户，必须登录）。
     *
     * @param type 收藏类型筛选（1-5），null=全部
     * @param subjectType 条目类型（2=动画），null=全部
     * @param offset 分页偏移；越界会 400，调用方需靠 total 自己收手
     */
    suspend fun myCollections(
        type: Int? = null,
        subjectType: Int? = null,
        limit: Int = 100,
        offset: Int = 0
    ): CollectionPage = withContext(Dispatchers.IO) {
        require(BangumiSession.isLogin) { "请先登录 Bangumi" }
        val text = client.get("https://api.bgm.tv/v0/users/-/collections") {
            parameter("limit", limit.coerceIn(1, 100))
            parameter("offset", offset.coerceAtLeast(0))
            type?.let { parameter("type", it) }
            subjectType?.let { parameter("subject_type", it) }
            header("User-Agent", "QWERZXCVA/piliAI (https://github.com/qwerzxcva/piliai)")
            withBangumiAuth()
        }.bodyAsText()
        json.decodeFromString(text)
    }

    /** 我的全部收藏（自动翻页，靠 total 收手；单页失败即整体失败） */
    suspend fun allMyCollections(
        type: Int? = null,
        subjectType: Int? = null
    ): List<UserCollection> = withContext(Dispatchers.IO) {
        val out = mutableListOf<UserCollection>()
        var offset = 0
        while (true) {
            val page = myCollections(type, subjectType, limit = 100, offset = offset)
            out.addAll(page.data)
            // total 为 0 但仍有数据时按数据长度推进，避免死循环
            val total = if (page.total > 0) page.total else out.size
            if (page.data.isEmpty() || out.size >= total) break
            offset = out.size
        }
        out
    }

    /**
     * 修改收藏状态（不存在则创建）。
     * 官方无删除接口，因此「取消收藏」只清本地，不动 Bangumi。
     */
    suspend fun setCollection(
        subjectId: Long,
        type: Int,
        rate: Int = 0,
        comment: String? = null,
        isPrivate: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        require(subjectId > 0L) { "subjectId 必须大于 0" }
        require(type in 1..5) { "收藏类型必须在 1..5" }
        val response = client.post("https://api.bgm.tv/v0/users/-/collections/$subjectId") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "QWERZXCVA/piliAI (https://github.com/qwerzxcva/piliai)")
            withBangumiAuth()
            setBody(
                json.encodeToString(
                    CollectionModifyRequest(
                        type = type,
                        rate = rate.coerceIn(0, 10),
                        comment = comment,
                        isPrivate = isPrivate
                    )
                )
            )
        }
        // 成功是 204 No Content；Ktor 的 expectSuccess 已保证非 2xx 抛异常
        response.status.value in 200..299
    }

    /**
     * 批量改剧集收藏状态。
     * @param type 剧集级编码：0=未收藏 1=想看 2=看过 3=抛弃（与条目级不同！）
     */
    suspend fun setEpisodeCollections(
        subjectId: Long,
        episodeIds: List<Long>,
        type: Int
    ): Boolean = withContext(Dispatchers.IO) {
        require(subjectId > 0L && episodeIds.isNotEmpty()) { "参数不完整" }
        require(type in 0..3) { "剧集收藏类型必须在 0..3" }
        val response = client.patch("https://api.bgm.tv/v0/users/-/collections/$subjectId/episodes") {
            contentType(ContentType.Application.Json)
            header("User-Agent", "QWERZXCVA/piliAI (https://github.com/qwerzxcva/piliai)")
            withBangumiAuth()
            setBody(json.encodeToString(EpisodeCollectionPatchRequest(episodeIds, type)))
        }
        response.status.value in 200..299
    }
}
