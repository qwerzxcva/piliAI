package com.example.piliai.data.repository

import com.example.piliai.AppContext
import com.example.piliai.database.SubscribeSourceEntity
import com.example.piliai.data.remote.SourceHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 订阅源解析（Animeko「添加订阅源链接」移植）
 *
 * 设计要点（审核角度）：
 * 1. 解析在 Dispatchers.IO，绝不在主线程做 XML/JSON 解析或网络。
 * 2. 全部异常在内部收敛为 Result.failure，调用方（ViewModel）统一展示错误，
 *    避免单个坏源导致订阅页整体崩溃。
 * 3. XmlPullParser 由 Android 自带 org.xmlpull 提供，不引入新依赖。
 * 4. 单源解析失败不影响其它源：ViewModel 层逐源 try，聚合成功的部分。
 */
object SubscribeParser {

    /**
     * 单个订阅源最多保留的条目数（审核轮2）
     *
     * 理由：野生订阅源可能有上万条历史条目，全量解析 + 全量 Upsert 会
     * 造成解析耗时、DB 膨胀、以及订阅页一次性渲染巨量卡片导致卡顿/ANR。
     * 订阅页本质是「最近更新」视图，截断到最近 500 条不影响可用性。
     */
    private const val MAX_ITEMS_PER_SOURCE = 500

    /**
     * 审核313：内联订阅源的 scheme 前缀。用户直接粘贴 JSON 内容（如 animeko
     * 的 exportedMediaSourceDataList）时，内容存到本地缓存，url 记为
     * `inline:<sha1>`，避免把一大段 JSON 当成 URL 去请求。
     */
    const val INLINE_SCHEME = "inline:"

    /** 内联内容缓存目录名 */
    private const val INLINE_DIR = "subscribe_inline"

    /**
     * 审核313：把用户粘贴的内容保存为内联源，返回 `inline:<hash>` 形式的 url。
     * 原样内容直接走 normalizeUrl 会被强加 https:// 前缀 → 报「链接无效」，
     * 这是「animeko 订阅源加不进来」的第二层原因（第一层是 parseJson
     * 不识别 animeko 格式，审核298 已修）。
     */
    fun saveInlineSource(content: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-1")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val dir = java.io.File(com.example.piliai.AppContext.get().filesDir, INLINE_DIR)
        if (!dir.exists()) dir.mkdirs()
        java.io.File(dir, "$hash.json").writeText(content)
        return INLINE_SCHEME + hash
    }

    /** 审核313：读源内容——`inline:` 走本地缓存，其余走网络 */
    internal suspend fun readSourceText(url: String): String {
        if (url.startsWith(INLINE_SCHEME)) {
            val hash = url.removePrefix(INLINE_SCHEME)
            val f = java.io.File(
                java.io.File(com.example.piliai.AppContext.get().filesDir, INLINE_DIR),
                "$hash.json"
            )
            if (!f.exists()) error("内联订阅源内容已丢失，请重新添加")
            return f.readText()
        }
        return SourceHttpClient.client.get(url).bodyAsText()
    }

    /** 解析出的条目（未落库） */
    data class ParsedItem(
        val title: String,
        val cover: String,
        val link: String,
        val desc: String,
        val pubAt: Long,
        val episode: String,
        /** Animeko 源类型（"rss"/"web-selector"）；普通源为 "" */
        val factoryId: String = ""
    )

    /** 解析结果：源名 + 封面 + 条目列表 */
    data class ParsedSource(
        val name: String,
        val cover: String,
        val items: List<ParsedItem>
    )

    /**
     * Animeko 媒体源配置里的一个「数据源」（调研实测格式，见
     * docs/animeko-source-format.md）
     *
     * 这类文件是**抓取器配置**，不是条目列表：每个源给出 name/iconUrl/
     * searchConfig，播放地址需运行时再按关键词搜索 + 选择器刮削。
     */
    data class AnimekoSource(
        val name: String,
        val iconUrl: String,
        val description: String,
        val factoryId: String,          // "web-selector" | "rss"
        val searchUrl: String           // 含 keyword 占位符；空=不支持搜索
    ) {
        /** rss 类型的源可直接用标准 RSS 解析出条目（其余需 CSS 引擎） */
        val isRss: Boolean
            get() = factoryId.equals("rss", ignoreCase = true)

        /** 是否支持关键词搜索（有 searchUrl 模板） */
        val canSearch: Boolean
            get() = searchUrl.isNotBlank()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 拉取并解析一个订阅源。
     * @param url 订阅源链接
     * @param hintType 用户/记录指定的类型；TYPE_BANGUMI 时先试 JSON 再回退 RSS
     */
    suspend fun fetch(
        url: String,
        hintType: Int = SubscribeSourceEntity.TYPE_BANGUMI
    ): Result<ParsedSource> = withContext(Dispatchers.IO) {
        runCatching {
            val text = SubscribeParser.readSourceText(url)
            if (text.isBlank()) error("订阅源返回为空")

            // Animeko 媒体源配置：优先识别。
            // 这类文件不是条目列表而是「数据源清单」，无法用下面的
            // RSS/JSON 条目解析路径处理，必须单独走 parseAnimekoSourceConfig。
            if (isAnimekoSourceConfig(text)) {
                val sources = parseAnimekoSourceConfig(text)
                if (sources.isEmpty()) error("Animeko 源配置为空或格式不符")
                // 转成条目：每个数据源作为一张卡片展示（名称+icon 封面+描述）
                // 链接用其 searchUrl（rss 源可用；web-selector 源无直链，
                // 故 link 留空并由 UI 标注"需搜索"）。
                val items = sources.map { s ->
                    ParsedItem(
                        title = s.name,
                        cover = s.iconUrl,
                        // link 存 searchUrl（rss 型可直接用；网页型留空→UI 引导搜索）
                        link = if (s.isRss) s.searchUrl else "",
                        desc = buildString {
                            append(if (s.isRss) "RSS 源" else "网页刮削源")
                            if (s.description.isNotBlank()) append(" · ${s.description}")
                            if (!s.isRss) append(" · 需关键词搜索")
                        },
                        pubAt = 0L,
                        // episode 存订阅配置文件 URL（后续两步要用它重取完整选择器配置）
                        episode = url,
                        factoryId = s.factoryId
                    )
                }
                return@runCatching ParsedSource(
                    name = "Animeko 媒体源",
                    cover = "",
                    items = items
                )
            }

            // 先按类型走，失败再自动嗅探，提升对野生源的兼容性
            val candidates = mutableListOf(hintType)
            listOf(
                SubscribeSourceEntity.TYPE_JSON,
                SubscribeSourceEntity.TYPE_RSS
            ).filter { it != hintType }.forEach { candidates.add(it) }

            // 审核252：读一次「RSS 只保留有集数信息」设置，供各候选解析共用。
            // 读不到默认 false（老行为：按标题猜集数，猜不到也保留条目）。
            val filterEpisodeInfoOnly = runCatching {
                com.example.piliai.utils.StorageManager
                    .getInstance(com.example.piliai.AppContext.get())
                    .rssEpisodeOnlyFlow.first()
            }.getOrDefault(false)

            var lastErr: Throwable? = null
            for (t in candidates) {
                val r = runCatching { parseByType(text, t, filterEpisodeInfoOnly) }
                if (r.isSuccess) {
                    val parsed = r.getOrThrow()
                    if (parsed.items.isNotEmpty()) return@runCatching parsed
                } else {
                    lastErr = r.exceptionOrNull()
                }
            }
            throw lastErr ?: error("订阅源解析失败：未识别格式或无条目")
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    private fun parseByType(
        text: String,
        type: Int,
        // 审核252：只保留能解析出集数的条目（animeko ContainsAnyEpisodeInfo）
        filterEpisodeInfoOnly: Boolean
    ): ParsedSource = when (type) {
        SubscribeSourceEntity.TYPE_JSON -> parseJson(text)
        SubscribeSourceEntity.TYPE_RSS -> parseRss(text, filterEpisodeInfoOnly)
        else -> parseRss(text, filterEpisodeInfoOnly) // Bangumi 源多暴露为 RSS/Atom
    }

    /** RSS 2.0 / Atom 解析（XmlPullParser，无需额外依赖） */
    private fun parseRss(
        text: String,
        // 审核252：只保留能解析出集数的条目（animeko
        // ContainsAnyEpisodeInfo）。默认 false 保持既有行为。
        filterEpisodeInfoOnly: Boolean = false
    ): ParsedSource {
        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(text))

        var sourceTitle = ""
        var sourceCover = ""
        val items = mutableListOf<ParsedItem>()

        // 当前条目字段
        var title = ""
        var link = ""
        var desc = ""
        var cover = ""
        var pubAt = 0L
        var episode = ""
        var enclosureUrl = ""

        var event = parser.eventType
        var inEntry = false
        var entryDepth = -1
        var sourceImageDepth = -1
        var isAtomEntry = false

        // Namespace-aware parsers expose local names, regardless of the chosen prefix.
        val mediaNamespace = "http://search.yahoo.com/mrss/"
        val itunesNamespace = "http://www.itunes.com/dtds/podcast-1.0.dtd"
        val atomNamespace = "http://www.w3.org/2005/Atom"

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name ?: ""
                    val namespace = parser.namespace ?: ""
                    val isCore = namespace.isEmpty() || namespace == atomNamespace ||
                        namespace == "http://purl.org/rss/1.0/"
                    when {
                        isCore && !inEntry && (tag.equals("item", true) || tag.equals("entry", true)) -> {
                            inEntry = true
                            entryDepth = parser.depth
                            isAtomEntry = tag.equals("entry", true)
                            title = ""; link = ""; desc = ""
                            cover = ""; pubAt = 0L; episode = ""
                            enclosureUrl = ""
                        }
                        inEntry && isCore && tag.equals("title", true) -> title = readText(parser)
                        inEntry && isCore && tag.equals("link", true) -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (href != null) {
                                when (parser.getAttributeValue(null, "rel")?.trim()?.lowercase() ?: "alternate") {
                                    "enclosure" -> if (href.isNotBlank()) enclosureUrl = href
                                    "alternate" -> if (href.isNotBlank()) link = href
                                    // self/edit/related links must not replace the entry URL.
                                }
                            } else if (!isAtomEntry) {
                                link = readText(parser)
                            }
                        }
                        inEntry && isCore && (tag.equals("description", true) ||
                            tag.equals("summary", true) ||
                            tag.equals("content", true)) -> desc = readText(parser)
                        inEntry && isCore && (tag.equals("pubDate", true) ||
                            tag.equals("published", true) ||
                            tag.equals("updated", true)) -> pubAt = parseDate(readText(parser))
                        inEntry && isCore && tag.equals("enclosure", true) -> {
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            if (url.isNotBlank()) enclosureUrl = url
                            // Only an explicitly image-typed enclosure is a cover.
                            if (parser.getAttributeValue(null, "type").orEmpty().startsWith("image/", true) &&
                                cover.isBlank()) cover = url
                        }
                        namespace == mediaNamespace && tag.equals("thumbnail", true) -> {
                            val url = parser.getAttributeValue(null, "url").orEmpty()
                            if (inEntry) {
                                if (url.isNotBlank()) cover = url
                            } else if (sourceCover.isBlank()) sourceCover = url
                        }
                        namespace == mediaNamespace && tag.equals("content", true) -> {
                            val isImage = parser.getAttributeValue(null, "medium").equals("image", true) ||
                                parser.getAttributeValue(null, "type").orEmpty().startsWith("image/", true)
                            if (isImage) {
                                val url = parser.getAttributeValue(null, "url").orEmpty()
                                if (inEntry && cover.isBlank()) cover = url
                                else if (!inEntry && sourceCover.isBlank()) sourceCover = url
                            }
                        }
                        namespace == itunesNamespace && tag.equals("image", true) -> {
                            val url = parser.getAttributeValue(null, "href")
                                ?: parser.getAttributeValue(null, "url") ?: ""
                            if (inEntry && cover.isBlank()) cover = url
                            else if (!inEntry && sourceCover.isBlank()) sourceCover = url
                        }
                        inEntry && isCore && tag.equals("guid", true) -> {
                            val guid = readText(parser)
                            if (link.isBlank()) link = guid
                        }
                        !inEntry && isCore && tag.equals("title", true) -> {
                            val value = readText(parser)
                            if (sourceTitle.isBlank()) sourceTitle = value
                        }
                        !inEntry && isCore && tag.equals("image", true) -> {
                            sourceImageDepth = parser.depth
                        }
                        !inEntry && isCore && tag.equals("url", true) &&
                            sourceImageDepth >= 0 && parser.depth == sourceImageDepth + 1 -> {
                            val url = readText(parser)
                            if (sourceCover.isBlank()) sourceCover = url
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (parser.depth == sourceImageDepth) sourceImageDepth = -1
                    if (inEntry && parser.depth == entryDepth &&
                        (tag.equals("item", true) || tag.equals("entry", true))) {
                        inEntry = false
                        val t = title.trim()
                        // 链接兜底：优先 enclosure（直链媒体），其次 link
                        val l = when {
                            enclosureUrl.isNotBlank() -> enclosureUrl
                            link.isNotBlank() -> link
                            else -> ""
                        }
                        // 审核252：animeko ContainsAnyEpisodeInfo —— 丢弃标题里
                        // 提取不出集数信息的条目（RssMediaSourceEngine.kt:157-159）。
                        // DMHY 之类源的 RSS 常混入 OVA/特典/合集，不过滤会让剧集
                        // 列表出现一堆没有集数标签的项。默认关闭：只按标题猜集数
                        // 的老行为对纯字幕组源更友好（它们的标题本就没有「第x集」）。
                        val ep = episode.ifBlank { guessEpisode(t) }
                        val keep = !filterEpisodeInfoOnly || ep.isNotBlank()
                        if (keep && (t.isNotBlank() || l.isNotBlank())) {
                            items.add(
                                ParsedItem(
                                    title = t.ifBlank { l },
                                    cover = cover.trim(),
                                    link = l,
                                    desc = desc.trim(),
                                    pubAt = pubAt,
                                    episode = ep
                                )
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return ParsedSource(
            name = sourceTitle.trim(),
            cover = sourceCover.trim(),
            // 审核轮2：截断，避免超大源导致解析/落库/渲染卡顿
            items = if (items.size > MAX_ITEMS_PER_SOURCE) {
                items.sortedByDescending { it.pubAt }.take(MAX_ITEMS_PER_SOURCE)
            } else items
        )
    }

    /**
     * 解析 Animeko 媒体源配置文件（用户给的 all.json / css.json / css1.json 均为此格式）
     *
     * 实测结构：
     * ```
     * { "exportedMediaSourceDataList": { "mediaSources": [
     *     { "factoryId":"web-selector"|"rss", "version":2,
     *       "arguments": { "name":..., "iconUrl":..., "description":...,
     *                      "searchConfig": { "searchUrl":"...{keyword}..." } } } ] } }
     * ```
     *
     * 重要：这里**没有可播放条目**。每个 mediaSources 元素是一个「数据源」，
     * 播放地址要运行时用关键词请求 searchUrl 再按选择器刮削。
     * 因此本函数返回的是源清单，不是 ParsedItem 列表。
     */
    fun parseAnimekoSourceConfig(text: String): List<AnimekoSource> {
        return runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val list = root["exportedMediaSourceDataList"]
                ?.jsonObject?.get("mediaSources")?.jsonArray
                ?: return@runCatching emptyList()

            list.mapNotNull { el ->
                val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                val factoryId = o["factoryId"]?.jsonPrimitive?.content ?: ""
                val args = runCatching { o["arguments"]?.jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                val name = args["name"]?.jsonPrimitive?.content?.trim()
                    ?: return@mapNotNull null
                val icon = args["iconUrl"]?.jsonPrimitive?.content ?: ""
                val desc = args["description"]?.jsonPrimitive?.content ?: ""
                // searchUrl 在 searchConfig 内，且含 keyword 占位符
                val searchUrl = runCatching {
                    args["searchConfig"]?.jsonObject?.get("searchUrl")?.jsonPrimitive?.content
                }.getOrNull() ?: ""
                AnimekoSource(
                    name = name,
                    iconUrl = icon,
                    description = desc,
                    factoryId = factoryId,
                    searchUrl = searchUrl
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 判断文本是否为 Animeko 媒体源配置（用于类型嗅探时优先识别） */
    fun isAnimekoSourceConfig(text: String): Boolean {
        val s = text.trimStart()
        // 不整篇 parse（大文件慢），先看特征串
        return s.contains("exportedMediaSourceDataList") && s.contains("mediaSources")
    }

    /** 自定义 JSON 源：兼容 {items:[...]} / [ ... ] 两种形态 */
    private fun parseJson(text: String): ParsedSource {
        // 审核298（真 bug）：先识别 animeko 媒体源配置。原来直接按
        // items/list/data 数组解析，用户粘一段 animeko 的
        // exportedMediaSourceDataList 配置进来只会得到
        // 「JSON 源缺少 items/list/data 数组」——animeko 源因此形同虚设。
        // AnimekoScraper 一直实现了配置解析与网页刮削，但订阅列表刷新
        // 这条路从来没接过它。
        if (text.contains("exportedMediaSourceDataList") ||
            text.contains("factoryId")
        ) {
            val cfgs = AnimekoScraper.parseConfig(text)
            if (cfgs.isNotEmpty()) {
                // animeko 是「搜索型」源：没有固定的条目列表，用源名占位，
                // 实际条目由订阅页内的搜索弹窗（SearchInSourceDialog）产生。
                return ParsedSource(
                    name = cfgs.first().name,
                    cover = cfgs.first().iconUrl,
                    items = emptyList()
                )
            }
        }
        val root = json.parseToJsonElement(text).let {
            when (it) {
                is JsonObject -> it
                else -> JsonObject(mapOf("items" to it))
            }
        }
        val name = root["title"]?.jsonPrimitive?.content
            ?: root["name"]?.jsonPrimitive?.content
            ?: ""
        val cover = root["cover"]?.jsonPrimitive?.content
            ?: root["image"]?.jsonPrimitive?.content
            ?: ""

        val arr = root["items"]?.jsonArray
            ?: root["list"]?.jsonArray
            ?: root["data"]?.jsonArray
            ?: error("JSON 源缺少 items/list/data 数组")

        val items = arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val link = o["link"]?.jsonPrimitive?.content
                ?: o["url"]?.jsonPrimitive?.content
                ?: o["playUrl"]?.jsonPrimitive?.content
                ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.content
                ?: o["name"]?.jsonPrimitive?.content ?: link
            ParsedItem(
                title = title,
                cover = o["cover"]?.jsonPrimitive?.content
                    ?: o["image"]?.jsonPrimitive?.content ?: "",
                link = link,
                desc = o["desc"]?.jsonPrimitive?.content
                    ?: o["description"]?.jsonPrimitive?.content ?: "",
                pubAt = o["pubAt"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: o["pubDate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                episode = o["episode"]?.jsonPrimitive?.content ?: guessEpisode(title)
            )
        }
        return ParsedSource(
            name,
            cover,
            if (items.size > MAX_ITEMS_PER_SOURCE) items.take(MAX_ITEMS_PER_SOURCE) else items
        )
    }

    /**
     * Consume the entire current element, including nested XHTML text and CDATA.
     * Leave the parser on its matching END_TAG so sibling fields remain intact.
     * Malformed XML is propagated to the caller's Result, not silently accepted.
     */
    private fun readText(parser: XmlPullParser): String {
        val startDepth = parser.depth
        val text = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF ->
                    text.append(parser.text.orEmpty())
                XmlPullParser.END_TAG -> if (parser.depth == startDepth) return text.toString()
                XmlPullParser.END_DOCUMENT -> error("订阅源 XML 标签未闭合")
            }
        }
    }

    /** RSS/Atom 常见日期 → 毫秒；失败返回 0（不抛异常，避免坏源整体失败） */
    private fun parseDate(raw: String): Long {
        val s = raw.trim()
        if (s.isBlank()) return 0L
        // 注意：不使用 java.time.Instant（需 API 26，minSdk=24 会被 Lint 拦下），
        // 统一用 SimpleDateFormat 覆盖常见格式。
        for (fmt in listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "dd MMM yyyy HH:mm:ss Z"
        )) {
            runCatching {
                val df = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
                return df.parse(s)?.time ?: 0L
            }
        }
        return 0L
    }

    /** 从标题猜分集标签（"第3集"/"EP03"/"P2"） */
    private fun guessEpisode(title: String): String {
        val t = title.trim()
        Regex("第\\s*(\\d+)\\s*[集话話]").find(t)?.let { return "第${it.groupValues[1]}集" }
        Regex("(?:EP|Ep|ep)\\s*(\\d+)").find(t)?.let { return "EP${it.groupValues[1]}" }
        Regex("(?:^|[^A-Za-z])P\\s*(\\d+)").find(t)?.let { return "P${it.groupValues[1]}" }
        return ""
    }

    /**
     * 按关键词搜索一个 Animeko 数据源（调研后实现）
     *
     * 仅对 **rss 类型**的源可用：其 searchConfig.searchUrl 就是标准 RSS
     * （如 `https://share.dmhy.org/topics/rss/rss.xml?keyword=KEYWORD`），
     * 直接取回走 RSS 解析即可得到条目（含磁力/直链）。
     *
     * web-selector 类型的源需要 CSS 选择器引擎 + 视频地址提取器，
     * 本版本**未实现**，直接返回明确失败（不做假结果）。
     */
    suspend fun searchAnimekoSource(
        searchUrl: String,
        keyword: String,
        factoryId: String
    ): Result<ParsedSource> = withContext(Dispatchers.IO) {
        runCatching {
            if (factoryId.equals("rss", ignoreCase = true)) {
                val url = searchUrl.replace("{keyword}", android.net.Uri.encode(keyword))
                val text = SourceHttpClient.client.get(url).bodyAsText()
                if (text.isBlank()) error("搜索返回为空")
                parseRss(text)
            } else if (factoryId.equals("web-selector", ignoreCase = true)) {
                // 需要完整配置（CSS 选择器），单靠 searchUrl 不够。
                // 由 searchAnimekoWeb() 处理，这里给出明确指引，避免静默失败。
                error("网页刮削源请使用 searchAnimekoWeb()（需要完整选择器配置）")
            } else {
                error("不支持的源类型：$factoryId")
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }
}
