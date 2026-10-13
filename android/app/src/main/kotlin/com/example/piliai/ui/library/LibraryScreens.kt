package com.example.piliai.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.FavFolder
import com.example.piliai.data.model.FavMedia
import com.example.piliai.data.model.HistoryItem

import com.example.piliai.utils.toCoverUrl
import com.example.piliai.data.repository.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.launch
import com.example.piliai.utils.toHttpsUrl

class LibraryViewModel(
    private val repo: LibraryRepository = LibraryRepository()
) : ViewModel() {

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    // 批次L21：历史搜索
    private val _historySearching = MutableStateFlow(false)
    val historySearching: StateFlow<Boolean> = _historySearching.asStateFlow()

    /** 搜索历史（keyword 空 = 恢复普通历史列表） */
    fun searchHistory(keyword: String) {
        if (keyword.isBlank()) { loadHistory(force = true); return }
        viewModelScope.launch {
            _historySearching.value = true
            _error.value = null
            repo.searchHistory(keyword)
                .onSuccess { resp -> _history.value = resp.data?.list.orEmpty() }
                .onFailure { _error.value = it.message ?: "搜索历史失败" }
            _historySearching.value = false
        }
    }

    private val _folders = MutableStateFlow<List<FavFolder>>(emptyList())
    val folders: StateFlow<List<FavFolder>> = _folders.asStateFlow()

    private val _medias = MutableStateFlow<List<FavMedia>>(emptyList())
    val medias: StateFlow<List<FavMedia>> = _medias.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 历史游标（cursor 分页，批次G）
    private var historyMax: Long = 0L
    private var historyViewAt: Long = 0L
    private var historyHasMore = false

    private val _toView = MutableStateFlow<List<HistoryItem>>(emptyList())
    val toView: StateFlow<List<HistoryItem>> = _toView.asStateFlow()

    fun loadHistory(force: Boolean = false) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            repo.history()
                .onSuccess { resp ->
                    _history.value = resp.data?.list.orEmpty()
                    historyMax = resp.data?.cursor?.max ?: 0L
                    historyViewAt = resp.data?.cursor?.view_at ?: 0L
                    historyHasMore = (resp.data?.cursor?.ps ?: 0) >= 20
                }
                .onFailure { _error.value = it.message ?: "加载历史失败" }
            _loading.value = false
        }
    }

    /** 历史 cursor 翻页 */
    fun loadMoreHistory() {
        if (_loading.value || !historyHasMore) return
        viewModelScope.launch {
            val api = BiliApiClient()
            api.getHistoryCursor(max = historyMax, viewAt = historyViewAt)
                .onSuccess { resp ->
                    val list = resp.data?.list.orEmpty()
                    if (list.isNotEmpty()) {
                        _history.value = _history.value + list
                        historyMax = resp.data?.cursor?.max ?: historyMax
                        historyViewAt = resp.data?.cursor?.view_at ?: historyViewAt
                        historyHasMore = list.size >= 20
                    } else historyHasMore = false
                }
        }
    }

    /** 删除单条历史 */
    fun delHistory(item: HistoryItem) {
        val kid = "${item.business}:${item.history?.oid ?: item.aid}"
        viewModelScope.launch {
            BiliApiClient().delHistory(kid).onSuccess { ok ->
                if (ok) _history.value = _history.value.filterNot { it.aid == item.aid }
            }
        }
    }

    /** 稍后再看列表 */
    fun loadToView() {
        viewModelScope.launch {
            BiliApiClient().getToView().onSuccess { resp ->
                if (resp.code == 0) {
                    _toView.value = resp.data.map { it.toHistoryItem() }
                }
            }
        }
    }

    /**
     * 审核268：清空稍后再看（成功后重新拉列表）。
     * 与 delToView 同一风格：直接调 API，结果经 message 告知 UI。
     */
    fun clearToView() {
        viewModelScope.launch {
            BiliApiClient().clearToView()
                .onSuccess { ok ->
                    _message.value = if (ok) "已清空稍后再看" else "清空失败"
                    if (ok) loadToView()
                }
                .onFailure { _message.value = "清空失败: ${it.message}" }
        }
    }

    /** 审核268：一次性提示（清空结果） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun delToView(aid: Long) {
        viewModelScope.launch {
            BiliApiClient().delToView(aid).onSuccess { ok ->
                if (ok) _toView.value = _toView.value.filterNot { it.aid == aid }
            }
        }
    }

    fun loadFolders(mid: Long) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            repo.favFolders(mid)
                .onSuccess { _folders.value = it.data.orEmpty() }
                .onFailure { _error.value = it.message ?: "加载收藏夹失败" }
            _loading.value = false
        }
    }

    // 审核轮203：收藏夹排序状态（"" 默认 / view 播放量 / pubtime 最新收藏）
    var favOrder: String = ""
        private set
    fun setFavOrder(mediaId: Long, order: String) {
        favOrder = order
        resetFavPage()
        loadMedias(mediaId, force = true)
    }

    fun loadMedias(mediaId: Long, force: Boolean = false) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            repo.favResources(mediaId, order = favOrder)
                .onSuccess { _medias.value = it.data?.medias.orEmpty() }
                .onFailure { _error.value = it.message ?: "加载收藏内容失败" }
            _loading.value = false
        }
    }

    // 收藏夹内容无限分页（批次L8）
    private var favPn = 1
    private var favLoadingMore = false
    private var favHasMore = true
    private val _favHasMore = MutableStateFlow(true)
    val favHasMoreFlow: StateFlow<Boolean> = _favHasMore.asStateFlow()

    fun loadMoreMedias(mediaId: Long) {
        if (favLoadingMore || !favHasMore) return
        favLoadingMore = true
        viewModelScope.launch {
            repo.favResourcesPage(mediaId, ++favPn, order = favOrder).onSuccess { (items, more) ->
                val known = _medias.value.map { it.id }.toSet()
                _medias.value = _medias.value + items.filterNot { it.id in known }
                favHasMore = more
                _favHasMore.value = more
            }.onFailure { favPn-- }
            favLoadingMore = false
        }
    }

    fun resetFavPage() { favPn = 1; favHasMore = true; _favHasMore.value = true }
}

/** 收藏夹列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    mid: Long,
    onBack: () -> Unit = {},
    onOpenFolder: (Long) -> Unit = {},
    viewModel: LibraryViewModel = viewModel()
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(mid) { viewModel.loadFolders(mid) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading && folders.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && folders.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text(error ?: "")
            }
            folders.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("暂无收藏夹")
            }
            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folders, key = { it.id }) { folder ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenFolder(folder.id) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder.title, fontWeight = FontWeight.Bold)
                                Text("${folder.media_count} 个内容",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 收藏夹内的视频 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavMediaScreen(
    mediaId: Long,
    onBack: () -> Unit = {},
    onOpenVideo: (bvid: String, cid: Long) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel = viewModel()
) {
    val medias by viewModel.medias.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    // 审核269：收藏夹内关键词过滤（本地过滤，不再走网络 —— 当前页数据全在内存）
    var keyword by remember(mediaId) { mutableStateOf("") }
    var showOrderMenu by remember(mediaId) { mutableStateOf(false) }
    val visibleMedias = remember(medias, keyword) {
        if (keyword.isBlank()) medias
        else medias.filter {
            it.title.contains(keyword, ignoreCase = true) ||
                it.upper?.name?.contains(keyword, ignoreCase = true) == true
        }
    }

    LaunchedEffect(mediaId) { viewModel.resetFavPage(); viewModel.loadMedias(mediaId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏内容") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 审核轮203：排序切换（默认/播放量/最新收藏，PiliPlus fav_sort 对齐）
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    val orderLabel = when (viewModel.favOrder) {
                        "view" -> "最多播放"; "pubtime" -> "最近收藏"; else -> "排序"
                    }
                    TextButton(onClick = { expanded = true }) { Text(orderLabel) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("" to "默认排序", "view" to "最多播放", "pubtime" to "最近收藏")
                            .forEach { (v, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { expanded = false; viewModel.setFavOrder(mediaId, v) }
                                )
                            }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { viewModel.loadMedias(mediaId, force = true) },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            when {
                loading && medias.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && medias.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error ?: "")
                }
                medias.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("暂无内容")
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 审核269：用过滤后的列表；空结果给明确提示，别显示白屏
                    if (visibleMedias.isEmpty() && keyword.isNotBlank()) {
                        item {
                            Box(
                                Modifier.fillMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) { Text("没有匹配「$keyword」的收藏") }
                        }
                    }
                    items(visibleMedias, key = { it.id }) { media ->
                        MediaRow(
                            cover = media.cover,
                            title = media.title,
                            subtitle = "${media.upper?.name.orEmpty()} · ${formatDur(media.duration)}",
                            onClick = { onOpenVideo(media.bvid, 0L) }
                        )
                    }
                    // 无限分页：滚到倒数第 3 个时加载下一页
                    if (medias.size >= 20) {
                        item {
                            LaunchedEffect(medias.size) { viewModel.loadMoreMedias(mediaId) }
                            Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 观看历史 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit = {},
    onOpenVideo: (bvid: String, cid: Long) -> Unit = { _, _ -> },
    onLogin: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel()
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val historySearching by viewModel.historySearching.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("观看历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 历史搜索框（批次L21）
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("搜索观看历史") },
                singleLine = true,
                trailingIcon = {
                    Row {
                        IconButton(onClick = { viewModel.searchHistory(searchQuery) }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""
                                viewModel.searchHistory("")
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "清除")
                            }
                        }
                    }
                }
            )
            Box(Modifier.weight(1f)) {
                PullToRefreshBox(
                    isRefreshing = loading || historySearching,
                    onRefresh = { viewModel.loadHistory() },
                    modifier = Modifier.fillMaxSize()
                ) {
            when {
                loading && history.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && history.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    // 审核48：错误态给出明确的登录引导，而非仅文字
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${error}\n（历史记录需要登录后查看）", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onLogin) { Text("去登录") }
                    }
                }
                history.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("暂无观看记录")
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history, key = { "${it.aid}-${it.history?.cid}" }) { item ->
                        MediaRow(
                            cover = item.cover,
                            title = item.title,
                            subtitle = "${item.author_name} · ${formatDur(item.duration)}" +
                                if (item.progress > 0 && item.duration > 0)
                                    " · 已看 ${item.progress * 100 / item.duration}%" else "",
                            onClick = { onOpenVideo(item.bvid, item.history?.cid ?: 0L) },
                            onLongClick = { viewModel.delHistory(item) }
                        )
                        if (item == history.last() && item != history.first()) {
                            // 触底加载更多（cursor 分页）
                            LaunchedEffect(history.size) { viewModel.loadMoreHistory() }
                        }
                    }
                }
            }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    cover: String, title: String, subtitle: String,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {}
) {
    // 审核271：对齐 Kototoro 列表项规格（KototoroContentCard.kt:630/640/650）——
    // 行内边距 16/8dp、卡片圆角 16dp、零 elevation（Kototoro 用 surface 分层而非阴影）、
    // 封面用 CompactContentCoverShape 8dp 圆角。
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick, onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = cover.toCoverUrl(),
                contentDescription = title,
                modifier = Modifier
                    .width(120.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatDur(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/** 稍后再看（批次G） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToViewScreen(
    onBack: () -> Unit = {},
    onOpenVideo: (String, Long) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel = viewModel()
) {
    val toView by viewModel.toView.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadToView() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("稍后再看") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 审核268：清空全部。有内容才显示，避免空列表时给个无效按钮
                actions = {
                    if (toView.isNotEmpty()) {
                        var confirmClear by remember { mutableStateOf(false) }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空稍后再看")
                        }
                        if (confirmClear) {
                            AlertDialog(
                                onDismissRequest = { confirmClear = false },
                                title = { Text("清空稍后再看？") },
                                text = { Text("将移除全部 ${toView.size} 个视频，此操作不可撤销。") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        viewModel.clearToView()
                                        confirmClear = false
                                    }) { Text("清空") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { viewModel.loadToView() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            when {
                loading && toView.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                toView.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("暂无稍后再看视频")
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(toView, key = { it.aid }) { item ->
                        MediaRow(
                            cover = item.cover,
                            title = item.title,
                            subtitle = "${item.author_name} · ${formatDur(item.duration)}",
                            onClick = { onOpenVideo(item.bvid, item.history?.cid ?: 0L) },
                            onLongClick = { viewModel.delToView(item.aid) }
                        )
                    }
                }
            }
        }
    }
}