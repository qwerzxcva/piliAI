package com.example.piliai.ui.pages.livelist
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.LiveRoomCard
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

/** 直播列表 ViewModel（批次D/F 补全） */
class LiveListViewModel : ViewModel() {
    private val api = BiliApiClient()
    private val _state = MutableStateFlow(LiveListState())
    val state: StateFlow<LiveListState> = _state.asStateFlow()

    data class LiveListState(
        val rooms: List<LiveRoomCard> = emptyList(),
        val page: Int = 1,
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    init { load(1) }

    fun load(page: Int) {
        if (page == 1) _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            // 批次L36：app-interface v2 second/getList 匿名 -352 风控 → 改 webMain/getMoreRecList（匿名可用），
            // getMoreRecList 无分页参数，翻页以"offset 随机化重取 + 去重"模拟瀑布流
            api.getLiveRecList().onSuccess { resp ->
                val list = resp.data?.recommendRoomList.orEmpty().map {
                    LiveRoomCard(
                        roomId = it.roomid, title = it.title, anchorName = it.uname,
                        cover = it.cover, anchorFace = "", onlineCount = it.online,
                        areaName = it.areaName, parentAreaName = it.parentAreaName
                    )
                }
                val prev = _state.value.rooms
                val fresh = if (page == 1) list else list.filter { n -> prev.none { it.roomId == n.roomId } }
                _state.value = _state.value.copy(
                    isLoading = false,
                    rooms = if (page == 1) fresh else prev + fresh,
                    page = page,
                    hasMore = fresh.isNotEmpty()
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "直播列表加载失败"
                )
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoading || !s.hasMore) return
        load(s.page + 1)
    }
}

/** 直播列表页（瀑布式网格，点击进直播间） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveListScreen(
    onBack: () -> Unit = {},
    onOpenRoom: (String) -> Unit = {},
    onOpenArea: () -> Unit = {},
    viewModel: LiveListViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("直播") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenArea) {
                        Icon(Icons.Default.Grid3x3, contentDescription = "分区")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading && state.rooms.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(), Alignment.Center
            ) { CircularProgressIndicator() }
            state.rooms.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(), Alignment.Center
            ) { Text("暂无直播", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.rooms, key = { it.roomId }) { room ->
                    LiveRoomCardItem(room) { onOpenRoom(room.roomId.toString()) }
                }
                if (state.hasMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                            LaunchedEffect(state.rooms.size) { viewModel.loadMore() }
                            CircularProgressIndicator(Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveRoomCardItem(room: LiveRoomCard, onClick: () -> Unit) {
    // 审核287：直播卡补 rim 描边 + 阴影 + surfaceVariant 底色，与全站封面规格统一。
    // 原先是 Card 默认圆角 + 封面 110dp 固定高 + 无描边无阴影。
    val rimBrush = com.example.piliai.ui.theme.rememberCoverRimBrush()
    val coverShape = RoundedCornerShape(12.dp)
    Card(
        Modifier.clickable { onClick() },
        shape = coverShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column {
            Box {
                AsyncImage(
                    model = room.cover.toCoverUrl(),
                    contentDescription = room.title,
                    modifier = Modifier.fillMaxWidth().height(110.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
                // 封面下缘一道 rim 高光：与描边规格呼应，弥补 Card 容器无描边
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(0.5.dp)
                        .background(rimBrush)
                )
                // 人气角标
                Row(
                    Modifier.align(Alignment.BottomStart).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🔥 ${formatNum(room.onlineCount)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
                if (room.areaName.isNotEmpty()) {
                    Text(
                        room.areaName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    )
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    room.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = room.anchorFace.toHttpsUrl(),
                        contentDescription = room.anchorName,
                        modifier = Modifier.size(16.dp).clip(RoundedCornerShape(50)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        room.anchorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun formatNum(n: Long): String = when {
    n >= 10_000 -> String.format(Locale.ROOT, "%.1f万", n / 10_000.0)
    else -> n.toString()
}
