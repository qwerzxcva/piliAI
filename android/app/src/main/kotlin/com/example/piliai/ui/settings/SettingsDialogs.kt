package com.example.piliai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.piliai.R

/**
 * 审核314：设置页的全部弹窗（19 个），从 SettingsScreen 拆出。
 *
 * 拆移前 SettingsScreen 1539 行，其中约 720 行是这些 dialog，主体
 * （8 个分区 + Scaffold）被稀释得难读。搬到这里后只依赖三个参数：
 * state / viewModel / dialogs，调用一行。
 */
@Composable
fun SettingsDialogs(
    state: SettingsViewModel.SettingsState,
    viewModel: SettingsViewModel,
    dialogs: SettingsDialogState,
) {
// 清晰度选择弹窗
if (dialogs.showQualityDialog) {
    val options = listOf("auto" to "自动", "1080p" to "1080P 高清", "720p" to "720P 高清", "480p" to "480P 清晰")
    AlertDialog(
        onDismissRequest = { dialogs.showQualityDialog = false },
        title = { Text("默认清晰度") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setVideoQuality(value)
                                dialogs.showQualityDialog = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.videoQuality == value,
                            onClick = {
                                viewModel.setVideoQuality(value)
                                dialogs.showQualityDialog = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

// 审核219：音质选择弹窗
if (dialogs.showAudioQaDialog) {
    val options = listOf("0" to "自动（最高可用）", "30251" to "Hi-Res 无损", "30280" to "192K 高码率", "30232" to "132K 中码率", "30216" to "64K 省流")
    AlertDialog(
        onDismissRequest = { dialogs.showAudioQaDialog = false },
        title = { Text("默认音质") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setAudioQa(value)
                                dialogs.showAudioQaDialog = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.audioQa == value,
                            onClick = {
                                viewModel.setAudioQa(value)
                                dialogs.showAudioQaDialog = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

// 审核220：解码器偏好弹窗
if (dialogs.showCodecDialog) {
    val options = listOf("avc" to "AVC/H.264（省电，兼容性最佳）", "hevc" to "HEVC/H.265（高压缩）", "av1" to "AV1（最新一代）")
    AlertDialog(
        onDismissRequest = { dialogs.showCodecDialog = false },
        title = { Text("解码器偏好") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setPreferCodec(value)
                                dialogs.showCodecDialog = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.preferCodec == value,
                            onClick = {
                                viewModel.setPreferCodec(value)
                                dialogs.showCodecDialog = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

// 审核221：蜂窝画质弹窗
if (dialogs.showCellularQaDialog) {
    val options = listOf("" to "跟随默认画质", "116" to "1080P 高清", "80" to "1080P", "64" to "720P 高清", "32" to "480P 清晰", "16" to "360P 流畅")
    AlertDialog(
        onDismissRequest = { dialogs.showCellularQaDialog = false },
        title = { Text("蜂窝画质上限") },
        text = { Column {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable {
                    viewModel.setVideoQaCellular(value); dialogs.showCellularQaDialog = false
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.videoQaCellular == value, onClick = {
                        viewModel.setVideoQaCellular(value); dialogs.showCellularQaDialog = false
                    })
                    Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
        } },
        confirmButton = {}
    )
}
// 审核221：蜂窝音质弹窗
if (dialogs.showCellularAudioDialog) {
    val options = listOf("" to "跟随默认音质", "30280" to "192K 高码率", "30232" to "132K 中码率", "30216" to "64K 省流")
    AlertDialog(
        onDismissRequest = { dialogs.showCellularAudioDialog = false },
        title = { Text("蜂窝音质上限") },
        text = { Column {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable {
                    viewModel.setAudioQaCellular(value); dialogs.showCellularAudioDialog = false
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.audioQaCellular == value, onClick = {
                        viewModel.setAudioQaCellular(value); dialogs.showCellularAudioDialog = false
                    })
                    Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
        } },
        confirmButton = {}
    )
}
// 审核221：蜂窝解码偏好弹窗
if (dialogs.showCellularCodecDialog) {
    val options = listOf("" to "跟随默认解码器", "avc" to "AVC/H.264（省电）", "hevc" to "HEVC/H.265（高压缩）", "av1" to "AV1（最新一代）")
    AlertDialog(
        onDismissRequest = { dialogs.showCellularCodecDialog = false },
        title = { Text("蜂窝解码偏好") },
        text = { Column {
            options.forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable {
                    viewModel.setPreferCodecCellular(value); dialogs.showCellularCodecDialog = false
                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.preferCodecCellular == value, onClick = {
                        viewModel.setPreferCodecCellular(value); dialogs.showCellularCodecDialog = false
                    })
                    Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
        } },
        confirmButton = {}
    )
}

// 主题选择弹窗
if (dialogs.showThemeDialog) {
    val options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")
    AlertDialog(
        onDismissRequest = { dialogs.showThemeDialog = false },
        title = { Text("主题模式") },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setThemeMode(value)
                                dialogs.showThemeDialog = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.themeMode == value,
                            onClick = {
                                viewModel.setThemeMode(value)
                                dialogs.showThemeDialog = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

// 主题颜色弹窗
if (dialogs.showAccentDialog) {
    AccentColorDialog(
        current = state.accentColor,
        onPick = { viewModel.setAccentColor(it) },
        onDismiss = { dialogs.showAccentDialog = false }
    )
}

// 关于弹窗
// 审核235：图片质量选择
if (dialogs.showPicQualityDialog) {
    val options = listOf(100 to "原图（最快加载）", 80 to "80% 高清", 50 to "50% 均衡", 25 to "25% 省流")
    AlertDialog(
        onDismissRequest = { dialogs.showPicQualityDialog = false },
        title = { Text("图片质量") },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setPicQuality(v)
                                dialogs.showPicQualityDialog = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.picQuality == v, onClick = {
                            viewModel.setPicQuality(v); dialogs.showPicQualityDialog = false
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

if (dialogs.showAboutDialog) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { dialogs.showAboutDialog = false },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { dialogs.showAboutDialog = false }) {
                androidx.compose.material3.Text("知道了")
            }
        },
        title = { androidx.compose.material3.Text("关于 PiliNara") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.material3.Text(
                    "PiliNara —— B站第三方客户端\n\n" +
                        "架构：Kotlin + Jetpack Compose + Rust native (ARM64)\n" +
                        "网络：Ktor + Wbi 签名\n" +
                        "存储：Room + DataStore\n" +
                        "播放：Media3 ExoPlayer\n\n" +
                        "本项目为 Flutter 原版的功能移植实现。"
                )
                Spacer(Modifier.height(12.dp))
                // 审核296：真实版本号 + 检查更新（GitHub Releases）
                val context = androidx.compose.ui.platform.LocalContext.current
                val pkgVersion = remember(context) {
                    runCatching {
                        context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "unknown"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "当前版本 v$pkgVersion",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.checkUpdate() },
                        enabled = !state.checkingUpdate
                    ) {
                        Text(if (state.checkingUpdate) "检查中…" else "检查更新")
                    }
                }
                state.updateCheckResult?.let { msg ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    )
}

// Kazumi：渲染器选择弹窗
if (dialogs.showRendererDialog) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { dialogs.showRendererDialog = false },
        title = { Text("视频渲染器") },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setRenderer(0)
                            dialogs.showRendererDialog = false
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = state.renderer == 0,
                        onClick = { viewModel.setRenderer(0); dialogs.showRendererDialog = false }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("SurfaceView", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "性能最佳、功耗最低（默认）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setRenderer(1)
                            dialogs.showRendererDialog = false
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = state.renderer == 1,
                        onClick = { viewModel.setRenderer(1); dialogs.showRendererDialog = false }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("TextureView", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "支持圆角/动画合成，性能略低",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.showRendererDialog = false }) { Text("取消") }
        }
    )
}

// 审核313：倍速档位编辑（勾选式，含自定义输入）
if (dialogs.showSpeedListDialog) {
    val current = state.playbackSpeedList
    // 常用档位池：用户勾选 + 自定义输入
    val pool = remember {
        listOf(0.5f, 0.75f, 0.9f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f, 5.0f)
    }
    val checked = remember { mutableStateOf(current.toSet()) }
    var customInput by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { dialogs.showSpeedListDialog = false },
        title = { Text("倍速档位") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "勾选要出现在播放器倍速菜单里的档位（1.0x 始终保留）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                // 档位池：固定 3 行×4 列排布（不用实验性 FlowRow）
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    pool.chunked(4).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowItems.forEach { v ->
                                val on = v in checked.value
                                androidx.compose.material3.FilterChip(
                                    selected = on,
                                    onClick = {
                                        checked.value = if (on) checked.value - v
                                        else checked.value + v
                                    },
                                    label = { Text("${formatSpeedLabel(v)}x") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // 补齐不足 4 的空位，保持对齐
                            repeat(4 - rowItems.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 自定义输入
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        label = { Text("自定义（如 1.35）") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            customInput.toFloatOrNull()?.let { v ->
                                if (v in com.example.piliai.utils.StorageManager.SPEED_MIN..
                                    com.example.piliai.utils.StorageManager.SPEED_MAX
                                ) {
                                    checked.value = checked.value + v
                                    customInput = ""
                                }
                            }
                        }
                    ) { Text("添加") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.setPlaybackSpeedList(checked.value.toList())
                dialogs.showSpeedListDialog = false
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { dialogs.showSpeedListDialog = false }) { Text("取消") }
        },
    )
}

if (dialogs.showSuperResolutionDialog) {
    AlertDialog(
        onDismissRequest = { dialogs.showSuperResolutionDialog = false },
        title = { Text("画质增强") },
        text = {
            Column {
                listOf(
                    "disable" to "关闭",
                    "efficiency" to "效率（锐化 + 最高 1080p）",
                    "quality" to "质量（强锐化 + 最高 4K）",
                ).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.setSuperResolutionMode(mode)
                                dialogs.showSuperResolutionDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.superResolutionMode == mode,
                            onClick = {
                                viewModel.setSuperResolutionMode(mode)
                                dialogs.showSuperResolutionDialog = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "基于 Media3 画面效果：先锐化再放大，不是 AI 超分。播放中切换即时生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.showSuperResolutionDialog = false }) { Text("取消") }
        }
    )
}

if (dialogs.showBackendDialog) {
    val gpuNextReady = com.example.piliai.playback.backend.GpuNextBackend.isAvailable()
    AlertDialog(
        onDismissRequest = { dialogs.showBackendDialog = false },
        title = { Text(stringResource(R.string.playback_backend_title)) },
        text = {
            Column {
                listOf(
                    "media3" to R.string.playback_backend_media3,
                    "gpu-next" to if (gpuNextReady)
                        R.string.playback_backend_gpu_next_ready
                    else R.string.playback_backend_gpu_next_unavailable
                ).forEach { (id, label) ->
                    val enabled = id == "media3" || gpuNextReady
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = enabled) {
                            viewModel.setPlaybackBackend(id)
                            dialogs.showBackendDialog = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = com.example.piliai.playback.backend.PlaybackBackends
                                .resolve(state.playbackBackend) == id,
                            enabled = enabled,
                            onClick = {
                                viewModel.setPlaybackBackend(id)
                                dialogs.showBackendDialog = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!gpuNextReady) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "GPU-next（mpv 渲染）尚未接通：原生库还没有真实输出帧，" +
                            "选择后会自动回退 Media3，不会出现黑屏。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.showBackendDialog = false }) {
                Text(stringResource(R.string.playback_settings_cancel))
            }
        }
    )
}

// 审核279：首页数据源选择（自动/推荐流/热门/入站必刷/每周必看）
if (dialogs.showFeedSourceDialog) {
    AlertDialog(
        onDismissRequest = { dialogs.showFeedSourceDialog = false },
        title = { Text("首页数据源") },
        text = {
            Column {
                com.example.piliai.data.repository.HomeRepository.PREF_VALUES
                    .forEach { pref ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                viewModel.setHomeFeedSource(pref)
                                dialogs.showFeedSourceDialog = false
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = state.homeFeedSource == pref,
                                onClick = {
                                    viewModel.setHomeFeedSource(pref)
                                    dialogs.showFeedSourceDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    com.example.piliai.data.repository.HomeRepository
                                        .prefLabel(pref),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (pref != com.example.piliai.data.repository
                                        .HomeRepository.PREF_AUTO
                                ) {
                                    Text(
                                        "失败时不自动切换",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.showFeedSourceDialog = false }) {
                Text(stringResource(R.string.playback_settings_cancel))
            }
        }
    )
}

if (dialogs.showCompletionDialog) {
    AlertDialog(
        onDismissRequest = { dialogs.showCompletionDialog = false },
        title = { Text(stringResource(R.string.playback_completion_title)) },
        text = {
            Column {
                listOf(
                    "next" to R.string.playback_completion_next,
                    "repeat" to R.string.playback_completion_repeat,
                    "stop" to R.string.playback_completion_stop
                ).forEach { (id, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            viewModel.setCompletionAction(id)
                            dialogs.showCompletionDialog = false
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.completionAction == id,
                            onClick = {
                                viewModel.setCompletionAction(id)
                                dialogs.showCompletionDialog = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.showCompletionDialog = false }) {
                Text(stringResource(R.string.playback_settings_cancel))
            }
        }
    )
}

// 缓冲时长选择弹窗（移植自 piliplus 缓冲策略）
if (dialogs.showBufferDialog) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { dialogs.showBufferDialog = false },
        title = { Text("缓冲时长") },
        text = {
            Column {
                listOf(8_000 to "8 秒（省流量，弱网易卡）",
                       16_000 to "16 秒（推荐，默认）",
                       32_000 to "32 秒（强网更流畅）").forEach { (ms, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setBufferDuration(ms)
                                dialogs.showBufferDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = state.bufferDurationMs == ms,
                            onClick = { viewModel.setBufferDuration(ms); dialogs.showBufferDialog = false }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.showBufferDialog = false }) { Text("取消") }
        }
    )
}

if (dialogs.showHwDecodeDialog) {
    AlertDialog(
        onDismissRequest = { dialogs.showHwDecodeDialog = false },
        title = { Text(stringResource(R.string.playback_decoder_title)) },
        text = {
            Column {
                listOf(
                    "auto" to R.string.playback_decoder_auto,
                    "hardware" to R.string.playback_decoder_prefer_hardware,
                    "software" to R.string.playback_decoder_prefer_software
                ).forEach { (mode, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setHardwareDecodeMode(mode)
                                dialogs.showHwDecodeDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = state.hardwareDecodeMode == mode,
                            onClick = { viewModel.setHardwareDecodeMode(mode); dialogs.showHwDecodeDialog = false }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.playback_decoder_restart_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.showHwDecodeDialog = false }) { Text("取消") }
        }
    )
}

if (dialogs.showSkipDialog) {
    var intro by remember(state.skipIntroSec) { mutableIntStateOf(state.skipIntroSec) }
    var outro by remember(state.skipOutroSec) { mutableIntStateOf(state.skipOutroSec) }
    AlertDialog(
        onDismissRequest = { dialogs.showSkipDialog = false },
        title = { Text("跳过片头片尾") },
        text = {
            Column {
                Text("片头 ${intro} 秒", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(
                    value = intro.toFloat(),
                    onValueChange = { intro = it.toInt() },
                    valueRange = 0f..90f,
                    steps = 17
                )
                Text("片尾 ${outro} 秒", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Slider(
                    value = outro.toFloat(),
                    onValueChange = { outro = it.toInt() },
                    valueRange = 0f..90f,
                    steps = 17
                )
                Text(
                    "0 秒表示不跳。只在每集开头和结尾各跳一次，拖回去不会反复跳。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.setSkipIntroSec(intro)
                viewModel.setSkipOutroSec(outro)
                dialogs.showSkipDialog = false
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = { dialogs.showSkipDialog = false }) { Text("取消") }
        }
    )
}
}
