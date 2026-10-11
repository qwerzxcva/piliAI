package com.example.piliai.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
        val images: Images = Images(),
        val rating: Rating = Rating()
    ) {
        val title: String get() = nameCn.ifBlank { name }
        val cover: String get() = images.large.ifBlank { images.common }
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
}
