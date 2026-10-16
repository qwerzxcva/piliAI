package com.example.piliai.danmaku

import com.example.piliai.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import android.util.Base64 as AndroidBase64
import kotlin.random.Random

/**
 * 弹弹play (DanDan) 弹幕源 API 客户端。
 *
 * 移植自 Flutter 版 `services/dandan/client.dart` + `api.dart`。
 *
 * 签名算法（来自 DanDanPlay 开放平台文档）：
 * ```
 * signature = base64(sha256(appId + timestamp + path + apikey))
 * ```
 *
 * 使用：在 `build.gradle.kts` 的 `buildConfigField` 或 `BuildConfig` 里注入：
 * ```kotlin
 * buildConfigField("String", "DANDAN_API_APPID", "\"<your-app-id>\"")
 * buildConfigField("String", "DANDAN_API_KEY", "\"<your-api-key>\"")
 * ```
 * 未配置时所有请求返回空列表（不崩溃）。
 */
object DandanApi {

    private const val DOMAIN = "https://api.dandanplay.net"
    private const val COMMENT_PATH = "/api/v2/comment/"
    private const val SEARCH_EPISODES_PATH = "/api/v2/search/episodes"
    private const val BANGUMI_INFO_PATH = "/api/v2/bangumi/"

    /** 随机 User-Agent 池（仿 Kazumi，避免被裸机器人过滤） */
    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
    )

    private fun randomUa() = USER_AGENTS[Random.nextInt(USER_AGENTS.size)]

    /**
     * 是否已配置凭据（通过 BuildConfig 检查）。
     *
     * 审核320（真 bug）：此前这里只查凭据，完全不看用户的
     * 「弹弹play 弹幕源」开关——开关在设置页写了 DataStore，播放器
     * 却从不读，用户点下去没有任何效果。现在两者都要满足。
     *
     * 进程内缓存由 [syncEnabled] 维护：DataStore 是异步 Flow，而弹幕
     * 加载发生在 IO 协程里，不适合每次 runBlocking 读盘。
     */
    val isEnabled: Boolean get() = userEnabled && hasCredentials

    /** 仅凭据是否就绪（与用户开关无关） */
    val hasCredentials: Boolean get() =
        BuildConfig.DANDAN_API_APPID.isNotEmpty() && BuildConfig.DANDAN_API_KEY.isNotEmpty()

    /** 用户开关（进程内缓存，启动时由 MainApplication 预热） */
    @Volatile
    var userEnabled: Boolean = false

    /**
     * 从 DataStore 同步用户开关到进程内缓存。
     * 在 Application.onCreate 与设置页开关变更时调用。
     */
    fun syncEnabled(value: Boolean) {
        userEnabled = value
    }

    /** 供 UI 判断「为什么不能用」：未配置凭据 / 已关闭 */
    fun disabledReason(): String? = when {
        !hasCredentials -> "未配置弹弹play API 凭据"
        !userEnabled -> "弹弹play 弹幕源已关闭"
        else -> null
    }

    private val gson = com.google.gson.Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun get(path: String, query: Map<String, String>? = null): Map<String, Any>? {
        if (!isEnabled) return null
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val sig = generateSignature(path, timestamp)
        val url = buildUrl(DOMAIN + path, query)
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", randomUa())
            .addHeader("Referer", "")
            .addHeader("X-Auth", "1")
            .addHeader("X-AppId", BuildConfig.DANDAN_API_APPID)
            .addHeader("X-Timestamp", timestamp)
            .addHeader("X-Signature", sig)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                // 审核轮210：Gson 提为伴生常量（原先每次请求新建）
                gson.fromJson(body, Map::class.java) as? Map<String, Any>
            }
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 公开 API ====================

    /** 搜索番剧（按标题） */
    suspend fun searchAnime(title: String): List<DandanAnime> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val data = get(SEARCH_EPISODES_PATH, mapOf("anime" to title, "v2" to "true"))
            val animesJson = data?.get("animes") as? List<*>
            animesJson.orEmpty().mapNotNull { it as? Map<*, *> }
                .map { map ->
                    DandanAnime(
                        animeId = (map["animeId"] as? Number)?.toInt() ?: 0,
                        animeTitle = (map["animeTitle"] as? String) ?: "",
                        typeDescription = (map["typeDescription"] as? String) ?: "",
                    )
                }
        }

    /** 通过 bangumiId 获取集数列表 */
    suspend fun getEpisodes(bangumiId: Int): List<DandanEpisode> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val data = get("$BANGUMI_INFO_PATH$bangumiId")
            val bangumi = data?.get("bangumi") as? Map<*, *>
            val episodesJson = bangumi?.get("episodes") as? List<*>
            episodesJson.orEmpty().mapNotNull { it as? Map<*, *> }
                .map { map ->
                    DandanEpisode(
                        episodeId = (map["episodeId"] as? Number)?.toInt() ?: 0,
                        episodeTitle = (map["episodeTitle"] as? String) ?: "",
                    )
                }
        }

    /**
     * 获取指定集数的弹幕。
     *
     * @param episodeId DanDan 集数 ID（= bangumiId 拼接 4 位零填充集数，例如 bangumiId=12345, ep=3 → 123450003）
     */
    suspend fun getComments(episodeId: Int): List<DandanComment> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val data = get("$COMMENT_PATH$episodeId", mapOf("withRelated" to "true", "chConvert" to "0"))
            val commentsJson = data?.get("comments") as? List<*>
            commentsJson.orEmpty().mapNotNull { it as? Map<*, *> }
                .map { map ->
                    val parts = ((map["p"] as? String) ?: "").split(",")
                    DandanComment(
                        time = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
                        type = parts.getOrNull(1)?.toIntOrNull() ?: 1,
                        color = parts.getOrNull(2)?.toIntOrNull() ?: 0x756ABE,
                        source = parts.getOrNull(3) ?: "DanDan",
                        message = (map["m"] as? String) ?: "",
                    )
                }
        }

    /**
     * 通过 BGM.tv ID 获取弹幕（Kazumi 兼容流程）。
     * BGM.tv ID → DanDan bangumiId → episode lookup → comments。
     *
     * @param bgmId BGM.tv 番剧 ID
     * @param episode 1-based 集数
     */
    suspend fun getCommentsByBgmId(bgmId: Int, episode: Int): List<DandanComment> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // DanDan 的 episodeId 约定：bangumiId + 4 位零填充集数
            val episodeIdStr = "${bgmId}${episode.toString().padStart(4, '0')}"
            val episodeId = episodeIdStr.toIntOrNull()
            if (episodeId == null) emptyList() else getComments(episodeId)
        }

    // ==================== 内部工具 ====================

    private fun buildUrl(base: String, query: Map<String, String>?): String {
        if (query == null || query.isEmpty()) return base
        return "$base?${query.entries.joinToString("&") { "${it.key}=${it.value}" }}"
    }

    /**
     * 生成 X-Signature header 值。
     * 算法：base64(sha256(appId + timestamp + path + apikey))
     */
    private fun generateSignature(path: String, timestamp: String): String {
        val appId = BuildConfig.DANDAN_API_APPID
        val apiKey = BuildConfig.DANDAN_API_KEY
        val data = "$appId$timestamp$path$apiKey"
        val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
        return AndroidBase64.encodeToString(digest, AndroidBase64.NO_WRAP)
    }
}
