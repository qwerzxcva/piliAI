package com.example.piliai.ui.pages.bangumi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.piliai.data.model.TimelineDay
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

class TimelineViewModel : ViewModel() {
    private val _days = MutableStateFlow<List<TimelineDay>>(emptyList())
    val days: StateFlow<List<TimelineDay>> = _days.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            BiliApiClient().getBangumiTimeline()
                .onSuccess { _days.value = it.result }
                .onFailure { _error.value = it.message ?: "加载失败" }
            _loading.value = false
        }
    }
}

/** 批次L30：番剧更新时间表（pgc/web/timeline，匿名可用） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onBack: () -> Unit = {},
    onOpenSeason: (seasonId: Int) -> Unit = {},
    viewModel: TimelineViewModel = viewModel()
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("番剧时间表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(error ?: "")
                    TextButton(onClick = { viewModel.load() }) { Text("重试") }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(days, key = { it.dateTs }) { day ->
                        Column {
                            Text(
                                "${day.date} · 周${"日一二三四五六".getOrElse(day.dayOfWeek) { '日' }}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(6.dp))
                            day.episodes.forEach { ep ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = ep.season_id > 0) { onOpenSeason(ep.season_id) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = ep.cover.toCoverUrl(),
                                        contentDescription = ep.seasonTitle,
                                        modifier = Modifier
                                            .width(96.dp)
                                            .height(60.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            ep.seasonTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (ep.delay == 1) "延播：${ep.delayReason ?: ""}" else "更新至 ${ep.pub_index} · ${ep.pubTime}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (ep.delay == 1) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
