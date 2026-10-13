package com.example.piliai.ui.pages.rank

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.RankItem
import com.example.piliai.data.model.RankOwner
import com.example.piliai.data.model.RankStat
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

/** 排行榜（批次L17） */
class RankViewModel : ViewModel() {
    private val _items = MutableStateFlow<List<RankItem>>(emptyList())
    val items: StateFlow<List<RankItem>> = _items.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(rid: Int) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            if (rid == 0) {
                // 全站榜：ranking/v2
                BiliApiClient().getRanking(rid)
                    .onSuccess { _items.value = it.data?.list.orEmpty() }
                    .onFailure { _error.value = it.message ?: "加载失败" }
            } else {
                // 分区榜（批次L34）：ranking/region 匿名可用，11 条/分区
                BiliApiClient().getRegionRanking(rid)
                    .onSuccess { resp ->
                        _items.value = resp.data.map { r ->
                            RankItem(
                                aid = r.aid, bvid = r.bvid, title = r.title,
                                pic = r.pic, score = r.pts, duration = r.duration.toInt(),
                                owner = RankOwner(mid = r.mid, name = r.author),
                                stat = r.stats?.let { RankStat(view = it.view, danmaku = it.danmaku) }
                            )
                        }
                    }
                    .onFailure { _error.value = it.message ?: "加载失败" }
            }
            _loading.value = false
        }
    }
}

// rid 分区（对齐 Flutter rank 页常用分区）
private val RANK_TABS = listOf(
    "全站" to 0, "动画" to 1, "游戏" to 4, "音乐" to 3,
    "科技" to 36, "生活" to 160, "娱乐" to 5, "影视" to 23
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankScreen(
    onBack: () -> Unit = {},
    onOpenVideo: (bvid: String) -> Unit = { _ -> },
    viewModel: RankViewModel = viewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var currentTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentTab) { viewModel.load(RANK_TABS[currentTab].second) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("排行榜") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = currentTab, edgePadding = 8.dp) {
                RANK_TABS.forEachIndexed { i, (name, _) ->
                    Tab(
                        selected = i == currentTab,
                        onClick = { currentTab = i },
                        text = { Text(name) }
                    )
                }
            }
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(error ?: "")
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(items) { idx, item ->
                        RankRow(idx + 1, item, onClick = { onOpenVideo(item.bvid) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, item: RankItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$rank",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                rank == 1 -> androidx.compose.ui.graphics.Color(0xFFFB7299)
                rank == 2 -> androidx.compose.ui.graphics.Color(0xFFFF9F43)
                rank == 3 -> androidx.compose.ui.graphics.Color(0xFFF7B731)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(32.dp)
        )
        AsyncImage(
            model = item.pic.toCoverUrl(), contentDescription = item.title,
            modifier = Modifier.size(width = 120.dp, height = 72.dp)
                .clip(MaterialTheme.shapes.medium)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(item.owner?.name ?: "")
                    append(" · ")
                    append(formatViews(item.stat?.view ?: 0))
                },
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatViews(v: Long): String = when {
    v >= 100_000_000 -> "%.1f亿".format(v / 100_000_000f)
    v >= 10_000 -> "%.1f万".format(v / 10_000f)
    else -> v.toString()
}
