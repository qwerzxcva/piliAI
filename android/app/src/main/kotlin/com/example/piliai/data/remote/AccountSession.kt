package com.example.piliai.data.remote

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 Cookie 存储（用于 B站登录 session：SESSDATA / bili_jct / DedeUserID ...）
 *
 * BiliHttpClient 用 OkHttp 引擎，但为了让登录态能在「无 Context 的仓储层」直接注入请求头，
 * 这里维护一个内存 cookie 表，并由 AuthInterceptor 统一挂到每次 B站请求上。
 * 持久化由 LoginRepository 落到 Room 的 LoginAccountEntity。
 */
object AccountSession {

    private val cookies = ConcurrentHashMap<String, String>()

    /** Cookie 白名单：只存登录相关字段，避免把无关 cookie 带进请求 */
    private val ALLOWED = setOf(
        "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
        "sid", "buvid3", "buvid4"
    )

    @Volatile
    var isLogin: Boolean = false

    /** csrf token（写操作用） */
    val biliJct: String get() = cookies["bili_jct"] ?: ""

    @Volatile
    var mid: Long = 0L
        private set

    /** 解析 Set-Cookie 头（形如 "SESSDATA=xxx; Path=/; ..."）并存入内存 */
    fun applySetCookies(headerValues: List<String>) {
        headerValues.forEach { raw ->
            val first = raw.substringBefore(';')
            val name = first.substringBefore('=', "").trim()
            val value = first.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty() && name in ALLOWED) {
                cookies[name] = value
            }
        }
        if (cookies["SESSDATA"]?.isNotEmpty() == true) {
            isLogin = true
        }
        cookies["DedeUserID"]?.toLongOrNull()?.let { mid = it }
    }

    /** 直接从 cookie 串恢复（从数据库读回时用），格式 "a=1; b=2" */
    fun restore(cookieString: String) {
        cookies.clear()
        cookieString.split(';').forEach { pair ->
            val k = pair.substringBefore('=', "").trim()
            val v = pair.substringAfter('=', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) cookies[k] = v
        }
        isLogin = cookies["SESSDATA"]?.isNotEmpty() == true
        cookies["DedeUserID"]?.toLongOrNull()?.let { mid = it }
    }

    /** 导出为 cookie 头值 */
    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun snapshot(): Map<String, String> = cookies.toMap()

    fun clear() {
        cookies.clear()
        isLogin = false
        mid = 0L
    }

    /** buvid3 是否已就绪（搜索/点赞/投币等风控接口强依赖） */
    fun hasBuvid3(): Boolean = !cookies["buvid3"].isNullOrEmpty()

    /**
     * 审核291：从持久化存储恢复 buvid（App 启动时调用）。
     *
     * 原来 buvid 只在内存里，进程一杀就丢，重启后 B 站视为「新设备」——
     * 等于每次冷启动都重新攒风控信任。启动时先把上次的值读回来。
     */
    suspend fun restoreBuvidFromStorage() {
        if (hasBuvid3()) return
        runCatching {
            val storage = com.example.piliai.utils.StorageManager.getInstance(
                com.example.piliai.AppContext.get()
            )
            val b3 = storage.buvid3Flow.first()
            if (b3.isNotEmpty()) {
                cookies["buvid3"] = b3
                storage.buvid4Flow.first().takeIf { it.isNotEmpty() }?.let {
                    cookies["buvid4"] = it
                }
            }
        }
    }

    /**
     * 匿名获取 buvid3/buvid4（GET /x/frontend/finger/spi，返回 b_3/b_4）。
     *
     * 审核291：拿到后**回写持久化存储**，下次冷启动直接复用同一指纹。
     */
    suspend fun ensureBuvid(): Boolean {
        if (hasBuvid3()) return true
        return runCatching {
            val json = BiliHttpClient.client.get("https://api.bilibili.com/x/frontend/finger/spi") {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            }.bodyAsText()
            val obj = org.json.JSONObject(json)
            val data = obj.optJSONObject("data") ?: return@runCatching false
            val b3 = data.optString("b_3")
            val b4 = data.optString("b_4")
            if (b3.isNotEmpty()) cookies["buvid3"] = b3
            if (b4.isNotEmpty()) cookies["buvid4"] = b4
            val ok = hasBuvid3()
            if (ok) {
                // 回写持久化：重启后仍是同一设备指纹
                runCatching {
                    com.example.piliai.utils.StorageManager.getInstance(
                        com.example.piliai.AppContext.get()
                    ).saveBuvid(b3, b4)
                }
            }
            ok
        }.getOrDefault(false)
    }
}
