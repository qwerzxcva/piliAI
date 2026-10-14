package com.example.piliai.ui.pages.bangumi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.PgcIndexItem
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

/** season_type 选项（与 B 站索引一致） */
private val SEASON_TYPES = listOf("番剧" to 1, "电影" to 2, "纪录片" to 3, "国创" to 4, "电视剧" to 5, "综艺" to 7)
private val ORDERS = listOf("更新时间" to "update", "最高评分" to "score", "最多播放" to "play")

/**
 * 批次L2：pgc 首页分类页——type 横滑 Tab + 排序 + 网格卡片 + 无限分页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgcIndexScreen(
    onBack: () -> Unit,
    onOpenSeason: (seasonId: Long) -> Unit,
    onOpenTimeline: () -> Unit = {}
) {
    val viewModel: PgcIndexViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            PgcIndexViewModel() as T
    })
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("番剧 · 影视分类") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenTimeline) {
                        Text("时间表", style = MaterialTheme.typography.labelLarge)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 类型 Tab
            ScrollableTabRow(selectedTabIndex = SEASON_TYPES.indexOfFirst { it.second == state.seasonType }) {
                SEASON_TYPES.forEach { (label, type) ->
                    Tab(
                        selected = state.seasonType == type,
                        onClick = { viewModel.setSeasonType(type) },
                        text = { Text(label) }
                    )
                }
            }
            // 排序
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ORDERS.forEach { (label, key) ->
                    FilterChip(
                        selected = state.order == key,
                        onClick = { viewModel.setOrder(key) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null && state.items.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error ?: "加载失败")
                        TextButton(onClick = { viewModel.refresh() }) { Text("重试") }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.items, key = { it.seasonId }) { item ->
                        PgcIndexCard(item, onOpenSeason)
                    }
                    if (state.hasNext) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                            LaunchedEffect(state.items.size) { viewModel.loadMore() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PgcIndexCard(item: PgcIndexItem, onOpenSeason: (Long) -> Unit) {
    // 审核284：竖版海报补 rim 描边 + 阴影（番剧海报是 88x118 竖比例，
    // VideoListRow 的 16:9 横向封面不适用，只统一封面质感规格）。
    val rimBrush = com.example.piliai.ui.theme.rememberCoverRimBrush()
    val coverShape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable { onOpenSeason(item.seasonId) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = item.cover.toCoverUrl(),
            contentDescription = item.title,
            modifier = Modifier.size(width = 88.dp, height = 118.dp)
                .shadow(2.dp, coverShape, clip = false)
                .clip(coverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(0.5.dp, rimBrush, coverShape)
        )
        Column(Modifier.weight(1f).height(118.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.badge.isNotEmpty()) {
                        Text(
                            item.badge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.subTitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.subTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    item.indexShow,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.score.isNotEmpty() && item.score != "0") {
                    Text(
                        item.score,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}