package com.example.piliai.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.SearchResultItem
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onVideoClick: (String) -> Unit = {},
    onUserClick: (Long) -> Unit = {},
    onLiveClick: (Long) -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.searchState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf(initialQuery) }
    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) { viewModel.setSearchKeyword(initialQuery); viewModel.performSearch() } }
    var dropdown by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadTrending() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 搜索输入
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.setSearchKeyword(it)
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("搜索视频、UP主、直播...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    Row {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                searchQuery = ""; viewModel.clearSearch()
                            }) { Icon(Icons.Default.Clear, "清除") }
                        }
                        IconButton(onClick = {
                            if (searchQuery.isNotEmpty()) viewModel.performSearch()
                        }) { Icon(Icons.AutoMirrored.Filled.Send, "搜索") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )

            // 建议联想
            if (state.suggestions.isNotEmpty() && state.results.isEmpty()) {
                LazyColumn(Modifier.fillMaxWidth()) {
                    // 审核273：关键词联想（bilipai 报告的缺失项）。
                    // SearchSuggest.query 是服务端规范化后的搜索词，但
                    // SearchState.suggestions 只保留了 UP 主列表、query 被丢弃。
                    // 补一个 query 联想入口：用当前输入前缀直接搜。
                    val q = searchQuery.trim()
                    if (q.isNotBlank()) {
                        item(key = "query:$q") {
                            ListItem(
                                headlineContent = {
                                    Text("搜 " + "\"$q\"", fontWeight = FontWeight.Medium)
                                },
                                leadingContent = { Icon(Icons.Default.Search, null) },
                                modifier = Modifier.clickable {
                                    viewModel.performSearch()
                                }
                            )
                        }
                    }
                    items(state.suggestions, key = { "${it.mid}:${it.uname}" }) { s ->
                        ListItem(
                            headlineContent = { Text(s.uname) },
                            leadingContent = { Icon(Icons.Default.Person, null) },
                            modifier = Modifier.clickable {
                                searchQuery = s.uname
                                viewModel.setSearchKeyword(s.uname)
                                viewModel.performSearch()
                            }
                        )
                    }
                }
                return@Column
            }

            // 结果区：分类 + 排序过滤条
            if (state.results.isNotEmpty() || state.isSearching) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.searchType == "video",
                        onClick = { viewModel.setFilter(searchType = "video") },
                        label = { Text("视频") }
                    )
                    FilterChip(
                        selected = state.searchType == "bili_user",
                        onClick = { viewModel.setFilter(searchType = "bili_user") },
                        label = { Text("用户") }
                    )
                    FilterChip(
                        selected = state.searchType == "live",
                        onClick = { viewModel.setFilter(searchType = "live") },
                        label = { Text("直播") }
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showFilter = !showFilter }) {
                        Icon(Icons.Default.Tune, "筛选")
                    }
                    if (showFilter) {
                        DropdownMenu(expanded = showFilter, onDismissRequest = { showFilter = false }) {
                            listOf(
                                "" to "综合排序", "click" to "最多播放",
                                "pubdate" to "最新发布", "danmaku" to "最多弹幕"
                            ).forEach { (v, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { viewModel.setFilter(order = v); showFilter = false }
                                )
                            }
                        }
                    }
                }
            }

            when {
                state.isSearching && state.results.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.error != null && state.results.isEmpty() -> Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("搜索失败: ${state.error}", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.performSearch() }) { Text("重试") }
                    }
                }

                state.results.isNotEmpty() -> {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.results, key = { it.bvid.ifEmpty { "u${it.mid}${it.name}" } }) { item ->
                            SearchResultRow(
                                item = item,
                                onVideoClick = onVideoClick,
                                onUserClick = onUserClick,
                                onLiveClick = onLiveClick
                            )
                        }
                        if (state.hasMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                    LaunchedEffect(state.results.size) { viewModel.loadMore() }
                                    CircularProgressIndicator(Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }

                // 首屏：热搜 + 历史
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    // 审核237：搜索历史→胶囊流（B站首页搜索视觉）
                    if (state.history.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("搜索历史", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { viewModel.clearHistory() }) { Text("清空") }
                            }
                        }
                        item {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                state.history.forEach { h ->
                                    Text(
                                        h,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .clickable {
                                                searchQuery = h
                                                viewModel.setSearchKeyword(h)
                                                viewModel.performSearch()
                                            }
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                RoundedCornerShape(50)
                                            )
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "热门搜索",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp, 12.dp)
                        )
                    }
                    // 审核237：热搜带排名序号（前三热色）
                    itemsIndexed(state.trending, key = { _, t -> t.keyword }) { idx, t ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    searchQuery = t.keyword
                                    viewModel.setSearchKeyword(t.keyword)
                                    viewModel.performSearch()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val rank = idx + 1
                            Text(
                                "$rank",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                // 审核281：原先是三个硬编码 Hex（0xFFFB7299 等），换主题后
                                // 与强调色脱节。改用 primary 透明度阶梯，跟随用户选的强调色。
                                color = when (rank) {
                                    1 -> MaterialTheme.colorScheme.primary
                                    2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                t.show_name.ifEmpty { t.keyword },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (t.recommend_reason != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    t.recommend_reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    item: SearchResultItem,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit = {}
) {
    when (item.resultType) {
        "bili_user" -> Row(
            Modifier.fillMaxWidth()
                .clickable { onUserClick(item.mid) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.uimage ?: item.pic.toCoverUrl(),
                contentDescription = item.author,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(item.author.ifEmpty { item.title }, fontWeight = FontWeight.SemiBold)
                Text("${item.play} 粉丝", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        "live" -> com.example.piliai.ui.components.VideoListRow(
            title = item.title,
            coverUrl = item.pic.toCoverUrl(),
            supportingText = "${item.author} · ${item.online} 在线",
            coverWidth = 140.dp,
            onClick = { if (item.roomid > 0) onLiveClick(item.roomid) },  // 审核20：直播结果补跳转
        )

        else -> {  // video
            // 审核281：改用通用 VideoListRow（Kototoro HomeListRailRow 规格）——
            // 原先是 140×88 硬尺寸 + 6dp 圆角 + 无描边无阴影，与全站视觉语言脱节。
            com.example.piliai.ui.components.VideoListRow(
                title = item.title.replace(Regex("<[^>]+>"), ""),  // 去 <em> 高亮标签
                coverUrl = item.pic.toCoverUrl(),
                supportingText = "${item.author} · ${item.duration}",
                sourceText = "播放 ${item.play} · 弹幕 ${item.danmaku}",
                coverWidth = 140.dp,
                onClick = { if (item.bvid.isNotEmpty()) onVideoClick(item.bvid) },
            )
        }
    }
}
