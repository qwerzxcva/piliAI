package com.example.piliai.ui.download

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.database.DownloadItemEntity
import com.example.piliai.data.repository.DownloadManager
import com.example.piliai.utils.toHttpsUrl

/**
 * 离线缓存页（批次I）——下载列表/进度/删除/离线播放
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit = {},
    // 审核244（P1-7）：带 cid。此前只传 bvid，播放器 local 分支拿不到 cid
    // 就不加载在线弹幕 → 离线视频大概率无弹幕。
    onPlayLocal: (String, Long) -> Unit = { _, _ -> }    // 传 bvid + cid
) {
    val context = LocalContext.current
    val items by DownloadManager.observeAll(context).collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线缓存") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DownloadDone, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("暂无离线缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("在视频页点击「缓存」即可下载", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(items, key = { it.bvid }) { item ->
                DownloadRow(
                    item = item,
                    onPlay = { onPlayLocal(item.bvid, item.cid) },
                    onDelete = { DownloadManager.delete(context, item.bvid) },
                    onPause = { DownloadManager.pause(context, item.bvid) },
                    onResume = { DownloadManager.resume(context, item.bvid) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItemEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = item.cover.toHttpsUrl(),
            contentDescription = null,
            modifier = Modifier.size(width = 120.dp, height = 68.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title.ifEmpty { item.bvid }, maxLines = 2,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(item.ownerName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            when (item.state) {
                DownloadItemEntity.STATE_DONE -> Text(
                    "已完成 · ${formatSize(item.videoSize + item.audioSize)}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.primary
                )
                DownloadItemEntity.STATE_FAILED -> Text(
                    "失败：${item.error ?: ""}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error, maxLines = 1
                )
                DownloadItemEntity.STATE_PAUSED -> {
                    LinearProgressIndicator(
                        progress = { item.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("已暂停 ${(item.progress * 100).toInt()}%", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    LinearProgressIndicator(
                        progress = { item.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("下载中 ${(item.progress * 100).toInt()}%", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        when (item.state) {
            DownloadItemEntity.STATE_RUNNING -> IconButton(onClick = onPause) {
                Icon(Icons.Default.Pause, "暂停")
            }
            DownloadItemEntity.STATE_PAUSED, DownloadItemEntity.STATE_FAILED ->
                IconButton(onClick = onResume) {
                    Icon(Icons.Default.PlayArrow, "继续/重试",
                        tint = MaterialTheme.colorScheme.primary)
                }
            DownloadItemEntity.STATE_DONE -> IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, "离线播放", tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.2f GB".format(bytes / (1 shl 30).toDouble())
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / (1 shl 20).toDouble())
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / (1 shl 10).toDouble())
    else -> "$bytes B"
}