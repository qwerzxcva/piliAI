package com.example.piliai.ui.pages.dynamics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.DynamicFeedItem as DynamicItem
import com.example.piliai.data.model.formatCount
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

/**
 * 动态页 ViewModel：聚合流 feed/all，offset 翻页，未登录显示引导。
 */
class DynamicsViewModel : ViewModel() {
    data class UiState(
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val items: List<DynamicItem> = emptyList(),
        val offset: String? = null,
        val hasMore: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val api = BiliApiClient()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            if (!AccountSession.isLogin) {
                _state.value = _state.value.copy(loading = false, error = "not_login")
                return@launch
            }
            api.getDynamicFeed(null)
                .onSuccess { resp ->
                    if (resp.code == 0 && resp.data != null) {
                        _state.value = UiState(
                            items = resp.data.items,
                            offset = resp.data.offset,
                            hasMore = resp.data.has_more
                        )
                    } else {
                        _state.value = _state.value.copy(
                            error = resp.message ?: "动态加载失败（code=${resp.code}）"
                        )
                    }
                }
                .onFailure {
                    // 审核292：过错误码翻译（原来是裸 it.message / "网络错误"）
                    _state.value = _state.value.copy(
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(it.message) ?: it.message ?: "动态加载失败，请检查网络"
                    )
                }
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || !s.hasMore || s.offset == null) return
        viewModelScope.launch {
            _state.value = s.copy(loadingMore = true)
            api.getDynamicFeed(s.offset)
                .onSuccess { resp ->
                    if (resp.code == 0 && resp.data != null) {
                        _state.value = _state.value.copy(
                            items = _state.value.items + resp.data.items,
                            offset = resp.data.offset,
                            hasMore = resp.data.has_more,
                            loadingMore = false
                        )
                    } else {
                        _state.value = _state.value.copy(loadingMore = false)
                    }
                }
                .onFailure { _state.value = _state.value.copy(loadingMore = false) }
        }
    }
}

/**
 * 动态页：聚合流（视频动态可点击播放），下拉刷新 + 无限翻页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicsScreen(
    onOpenVideo: (bvid: String, cid: Long) -> Unit = { _, _ -> },
    onGoLogin: () -> Unit = {},
    viewModel: DynamicsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (state.items.isEmpty() && state.error == null) viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("动态") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        when {
            // 未登录引导
            state.error == "not_login" -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Person, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("登录后查看动态", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "同步关注的UP主动态更新",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onGoLogin) { Text("去登录") }
                }
            }

            state.error != null && state.items.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { Text(state.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            else -> PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items, key = { it.id_str }) { item ->
                        DynamicCard(item = item, onOpenVideo = onOpenVideo)
                    }
                    if (state.hasMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                LaunchedEffect(state.items.size) { viewModel.loadMore() }
                                if (state.loadingMore) CircularProgressIndicator(Modifier.size(22.dp))
                            }
                        }
                    }
                    if (!state.hasMore && state.items.isNotEmpty()) {
                        item {
                            Text(
                                "— 没有更多了 —",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicCard(
    item: DynamicItem,
    onOpenVideo: (bvid: String, cid: Long) -> Unit
) {
    val v = item.videoInfo
    val author = item.modules?.module_author
    val stat = item.modules?.module_stat
    val desc = item.modules?.module_dynamic?.desc?.text.orEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .let { m ->
                if (v != null && v.bvid.isNotEmpty()) m.clickable { onOpenVideo(v.bvid, v.cid) } else m
            }
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = author?.face.toCoverUrl(),
                    contentDescription = author?.name,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        author?.name ?: "",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        author?.pub_time ?: "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (v != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                            Text("视频", fontSize = 11.sp)
                        }
                    }
                }
            }

            if (desc.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(desc, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }

            // 审核266：图片动态九宫格。数据层的 DynamicMajor.draw 一直有解析，
            // 但 UI 从未渲染 —— 用户发图动态只看到文字，图片全丢。
            val drawItems = item.modules?.major?.draw?.items.orEmpty()
            if (v == null && drawItems.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                DynamicImageGrid(drawItems.map { it.src })
            }

            if (v != null && v.bvid.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                // 审核284：视频引用块封面补 rim 描边 + 阴影 + surfaceVariant 底色——
                // 原先是 140×88 写死尺寸 + 8dp 圆角 + 无描边，与全站视觉语言脱节。
                // 保留固定 88dp 高度：动态卡片是嵌入块，不能像列表行那样自然撑高。
                // 不加 clickable：整张动态卡在 DynamicCard 外层已可点（见上方 .let 分支），
                // 这里再叠一层会造成「点引用块与点卡片其他区域」行为分裂。
                val rimBrush = com.example.piliai.ui.theme.rememberCoverRimBrush()
                val coverShape = RoundedCornerShape(10.dp)
                Row {
                    Box(
                        Modifier
                            .width(140.dp)
                            .height(88.dp)
                            .shadow(2.dp, coverShape, clip = false)
                            .clip(coverShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(0.5.dp, rimBrush, coverShape)
                    ) {
                        AsyncImage(
                            model = v.pic.toCoverUrl(),
                            contentDescription = v.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Column(Modifier.padding(start = 10.dp).height(88.dp)) {
                        Text(
                            v.title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            v.duration_text.ifEmpty { v.desc },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 审核266：转发动态的引用块。此前转发只显示用户自己写的文字，
            // 被转发的原动态内容完全看不到（用户刷到转发以为是空动态）。
            val forward = item.forwardInfo
            if (forward != null) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        val fwdVideo = forward.archive
                        val fwdDraw = forward.draw?.items.orEmpty()
                        if (fwdVideo != null && fwdVideo.bvid.isNotEmpty()) {
                            Text(
                                fwdVideo.title,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            // 审核284：转发块封面补 rim 描边 + 阴影（原先是
                            // 6dp 圆角 + 无描边，动态页唯一没有封面规格的一处）
                            val fwdRim = com.example.piliai.ui.theme.rememberCoverRimBrush()
                            val fwdShape = RoundedCornerShape(10.dp)
                            AsyncImage(
                                model = fwdVideo.pic.toCoverUrl(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(120.dp)
                                    .shadow(2.dp, fwdShape, clip = false)
                                    .clip(fwdShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(0.5.dp, fwdRim, fwdShape),
                                contentScale = ContentScale.Crop
                            )
                        } else if (fwdDraw.isNotEmpty()) {
                            DynamicImageGrid(fwdDraw.map { it.src })
                        }
                        val fwdText = forward.desc?.text.orEmpty()
                        if (fwdText.isNotBlank()) {
                            if (fwdVideo != null || fwdDraw.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                fwdText,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (stat != null && (stat.comment > 0 || stat.like > 0)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${formatCount(stat.forward)}转发 · ${formatCount(stat.comment)}评论 · ${formatCount(stat.like)}点赞",
                    fontSize = 11.sp,
                    // 审核270：跟随主题的次要文字色（暗色/亮色自动切换），
                    // 不用硬编码灰 —— AMOLED 纯黑主题下 0xFF999999 会发雾
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/**
 * 审核266：动态图片九宫格（B站动态的图片排布）。
 *
 * 1 张 → 大图（16:10）；2 张 → 并排两列；3 张 → 左大右两小；
 * 4 张 → 2x2；更多 → 3 列网格，最多显示 9 张。
 */
@Composable
private fun DynamicImageGrid(urls: List<String>) {
    if (urls.isEmpty()) return
    when (urls.size) {
        1 -> AsyncImage(
            model = urls[0].toCoverUrl(),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        2 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            urls.take(2).forEach { url ->
                AsyncImage(
                    model = url.toCoverUrl(),
                    contentDescription = null,
                    modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }
        else -> {
            // 3 列网格，最多 9 张（与 B 站一致）
            val columns = 3
            val rows = (minOf(urls.size, 9) + columns - 1) / columns
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (col in 0 until columns) {
                            val index = row * columns + col
                            val url = urls.getOrNull(index)
                            if (url != null) {
                                AsyncImage(
                                    model = url.toCoverUrl(),
                                    contentDescription = null,
                                    modifier = Modifier.weight(1f).aspectRatio(1f)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
