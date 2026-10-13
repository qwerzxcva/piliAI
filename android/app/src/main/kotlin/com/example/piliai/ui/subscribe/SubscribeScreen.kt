package com.example.piliai.ui.subscribe

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import com.example.piliai.utils.toCoverUrl
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.piliai.database.SubscribeItemEntity
import com.example.piliai.database.SubscribeSourceEntity
import com.example.piliai.database.SubscribeTitleEntity
import com.example.piliai.ui.theme.PiliCardShape

/**
 * 订阅页（Animeko「订阅」移植）
 *
 * - 顶部：刷新 / 添加订阅源
 * - 内容：订阅源解析出的条目网格（封面 + 标题 + 分集）
 * - 点击条目：解析播放地址 → 交给 PiliNara 播放器
 * - 管理：长按/源卡片可停用、删除
 */
/**
 * 订阅页 ViewModel 工厂：注入 Room DAO（ViewModel 不持有 Context，避免泄漏）
 */
class SubscribeViewModelFactory : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SubscribeViewModel::class.java)) {
            val db = com.example.piliai.database.PiliNaraDatabase
                .getDatabase(com.example.piliai.AppContext.get())
            return SubscribeViewModel(
                com.example.piliai.data.repository.SubscribeRepository(
                    db.subscribeSourceDao(),
                    db.subscribeItemDao(),
                    db.subscribeTitleDao()
                )
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscribeScreen(
    viewModel: SubscribeViewModel = viewModel(factory = SubscribeViewModelFactory()),
    onPlay: (url: String, title: String, cover: String) -> Unit,
    onBack: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onOpenSubject: (Long) -> Unit = {},
    onSourcePlay: (com.example.piliai.playback.SourcePlaybackRequest, String, String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val libraryQuery by viewModel.libraryQuery.collectAsStateWithLifecycle()
    var showLibrarySearch by remember { mutableStateOf(false) }
    var searchTarget by remember { mutableStateOf<SubscribeItemEntity?>(null) }
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val currentSubject by viewModel.currentSubject.collectAsStateWithLifecycle()
    // 网页刮削源：卡片记录所属源的配置 URL + 源名（存于 desc/sourceName）
    var webTarget by remember { mutableStateOf<SubscribeItemEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val enabledSourceIds = state.sources.filter { it.enabled }.map { it.id }.toSet()
    val query = libraryQuery.trim()
    // 审核256：搜索结果与订阅源条目是两种类型，统一映射成 SubscribeItemEntity
    // 供同一套卡片渲染；搜索结果额外带 kind，点击分派时要用。
    // 用普通 HashMap 而非 mutableStateMapOf：它只在点击时被读，不需要触发重组。
    val searchKindOf = remember { HashMap<String, SearchResultKind>() }
    val displayItems = (if (state.isSearching) {
        searchResults
            .filter { it.sourceId in enabledSourceIds }
            .filter { item ->
                query.isBlank() ||
                    item.title.contains(query, ignoreCase = true) ||
                    item.sourceName.contains(query, ignoreCase = true) ||
                    item.desc.contains(query, ignoreCase = true)
            }
            .onEach { item ->
                // 键 = sourceId + link（同一源内 link 唯一）
                searchKindOf["${item.sourceId}:${item.link}"] = item.kind
            }
            .map { item ->
                SubscribeItemEntity(
                    id = 0L,
                    sourceId = item.sourceId,
                    title = item.title,
                    cover = item.cover,
                    link = item.link,
                    desc = item.desc,
                    episode = item.episode,
                    sourceName = item.sourceName
                )
            }
    } else {
        state.items
            .filter { it.sourceId in enabledSourceIds }
            .filter { item ->
                query.isBlank() ||
                    item.title.contains(query, ignoreCase = true) ||
                    item.sourceName.contains(query, ignoreCase = true) ||
                    item.desc.contains(query, ignoreCase = true)
            }
    })
    // An empty episode list is still an episode view, not the search results.
    val inEpisodeView = currentSubject != null
    val displayItems2 = if (inEpisodeView) {
        episodes.map {
            SubscribeItemEntity(
                id = 0L,
                title = it.name,
                link = it.url,
                desc = it.channel,
                sourceName = "episode"
            )
        }
    } else displayItems
    var showAddDialog by remember { mutableStateOf(false) }
    var showManageDialog by remember { mutableStateOf(false) }
    var showBangumiLoginDialog by remember { mutableStateOf(false) }

    // 错误/提示统一走 Snackbar，展示后清空避免重复弹出
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearInfo()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (showLibrarySearch && !state.isSearching && currentSubject == null) {
                        OutlinedTextField(
                            value = libraryQuery,
                            onValueChange = viewModel::setLibraryQuery,
                            singleLine = true,
                            placeholder = {
                                Text(stringResource(com.example.piliai.R.string.subscribe_search_library))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(currentSubject ?: "订阅")
                    }
                },
                navigationIcon = {
                    if (currentSubject != null || state.isSearching) {
                        IconButton(onClick = {
                            if (currentSubject != null) viewModel.backToSubjects()
                            else viewModel.clearSearch()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!state.isSearching && currentSubject == null) {
                        IconButton(onClick = {
                            showLibrarySearch = !showLibrarySearch
                            if (!showLibrarySearch) viewModel.setLibraryQuery("")
                        }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(com.example.piliai.R.string.subscribe_search_library)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加订阅源")
                    }
                    IconButton(onClick = { showManageDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "管理订阅源")
                    }
                    IconButton(onClick = { showBangumiLoginDialog = true }) {
                        Icon(Icons.Default.Person, contentDescription = "Bangumi 登录")
                    }
                }
            )
        }
    ) { padding ->
        // 审核240：追番分段筛选（animeko 收藏页 COLLECTION_TABS_SORTED 对齐）
        Column(Modifier.fillMaxSize().padding(padding)) {
            val titleFilter by viewModel.titleFilter.collectAsStateWithLifecycle()
            val followedTitles by viewModel.visibleTitles.collectAsStateWithLifecycle()
            val unwatchedTotal by viewModel.unwatchedTotal.collectAsStateWithLifecycle()
            // 有追番才显示筛选条（没追番时保持原有「订阅源条目」视图）
            if (followedTitles.isNotEmpty() || titleFilter != SubscribeViewModel.TITLE_FILTER_ALL) {
                val filterLabels = listOf(
                    "全部",
                    "想看",
                    "在看",
                    "看过",
                    "搁置",
                    "抛弃",
                    "更新" + if (unwatchedTotal > 0) " $unwatchedTotal" else ""
                )
                ScrollableTabRow(
                    selectedTabIndex = titleFilter,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    filterLabels.forEachIndexed { index, label ->
                        Tab(
                            selected = titleFilter == index,
                            onClick = { viewModel.setTitleFilter(index) },
                            text = { Text(label, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refreshAll() },
                modifier = Modifier.fillMaxSize()
            ) {
            when {
                displayItems2.isEmpty() && !state.isRefreshing -> {
                    if (state.isSearching || currentSubject != null || libraryQuery.isNotBlank()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(com.example.piliai.R.string.subscribe_search_empty))
                        }
                    } else {
                        EmptySubscribeHint(onAdd = { showAddDialog = true })
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 审核240：追番卡片区（全宽 header + 每部作品一行卡）
                        if (followedTitles.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "我的追番" + if (unwatchedTotal > 0) " · $unwatchedTotal 集未看" else "",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            items(followedTitles, key = { it.id }) { title ->
                                FollowedTitleCard(
                                    title = title,
                                    onToggleWatched = {
                                        // 点「标记看到」：+1 集，到达最新集后归零重看
                                        val next = if (title.lastWatchedEpisode >= title.latestEpisode) 0
                                            else title.lastWatchedEpisode + 1
                                        viewModel.setTitleWatched(title.id, next)
                                    },
                                    onCycleCollectionType = {
                                        // 点击类型 chip：想看→看过→在看→搁置→抛弃→想看
                                        // （审核240：顺序按 Bangumi 官方编码 1/2/3/4/5）
                                        val next = when (title.collectionType) {
                                            SubscribeTitleEntity.COLLECTION_WISH -> SubscribeTitleEntity.COLLECTION_DONE
                                            SubscribeTitleEntity.COLLECTION_DONE -> SubscribeTitleEntity.COLLECTION_DOING
                                            SubscribeTitleEntity.COLLECTION_DOING -> SubscribeTitleEntity.COLLECTION_ON_HOLD
                                            SubscribeTitleEntity.COLLECTION_ON_HOLD -> SubscribeTitleEntity.COLLECTION_DROPPED
                                            else -> SubscribeTitleEntity.COLLECTION_WISH
                                        }
                                        viewModel.setTitleCollectionType(title.id, next)
                                    },
                                    onUnfollow = { viewModel.unfollowTitle(title.id) }
                                )
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "订阅源条目",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                )
                            }
                        }
                        items(displayItems2, key = { item ->
                            buildString {
                                append(item.sourceId).append(':')
                                for (part in listOf(item.link, item.title, item.desc)) {
                                    append(part.length).append(':').append(part)
                                }
                            }
                        }) { item ->
                            SubscribeItemCard(
                                item = item,
                                onInfo = if (inEpisodeView) null else {
                                    { viewModel.lookupBangumi(item.title, onOpenSubject) }
                                },
                                // 审核240：追番入口。作品条目可追番；剧集/RSS 不显示。
                                isFollowed = if (inEpisodeView) false else viewModel.isFollowed(
                                    item.sourceId,
                                    item.link.ifBlank { item.title }
                                ),
                                onFollow = if (inEpisodeView) null else {
                                    {
                                        val key = item.link.ifBlank { item.title }
                                        if (viewModel.isFollowed(item.sourceId, key)) {
                                            // 已追番：取消（需要 id，由 ViewModel 反查）
                                            viewModel.unfollowByKey(item.sourceId, key)
                                        } else {
                                            viewModel.followTitle(
                                                sourceId = item.sourceId,
                                                sourceKey = key,
                                                name = item.title,
                                                cover = item.cover,
                                                intro = item.desc
                                            )
                                        }
                                    }
                                }
                            ) {
                                // 审核轮8：不是所有条目都有可播放直链。
                                // RSS 的 <link> 常是网页而非媒体；只有 enclosure/直链
                                // 才能直接交给 ExoPlayer。这里先判定可否播放，
                                // 不能播放时给出明确提示，而不是把网页 URL 丢给播放器
                                // 导致「点了没反应/一直转圈」。
                                when {
                                    // 剧集：取直链后播放
                                    item.sourceName == "episode" ->
                                        viewModel.playEpisode(item.link) { request ->
                                            onSourcePlay(request, item.title, item.cover)
                                        }
                                    // 作品：进剧集列表
                                    // 审核256：搜索结果的作品页也走这里。此前搜索
                                    // 结果的 sourceName 存的是源显示名（如「樱花动漫」），
                                    // 匹配不上 "subject"，于是落到 classifySubscriptionLink
                                    // 被判 WEB_PAGE → 报「不可播放」。现在用 kind 判定，
                                    // 与 sourceName 存什么无关。
                                    item.sourceName == "subject" ||
                                        searchKindOf["${item.sourceId}:${item.link}"] ==
                                        SearchResultKind.WORK ->
                                        viewModel.openSubject(item.link, item.title)
                                    // RSS 型 Animeko 源：link 是 searchUrl（.xml），
                                    // 点开应弹关键词搜索（走 RSS 解析）
                                    // 审核256：搜索结果也按 kind 判定（它的 sourceName
                                    // 存的是源显示名，匹配不上 "rss"）。
                                    // 括号必须有：混用 && / || 时 Kotlin 左结合会把
                                    // 后面的直链判定吞掉。
                                    (item.sourceName == "rss") ||
                                        (searchKindOf["${item.sourceId}:${item.link}"] ==
                                            SearchResultKind.RSS_ITEM &&
                                            looksLikeRssFeedUrl(item.link)) -> searchTarget = item
                                    // 磁力/种子不能交给 ExoPlayer，也不能当成网页去刮。
                                    classifySubscriptionLink(item.link) ==
                                        SubscriptionLinkKind.BITTORRENT ->
                                        viewModel.reportBitTorrent(item)
                                    // 可直接播放的直链
                                    classifySubscriptionLink(item.link) ==
                                        SubscriptionLinkKind.DIRECT_MEDIA ->
                                        onPlay(item.link, item.title, item.cover)
                                    // 无直链：网页刮削源 → 弹搜索框
                                    item.link.isBlank() -> webTarget = item
                                    else -> viewModel.reportNotPlayable(item)
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url, name, type ->
                viewModel.addSource(url, name, type)
                showAddDialog = false
            }
        )
    }

    if (showManageDialog) {
        ManageSourcesDialog(
            sources = state.sources,
            onDismiss = { showManageDialog = false },
            onToggle = { id, enabled -> viewModel.toggleSource(id, enabled) },
            onDelete = { id -> viewModel.removeSource(id) }
        )
    }

    searchTarget?.let { target ->
        SearchInSourceDialog(
            sourceName = target.title,
            supportsWebSelector = false,
            onDismiss = { searchTarget = null },
            onSearch = { kw ->
                // RSS 型：link 就是 searchUrl，sourceName 存 "rss"
                viewModel.searchInSource(
                    searchUrl = target.link,
                    keyword = kw,
                    factoryId = target.sourceName,
                    sourceId = target.sourceId
                )
                searchTarget = null
            }
        )
    }

    if (state.bangumiCandidates.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBangumiCandidates() },
            title = { Text(stringResource(com.example.piliai.R.string.bangumi_pick_subject)) },
            text = {
                Column {
                    state.bangumiCandidates.forEach { subject ->
                        Text(
                            text = buildString {
                                append(subject.title)
                                if (subject.date.isNotBlank()) append(" · ").append(subject.date)
                                if (subject.rating.score > 0) {
                                    append(" · ").append(String.format(java.util.Locale.ROOT, "%.1f", subject.rating.score))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.dismissBangumiCandidates()
                                    onOpenSubject(subject.id)
                                }
                                .padding(vertical = 10.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.dismissBangumiCandidates() }) {
                    Text(stringResource(com.example.piliai.R.string.bangumi_back))
                }
            }
        )
    }

    webTarget?.let { target ->
        SearchInSourceDialog(
            sourceName = target.title,
            supportsWebSelector = true,
            onDismiss = { webTarget = null },
            onSearch = { kw ->
                // target.episode 存的是订阅配置文件 URL，
                // target.sourceName 存的是 factoryId
                if (target.sourceName.equals("rss", ignoreCase = true)) {
                    viewModel.searchInSource(target.link, kw, target.sourceName, target.sourceId)
                } else {
                    viewModel.searchWebSource(target.episode, target.title, kw)
                }
                webTarget = null
            }
        )
    }

    if (showBangumiLoginDialog) {
        BangumiLoginDialog(
            onDismiss = { showBangumiLoginDialog = false },
            onLogin = { token ->
                viewModel.loginBangumi(token)
                showBangumiLoginDialog = false
            },
            onLogout = {
                viewModel.logoutBangumi()
                showBangumiLoginDialog = false
            }
        )
    }
}

/**
 * 在 Animeko 数据源内按关键词搜索
 *
 * RSS 型源直接解析返回的 feed；网页刮削型源继续经过作品、剧集和视频地址三步链路。
 */
@Composable
private fun SearchInSourceDialog(
    sourceName: String,
    supportsWebSelector: Boolean,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit
) {
    var kw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在「$sourceName」中搜索") },
        text = {
            Column {
                OutlinedTextField(
                    value = kw,
                    onValueChange = { kw = it },
                    label = { Text("关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        if (supportsWebSelector) {
                            com.example.piliai.R.string.subscribe_search_rss_and_web_hint
                        } else {
                            com.example.piliai.R.string.subscribe_search_rss_hint
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSearch(kw) }, enabled = kw.isNotBlank()) { Text("搜索") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * Bangumi 登录弹窗（Animeko「Bangumi 登录」移植）
 *
 * 使用个人访问令牌（api.bgm.tv 后台生成），登录时会真实请求 /v0/me 校验，
 * 校验失败不会写入任何凭据（不做"假登录"）。
 */
@Composable
private fun BangumiLoginDialog(
    onDismiss: () -> Unit,
    onLogin: (String) -> Unit,
    onLogout: () -> Unit
) {
    var token by remember { mutableStateOf("") }
    val loggedIn = com.example.piliai.data.remote.BangumiSession.isLogin
    val nickname = com.example.piliai.data.remote.BangumiSession.nickname

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bangumi 登录") },
        text = {
            Column {
                if (loggedIn) {
                    Text(
                        "已登录：${nickname.ifBlank { "Bangumi 用户" }}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "登录后可同步 Bangumi 收藏与收视进度（用于订阅源）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "在 bgm.tv 生成个人访问令牌后粘贴到下方",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("访问令牌") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (loggedIn) {
                TextButton(onClick = onLogout) { Text("退出登录") }
            } else {
                TextButton(
                    onClick = { onLogin(token) },
                    enabled = token.isNotBlank()
                ) { Text("登录") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

internal enum class SubscriptionLinkKind {
    EMPTY,
    BITTORRENT,
    DIRECT_MEDIA,
    WEB_PAGE
}

/**
 * 订阅条目链接分类。只看 URL 本身，不发起网络请求。
 *
 * 磁力与种子不是网页，也不能交给 ExoPlayer；直链媒体、HLS 与 DASH
 * 才进播放器。判定为直链不代表一定能播（可能 403/风控）。
 */

/**
 * 审核256：判断链接是否是 RSS/Atom feed 地址。
 * RSS 型 Animeko 源的 searchUrl 就是 feed 地址（.xml/.rss/atom），
 * 点它不该进播放器，而应弹关键词搜索框。
 */
private fun looksLikeRssFeedUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".xml") || path.endsWith(".rss") ||
        path.endsWith(".atom") || path.endsWith("/feed") ||
        path.contains("/rss") || path.contains("/feed")
}

internal fun classifySubscriptionLink(url: String): SubscriptionLinkKind {
    val value = url.trim()
    if (value.isEmpty()) return SubscriptionLinkKind.EMPTY
    if (isBitTorrentLink(value)) return SubscriptionLinkKind.BITTORRENT
    if (isDirectMediaLink(value)) return SubscriptionLinkKind.DIRECT_MEDIA
    return SubscriptionLinkKind.WEB_PAGE
}

private fun isBitTorrentLink(url: String): Boolean {
    if (url.startsWith("magnet:", ignoreCase = true)) {
        val query = url.substringAfter('?', "")
        return query.split('&').any { parameter ->
            val separator = parameter.indexOf('=')
            if (separator < 0) return@any false
            val key = parameter.substring(0, separator)
            val raw = parameter.substring(separator + 1)
            key.equals("xt", ignoreCase = true) &&
                raw.startsWith("urn:btih:", ignoreCase = true) &&
                isBitTorrentInfoHash(raw.substring(9))
        }
    }
    val path = linkPath(url)
    return path.endsWith(".torrent", ignoreCase = true)
}

private fun isBitTorrentInfoHash(value: String): Boolean {
    val hash = value.substringBefore('&').substringBefore('/')
    val hex = hash.length == 40 && hash.all {
        it.isDigit() || it.lowercaseChar() in 'a'..'f'
    }
    val base32 = hash.length == 32 && hash.all {
        val c = it.uppercaseChar()
        (c in 'A'..'Z' && c !in listOf('I', 'L', 'O')) || c in '2'..'7'
    }
    return hex || base32
}

private fun isDirectMediaLink(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.contains(".m3u8") || lower.contains(".mpd")) return true
    val path = linkPath(url)
    return listOf(
        ".mp4", ".m4v", ".webm", ".mkv", ".flv",
        ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav", ".ts"
    ).any { path.endsWith(it, ignoreCase = true) }
}

private fun linkPath(url: String): String {
    val withoutFragment = url.substringBefore('#')
    val withoutQuery = withoutFragment.substringBefore('?')
    return runCatching { withoutQuery.toUri().path ?: withoutQuery }
        .getOrDefault(withoutQuery)
}

@Composable
private fun EmptySubscribeHint(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "还没有订阅内容",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "添加订阅源链接后，这里会显示可播放的条目",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd) { Text("添加订阅源") }
        }
    }
}

@Composable
private fun SubscribeItemCard(
    item: SubscribeItemEntity,
    onInfo: (() -> Unit)? = null,
    isFollowed: Boolean = false,
    onFollow: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            Box {
                AsyncImage(
                    model = item.cover.ifBlank { null },
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentScale = ContentScale.Crop
                )
                if (item.episode.isNotBlank()) {
                    Text(
                        text = item.episode,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                    )
                }
            }
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (onFollow != null) {
                        // 审核240：追番按钮（已追番时实心，未追番时空心）
                        IconButton(onClick = onFollow) {
                            Icon(
                                imageVector = if (isFollowed) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                contentDescription = if (isFollowed) "取消追番" else "追番",
                                tint = if (isFollowed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (onInfo != null) {
                        IconButton(onClick = onInfo) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = stringResource(com.example.piliai.R.string.bangumi_subject_details)
                            )
                        }
                    }
                }
                if (item.desc.isNotBlank() && item.sourceName != "rss" && item.sourceName != "episode") {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.sourceName.isNotBlank() && item.sourceName != "rss") {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String, type: Int) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(SubscribeSourceEntity.TYPE_BANGUMI) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加订阅源") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("订阅源链接") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == SubscribeSourceEntity.TYPE_BANGUMI,
                        onClick = { type = SubscribeSourceEntity.TYPE_BANGUMI },
                        label = { Text("Bangumi") }
                    )
                    FilterChip(
                        selected = type == SubscribeSourceEntity.TYPE_RSS,
                        onClick = { type = SubscribeSourceEntity.TYPE_RSS },
                        label = { Text("RSS") }
                    )
                    FilterChip(
                        selected = type == SubscribeSourceEntity.TYPE_JSON,
                        onClick = { type = SubscribeSourceEntity.TYPE_JSON },
                        label = { Text("JSON") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url, name, type) },
                enabled = url.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ManageSourcesDialog(
    sources: List<SubscribeSourceEntity>,
    onDismiss: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理订阅源") },
        text = {
            if (sources.isEmpty()) {
                Text("暂无订阅源", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    sources.forEach { s ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.name.ifBlank { s.url },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    s.typeLabel + if (s.lastError != null) " · ${s.lastError}" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = s.enabled, onCheckedChange = { onToggle(s.id, it) })
                            IconButton(onClick = { onDelete(s.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * 审核240：追番卡片（animeko 收藏页条目）。
 *
 * 一屏一部作品：封面 + 名称 + 收藏状态 + 进度。交互：
 * - 点进度 chip：看到下一集（到最新集后归零，方便重看）
 * - 点状态 chip：收藏状态循环
 * - 点右侧关闭：取消追番
 */
@Composable
private fun FollowedTitleCard(
    title: SubscribeTitleEntity,
    onToggleWatched: () -> Unit,
    onCycleCollectionType: () -> Unit,
    onUnfollow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = PiliCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = title.cover.toCoverUrl(),
                contentDescription = title.name,
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 进度 chip：点一下 +1 集
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable(onClick = onToggleWatched)
                    ) {
                        Text(
                            text = if (title.latestEpisode > 0)
                                "${title.lastWatchedEpisode}/${title.latestEpisode}"
                            else "第 ${title.lastWatchedEpisode} 集",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (title.hasUnwatched) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    // 收藏状态 chip：点一下循环
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clickable(onClick = onCycleCollectionType)
                    ) {
                        Text(
                            text = when (title.collectionType) {
                                SubscribeTitleEntity.COLLECTION_WISH -> "想看"
                                SubscribeTitleEntity.COLLECTION_DONE -> "看过"
                                SubscribeTitleEntity.COLLECTION_ON_HOLD -> "搁置"
                                SubscribeTitleEntity.COLLECTION_DROPPED -> "抛弃"
                                else -> "在看"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    if (title.rating > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = String.format(java.util.Locale.US, "★ %.1f", title.rating),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onUnfollow) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "取消追番",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}