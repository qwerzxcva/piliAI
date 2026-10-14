package com.example.piliai.data.remote

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 审核290：瞬时风控（-352/-799/-412）的最大重试次数 */
const val MAX_TRANSIENT_RISK_RETRIES = 2

/**
 * 审核288/290：安全的 JSON GET——风控 HTML 拦截 + 瞬时风控重试。
 *
 * 与 HttpClient.get 签名保持兼容（第一个位置参数是 path，尾随 lambda 是
 * 请求配置）。调用点把 `client.get(path) { ... }.body()` 改成
 * `client.getSafe(path) { ... }` 即可。
 *
 * 审核290 补重试：实测 B 站对 -352/-799 的拒绝**常常是瞬时的**——同一个
 * ranking/v2 请求，第一次 -352、几秒后就是 code=0 返回 100 条数据。
 * HttpRequestRetry 只覆盖 5xx 和 IO 异常，覆盖不到「HTTP 200 + code=-352」
 * 这种形态。这里补上：命中瞬时风控码就退避重试，避免一次抖动就整页失败。
 */
suspend inline fun <reified T : Any> HttpClient.getSafe(
    path: String,
    noinline block: HttpRequestBuilder.() -> Unit = {}
): T = withContext(Dispatchers.IO) {
    var attempt = 0
    var result: T? = null
    while (result == null) {
        val text = get(path) { block() }.bodyAsText()
        if (text.trimStart().startsWith("<")) {
            error("B站返回风控页面（非 JSON），请稍后重试")
        }
        val code = peekJsonCode(text)
        if (code != null && isTransientRiskCode(code) && attempt < MAX_TRANSIENT_RISK_RETRIES) {
            attempt++
            // 退避：第一次 800ms，之后 1600ms（B 站建议请求间隔 >= 1s）
            kotlinx.coroutines.delay(if (attempt == 1) 800L else 1600L)
            continue
        }
        result = BiliHttpClient.json.decodeFromString<T>(text)
    }
    result
}

/**
 * 审核290：识别「瞬时风控」错误码。
 *
 * 实测发现 B 站对 -352/-799 的返回**不总是持久风控**：同一个 ranking/v2
 * 请求，第一次 -352、几秒后重试就是 code=0 且返回 100 条数据。容器/弱网
 * 环境下这种瞬时拒绝很常见。
 */
fun isTransientRiskCode(code: Int): Boolean = code == -352 || code == -799 || code == -412

/** 从 JSON 文本里快速取 code 字段（解析失败或字段缺失返回 null，不抛异常） */
fun peekJsonCode(text: String): Int? {
    if (!text.contains("\"code\"")) return null
    return try {
        val obj = org.json.JSONObject(text)
        if (obj.has("code")) obj.optInt("code") else null
    } catch (_: Exception) {
        null
    }
}

object BiliHttpClient {
    // 共享单例：避免每次解析都新建 Json 实例（性能 + 统一容错策略）
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true   // 审核4：null/越界值回退默认值，防线上字段突变崩溃
        explicitNulls = false
    }
    
    val client = HttpClient(OkHttp) {
        // Enforce credential boundaries on every actual network hop.
        followRedirects = false
        engine {
            config {
                followRedirects(true)
                followSslRedirects(false)
                addNetworkInterceptor { chain ->
                    val original = chain.call().request().url
                    val request = chain.request()
                    val destination = request.url
                    val originalTrusted = original.scheme == "https" &&
                        original.port == 443 &&
                        (original.host == "bilibili.com" ||
                            original.host.endsWith(".bilibili.com"))
                    val destinationTrusted = destination.scheme == "https" &&
                        destination.port == 443 &&
                        (destination.host == "bilibili.com" ||
                            destination.host.endsWith(".bilibili.com"))
                    val builder = request.newBuilder().removeHeader("Cookie")
                    if (originalTrusted && destinationTrusted) {
                        val cookie = AccountSession.cookieHeader()
                        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
                    } else {
                        builder.removeHeader("Authorization")
                        builder.removeHeader("Proxy-Authorization")
                    }
                    chain.proceed(builder.build())
                }
            }
        }
        install(ContentNegotiation) {
            json(json)
        }
        // 审核31：请求级超时（弱网下防挂死）——总 30s，连接 15s，socket 20s
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        // 审核31：幂等 GET 失败自动重试（网络抖动/风控瞬时），其余方法不重试
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 2)
            retryOnExceptionIf { _, cause -> cause is java.io.IOException }
            exponentialDelay(base = 1.5, maxDelayMs = 4_000)
            retryIf(maxRetries = 2) { _, resp -> resp.status.value in 500..599 }
        }
        
        defaultRequest {
            url("https://api.bilibili.com")
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Accept, "application/json")
            header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36")
        }
    }
    
    suspend inline fun <reified T : Any> get(path: String, noinline block: HttpRequestBuilder.() -> Unit = {}): T = withContext(Dispatchers.IO) {
        client.get(path) {
            block()
        }.body()
    }

    suspend inline fun <reified T : Any> post(path: String, body: Any? = null, noinline block: HttpRequestBuilder.() -> Unit = {}): T = withContext(Dispatchers.IO) {
        client.post(path) {
            if (body != null) {
                setBody(body)
            }
            block()
        }.body()
    }

    suspend inline fun <reified T : Any> postForm(path: String, form: Map<String, String>): T = withContext(Dispatchers.IO) {
        client.submitForm(
            url = path,
            formParameters = Parameters.build {
                form.forEach { (key, value) -> append(key, value) }
            }
        ).body()
    }

    /**
     * 带登录态的表单 POST（写操作：点赞/投币/收藏等）。
     * B站写操作统一要求：SESSDATA cookie（CookieJar 自动带）+ csrf=bili_jct 表单字段 + Referer。
     */
    suspend inline fun <reified T : Any> postAuthForm(
        path: String,
        form: Map<String, String>,
        noinline block: HttpRequestBuilder.() -> Unit = {}
    ): T = withContext(Dispatchers.IO) {
        val csrf = AccountSession.snapshot()["bili_jct"].orEmpty()
        client.post(path) {
            header("Referer", "https://www.bilibili.com")
            setBody(FormDataContent(Parameters.build {
                form.forEach { (k, v) -> append(k, v) }
                if (csrf.isNotEmpty()) append("csrf", csrf)
            }))
            block()
        }.body()
    }
}
