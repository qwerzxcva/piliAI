package com.example.piliai.ui.settings

/**
 * 设置页通用组件（从 SettingsScreen.kt 拆出，审核250）。
 *
 * 拆出原因：SettingsScreen.kt 原本 1577 行，其中 5 个 section 的内容
 * （播放/弹幕/外观/字幕/关于）与这些通用积木（行/开关/滑块/分区标题/
 * 图标容器/两个弹窗）混在一起。这些积木与「设置页」无关，
 * 独立成文件后 SettingsScreen 只剩真正的设置内容。
 *
 * 注意：它们在本包内被 SettingsScreen 使用。原本是 private，拆文件后
 * private 会阻止跨文件访问，因此去掉 private（同包可见，不外泄到其它包）。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale
import com.example.piliai.ui.theme.ACCENT_OPTIONS
import com.example.piliai.ui.theme.PiliCardShape

/** 主题颜色选择弹窗（20 色板） */
@Composable
fun AccentColorDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题颜色") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.height(220.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(ACCENT_OPTIONS.size) { idx ->
                    val opt = ACCENT_OPTIONS[idx]
                    val selected = opt.hex.equals(current, ignoreCase = true) ||
                        (current.isEmpty() && idx == 0)
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(color = opt.color, shape = MaterialTheme.shapes.small)
                            .clickable {
                                onPick(if (idx == 0) "" else opt.hex)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(Icons.Default.Check, "已选", tint = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun SectionTitle(text: String) {
    // 审核215：Kototoro 风格分组标题——primary 色条 + 字距
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            ),
        )
    }
}

/** 审核238：Kototoro iOS 图标容器（40dp 圆角 12dp 着色底） */
@Composable
fun SettingIconBox(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit = {}
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { if (subtitle.isNotEmpty()) Text(subtitle) },
        leadingContent = { SettingIconBox(icon) },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier
            .heightIn(min = 56.dp)   // Kototoro settingsItemMinHeight
            .clip(PiliCardShape)
            .clickable { onClick() },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
}

@Composable
fun SettingSwitch(
    title: String,
    subtitle: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { if (subtitle.isNotEmpty()) Text(subtitle) },
        leadingContent = { SettingIconBox(icon) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onChange)
        },
        modifier = Modifier.clip(PiliCardShape),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
}

@Composable
fun SettingSlider(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    displayValue: String? = null,
    subtitle: String? = null,
    onValueChange: (Float) -> Unit
) {
    ListItem(
        headlineContent = {
            Column {
                Row {
                    Text(title)
                    Spacer(Modifier.weight(1f))
                    Text(
                        displayValue ?: String.format(Locale.ROOT, "%.0f%%", value * 100),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = valueRange,
                    steps = steps
                )
            }
        },
        leadingContent = { SettingIconBox(icon) },
        modifier = Modifier.clip(PiliCardShape),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    )
}


/** 审核233：推荐流标题屏蔽词（正则）输入对话框 */
@Composable
fun BanWordDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标题屏蔽词") },
        text = {
            Column {
                Text(
                    "正则表达式，大小写不敏感；命中标题的推荐视频将被隐藏。留空关闭。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(text.trim()); onDismiss() }) { Text("保存") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 审核轮204：黑名单管理对话框（列表 + 分页 + 移除） */
@Composable
fun BlacklistDialog(onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var items by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var pn by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }

    suspend fun load(page: Int) {
        loading = true
        com.example.piliai.data.remote.BiliApiClient().getBlacklist(page)
            .onSuccess { resp ->
                if (resp.optInt("code") == 0) {
                    // 审核轮211：消除 !!（val 捕获后智能转换）
                    val data = resp.optJSONObject("data")
                    val list = data?.optJSONArray("list")
                    total = data?.optInt("total") ?: 0
                    val safeList = list ?: org.json.JSONArray()
                    val fresh = (0 until safeList.length()).map { safeList.getJSONObject(it) }
                    items = if (page == 1) fresh else items + fresh
                    pn = page
                } else msg = "加载失败 code=${resp.optInt("code")}"
            }.onFailure { msg = "加载失败: ${it.message}" }
        loading = false
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { load(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("黑名单（$total 人）") },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                if (loading && items.isEmpty()) {
                    Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
                }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading && items.isEmpty() && msg == null) Text("黑名单为空")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(items) { _, u ->
                        val mid = u.optLong("mid")
                        val uname = u.optString("uname", "用户$mid")
                        val face = u.optString("face")
                        ListItem(
                            headlineContent = { Text(uname, maxLines = 1) },
                            leadingContent = {
                                coil.compose.AsyncImage(
                                    model = face,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp)
                                )
                            },
                            trailingContent = {
                                TextButton(onClick = {
                                    scope.launch {
                                        com.example.piliai.data.remote.BiliApiClient()
                                            .modifyBlacklist(mid, 2)
                                            .onSuccess { ok ->
                                                if (ok) {
                                                    items = items.filterNot { it.optLong("mid") == mid }
                                                    total = (total - 1).coerceAtLeast(0)
                                                    msg = "已将 $uname 移出黑名单"
                                                } else msg = "移除失败（需登录）"
                                            }.onFailure { msg = "移除失败: ${it.message}" }
                                    }
                                }) { Text("移除") }
                            }
                        )
                    }
                    if (items.size < total) {
                        item {
                            TextButton(onClick = { scope.launch { load(pn + 1) } }) {
                                Text("加载更多（${items.size}/$total）")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
