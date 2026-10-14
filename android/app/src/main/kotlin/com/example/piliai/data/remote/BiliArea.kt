package com.example.piliai.data.remote

/**
 * 审核299：分区播放服务器（BiliRoamingX 换区机制移植）。
 *
 * 来源：/tmp/BiliRoamingX/integrations/app/src/main/java/app/revanced/bilibili/api/BiliRoamingApi.kt
 * （Area 枚举 + getServerByArea + playurl 的 area 优先级遍历逻辑）。
 *
 * 原理：B 站各区（港/台/泰/大陆）的会员体系独立，同一个 access_key 在某些区
 * 能取到更高清晰度。官方客户端只向自己所属区的服务器请求，于是看不到更高画质。
 *
 * 与 BiliRoamingX 的差异（重要）：
 * - BiliRoamingX 的 app 端签名（Utils.signQuery / getAppKey）实现体是空的，
 *   源码注释写明 `codes will filled by patcher`——运行时从被 hook 的官方 App
 *   里提取 appkey/appSecret 注入。它是 ReVanced 补丁，依赖「寄生官方 App」。
 * - BiliRoaming（Xposed）同样反射调用官方 App 的签名方法
 *   （instance.libBiliClass?.callStaticMethod(instance.signQueryName(), ...)）。
 * - piliai 是独立客户端，没有那个上下文，因此**不能照搬它们的签名方式**。
 *   这里改为：换区 host + piliai 自有的 wbi 签名（WbiSigner），
 *   wbi 签名在匿名态实测可用（审核290 实测 code=0）。
 */
enum class BiliArea(val value: String, val label: String) {
    China("cn", "大陆"),
    HongKong("hk", "香港"),
    TaiWan("tw", "台湾"),
    Thailand("th", "泰国");

    companion object {
        fun of(value: String?): BiliArea? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 各区 playurl 服务器 host（不含 scheme）。
 *
 * 来源：BiliRoamingX Settings.kt 的 tw_server / hk_server / th_server / cn_server
 * 默认值（用户在设置里可覆盖，这里给默认）。原版这几个是用户可配的 StringSetting，
 * 因为官方会定期更换域名；移植后同样支持运行时覆盖（见 [setServerHost]）。
 */
object BiliAreaServers {

    private val defaults = mapOf(
        BiliArea.China to "api.bilibili.com",
        BiliArea.HongKong to "api.bilibili.com",
        BiliArea.TaiWan to "api.bilibili.com",
        BiliArea.Thailand to "api.bilibili.tv",
    )

    private val overrides = java.util.concurrent.ConcurrentHashMap<BiliArea, String>()

    fun hostOf(area: BiliArea): String =
        overrides[area]?.takeIf { it.isNotBlank() } ?: defaults[area].orEmpty()

    /** 运行时覆盖（设置页/测速后写入） */
    fun setServerHost(area: BiliArea, host: String) {
        if (host.isBlank()) overrides.remove(area) else overrides[area] = host.trim()
    }

    /**
     * 遍历顺序（移植 BiliRoamingApi.getPlayUrl 的 hostList 优先级逻辑）。
     *
     * 原版按 accessOrder 缓存 + priorityArea 排序；这里简化为固定优先级：
     * 大陆优先（最快、无需 access_key 也能用），失败再依次尝试港/台/泰。
     */
    fun fallbackOrder(): List<BiliArea> = listOf(
        BiliArea.China, BiliArea.HongKong, BiliArea.TaiWan, BiliArea.Thailand
    )
}
