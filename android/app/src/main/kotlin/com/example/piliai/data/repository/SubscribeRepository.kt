package com.example.piliai.data.repository

import com.example.piliai.data.remote.SourceHttpClient
import com.example.piliai.database.SubscribeItemEntity
import com.example.piliai.database.SubscribeSourceDao
import com.example.piliai.database.SubscribeSourceEntity
import androidx.room.withTransaction
import androidx.core.net.toUri
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.first
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
            val text = SourceHttpClient.client.get(url).bodyAsText()
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

class SubscribeRepository(
    private val dao: SubscribeSourceDao,
    private val itemDao: com.example.piliai.database.SubscribeItemDao,
    private val titleDao: com.example.piliai.database.SubscribeTitleDao? = null
) {
    /** 事务宿主：保证「删旧 + 写新」原子提交（审核轮3） */
    private val db by lazy {
        com.example.piliai.database.PiliNaraDatabase
            .getDatabase(com.example.piliai.AppContext.get())
    }

    /**
     * 追番 DAO：构造时未注入（旧调用点）时按需取默认实例。
     * 审核240：这是 animeko「收藏」页的数据入口。
     */
    private val titles: com.example.piliai.database.SubscribeTitleDao
        get() = titleDao ?: db.subscribeTitleDao()

    // ==================== 追番（animeko「收藏」移植，审核240）====================

    /** 观察全部追番（UI 直接订阅） */
    fun observeTitles(): kotlinx.coroutines.flow.Flow<List<com.example.piliai.database.SubscribeTitleEntity>> =
        titles.observeAll()

    /** 按收藏类型观察 */
    fun observeTitlesByType(type: Int) = titles.observeByType(type)

    /** 只看有更新的追番 */
    fun observeUnwatchedTitles() = titles.observeUnwatched()

    /**
     * 把订阅源里的条目加入追番（或更新已有追番的集数）。
     *
     * 以 `(sourceId, sourceKey)` 唯一：同一源内同一作品只占一行，
     * 重复加入不会产生第二行（UI 上的「追番」按钮可反复点）。
     * 已存在时**保留**用户的收藏状态与观看进度，只更新源侧信息。
     */
    suspend fun followTitle(
        sourceId: Long,
        sourceKey: String,
        name: String,
        cover: String = "",
        intro: String = "",
        latestEpisode: Int = 0,
        bangumiId: Long = 0L
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            require(sourceKey.isNotBlank()) { "追番需要源内标识" }
            val existing = titles.getByKey(sourceId, sourceKey)
            val entity = if (existing == null) {
                com.example.piliai.database.SubscribeTitleEntity(
                    sourceId = sourceId,
                    sourceKey = sourceKey,
                    name = name,
                    cover = cover,
                    intro = intro,
                    bangumiId = bangumiId,
                    latestEpisode = latestEpisode,
                    collectionType = com.example.piliai.database.SubscribeTitleEntity.COLLECTION_DOING
                )
            } else {
                existing.copy(
                    name = name.ifBlank { existing.name },
                    cover = cover.ifBlank { existing.cover },
                    intro = intro.ifBlank { existing.intro },
                    latestEpisode = if (latestEpisode > 0) latestEpisode else existing.latestEpisode,
                    bangumiId = if (bangumiId > 0) bangumiId else existing.bangumiId,
                    updatedAt = System.currentTimeMillis()
                )
            }
            // 审核258：新增追番要顺手推 Bangumi。此前只有 setTitleCollectionType
            // 会 push，followTitle 不推 —— 用户在订阅页点爱心追的番只存在于本地，
            // Bangumi 网页上看不到，换设备/重装就丢。
            // 失败不回滚本地：网络失败不该吃掉用户刚点的那一下。
            val rowId = titles.upsert(entity)
            if (bangumiId > 0L && rowId > 0L) {
                pushCollectionType(rowId)
            }
            rowId
        }
    }

    /** 取消追番（仅删本地记录，不动 Bangumi 侧） */
    suspend fun unfollowTitle(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { titles.deleteById(id) }
    }

    /** 改收藏状态（想看/在看/看过/搁置/抛弃） */
    suspend fun setTitleCollectionType(id: Long, type: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                titles.setCollectionType(
                    id,
                    com.example.piliai.database.SubscribeTitleEntity.fromBangumiType(type)
                )
            }
            // 审核240：本地改完顺手推 Bangumi。失败不回滚本地改动，
            // 网络失败不该把用户刚点的状态撤掉。
            pushCollectionType(id)
        }

    /** 标记看到第几集 */
    suspend fun setTitleWatched(id: Long, episode: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { titles.setWatchedEpisode(id, episode.coerceAtLeast(0)) }
        }

    /** 刷新某源全部追番的最新集数（syncSource 成功后调用） */
    /**
     * 审核261：用 Bangumi 章节的放送日期统计「已放送集数」。
     *
     * 为什么不能直接用 subject.eps / total_episodes：那里面包含**未放送**的
     * 分集（当季新番常有 12 计划集数但只放到第 5 集）。用它当 latestEpisode
     * 会让「只看更新」显示有 7 集未看，点进去却是预告片/未放送占位。
     *
     * 判定：airdate <= 今天（服务端时区由 Bangumi 决定，这里用本地日期比较，
     * 误差一天可接受 —— 总比把未放送的算成已放送好）。
     * 取不到日期的条目（airdate 为空）按已放送计，避免整片失效。
     */
    suspend fun refreshAiredEpisodeCount(bangumiId: Long): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (bangumiId <= 0L) return@runCatching 0
                val page = com.example.piliai.data.remote.BangumiApi.episodes(bangumiId)
                val today = java.time.LocalDate.now().toString()
                page.data.count { ep ->
                    ep.type == 0 && (ep.airdate.isNullOrBlank() || ep.airdate <= today)
                }
            }
        }

    suspend fun refreshTitleEpisode(sourceId: Long, sourceKey: String, latestEpisode: Int) =
        withContext(Dispatchers.IO) {
            runCatching {
                if (latestEpisode <= 0) return@runCatching
                val existing = titles.getByKey(sourceId, sourceKey) ?: return@runCatching
                if (latestEpisode != existing.latestEpisode) {
                    titles.setLatestEpisode(existing.id, latestEpisode)
                }
            }
        }

    /** 删订阅源时清掉它的追番，避免孤儿行 */
    suspend fun deleteTitlesBySource(sourceId: Long) = withContext(Dispatchers.IO) {
        runCatching { titles.deleteBySource(sourceId) }
    }

    suspend fun getTitle(id: Long) = withContext(Dispatchers.IO) {
        runCatching { titles.getById(id) }.getOrNull()
    }

    suspend fun getTitleByKey(sourceId: Long, sourceKey: String) = withContext(Dispatchers.IO) {
        runCatching { titles.getByKey(sourceId, sourceKey) }.getOrNull()
    }

    /**
     * 审核240：把 Bangumi 收藏同步进追番表。
     *
     * - 匹配键是 `bangumiId`（本地已关联过的作品）
     * - 未命中则插入新行：sourceId=0（Bangumi 直连，无订阅源），
     *   sourceKey="bgm:{id}" 保证唯一索引不冲突
     * - 同步失败/未登录时返回 0，由调用方决定是否提示
     */
    suspend fun syncBangumiCollections(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (!com.example.piliai.data.remote.BangumiSession.isLogin) return@runCatching 0
            val remote = com.example.piliai.data.remote.BangumiApi.allMyCollections()
            var touched = 0
            val now = System.currentTimeMillis()
            remote.forEach { item ->
                val subjectId = item.subjectId
                if (subjectId <= 0L) return@forEach
                val slim = item.subject
                // 审核261：用章节放送日期算「已放送集数」而不是 slim.eps ——
                // 后者含未放送分集，会让「只看更新」虚高。
                val episodes = com.example.piliai.data.remote.BangumiApi
                    .episodes(subjectId)
                    .data
                    .count { ep ->
                        ep.type == 0 && (ep.airdate.isNullOrBlank() ||
                            ep.airdate <= java.time.LocalDate.now().toString())
                    }
                    .takeIf { it > 0 }
                    ?: slim?.eps
                    ?: 0
                val existing = titles.getByBangumiId(subjectId)
                val entity = if (existing != null) {
                    existing.copy(
                        name = slim?.displayTitle?.takeIf { it.isNotBlank() } ?: existing.name,
                        cover = slim?.images?.large?.takeIf { it.isNotBlank() } ?: existing.cover,
                        intro = slim?.shortSummary?.takeIf { !it.isNullOrBlank() } ?: existing.intro,
                        // 审核258：只在「本地没有未推送的更新」时才采纳远端状态。
                        // 判定用 updatedAt 与 bangumiSyncedAt：本地在最后一次成功同步
                        // 之后又被改过（用户刚点了收藏状态）就保留本地值，
                        // 否则下次 sync 会把用户刚点的状态冲掉。
                        collectionType = if (existing.updatedAt > existing.bangumiSyncedAt) {
                            existing.collectionType
                        } else {
                            com.example.piliai.database.SubscribeTitleEntity
                                .fromBangumiType(item.type)
                        },
                        // 远端集数更大才覆盖（本地可能有更细的源数据）
                        latestEpisode = maxOf(existing.latestEpisode, episodes),
                        rating = if (slim?.score ?: 0.0 > 0) slim!!.score else existing.rating,
                        bangumiSyncedAt = now,
                        bangumiPrivate = item.isPrivate,
                        updatedAt = now
                    )
                } else {
                    com.example.piliai.database.SubscribeTitleEntity(
                        sourceId = 0L,
                        sourceKey = "bgm:$subjectId",
                        name = slim?.displayTitle ?: "条目 $subjectId",
                        cover = slim?.images?.large.orEmpty(),
                        intro = slim?.shortSummary.orEmpty(),
                        bangumiId = subjectId,
                        collectionType = com.example.piliai.database.SubscribeTitleEntity
                            .fromBangumiType(item.type),
                        latestEpisode = episodes,
                        rating = slim?.score ?: 0.0,
                        bangumiSyncedAt = now,
                        bangumiPrivate = item.isPrivate
                    )
                }
                titles.upsert(entity)
                touched++
            }
            touched
        }
    }

    /**
     * 审核240：把本地收藏状态推到 Bangumi。
     * 未登录 / 未关联 bangumiId 时静默跳过（不阻塞本地操作）。
     */
    private suspend fun pushCollectionType(id: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val row = titles.getById(id)
            if (row == null) return@runCatching
            if (row.bangumiId <= 0L) return@runCatching
            if (!com.example.piliai.data.remote.BangumiSession.isLogin) return@runCatching
            val ok = com.example.piliai.data.remote.BangumiApi.setCollection(
                subjectId = row.bangumiId,
                type = row.collectionType,
                isPrivate = row.bangumiPrivate
            )
            if (ok) titles.setBangumiSyncedAt(id, System.currentTimeMillis())
            Unit
        }.map { Unit }
    }

    /**
     * 添加一个订阅源。
     * 先做 URL 基本校验，再查重（同 url 覆盖更新），最后落库。
     */
    suspend fun addSource(url: String, name: String = "", type: Int): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = normalizeUrl(url)
                val existing = dao.getByUrl(normalized)
                val entity = SubscribeSourceEntity(
                    id = existing?.id ?: 0L,
                    name = name.ifBlank { existing?.name ?: hostOf(normalized) },
                    url = normalized,
                    type = type,
                    cover = existing?.cover ?: "",
                    enabled = true,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis()
                )
                dao.upsert(entity)
            }
        }

    /**
     * 同步单个源：拉取 → 解析 → 覆盖写入条目表，并回写同步结果。
     * 失败时把错误记到 lastError，供 UI 展示（不删除源，允许用户修链接后重试）。
     */
    suspend fun syncSource(source: SubscribeSourceEntity): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = SubscribeParser.fetch(source.url, source.type).getOrThrow()

                val now = System.currentTimeMillis()
                // 审核轮22：Upsert 以主键为准。若每次新建 id=0 的实体，
                // 会在 (sourceId, link) 唯一索引上冲突 —— 表现为重复刷新后
                // 要么插入失败，要么 REPLACE 掉旧行导致主键漂移。
                // 这里先按 (sourceId, link) 查出既有主键，复用它做真正更新。
                val existingByLink = itemDao.getBySourceOnce(source.id)
                    .associateBy { it.link }
                val entities = parsed.items.map { p ->
                    SubscribeItemEntity(
                        id = existingByLink[p.link]?.id ?: 0L,
                        sourceId = source.id,
                        title = p.title,
                        cover = p.cover,
                        link = p.link,
                        desc = p.desc,
                        pubAt = p.pubAt,
                        episode = p.episode,
                        // Animeko 源：sourceName 存 factoryId（供搜索时判定类型）；
                        // 普通源：存源名（UI 展示用）
                        sourceName = p.factoryId.ifBlank { parsed.name.ifBlank { source.name } },
                        updatedAt = now
                    )
                }
                // 先清旧条目再写新条目：避免源内删除的条目长期残留。
                // 审核轮3：必须放在同一事务里 —— 否则「删完还没写完」时进程被杀
                // 或写入失败，用户会看到订阅页被清空（数据丢失）。
                // withTransaction 保证删+写原子提交，任一步失败则整体回滚。
                db.withTransaction {
                    itemDao.deleteBySource(source.id)
                    itemDao.upsertAll(entities)
                }

                dao.updateSyncResult(
                    id = source.id,
                    name = parsed.name.ifBlank { source.name },
                    cover = parsed.cover.ifBlank { source.cover },
                    at = now,
                    err = null
                )
                // 审核：同步成功后使配置缓存失效，下次搜索用最新配置
                invalidateConfigCache(source.url)
                entities.size
            }.onFailure { e ->
                runCatching {
                    dao.updateSyncResult(
                        id = source.id,
                        name = source.name,
                        cover = source.cover,
                        at = source.lastSyncAt,
                        // 审核264：把底层网络错误翻译成用户能行动的话。
                        // 原来直接存 e.message，用户看到的是
                        // 「Request failed with status code: 403」这种天书，
                        // 不知道该改链接、该开代理还是等一会儿。
                        err = describeSyncFailure(source.url, e)
                    )
                }
            }
        }

    /**
     * 审核264：把同步失败翻译成用户可行动的描述。
     *
     * 覆盖最常见的四类：DNS/连接失败（链接错或站点挂了）、
     * 403/401（被风控或需要登录）、404（链接路径错）、
     * 超时（站点慢或网络不通），以及「拿到的不是有效源」这种解析层错误。
     */
    /**
     * 审核264：把同步失败翻译成用户可行动的描述。public 供 ViewModel 复用 ——
     * refreshAll 汇总错误时也要用同一套话术，不能一个地方可读、一个地方天书。
     */
    fun describeSyncFailure(url: String, error: Throwable): String {
        val raw = error.message.orEmpty()
        val status = Regex("status code:? ([0-9]{3})").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            status == 403 || status == 401 ->
                "源站拒绝访问（$status）——可能被风控，稍后重试或检查是否需要登录"
            status == 404 -> "链接不存在（404）——请检查订阅源地址是否正确"
            status == 429 -> "请求过于频繁（429）——稍后重试"
            status != null && status >= 500 ->
                "源站服务器错误（$status）——对方挂了，稍后重试"
            status != null -> "源站返回异常状态码 $status"
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ->
                "连接超时——检查网络或源站是否可达"
            raw.contains("Unable to resolve host", ignoreCase = true) ||
                raw.contains("UnknownHost", ignoreCase = true) ->
                "无法解析域名——链接可能写错，或该站点已失效"
            raw.contains("Connection reset", ignoreCase = true) ||
                raw.contains("Connection refused", ignoreCase = true) ->
                "连接被拒绝——检查网络或代理设置"
            raw.contains("订阅源返回为空") -> "订阅源返回空内容——链接可能失效"
            raw.contains("未识别格式") || raw.contains("格式不符") ->
                "链接内容不是有效的订阅源（支持 RSS/Atom/JSON/Animeko 配置）"
            raw.isNotBlank() -> raw
            else -> "同步失败（未知原因）"
        }
    }

    // ==================== Animeko 网页刮削（web-selector）====================
    //
    // 三步走（调研真实配置后实现，见 docs/animeko-source-format.md）：
    //   1. 搜索页 --CSS--> 作品列表
    //   2. 作品页 --CSS--> 剧集列表
    //   3. 剧集页 --正则--> 视频直链
    // 每一步都用 runCatching 兜底：单源失败不崩溃，返回明确错误。

    /** 缓存已解析的源配置，避免每次搜索都重新下载/解析订阅配置文件 */
    // 审核：缓存必须能过期。原实现只增不减，订阅源配置（all.json）更新后
    // App 会一直用旧缓存，违背「刷新」语义。加 TTL + 同步成功后主动失效。
    private val configCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<AnimekoScraper.SourceConfig>>>()
    private val configCacheTtlMs = 10 * 60 * 1000L   // 10 分钟

    /** 让指定订阅源配置缓存失效（同步成功后调用） */
    fun invalidateConfigCache(sourceUrl: String) {
        configCache.remove(sourceUrl)
    }

    /**
     * 审核238：per-host 请求间隔节流（animeko `requestInterval`，SelectorSearchConfig.kt:61）。
     *
     * 之前 `requestIntervalMs` 解析后从未使用：三步刮削（搜索→作品→剧集→播放页）
     * 会对同一 host 连打多次请求，63 个源全并发时必然触发风控。
     * 这里按 host 记上次请求时间，不足间隔就 delay；并发下用 synchronized 保证
     * 「读时间 + 更新时间」原子，否则多个协程会同时通过检查。
     */
    private val lastRequestAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun throttle(host: String, intervalMs: Long) {
        if (intervalMs <= 0L) return
        while (true) {
            val now = System.currentTimeMillis()
            val waitUntil: Long
            synchronized(lastRequestAt) {
                val last = lastRequestAt[host] ?: 0L
                waitUntil = if (last == 0L) now else last + intervalMs
                // 抢占时间片（提前占位，避免同一批协程都算出同一个 waitUntil）
                lastRequestAt[host] = waitUntil.coerceAtLeast(now)
            }
            val delayMs = waitUntil - now
            if (delayMs <= 0L) return
            kotlinx.coroutines.delay(delayMs)
        }
    }

    private suspend fun configsOf(sourceUrl: String): List<AnimekoScraper.SourceConfig> {
        val now = System.currentTimeMillis()
        configCache[sourceUrl]?.let { (ts, cfgs) ->
            if (now - ts < configCacheTtlMs) return cfgs
        }
        return withContext(Dispatchers.IO) {
            val text = SourceHttpClient.client.get(sourceUrl).bodyAsText()
            val cfgs = AnimekoScraper.parseConfig(text)
            if (cfgs.isNotEmpty()) configCache[sourceUrl] = now to cfgs
            cfgs
        }
    }

    suspend fun searchableWebSourceNames(sourceUrl: String): List<String> =
        configsOf(sourceUrl).filter {
            it.factoryId == "web-selector" && it.cfg.searchUrl.isNotBlank()
        }.map { it.name }.distinct()

    /**
     * 在网页刮削源里搜索（第一步：搜索页 → 作品列表）
     *
     * @param sourceUrl 订阅配置文件 URL（如 .../all.json）
     * @param sourceName 源名（如"酱紫社(修复)"）
     * @param keyword 搜索关键词
     */
    suspend fun searchAnimekoWeb(
        sourceUrl: String,
        sourceName: String,
        keyword: String
    ): Result<List<AnimekoScraper.Subject>> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = configsOf(sourceUrl).firstOrNull { it.name == sourceName }
                ?: error("未找到源「$sourceName」的配置")
            if (cfg.cfg.searchUrl.isBlank()) error("该源未配置 searchUrl，无法搜索")
            val url = AnimekoScraper.buildSearchUrl(cfg.cfg, keyword)
            // 传入完整搜索页 URL，让 URI.resolve 正确处理目录相对链接。
            val base = url
            val html = fetch(url, cfg)
            if (html.isBlank()) error("搜索页返回为空")
            val list = AnimekoScraper.parseSubjects(html, base, cfg.cfg)
            if (list.isEmpty()) {
                // 不伪造结果：明确告知可能原因（优先诊断 JS 渲染）
                val diag = AnimekoScraper.diagnoseJsRendered(html)
                error(diag ?: "未解析到作品（可能站点改版或选择器失效）")
            }
            list
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    /** 第二步：作品页 → 剧集列表 */
    suspend fun fetchEpisodes(
        sourceUrl: String,
        sourceName: String,
        subjectUrl: String
    ): Result<List<AnimekoScraper.Episode>> = withContext(Dispatchers.IO) {
        runCatching {
            val cfg = configsOf(sourceUrl).firstOrNull { it.name == sourceName }
                ?: error("未找到源「$sourceName」的配置")
            // 传入完整作品页 URL，保留目录上下文。
            val base = subjectUrl
            val html = fetch(subjectUrl, cfg)
            if (html.isBlank()) error("作品页返回为空")
            val list = AnimekoScraper.parseEpisodes(html, base, cfg.cfg)
            if (list.isEmpty()) {
                val diag = AnimekoScraper.diagnoseJsRendered(html)
                error(diag ?: "未解析到剧集（可能站点改版或需要登录）")
            }
            list
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    /** 第三步：剧集页 → 视频直链（返回空串表示未提取到） */
    suspend fun fetchVideoUrl(
        sourceUrl: String,
        sourceName: String,
        episodeUrl: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cfg = configsOf(sourceUrl).firstOrNull { it.name == sourceName }
                ?: error("未找到源「$sourceName」的配置")
            extractVideoUrlWithConfig(episodeUrl, cfg)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun extractVideoUrlWithConfig(
        episodeUrl: String,
        cfg: AnimekoScraper.SourceConfig
    ): Result<String> {
        return runCatching {
            // 先取剧集页
            var html = fetch(episodeUrl, cfg)
            val base = episodeUrl

            // 先试本页直链
            var v = AnimekoScraper.extractVideoUrl(html, base, cfg.cfg)
            if (v.isNotBlank()) return@runCatching v

            // 本页没有 → nestedUrl 二级跳（60/63 源如此）
            // 审核：visited 集合防自引用/环（A→A 或 A→B→A），比 take(3) 更可靠。
            val visited = mutableSetOf(episodeUrl)
            val nested = AnimekoScraper.extractNestedUrls(html, base, cfg.cfg)
            for (n in nested) {
                if (!visited.add(n)) continue   // 已访问过，跳过
                if (visited.size > 4) break      // 含首条 episodeUrl，最多再跳 3 次
                val nHtml = fetch(n, cfg)
                v = AnimekoScraper.extractVideoUrl(nHtml, n, cfg.cfg)
                if (v.isNotBlank()) return@runCatching v
                // 嵌套页里可能再指向下一层
                val deeper = AnimekoScraper.extractNestedUrls(nHtml, n, cfg.cfg)
                for (d in deeper) {
                    if (visited.add(d) && visited.size <= 4) {
                        val dHtml = fetch(d, cfg)
                        v = AnimekoScraper.extractVideoUrl(dHtml, d, cfg.cfg)
                        if (v.isNotBlank()) return@runCatching v
                    }
                }
            }
            val diag = AnimekoScraper.diagnoseJsRendered(html)
            error(diag ?: "未提取到视频地址（可能站点改版/风控/需要 JS 渲染）")
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }

    suspend fun resolvePlaybackRequest(
        sourceId: Long,
        sourceName: String,
        episodeUrl: String
    ): Result<com.example.piliai.playback.SourcePlaybackRequest> =
        withContext(Dispatchers.IO) {
            try {
                val source = dao.getById(sourceId)
                    ?: error("Owning subscription was removed")
                require(source.enabled) { "Owning subscription is disabled" }
                val config = configsOf(source.url).firstOrNull { it.name == sourceName }
                    ?: error("Configured resolver was not found")
                // Resolve media and headers from the same configuration snapshot.
                val videoUrl = extractVideoUrlWithConfig(episodeUrl, config).getOrThrow()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val currentSource = dao.getById(sourceId)
                require(currentSource?.enabled == true && currentSource.url == source.url) {
                    "Owning subscription changed during playback resolution"
                }
                Result.success(
                    com.example.piliai.playback.SourcePlaybackRequest(
                        videoUrl = videoUrl,
                        sourceId = sourceId,
                        sourceName = sourceName,
                        episodeUrl = episodeUrl,
                        referer = config.cfg.referer,
                        userAgent = config.cfg.userAgent,
                        cookies = if (canSendSourceCookies(episodeUrl, config)) {
                            config.cfg.cookies
                        } else {
                            ""
                        }
                    )
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    private fun canSendSourceCookies(
        url: String,
        cfg: AnimekoScraper.SourceConfig
    ): Boolean = runCatching {
        val owner = java.net.URI(AnimekoScraper.buildSearchUrl(cfg.cfg, ""))
        val destination = java.net.URI(url)
        fun port(uri: java.net.URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        !owner.host.isNullOrBlank() && owner.rawUserInfo == null &&
            destination.rawUserInfo == null &&
            (owner.scheme.equals("https", ignoreCase = true) ||
                owner.scheme.equals("http", ignoreCase = true)) &&
            owner.scheme.equals(destination.scheme, ignoreCase = true) &&
            owner.host.equals(destination.host, ignoreCase = true) &&
            port(owner) == port(destination)
    }.getOrDefault(false)

    /** Source cookies belong to the configured search origin, not extracted links. */
    private suspend fun fetch(url: String, cfg: AnimekoScraper.SourceConfig): String {
        val destination = java.net.URI(url)
        require(!destination.host.isNullOrBlank() && destination.rawUserInfo == null &&
            (destination.scheme.equals("https", ignoreCase = true) ||
                destination.scheme.equals("http", ignoreCase = true))) {
            "Source requests require HTTP or HTTPS without embedded credentials"
        }
        // 审核238：尊重源配置的请求间隔（默认 3s），避免同 host 连续请求被风控
        throttle(destination.host, cfg.cfg.requestIntervalMs)
        return SourceHttpClient.client.get(url) {
            if (cfg.cfg.userAgent.isNotBlank()) header("User-Agent", cfg.cfg.userAgent)
            if (cfg.cfg.cookies.isNotBlank() && canSendSourceCookies(url, cfg)) {
                header("Cookie", cfg.cfg.cookies)
            }
            if (cfg.cfg.referer.isNotBlank()) header("Referer", cfg.cfg.referer)
        }.bodyAsText()
    }

    fun observeSources(): kotlinx.coroutines.flow.Flow<List<SubscribeSourceEntity>> = dao.observeAll()

    /** 观察全部条目（Flow，UI 自动刷新） */
    fun observeItems(): kotlinx.coroutines.flow.Flow<List<SubscribeItemEntity>> =
        itemDao.observeAll()

    suspend fun removeSource(id: Long) = withContext(Dispatchers.IO) {
        runCatching {
            // 审核240：删源要连追番一起清，否则订阅页留下孤儿作品
            titles.deleteBySource(id)
            itemDao.deleteBySource(id)
            dao.deleteById(id)
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        runCatching { dao.setEnabled(id, enabled) }
    }

    suspend fun updateName(id: Long, name: String) = withContext(Dispatchers.IO) {
        runCatching {
            val s = dao.getById(id) ?: error("源不存在")
            dao.upsert(s.copy(name = name))
        }
    }

    private fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        require(s.isNotBlank()) { "链接不能为空" }
        val withScheme = when {
            s.startsWith("http://", true) || s.startsWith("https://", true) -> s
            s.startsWith("//") -> "https:$s"
            else -> "https://$s"
        }
        // 基本合法性校验（防用户粘贴任意文本导致后续解析异常）
        val uri = withScheme.toUri()
        val host = uri.host
        require(!host.isNullOrBlank()) { "链接无效：缺少域名" }
        // 审核轮6：显式 scheme 白名单。
        // 只放行 http/https —— 避免 file://、content:// 等被当作订阅源请求
        // （本地文件读取 / 跨应用内容泄露），也避免 Ktor 用非预期引擎处理。
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "仅支持 http/https 链接（当前：${scheme ?: "无"}）"
        }
        return withScheme
    }

    private fun hostOf(url: String): String =
        runCatching { url.toUri().host ?: url }.getOrDefault(url)
}
