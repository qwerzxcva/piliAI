package com.example.piliai.ui.pages.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.piliai.data.model.VideoItem
import com.example.piliai.data.model.WeeklyItem
import com.example.piliai.data.model.formatCount
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

/**
 * 批次L26：热门扩展 ViewModel —— 入站必刷 + 每周必看
 */
class HotMoreViewModel : ViewModel() {
    private val api = BiliApiClient()

    // tab 0=入站必刷 1=每周必看
    private val _tab = MutableStateFlow(0)
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _precious = MutableStateFlow<List<VideoItem>>(emptyList())
    val precious: StateFlow<List<VideoItem>> = _precious.asStateFlow()

    private val _weeklyIssues = MutableStateFlow<List<WeeklyItem>>(emptyList())
    val weeklyIssues: StateFlow<List<WeeklyItem>> = _weeklyIssues.asStateFlow()

    // 批次L28：每周必看期数详情
    private val _weeklyVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    val weeklyVideos: StateFlow<List<VideoItem>> = _weeklyVideos.asStateFlow()

    private val _openWeekly = MutableStateFlow<WeeklyItem?>(null)
    val openWeekly: StateFlow<WeeklyItem?> = _openWeekly.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { loadTab(0) }

    fun setTab(t: Int) {
        if (_tab.value == t) return
        _tab.value = t
        loadTab(t)
    }

    private fun loadTab(t: Int) {
        if (_loading.value) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            if (t == 0) {
                api.getPopularPrecious()
                    .onSuccess { resp ->
                        if (resp.code == 0) _precious.value = resp.data?.list.orEmpty()
                        else _error.value = resp.message
                    }
                    .onFailure { _error.value = it.message }
            } else {
                api.getWeeklyList()
                    .onSuccess { resp ->
                        if (resp.code == 0) _weeklyIssues.value = resp.data?.list.orEmpty()
                        else _error.value = resp.message
                    }
                    .onFailure { _error.value = it.message }
            }
            _loading.value = false
        }
    }

    /** 批次L28：打开每周必看期数（加载该期视频） */
    fun openWeekly(issue: WeeklyItem) {
        _openWeekly.value = issue
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            api.getWeeklyDetail(issue.number)
                .onSuccess { resp ->
                    if (resp.code == 0) _weeklyVideos.value = resp.data?.list.orEmpty()
                    else _error.value = resp.message
                }
                .onFailure { _error.value = it.message }
            _loading.value = false
        }
    }

    fun closeWeekly() {
        _openWeekly.value = null
        _weeklyVideos.value = emptyList()
    }
}

/**
 * 热门扩展页（批次L26）：Tab 切换 入站必刷 / 每周必看期数
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotMoreScreen(
    onBack: () -> Unit = {},
    onOpenVideo: (String, Long) -> Unit = { _, _ -> },
    onOpenZone: () -> Unit = {},
    viewModel: HotMoreViewModel = viewModel()
) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val precious by viewModel.precious.collectAsStateWithLifecycle()
    val weeklyIssues by viewModel.weeklyIssues.collectAsStateWithLifecycle()
    val weeklyVideos by viewModel.weeklyVideos.collectAsStateWithLifecycle()
    val openWeekly by viewModel.openWeekly.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("热门") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenZone) { Text("分区", style = MaterialTheme.typography.labelLarge) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { viewModel.setTab(0) }, text = { Text("入站必刷") })
                Tab(selected = tab == 1, onClick = { viewModel.setTab(1) }, text = { Text("每周必看") })
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("加载失败: $error")
                }
                tab == 0 -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(precious, key = { it.bvid }) { v ->
                        PreciousRow(v) { onOpenVideo(v.bvid, v.cid) }
                    }
                }
                else -> {
                    // 打开某期：显示该期视频列表（批次L28）
                    if (openWeekly != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                openWeekly?.name ?: "",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.closeWeekly() }) { Text("返回") }
                        }
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(weeklyVideos, key = { it.bvid }) { v ->
                                PreciousRow(v) { onOpenVideo(v.bvid, v.cid) }
                            }
                        }
                    } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(weeklyIssues, key = { it.number }) { w ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { viewModel.openWeekly(w) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(w.name, style = MaterialTheme.typography.titleSmall)
                                    if (w.subject.isNotBlank()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            w.subject,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreciousRow(v: VideoItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = v.pic.toCoverUrl(),
            contentDescription = v.title,
            modifier = Modifier.width(140.dp).aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "${v.author} · ${formatCount(v.stat.view)}观看",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
