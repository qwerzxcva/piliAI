package com.example.piliai.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Animeko 媒体源配置模型 + 网页刮削引擎
 *
 * 配置格式（调研实测，见 docs/animeko-source-format.md）：
 * ```
 * { "exportedMediaSourceDataList": { "mediaSources": [
 *     { "factoryId": "web-selector" | "rss",
 *       "arguments": {
 *         "name": "...", "iconUrl": "...", "description": "...",
 *         "searchConfig": {
 *            "searchUrl": "...{keyword}...",
 *            "subjectFormatId": "a" | "indexed" | "json-path-indexed",
 *            "channelFormatId": "no-channel" | "index-grouped",
 *            "selectorSubjectFormatA": {"selectLists": "css"},
 *            "selectorSubjectFormatIndexed": {"selectNames":"css","selectLinks":"css"},
 *            "selectorChannelFormatNoChannel": {"selectEpisodes":"css",...},
 *            "selectorChannelFormatFlattened": {"selectChannelNames":"css",...},
 *            "matchVideo": {"matchVideoUrl":"regex","enableNestedUrl":bool,
 *                           "matchNestedUrl":"regex","cookies":"...",
 *                           "addHeadersToVideo":{"referer":"","userAgent":""}},
 *            "rawBaseUrl": "...", "requestInterval": 3000
 *         } } } ] } }
 * ```
 *
 * 设计原则（审核）：
 * 1. 全部解析用 runCatching 兜底 —— 单个源配置畸形不应影响其它源。
 * 2. 抓到的 URL 一律经 matchVideoUrl 正则过滤，只保留可播地址。
 * 3. 不伪造结果：刮不到就返回空列表 + 明确错误信息。
 */
object AnimekoScraper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一个完整的媒体源配置 */
    data class SourceConfig(
        val name: String,
        val iconUrl: String,
        val description: String,
        val factoryId: String,
        val cfg: SearchConfig
    ) {
        val isRss: Boolean get() = factoryId.equals("rss", ignoreCase = true)
    }

    data class SearchConfig(
        val searchUrl: String = "",
        val searchUseOnlyFirstWord: Boolean = false,
        val subjectFormatId: String = "",
        val channelFormatId: String = "",
        val selectLists: String = "",           // subjectFormat "a"
        val selectNames: String = "",           // subjectFormat "indexed"
        val selectLinks: String = "",
        val selectNamesJsonPath: String = "",
        val selectLinksJsonPath: String = "",
        // 审核238：linkTemplate（animeko SelectorSubjectFormat*.kt:65-67/121-123/165-170）
        // 详情页模板：把 selectLists 取到的裸 id 拼成详情页 URL。缺失时拼不出链接。
        val linkTemplateA: String = "",
        val linkTemplateIndexed: String = "",
        val linkTemplateJsonPathIndexed: String = "",
        val selectEpisodes: String = "",        // channel no-channel
        val selectEpisodeLinks: String = "",
        val selectChannelNames: String = "",    // channel index-grouped
        // 审核238：index-grouped 的容器三段式（animeko SelectorChannelFormat.kt:104-134, 166-190）
        // selectEpisodeLists=线路容器，selectEpisodesFromList=容器内剧集，
        // selectEpisodeLinksFromList=容器内链接（可与剧集分离）。
        val selectEpisodeLists: String = "",
        val selectEpisodesFromList: String = "",
        val selectEpisodeLinksFromList: String = "",
        val matchVideoUrl: String = "",
        val matchNestedUrl: String = "",
        val enableNestedUrl: Boolean = false,
        val cookies: String = "",
        val referer: String = "",
        val userAgent: String = "",
        val rawBaseUrl: String = "",
        val requestIntervalMs: Long = 3000L,
        val matchEpisodeSortFromName: String = "",
        val matchChannelName: String = "",
        val filterByEpisodeSort: Boolean = false,
        val searchRemoveSpecial: Boolean = false,
        val searchUseSubjectNamesCount: Int = 0
    )

    /** 作品（番剧）条目 */
    data class Subject(
        val name: String,
        val url: String
    )

    /** 一集 */
    data class Episode(
        val name: String,
        val url: String,
        val channel: String = ""
    )

    /** 解析订阅源配置文件 */
    fun parseConfig(text: String): List<SourceConfig> = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val arr = root["exportedMediaSourceDataList"]?.jsonObject
            ?.get("mediaSources")?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: return emptyList()

        arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val factoryId = o["factoryId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val a = runCatching { o["arguments"]?.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = a["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SourceConfig(
                name = name,
                iconUrl = a["iconUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                description = a["description"]?.jsonPrimitive?.contentOrNull ?: "",
                factoryId = factoryId,
                cfg = parseSearchConfig(a["searchConfig"])
            )
        }
    }.getOrDefault(emptyList())

    private fun parseSearchConfig(sc: kotlinx.serialization.json.JsonElement?): SearchConfig {
        val o = runCatching { sc?.jsonObject }.getOrNull() ?: return SearchConfig()
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""

        val a = runCatching { o["selectorSubjectFormatA"]?.jsonObject }.getOrNull()
        val idx = runCatching { o["selectorSubjectFormatIndexed"]?.jsonObject }.getOrNull()
        val noCh = runCatching { o["selectorChannelFormatNoChannel"]?.jsonObject }.getOrNull()
        val flat = runCatching { o["selectorChannelFormatFlattened"]?.jsonObject }.getOrNull()
        val mv = runCatching { o["matchVideo"]?.jsonObject }.getOrNull()
        val hdr = runCatching { mv?.get("addHeadersToVideo")?.jsonObject }.getOrNull()

        return SearchConfig(
            searchUrl = s("searchUrl"),
            searchUseOnlyFirstWord = o["searchUseOnlyFirstWord"]?.jsonPrimitive?.booleanOrNull ?: false,
            subjectFormatId = s("subjectFormatId"),
            channelFormatId = s("channelFormatId"),
            selectLists = a?.get("selectLists")?.jsonPrimitive?.contentOrNull ?: "",
            selectNames = idx?.get("selectNames")?.jsonPrimitive?.contentOrNull ?: "",
            selectLinks = idx?.get("selectLinks")?.jsonPrimitive?.contentOrNull ?: "",
            selectNamesJsonPath = runCatching {
                o["selectorSubjectFormatJsonPathIndexed"]?.jsonObject
                    ?.get("selectNames")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "",
            selectLinksJsonPath = runCatching {
                o["selectorSubjectFormatJsonPathIndexed"]?.jsonObject
                    ?.get("selectLinks")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "",
            linkTemplateA = a?.get("linkTemplate")?.jsonPrimitive?.contentOrNull ?: "",
            linkTemplateIndexed = idx?.get("linkTemplate")?.jsonPrimitive?.contentOrNull ?: "",
            linkTemplateJsonPathIndexed = runCatching {
                o["selectorSubjectFormatJsonPathIndexed"]?.jsonObject
                    ?.get("linkTemplate")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "",
            selectEpisodes = noCh?.get("selectEpisodes")?.jsonPrimitive?.contentOrNull ?: "",
            selectEpisodeLinks = noCh?.get("selectEpisodeLinks")?.jsonPrimitive?.contentOrNull ?: "",
            selectChannelNames = flat?.get("selectChannelNames")?.jsonPrimitive?.contentOrNull
                ?: flat?.get("selectEpisodeLinks")?.jsonPrimitive?.contentOrNull ?: "",
            selectEpisodeLists = flat?.get("selectEpisodeLists")?.jsonPrimitive?.contentOrNull ?: "",
            selectEpisodesFromList = flat?.get("selectEpisodesFromList")?.jsonPrimitive?.contentOrNull ?: "",
            selectEpisodeLinksFromList = flat?.get("selectEpisodeLinksFromList")?.jsonPrimitive?.contentOrNull ?: "",
            matchVideoUrl = mv?.get("matchVideoUrl")?.jsonPrimitive?.contentOrNull ?: "",
            matchNestedUrl = mv?.get("matchNestedUrl")?.jsonPrimitive?.contentOrNull ?: "",
            enableNestedUrl = mv?.get("enableNestedUrl")?.jsonPrimitive?.booleanOrNull ?: false,
            cookies = mv?.get("cookies")?.jsonPrimitive?.contentOrNull ?: "",
            referer = hdr?.get("referer")?.jsonPrimitive?.contentOrNull ?: "",
            userAgent = hdr?.get("userAgent")?.jsonPrimitive?.contentOrNull ?: "",
            rawBaseUrl = s("rawBaseUrl"),
            requestIntervalMs = runCatching {
                o["requestInterval"]?.jsonPrimitive?.content?.toLongOrNull()
            }.getOrNull() ?: 3000L,
            matchEpisodeSortFromName = noCh?.get("matchEpisodeSortFromName")?.jsonPrimitive?.contentOrNull ?: "",
            matchChannelName = flat?.get("matchChannelName")?.jsonPrimitive?.contentOrNull ?: "",
            filterByEpisodeSort = o["filterByEpisodeSort"]?.jsonPrimitive?.booleanOrNull ?: false,
            searchRemoveSpecial = o["searchRemoveSpecial"]?.jsonPrimitive?.booleanOrNull ?: false,
            searchUseSubjectNamesCount = runCatching {
                o["searchUseSubjectNamesCount"]?.jsonPrimitive?.content?.toIntOrNull()
            }.getOrNull() ?: 0
        )
    }

    /**
     * 第一步：搜索页 → 作品列表
     *
     * 支持两种（实测分布）：
     * - "a"        (41个)：`selectLists` 选出 `<a>`，文本=作品名，href=链接
     * - "indexed"  (21个)：`selectNames` + `selectLinks` 两组分别取文本与链接
     * - "json-path-indexed"：根数组上的 `$[*].字段` 或 `$[*]['字段']`；其它路径明确报错
     */
    fun parseSubjects(html: String, baseUrl: String, cfg: SearchConfig): List<Subject> {
        val effectiveBase = cfg.rawBaseUrl.trim().ifBlank { baseUrl }
        // JSON configuration errors must reach the repository's Result.failure.
        if (cfg.subjectFormatId == "json-path-indexed") {
            return parseSubjectsByJsonPath(html, effectiveBase, cfg)
        }
        return runCatching {
            val doc = org.jsoup.Jsoup.parse(html, effectiveBase)
            when (cfg.subjectFormatId) {
                "a" -> {
                    if (cfg.selectLists.isBlank()) return emptyList()
                    doc.select(cfg.selectLists).mapNotNull { el ->
                        val name = el.text().trim()
                        // 审核238：linkTemplate 优先（animeko SelectorSubjectFormatA.kt:65-67）
                        val raw = el.attr("href").trim()
                        val url = applyLinkTemplate(cfg.linkTemplateA, raw, effectiveBase)
                            ?: absUrl(el, "href", effectiveBase)
                        if (name.isBlank() || url.isBlank()) null else Subject(name, url)
                    }.distinctBy { it.url }
                }
                "indexed" -> {
                    if (cfg.selectNames.isBlank() || cfg.selectLinks.isBlank()) return emptyList()
                    val names = doc.select(cfg.selectNames).map { it.text().trim() }
                    val links = doc.select(cfg.selectLinks).mapNotNull {
                        val raw = it.attr("href").trim()
                        applyLinkTemplate(cfg.linkTemplateIndexed, raw, effectiveBase)
                            ?: absUrl(it, "href", effectiveBase).ifBlank { null }
                    }
                    names.zip(links) { n, l -> if (n.isBlank()) null else Subject(n, l) }
                        .filterNotNull().distinctBy { it.url }
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun parseSubjectsByJsonPath(
        text: String,
        baseUrl: String,
        cfg: SearchConfig
    ): List<Subject> {
        // 审核238：多键路径（$[*]['url','link']）→ 按顺序取第一个非空值
        val nameKeys = jsonPathFields(cfg.selectNamesJsonPath)
            .ifEmpty { error("Unsupported subject-name JSON path") }
        val linkKeys = jsonPathFields(cfg.selectLinksJsonPath)
            .ifEmpty { error("Unsupported subject-link JSON path") }
        val rows = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            ?: error("Configured JSON paths require an array at the document root")
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val name = firstString(row, nameKeys)
            val rawLink = firstString(row, linkKeys)
            if (name.isBlank() || rawLink.isBlank()) return@mapNotNull null
            // 审核238：json-path 分支也要支持 linkTemplate
            val url = applyLinkTemplate(cfg.linkTemplateJsonPathIndexed, rawLink, baseUrl)
                ?: resolveUrl(rawLink, baseUrl)
            if (!isHttpUrl(url)) return@mapNotNull null
            Subject(name, url)
        }.distinctBy { it.url }
    }

    /** 按 key 顺序取第一个非空字符串值 */
    private fun firstString(
        row: JsonObject,
        keys: List<String>
    ): String = keys.firstNotNullOfOrNull { key ->
        (row[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }.orEmpty()

    private fun jsonPathField(path: String): String? {
        val dot = Regex("""^\$\[\*]\.([A-Za-z0-9_]+)$""").find(path.trim())
        if (dot != null) return dot.groupValues[1]
        val bracket = Regex("""^\$\[\*]\['([^']+)'\]$""").find(path.trim())
        return bracket?.groupValues?.get(1)
    }

    /**
     * 审核238：json-path 多键选择器（animeko SelectorSubjectFormat.kt:159-162, 218-236）。
     * animeko 支持 `$[*]['url','link']` 这类多键路径：取到的是候选值数组，
     * 按顺序取第一个非空值压平。此前只支持单键，遇到多键配置整源解析失败。
     */
    private fun jsonPathFields(path: String): List<String> {
        val trimmed = path.trim()
        val multi = Regex("""^\$\[\*]\[([^]]+)]$""").find(trimmed)
        if (multi != null) {
            return multi.groupValues[1].split(',').mapNotNull { key ->
                val k = key.trim().removeSurrounding("'").removeSurrounding("\"").trim()
                k.ifBlank { null }
            }
        }
        return listOfNotNull(jsonPathField(trimmed))
    }

    /**
     * 审核238：linkTemplate（animeko SelectorSubjectFormat*.kt 的 linkTemplate）。
     * 模板形如 `https://example.com/detail/{value}.html`，`{value}` 处替换为
     * 选择器取到的原始值（通常是 id）。无模板或模板不含 `{value}` 时返回 null，
     * 让调用方回退到 href 绝对化。
     */
    private fun applyLinkTemplate(template: String, rawValue: String, baseUrl: String): String? {
        if (template.isBlank() || !template.contains("{value}")) return null
        val value = rawValue.trim()
        if (value.isBlank()) return null
        val url = template.replace("{value}", value)
        return resolveUrl(url, baseUrl).takeIf { it.isNotBlank() }
    }

    /**
     * 第二步：作品页 → 剧集列表
     *
     * - "no-channel"    (4个)：`selectEpisodes` 直接选出一集一个元素
     * - "index-grouped"(59个)：`selectChannelNames` 选线路/分组，`selectEpisodes` 选集
     */
    fun parseEpisodes(html: String, baseUrl: String, cfg: SearchConfig): List<Episode> =
        runCatching {
            val effectiveBase = cfg.rawBaseUrl.trim().ifBlank { baseUrl }
            val doc = org.jsoup.Jsoup.parse(html, effectiveBase)
            val out = mutableListOf<Episode>()
            when (cfg.channelFormatId) {
                "no-channel" -> {
                    if (cfg.selectEpisodes.isBlank()) return emptyList()
                    doc.select(cfg.selectEpisodes).forEach { el ->
                        val name = el.text().trim().ifBlank { el.attr("title") }
                        val url = absUrl(el, "href", effectiveBase)
                            .ifBlank { absUrl(el, "data-href", effectiveBase) }
                        if (name.isNotBlank() && url.isNotBlank()) out.add(Episode(name, url))
                    }
                }
                "index-grouped" -> {
                    // 审核238：animeko 的 index-grouped 是「线路容器 → 容器内剧集」的三段式
                    // （SelectorChannelFormat.kt:104-134 与 166-190）。此前只用两个平铺
                    // 选择器 + channels.firstOrNull()，多线路站点全部塌成一条线路。
                    //
                    // 选择器兜底顺序：容器三段式 > selectEpisodesFromList 单独用 > selectEpisodes。
                    // 原实现开头 `if (cfg.selectEpisodes.isBlank()) return emptyList()` 会把
                    // 只配了 FromList 的源直接判死（容器选择器为空时永远零剧集）。
                    val episodeSelector = when {
                        cfg.selectEpisodesFromList.isNotBlank() -> cfg.selectEpisodesFromList
                        cfg.selectEpisodes.isNotBlank() -> cfg.selectEpisodes
                        else -> return emptyList()
                    }
                    val containerSel = cfg.selectEpisodeLists
                    if (containerSel.isNotBlank() && cfg.selectEpisodesFromList.isNotBlank()) {
                        val containers = doc.select(containerSel)
                        val channelNames = if (cfg.selectChannelNames.isNotBlank())
                            doc.select(cfg.selectChannelNames).map { it.text().trim() } else emptyList()
                        containers.forEachIndexed { i, container ->
                            // 线路名与容器按索引对齐；取不到就退回容器自身文本
                            val channel = channelNames.getOrNull(i)
                                ?: container.text().trim().take(40)
                            val linkSel = cfg.selectEpisodeLinksFromList
                                .ifBlank { episodeSelector }
                            val episodeEls = container.select(episodeSelector)
                            val linkEls = if (linkSel == episodeSelector) episodeEls
                                else container.select(linkSel)
                            episodeEls.forEachIndexed { j, el ->
                                val name = el.text().trim().ifBlank { el.attr("title") }
                                // 链接优先从对应的 link 元素取；数量不齐时退回 episode 元素自身
                                val url = linkEls.getOrNull(j)
                                    ?.let { absUrl(it, "href", effectiveBase) }
                                    ?.ifBlank { null }
                                    ?: absUrl(el, "href", effectiveBase)
                                        .ifBlank { absUrl(el, "data-href", effectiveBase) }
                                if (name.isNotBlank() && url.isNotBlank()) {
                                    out.add(Episode(name, url, channel))
                                }
                            }
                        }
                    } else {
                        // 无容器配置时按平铺解析（兼容少数真平铺源）
                        val channels = if (cfg.selectChannelNames.isNotBlank())
                            doc.select(cfg.selectChannelNames).map { it.text().trim() } else emptyList()
                        val eps = doc.select(episodeSelector)
                        eps.forEach { el ->
                            val name = el.text().trim().ifBlank { el.attr("title") }
                            val url = absUrl(el, "href", effectiveBase)
                                .ifBlank { absUrl(el, "data-href", effectiveBase) }
                            if (name.isNotBlank() && url.isNotBlank()) {
                                out.add(Episode(name, url, channels.firstOrNull() ?: ""))
                            }
                        }
                    }
                }
                else -> {
                    // 未指定时退化为 no-channel 行为
                    if (cfg.selectEpisodes.isBlank()) return emptyList()
                    doc.select(cfg.selectEpisodes).forEach { el ->
                        val name = el.text().trim()
                        val url = absUrl(el, "href", effectiveBase)
                        if (name.isNotBlank() && url.isNotBlank()) out.add(Episode(name, url))
                    }
                }
            }
            // 审核：filterByEpisodeSort=true（53 源）按集数排序，否则剧集乱序。
            // 优先用源配置的 matchEpisodeSortFromName 正则（命名组 ep），
            // 无配置时用通用启发式提取。
            val sorted = if (cfg.filterByEpisodeSort) {
                out.sortedWith(compareBy(
                    { episodeNum(it.name, cfg.matchEpisodeSortFromName) },
                    { it.name }
                ))
            } else out
            sorted.distinctBy { it.url }
        }.getOrDefault(emptyList())

    /**
     * 从剧集名提取数字序号（用于排序）；提取不到返回 Int.MAX_VALUE 排最后。
     *
     * 优先用源配置的 matchEpisodeSortFromName（含命名组 (?<ep>...)），
     * 复用命名组转换逻辑（避免 groups["ep"] 的 API26 限制）。
     */
    private fun episodeNum(name: String, customPattern: String): Int {
        // 1) 源配置的正则（如 第\s*(?<ep>.+)\s*[话集]）
        if (customPattern.isNotBlank()) {
            val (re, idx) = compileWithNamedEp(customPattern)
            re.find(name)?.let { m ->
                val raw = if (idx != null) m.groupValues.getOrNull(idx) else m.groupValues.getOrNull(0)
                return raw?.let { epNumFrom(it) } ?: Int.MAX_VALUE
            }
        }
        // 2) 通用启发式兜底
        Regex("第\\s*(\\d+)").find(name)?.let { return it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
        Regex("(?:EP|Ep|ep)\\s*(\\d+)").find(name)?.let { return it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
        Regex("(?:^|[^0-9])(\\d{1,4})(?:[^0-9]|$)").find(name)?.let { return it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE }
        return Int.MAX_VALUE
    }

    /** 把 "第3集" / "EP03" / "3.5" 等转成可比较的数值（用于排序） */
    private fun epNumFrom(s: String): Int {
        val n = s.trim().filter { it.isDigit() }
        return n.toIntOrNull() ?: Int.MAX_VALUE
    }

    /** 把含命名组 (?<ep>...) 的正则转换为普通正则并返回 ep 组序号 */
    private fun compileWithNamedEp(pattern: String): Pair<Regex, Int?> =
        compileWithNamedGroup(pattern, "ep")

    /**
     * 第三步：剧集页 → 视频地址
     *
     * 1. 先用 matchVideoUrl 在本页文本匹配直链；
     * 2. 若本页没有、且启用 nestedUrl（60/63 源如此），则用 matchNestedUrl
     *    在页内找出「嵌套播放页」链接，由调用方跳转后再取直链。
     *
     * 注意：本函数只返回「本页」能直接匹配到的直链；
     * 嵌套跳转由 SubscribeRepository.fetchVideoUrl 循环处理。
     */
    fun extractVideoUrl(html: String, baseUrl: String, cfg: SearchConfig): String =
        runCatching {
            if (cfg.matchVideoUrl.isBlank()) return ""
            val (re, vGroup) = compileWithNamedV(cfg.matchVideoUrl)
            // 先分离候选 URL，再用源规则判断。^http 是对 URL 的约束，
            // 不能直接在 HTML 上 find，否则普通 CDN 的 JS 字符串永远不命中。
            val normalized = html.replace("\\/", "/").replace("&amp;", "&")
            val candidates = Regex("(?i)https?://[^\\s\"'<>\\\\]+")
            for (candidate in candidates.findAll(normalized)) {
                val url = candidate.value
                val match = re.find(url) ?: continue
                val captured = vGroup?.let { match.groupValues.getOrNull(it) }
                    ?.takeIf { it.isNotBlank() }
                val selected = captured ?: url
                if (isHttpUrl(selected)) return selected
            }
            // url=(?<v>...) 等规则可能捕获编码或协议相对地址。
            // 不因首个特征词（例如说明文字中的 akamaized）而放弃后续匹配。
            for (match in re.findAll(normalized)) {
                val captured = vGroup?.let { match.groupValues.getOrNull(it) }
                    ?.takeIf { it.isNotBlank() } ?: continue
                val raw = if (captured.startsWith("http://", true) || captured.startsWith("https://", true)) {
                    captured
                } else {
                    runCatching { java.net.URLDecoder.decode(captured, "UTF-8") }.getOrDefault(captured)
                }
                val resolved = resolveUrl(raw, baseUrl)
                if (isHttpUrl(resolved)) return resolved
            }
            ""
        }.getOrDefault("")

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        // rawUserInfo 非空说明含 user:pass@——这通常不是 CDN 直链，
        // 而是 mailto/邮箱等被误判为 URL 的解析产物，应拒绝。
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    /**
     * 把含命名组 (?<v>...) 的正则转换为普通正则，并返回 v 组的序号。
     *
     * 注意：这里不解析嵌套/转义，只处理 `(?<v>` 字面量替换 ——
     * 实测 25 个源里命名组名都叫 v，且 `(?<v>` 仅出现在组开头。
     */
    private fun compileWithNamedV(pattern: String): Pair<Regex, Int?> =
        compileWithNamedGroup(pattern, "v")

    /**
     * 把含命名组 (?<name>...) 的正则转换为普通正则，并返回该组的序号。
     *
     * 注意：这里不解析嵌套/转义，只处理 `(?<name>` 字面量替换 ——
     * 实测命名组名只有 v（视频地址）与 ep（集数），且 `(?<name>` 出现在组开头。
     * 组序号计算：数 `(?<name>` 之前有多少个非捕获组之外的 '('。
     */
    private fun compileWithNamedGroup(pattern: String, name: String): Pair<Regex, Int?> {
        val marker = "(?<$name>"
        val idx = pattern.indexOf(marker)
        if (idx < 0) return Regex(pattern) to null
        var groupNo = 1
        var i = 0
        var inClass = false
        var quoted = false
        while (i < idx) {
            val c = pattern[i]
            if (c == '\\' && i + 1 < idx) {
                val next = pattern[i + 1]
                if (next == 'Q' && !inClass) quoted = true
                if (next == 'E') quoted = false
                i += 2
                continue
            }
            if (!quoted) {
                if (c == '[') inClass = true
                if (c == ']') inClass = false
                if (c == '(' && !inClass) {
                    val special = pattern.getOrNull(i + 1) == '?'
                    val named = pattern.startsWith("(?<", i) &&
                        pattern.getOrNull(i + 3) !in listOf('=', '!')
                    if (!special || named) groupNo++
                }
            }
            i++
        }
        // 保留原表达式（包括反向引用）；仅以序号读命名组以兼容 API 24。
        return Regex(pattern) to groupNo
    }

    /**
     * 从页内提取「嵌套播放页」链接（nestedUrl 二级跳）
     *
     * matchNestedUrl 的语义（调研 + Animeko 源码）：
     * - `$^`：恒不匹配 → 表示「本页没有嵌套，直接用 matchVideoUrl」
     * - 其它正则：在页面里匹配嵌套播放页的 URL 片段（如 `xigua.php`、
     *   `vip`、`m3u8`），命中说明需要跳到该链接再取视频
     *
     * 这里返回：页面里所有符合 matchNestedUrl 的 http(s) 链接（去重）。
     */
    fun extractNestedUrls(html: String, baseUrl: String, cfg: SearchConfig): List<String> =
        runCatching {
            if (!cfg.enableNestedUrl) return emptyList()
            val pat = cfg.matchNestedUrl
            if (pat.isBlank() || pat == "$^") return emptyList()
            val re = Regex(pat)
            val doc = org.jsoup.Jsoup.parse(html, baseUrl)
            // 在文本与所有链接里找匹配
            val found = linkedSetOf<String>()
            // 1) 链接属性里的 http(s) 且匹配
            for (el in doc.select("a[href], iframe[src], script[src]")) {
                val attr = when {
                    el.hasAttr("href") -> "href"
                    el.hasAttr("src") -> "src"
                    else -> continue
                }
                val v = absUrl(el, attr, baseUrl)
                if (v.startsWith("http") && re.containsMatchIn(v)) found.add(v)
            }
            // 2) 页面文本里内嵌的 http 链接（正则抓）
            for (m in re.findAll(html)) {
                val u = m.value
                if (u.startsWith("http")) found.add(u)
            }
            found.toList()
        }.getOrDefault(emptyList())

    /**
     * 诊断：判断页面是否疑似「JS 动态渲染」（jsoup 拿不到真正内容）
     *
     * 启发式：
     * - 页面几乎没有可见文本（body 文本长度极小），但含 <script>
     * - 或含常见 SPA 框架的挂载点（#app / #root）且无实际内容
     *
     * 返回 null 表示「无法判断」，非 null 为诊断信息。
     */
    fun diagnoseJsRendered(html: String): String? {
        if (html.isBlank()) return "页面为空（可能被风控拦截或无权限）"
        return runCatching {
            val doc = org.jsoup.Jsoup.parse(html)
            val text = doc.body()?.text()?.trim().orEmpty()
            val scripts = doc.select("script").size
            val hasAppMount = doc.select("#app, #root, #__next, #app-root").isNotEmpty()
            // body 文本极少 + 有 script + 有 SPA 挂载点 → 极可能是 JS 渲染
            if (text.length < 40 && scripts > 0 && hasAppMount) {
                "该站点疑似 JS 动态渲染（jsoup 无法执行脚本，需 WebView/JS 引擎）"
            } else if (text.length < 40 && scripts > 3) {
                "该站点疑似 JS 动态渲染"
            } else null
        }.getOrNull()
    }

    /** 从元素取绝对 URL（jsoup 的 absUrl 需要 baseUri；这里显式兜底） */
    private fun absUrl(el: org.jsoup.nodes.Element, attr: String, base: String): String {
        val v = el.attr(attr).trim()
        if (v.isBlank()) return ""
        return resolveUrl(v, base)
    }

    /** 标准 URI resolve：正确处理 /、相对目录、../、协议相对链接。 */
    private fun resolveUrl(value: String, base: String): String {
        val v = value.trim()
        if (v.isBlank()) return ""
        return runCatching {
            when {
                v.startsWith("//") -> {
                    val scheme = java.net.URI(base).scheme ?: "https"
                    "$scheme:$v"
                }
                else -> java.net.URI(base).resolve(v).toString()
            }
        }.getOrDefault(v)
    }

    /** 构造搜索 URL（替换 {keyword} 占位） */
    fun buildSearchUrl(cfg: SearchConfig, keyword: String): String {
        // 审核：多数源 searchUseOnlyFirstWord=true，只取第一个词（站点搜索框
        // 通常只支持单关键词，多词反而搜不到）。空格等空白符也一并处理。
        val k = if (cfg.searchUseOnlyFirstWord) {
            keyword.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
                ?: keyword.trim()
        } else keyword.trim()
        // 审核：searchRemoveSpecial=true（51 源）移除特殊字符，避免破坏站点搜索
        val cleaned = if (cfg.searchRemoveSpecial) {
            k.replace(Regex("[^\\p{L}\\p{N}\\s]"), "").trim()
        } else k
        return cfg.searchUrl.replace("{keyword}", java.net.URLEncoder.encode(cleaned, "UTF-8"))
    }

    /**
     * 已废弃：调用方应传入完整页面 URL，由 URI.resolve 保留目录上下文。
     */
}
