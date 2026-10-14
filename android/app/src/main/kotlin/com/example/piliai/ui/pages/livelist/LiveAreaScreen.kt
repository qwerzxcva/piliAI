package com.example.piliai.ui.pages.livelist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.LiveParentArea
import com.example.piliai.data.model.LiveRecRoom
import com.example.piliai.data.model.formatCount
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

/**
 * 批次L25：直播分区页 ViewModel
 * 分区列表（room/v1/Area/getList）+ 推荐流（webMain/getMoreRecList），前端按分区过滤
 */
class LiveAreaViewModel : ViewModel() {
    private val api = BiliApiClient()

    private val _areas = MutableStateFlow<List<LiveParentArea>>(emptyList())
    val areas: StateFlow<List<LiveParentArea>> = _areas.asStateFlow()

    private val _rooms = MutableStateFlow<List<LiveRecRoom>>(emptyList())
    val rooms: StateFlow<List<LiveRecRoom>> = _rooms.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** 累积的推荐流（每次刷新追加去重，供分区过滤） */
    private val pool = LinkedHashMap<Long, LiveRecRoom>()

    init { loadAreas(); refreshRooms() }

    fun loadAreas() {
        viewModelScope.launch {
            api.getLiveAreaList()
                .onSuccess { resp -> if (resp.code == 0) _areas.value = resp.data }
                .onFailure { _error.value = it.message }
        }
    }

    fun refreshRooms() {
        if (_loading.value) return
        _loading.value = true
        viewModelScope.launch {
            // 连拉 4 页推荐流扩大池子（推荐流每次随机，多拉可覆盖更多分区）
            repeat(4) {
                api.getLiveRecList().onSuccess { resp ->
                    resp.data?.recommendRoomList?.forEach { r ->
                        if (r.roomid > 0) pool[r.roomid] = r
                    }
                }
            }
            _rooms.value = pool.values.toList()
            _loading.value = false
        }
    }

    /** 按分区过滤（parentAreaId=0 表示全部） */
    fun filterByParent(parentAreaId: Long): List<LiveRecRoom> =
        if (parentAreaId == 0L) _rooms.value
        else _rooms.value.filter { it.parentAreaId == parentAreaId }

    fun filterByArea(areaId: Long): List<LiveRecRoom> =
        if (areaId == 0L) _rooms.value else _rooms.value.filter { it.areaId == areaId }
}

/**
 * 直播分区页（批次L25）：大分区 LazyRow Tab + 房间网格
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveAreaScreen(
    onBack: () -> Unit = {},
    onOpenRoom: (Long) -> Unit = {},
    viewModel: LiveAreaViewModel = viewModel()
) {
    val areas by viewModel.areas.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selectedParent by remember { mutableLongStateOf(0L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("直播分区") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshRooms() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 大分区 Tab
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedParent == 0L,
                        onClick = { selectedParent = 0L },
                        label = { Text("推荐") }
                    )
                }
                items(areas, key = { it.id }) { a ->
                    FilterChip(
                        selected = selectedParent == a.id,
                        onClick = { selectedParent = a.id },
                        label = { Text(a.name) }
                    )
                }
            }

            val shown = remember(rooms, selectedParent) { viewModel.filterByParent(selectedParent) }

            when {
                loading && rooms.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && rooms.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("加载失败: $error")
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        if (selectedParent == 0L) "暂无直播" else "该分区暂无推荐房间（点右上角刷新）",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { it.roomid }) { room ->
                        LiveAreaCard(room) { onOpenRoom(room.roomid) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveAreaCard(room: LiveRecRoom, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            Box {
                // 审核281：直播卡片封面补 rim 描边 + 阴影 + surfaceVariant 底色，
                // 与首页视频卡/VideoListRow 同一套封面规格（Kototoro 移植）。
                // 原先只有 clip，无描边无阴影，在暗色主题下封面边缘发糊。
                val rimBrush = com.example.piliai.ui.theme.rememberCoverRimBrush()
                val coverShape = RoundedCornerShape(10.dp)
                AsyncImage(
                    model = room.cover.toCoverUrl(),
                    contentDescription = room.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        .shadow(2.dp, coverShape, clip = false)
                        .clip(coverShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(0.5.dp, rimBrush, coverShape),
                    contentScale = ContentScale.Crop
                )
                // 观看人数
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                ) {
                    Text(
                        formatCount(room.online),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                // 分区标签
                if (room.areaName.isNotBlank()) {
                    Surface(
                        color = Color(0xCCFB7299),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    ) {
                        Text(
                            room.areaName,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    room.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    room.uname,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
