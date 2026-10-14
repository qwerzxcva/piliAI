package com.example.piliai.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.piliai.data.model.VideoItem
import com.example.piliai.data.repository.HomeRepository
import com.example.piliai.utils.toCoverUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 审核轮207（BiliPai feature/story 移植）：竖屏沉浸式推荐流（抖音式上下滑动）。
 * 审核237：数据源从 HomeRepository 的推荐流（feed/rcmd + popular 兜底）获取，
 * 与首页同一套去重/翻页逻辑（原 getPopularVideos 已并入仓库内部）。
 */

class StoryViewModel(
    private val repo: HomeRepository = HomeRepository()
) : ViewModel() {
    data class UiState(
        val items: List<VideoItem> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val endReached: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init { loadMore() }

    fun loadMore() {
        if (_state.value.loading || _state.value.endReached) return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            // 审核237：仓库内部维护 fresh_idx/页码，并做 bvid 去重；
            // 首屏走 refresh（推荐流第一页），之后走 loadMore 追加。
            val isFirst = _state.value.items.isEmpty()
            val result = if (isFirst) repo.refresh() else repo.loadMore()
            result.onSuccess { added ->
                val list = if (isFirst) added else repo.videos.value
                _state.value = _state.value.copy(
                    items = list,
                    loading = false,
                    endReached = list.isEmpty() || added.isEmpty()
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "视频流加载失败"
                )
            }
        }
    }

    fun retry() {
        _state.value = _state.value.copy(endReached = false, error = null)
        loadMore()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryScreen(onBack: () -> Unit, onVideoClick: (String, Long) -> Unit = { _, _ -> }, vm: StoryViewModel = viewModel(factory = viewModelFactory {
    initializer { StoryViewModel() }
})) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { state.items.size })
    val nearEnd by remember {
        derivedStateOf { pagerState.currentPage >= state.items.size - 3 }
    }

    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMore() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("竖屏流", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (state.items.isEmpty() && state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.error?.let { err ->
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(err, color = Color.White)
                    Button(onClick = { vm.retry() }) { Text("重试") }
                }
            }
            if (state.items.isNotEmpty()) {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { idx ->
                    val v = state.items[idx]
                    StoryPage(v, isActive = pagerState.currentPage == idx, onVideoClick = onVideoClick)
                }
            }
        }
    }
}


/** 竖屏流单页：封面 + 底部信息，点击进入全功能播放器（BiliPai PortraitVideoPager 的轻量版） */
@Composable
private fun StoryPage(v: VideoItem, isActive: Boolean, onVideoClick: (String, Long) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        coil.compose.AsyncImage(
            model = v.pic.toCoverUrl(),
            contentDescription = v.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(16.dp, 12.dp)
        ) {
            Text(v.title, color = Color.White, maxLines = 2)
            Text("@${v.author}", color = Color.White.copy(alpha = 0.8f))
        }
        androidx.compose.material3.TextButton(
            onClick = { onVideoClick(v.bvid, v.cid) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Text("▶ 全屏播放", color = Color.White) }
    }
}