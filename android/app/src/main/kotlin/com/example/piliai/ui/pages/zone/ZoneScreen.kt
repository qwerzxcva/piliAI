package com.example.piliai.ui.pages.zone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.piliai.data.model.NewListArchive
import com.example.piliai.data.model.VIDEO_ZONES
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

class ZoneViewModel : ViewModel() {
    private val _items = MutableStateFlow<List<NewListArchive>>(emptyList())
    val items: StateFlow<List<NewListArchive>> = _items.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var currentRid by mutableIntStateOf(1)
        private set
    private var page = 1
    private var loadingMore = false
    private val seen = mutableSetOf<String>()

    fun selectZone(rid: Int) {
        currentRid = rid
        page = 1
        seen.clear()
        _items.value = emptyList()
        load()
    }

    fun refresh() {
        page = 1
        seen.clear()
        _items.value = emptyList()
        load()
    }

    fun loadMore() {
        if (loadingMore || _loading.value) return
        loadingMore = true
        page += 1
        fetch()
    }

    init { load() }

    private fun load() {
        _loading.value = true
        _error.value = null
        fetch()
    }

    private fun fetch() {
        viewModelScope.launch {
            BiliApiClient().getNewList(currentRid, page)
                .onSuccess { resp ->
                    if (resp.code == 0) {
                        val fresh = resp.data?.archives.orEmpty().filter { it.bvid.isNotEmpty() && seen.add(it.bvid) }
                        _items.value = _items.value + fresh
                    } else {
                        _error.value = resp.message
                    }
                }
                .onFailure { _error.value = it.message ?: "加载失败" }
            _loading.value = false
            loadingMore = false
        }
    }
}

/** 批次L32：视频分区浏览（newlist 匿名可用） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneScreen(
    onBack: () -> Unit = {},
    onOpenVideo: (bvid: String, cid: Long) -> Unit = { _, _ -> },
    onOpenUser: (mid: Long) -> Unit = {},
    viewModel: ZoneViewModel = viewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    // 滚动近底自动加载（对齐 HomeScreen 模式）
    LaunchedEffect(gridState, items.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 4 && info.totalItemsCount > 0
        }.collect { should -> if (should) viewModel.loadMore() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分区浏览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 分区横向滚动条
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VIDEO_ZONES.forEach { z ->
                    FilterChip(
                        selected = viewModel.currentRid == z.rid,
                        onClick = { viewModel.selectZone(z.rid) },
                        label = { Text(z.name) }
                    )
                }
            }
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && items.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(error ?: "", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.refresh() }) { Text("重试") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    // 审核285：间距对齐首页（Kototoro GridSpacing 8dp、页边 12dp）——
                    // 原先是 4dp/4dp，比首页密一倍，两页观感不一致。
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.bvid }) { v ->
                        ZoneCard(v, onOpenUser) { onOpenVideo(v.bvid, v.cid) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoneCard(v: NewListArchive, onOpenUser: (Long) -> Unit, onClick: () -> Unit) {
    // 审核285：改用公共 VideoGridCard（与首页同一份实现、同一套 Kototoro 封面
    // 规格）。原先是 Card 包 Column 的旧结构：封面只有 topStart/topEnd 圆角、
    // 无描边无阴影、标题在封面外且用 bodySmall、UP 名与播放数挤在一行——
    // 分区页和首页曾是两套视觉语言，这是「翻两页就觉得廉价」的主因之一。
    com.example.piliai.ui.components.VideoGridCard(
        title = v.title,
        coverUrl = v.pic.toCoverUrl(),
        durationText = formatDuration(v.duration),
        viewCountText = "${v.stat?.view ?: 0} 播放",
        authorName = v.owner?.name ?: "",
        onAuthorClick = if ((v.owner?.mid ?: 0L) > 0) {
            { v.owner?.let { onOpenUser(it.mid) } }
        } else {
            null
        },
        onClick = onClick,
    )
}

private fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
