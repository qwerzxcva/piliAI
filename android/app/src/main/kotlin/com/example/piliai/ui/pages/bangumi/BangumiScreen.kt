package com.example.piliai.ui.pages.bangumi
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.PgcEpisode
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl

/** 番剧详情页（批次D）——封面/简介/选集列表/追番，点击集数跳播放器（bvid+cid） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BangumiScreen(
    seasonId: Long = 0L,
    epId: Long = 0L,
    onBack: () -> Unit = {},
    onOpenVideo: (String, Long) -> Unit = { _, _ -> },
    onOpenIndex: () -> Unit = {},
    viewModel: BangumiViewModel = viewModel(
        key = "$seasonId-$epId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BangumiViewModel(seasonId, epId) as T
        }
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.season?.title ?: "番剧", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenIndex) { Text("分类") }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null && state.season == null -> Box(
                Modifier.padding(padding).fillMaxSize(), Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败: ${state.error}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.load() }) { Text("重试") }
                }
            }
            else -> {
                val season = state.season ?: return@Scaffold
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    // 头部：封面 + 信息
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp)) {
                            AsyncImage(
                                model = season.cover.toCoverUrl(),
                                contentDescription = season.title,
                                modifier = Modifier.width(120.dp).height(160.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(season.title, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (season.isFinished) "已完结 · 共 ${season.totalEp} 话" else "连载中 · 更新至 ${season.totalEp} 话",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                season.stat?.let { stat ->
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "播放 ${formatNum(stat.views)} · 弹幕 ${formatNum(stat.danmakus)} · 追番 ${formatNum(stat.followers)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { viewModel.toggleFollow() }) {
                                    Text(if (state.isFollowed) "已追番" else "追番")
                                }
                            }
                        }
                    }
                    // 简介
                    if (season.evaluate.isNotEmpty()) {
                        item {
                            Text(
                                season.evaluate,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                    // 选集标题
                    item {
                        Text(
                            "选集 (${season.episodes.size})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp)
                        )
                    }
                    // 选集列表（横滑卡片）
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(season.episodes, key = { it.id }) { ep ->
                                val selected = ep.id == state.currentEp?.id
                                EpisodeCard(
                                    ep = ep,
                                    selected = selected,
                                    onClick = { viewModel.selectEpisode(ep) }
                                )
                            }
                        }
                    }
                    // 播放按钮
                    item {
                        val cur = state.currentEp
                        if (cur != null) {
                            Button(
                                onClick = { onOpenVideo("ep${cur.id}", cur.cid) },
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("播放 ${cur.title} ${cur.longTitle}")
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(ep: PgcEpisode, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(120.dp).clickable { onClick() },
        horizontalAlignment = Alignment.Start
    ) {
        Box {
            AsyncImage(
                model = ep.cover.toCoverUrl(),
                contentDescription = ep.title,
                modifier = Modifier.fillMaxWidth().height(72.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
            if (ep.badgeText.isNotEmpty()) {
                Text(
                    ep.badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${ep.title} ${ep.longTitle}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatNum(n: Long): String = when {
    n >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format(Locale.ROOT, "%.1f万", n / 10_000.0)
    else -> n.toString()
}
