package com.example.piliai.ui.settings
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.piliai.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.piliai.ui.theme.ACCENT_OPTIONS
import com.example.piliai.ui.theme.accentFromHex

/**
 * 设置页（真实持久化版）——播放/弹幕/主题，DataStore 存储
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDanmakuBlockClick: () -> Unit = {},
    // 审核241：离线/订阅入口（此前无参数，设置页这两项点了没反应）
    onDownloadsClick: () -> Unit = {},
    onSubscribeClick: () -> Unit = {}
) {
    // 审核轮106：用全局 Application 而非 LocalContext（Activity 旋转重建时 factory 不会捕获旧 Activity）
    val viewModel: SettingsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(com.example.piliai.AppContext.get()) as T
    })
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 审核314：弹窗可见性收拢成一个状态对象（便于拆到独立文件）
    val dialogs = remember { SettingsDialogState() }


    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        // 审核216：设置分类子页——LazyListScope 局部扩展函数（捕获 state/viewModel），
        // Tab 切换按分类组合段落（PiliPlus 七分类思路的轻量落地，避免 DSL receiver 断裂）
        var category by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val categories = listOf("全部", "播放", "弹幕", "外观", "关于")

        fun LazyListScope.playSection() {
            // ===== 播放设置 =====
            item { SectionTitle("播放设置") }

            item {
                SettingSwitch(
                    title = "自动播放",
                    subtitle = "进入页面后自动播放视频",
                    icon = Icons.Default.PlayArrow,
                    checked = state.autoPlay,
                    onChange = { viewModel.setAutoPlay(it) }
                )
            }
            // ===== Kazumi 特性：低延迟音频 + 渲染器 =====
            item {
                SettingSwitch(
                    title = "低延迟音频",
                    subtitle = "降低音画延迟（弱网下可能卡顿）",
                    icon = Icons.Default.Speed,
                    checked = state.lowLatencyAudio,
                    onChange = { viewModel.setLowLatencyAudio(it) }
                )
            }
            item {
                // 审核轮210：音量归一化（AudioNormalizationProcessor 真接入）
                SettingRow(
                    title = "音量归一化",
                    subtitle = if (state.audioNormalization) "动态拉平响度（-16 LUFS）" else "关闭",
                    icon = Icons.Default.GraphicEq,
                    onClick = { viewModel.setAudioNormalization(!state.audioNormalization) }
                )
            }
            item {
                SettingRow(
                    title = "视频渲染器",
                    subtitle = if (state.renderer == 1) "TextureView（可合成动画）" else "SurfaceView（性能最佳）",
                    icon = Icons.AutoMirrored.Filled.ViewQuilt,
                    onClick = { dialogs.showRendererDialog = true }
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.playback_backend_title),
                    subtitle = stringResource(
                        if (state.playbackBackend == "gpu-next") {
                            if (com.example.piliai.playback.backend.GpuNextBackend.isAvailable())
                                R.string.playback_backend_gpu_next_ready
                            else R.string.playback_backend_gpu_next_unavailable
                        } else R.string.playback_backend_media3
                    ),
                    icon = Icons.Default.Speed,
                    onClick = { dialogs.showBackendDialog = true }
                )
            }
            item {
                // 审核279：首页数据源（可多选切换）——某个接口被风控时
                // 用户能手动切到能用的，不用干等自动降级。
                SettingRow(
                    title = "首页数据源",
                    subtitle = com.example.piliai.data.repository.HomeRepository
                        .prefLabel(state.homeFeedSource),
                    icon = Icons.Default.Home,
                    onClick = { dialogs.showFeedSourceDialog = true }
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.playback_completion_title),
                    subtitle = stringResource(
                        when (state.completionAction) {
                            "repeat" -> R.string.playback_completion_repeat
                            "stop" -> R.string.playback_completion_stop
                            else -> R.string.playback_completion_next
                        }
                    ),
                    icon = Icons.Default.SkipNext,
                    onClick = { dialogs.showCompletionDialog = true }
                )
            }
            item {
                SettingRow(
                    title = "缓冲时长",
                    subtitle = bufferLabel(state.bufferDurationMs),
                    icon = Icons.Default.Storage,
                    onClick = { dialogs.showBufferDialog = true }
                )
            }
            item {
                // bv P2：硬件解码开关（重建播放器生效）
                SettingRow(
                    title = stringResource(R.string.playback_decoder_title),
                    subtitle = stringResource(
                        when (state.hardwareDecodeMode) {
                            "hardware" -> R.string.playback_decoder_prefer_hardware
                            "software" -> R.string.playback_decoder_prefer_software
                            else -> R.string.playback_decoder_auto
                        }
                    ),
                    icon = Icons.Default.Memory,
                    onClick = { dialogs.showHwDecodeDialog = true }
                )
            }
            item {
                SettingRow(
                    title = "跳过片头片尾",
                    subtitle = "片头 ${state.skipIntroSec} 秒 · 片尾 ${state.skipOutroSec} 秒",
                    icon = Icons.Default.SkipNext,
                    onClick = { dialogs.showSkipDialog = true }
                )
            }
            item {
                SettingRow(
                    title = "画质增强",
                    // 审核328：原来是「点一下循环切换」的三态 SettingRow——用户
                    // 看不到有哪些档，也不知道当前点到了哪档，以为点不动。
                    // 改成弹窗三选一，和其他单选设置一致。
                    subtitle = when (state.superResolutionMode) {
                        "efficiency" -> "效率（锐化 + 最高 1080p）"
                        "quality" -> "质量（强锐化 + 最高 4K）"
                        else -> "关闭"
                    },
                    icon = Icons.Default.AutoAwesome,
                    onClick = { dialogs.showSuperResolutionDialog = true }
                )
            }
            item {
                SettingRow(
                    title = "默认清晰度",
                    subtitle = qualityLabel(state.videoQuality),
                    icon = Icons.Default.HighQuality,
                    onClick = { dialogs.showQualityDialog = true }
                )
            }
            item {
                // 审核219：默认音质（PiliPlus defaultAudioQa 对齐）
                SettingRow(
                    title = "默认音质",
                    subtitle = when (state.audioQa) {
                        "30251" -> "Hi-Res 无损"
                        "30280" -> "192K 高码率"
                        "30232" -> "132K 中码率"
                        "30216" -> "64K 省流"
                        else -> "自动（最高可用）"
                    },
                    icon = Icons.Default.MusicNote,
                    onClick = { dialogs.showAudioQaDialog = true }
                )
            }
            item {
                // 审核220：解码器偏好（PiliPlus preferCodecs 对齐）
                SettingRow(
                    title = "解码器偏好",
                    subtitle = when (state.preferCodec) {
                        "hevc" -> "HEVC/H.265（高压缩，需硬解支持）"
                        "av1" -> "AV1（最新一代，设备相关）"
                        else -> "AVC/H.264（省电，兼容性最佳）"
                    },
                    icon = Icons.Default.Memory,
                    onClick = { dialogs.showCodecDialog = true }
                )
            }
            // ===== 审核221：蜂窝网络覆盖（PiliPlus *Cellular 键对齐）=====
            item { SectionTitle("蜂窝网络（移动数据）") }
            item {
                SettingRow(
                    title = "蜂窝画质上限",
                    subtitle = if (state.videoQaCellular.isBlank()) "跟随默认画质"
                    else qualityLabel(
                        when (state.videoQaCellular) {
                            "116" -> "1080p"; "80" -> "1080p"; "64" -> "720p"; "32" -> "480p"; "16" -> "360p"
                            else -> state.videoQaCellular
                        }
                    ),
                    icon = Icons.Default.SignalCellular4Bar,
                    onClick = { dialogs.showCellularQaDialog = true }
                )
            }
            item {
                SettingRow(
                    title = "蜂窝音质上限",
                    subtitle = if (state.audioQaCellular.isBlank()) "跟随默认音质"
                    else when (state.audioQaCellular) {
                        "30251" -> "Hi-Res 无损"; "30280" -> "192K"; "30232" -> "132K"; "30216" -> "64K"
                        else -> state.audioQaCellular
                    },
                    icon = Icons.Default.MusicOff,
                    onClick = { dialogs.showCellularAudioDialog = true }
                )
            }
            item {
                SettingRow(
                    title = "蜂窝解码偏好",
                    subtitle = if (state.preferCodecCellular.isBlank()) "跟随默认解码器"
                    else when (state.preferCodecCellular) {
                        "hevc" -> "HEVC/H.265"; "av1" -> "AV1"; else -> "AVC/H.264"
                    },
                    icon = Icons.Default.SimCardAlert,
                    onClick = { dialogs.showCellularCodecDialog = true }
                )
            }
            item {
                // 审核216：PiliPlus enableAutoEnter
                SettingSwitch(
                    title = "自动进入全屏",
                    subtitle = "进入播放页即全屏播放",
                    icon = Icons.Default.Fullscreen,
                    checked = state.autoEnterFullscreen,
                    onChange = { viewModel.setAutoEnterFullscreen(it) }
                )
            }
            item {
                // 审核216：PiliPlus pauseOnMinimize
                SettingSwitch(
                    title = "切后台自动暂停",
                    subtitle = "应用退到后台时暂停播放",
                    icon = Icons.Default.Pause,
                    checked = state.pauseOnMinimize,
                    onChange = { viewModel.setPauseOnMinimize(it) }
                )
            }
            item {
                // 审核216：PiliPlus enableOnlineTotal
                SettingSwitch(
                    title = "显示在线人数",
                    subtitle = "播放页展示实时在线观众数",
                    icon = Icons.Default.Groups,
                    checked = state.showOnlineTotal,
                    onChange = { viewModel.setShowOnlineTotal(it) }
                )
            }
            // ===== 审核233：播放行为（PiliPlus 对齐）=====
            item {
                SettingSwitch(
                    title = "自动连播分P",
                    subtitle = "播放结束自动切换下一分P",
                    icon = Icons.Default.PlaylistPlay,
                    checked = state.continuePlayingPart,
                    onChange = { viewModel.setContinuePlayingPart(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "后台继续播放",
                    subtitle = "退到后台时保持音频播放（与自动暂停互斥）",
                    icon = Icons.Default.Headphones,
                    checked = state.continuePlayInBackground,
                    onChange = { viewModel.setContinuePlayInBackground(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "返回键直接退出",
                    subtitle = "播放页按返回直接退出而非最小化",
                    icon = Icons.Default.ExitToApp,
                    checked = state.directExitOnBack,
                    onChange = { viewModel.setDirectExitOnBack(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "长按倍速",
                    subtitle = "长按播放器临时加速播放",
                    icon = Icons.Default.FastForward,
                    checked = state.enableAutoLongPressSpeed,
                    onChange = { viewModel.setEnableAutoLongPressSpeed(it) }
                )
            }
            item {
                SettingSlider(
                    title = "长按倍速档位",
                    icon = Icons.Default.Speed,
                    value = state.longPressSpeedDefault,
                    valueRange = 1.5f..5f,
                    steps = 6,
                    displayValue = String.format(java.util.Locale.ROOT, "%.1fx", state.longPressSpeedDefault),
                    onValueChange = { viewModel.setLongPressSpeedDefault(it) }
                )
            }
            item {
                // 审核313：倍速档位列表（原来播放器硬编码 6 档，用户想加 3.0x
                // 或去掉不用的档位只能改代码）。这里开放编辑。
                SettingRow(
                    title = "倍速档位",
                    subtitle = state.playbackSpeedList
                        .joinToString(" / ") { "${formatSpeedLabel(it)}x" },
                    icon = Icons.Default.Speed,
                    onClick = { dialogs.showSpeedListDialog = true }
                )
            }
            // ===== 审核233：推荐过滤（PiliPlus recommend_filter 对齐）=====
            item { SectionTitle("推荐过滤") }
            item {
                SettingSlider(
                    title = "最短时长",
                    subtitle = "过滤时长小于该值的视频（秒，0 关闭）",
                    icon = Icons.Default.Timer,
                    value = state.minDurationForRcmd.toFloat(),
                    valueRange = 0f..600f,
                    steps = 19,
                    displayValue = if (state.minDurationForRcmd == 0) "关闭" else "${state.minDurationForRcmd}s",
                    onValueChange = { viewModel.setMinDurationForRcmd((it / 10).toInt() * 10) }
                )
            }
            item {
                SettingSlider(
                    title = "最低播放量",
                    subtitle = "过滤播放量低于该值的视频（0 关闭）",
                    icon = Icons.Default.Visibility,
                    value = state.minPlayForRcmd.toFloat(),
                    valueRange = 0f..100000f,
                    displayValue = if (state.minPlayForRcmd == 0) "关闭" else com.example.piliai.data.model.formatCount(state.minPlayForRcmd.toLong()),
                    onValueChange = { viewModel.setMinPlayForRcmd((it / 1000).toInt() * 1000) }
                )
            }
            item {
                SettingSlider(
                    title = "最低点赞率",
                    subtitle = "点赞数/播放数 低于该百分比时过滤（0 关闭）",
                    icon = Icons.Default.ThumbUp,
                    value = state.minLikeRatioForRecommend.toFloat(),
                    valueRange = 0f..20f,
                    steps = 19,
                    displayValue = if (state.minLikeRatioForRecommend == 0) "关闭" else "${state.minLikeRatioForRecommend}%",
                    onValueChange = { viewModel.setMinLikeRatioForRecommend(it.toInt()) }
                )
            }
            item {
                SettingSwitch(
                    title = "关注内容豁免",
                    subtitle = "已关注 UP 主的内容不过滤",
                    icon = Icons.Default.Favorite,
                    checked = state.exemptFilterForFollowed,
                    onChange = { viewModel.setExemptFilterForFollowed(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "过滤相关视频",
                    subtitle = "播放页相关视频列表同样应用过滤",
                    icon = Icons.Default.FilterList,
                    checked = state.applyFilterToRelatedVideos,
                    onChange = { viewModel.setApplyFilterToRelatedVideos(it) }
                )
            }
            item {
                SettingRow(
                    title = "标题屏蔽词",
                    subtitle = if (state.banWordForRecommend.isBlank()) "未设置（支持正则，大小写不敏感）"
                        else state.banWordForRecommend.take(24),
                    icon = Icons.Default.TextFields,
                    onClick = { dialogs.showBanDialog = true }
                )
                if (dialogs.showBanDialog) {
                    BanWordDialog(
                        initial = state.banWordForRecommend,
                        onSave = { viewModel.setBanWordForRecommend(it) },
                        onDismiss = { dialogs.showBanDialog = false }
                    )
                }
            }
            // 审核368：评论过滤（对齐 PiliNara reply_settings）
            item {
                SettingRow(
                    title = "评论屏蔽词",
                    subtitle = if (state.replyBanWords.isBlank()) "未设置（支持正则，大小写不敏感）"
                        else state.replyBanWords.take(24),
                    icon = Icons.Default.Comment,
                    onClick = { dialogs.showReplyBanDialog = true }
                )
                if (dialogs.showReplyBanDialog) {
                    BanWordDialog(
                        initial = state.replyBanWords,
                        onSave = { viewModel.setReplyBanWords(it) },
                        onDismiss = { dialogs.showReplyBanDialog = false }
                    )
                }
            }
            item {
                SettingRow(
                    title = "评论屏蔽用户",
                    subtitle = if (state.replyBlockedMids.isBlank()) "未设置（填 UID，逗号分隔）"
                        else state.replyBlockedMids.take(24),
                    icon = Icons.Default.Block,
                    onClick = { dialogs.showReplyBlockDialog = true }
                )
                if (dialogs.showReplyBlockDialog) {
                    BanWordDialog(
                        initial = state.replyBlockedMids,
                        onSave = { viewModel.setReplyBlockedMids(it) },
                        onDismiss = { dialogs.showReplyBlockDialog = false }
                    )
                }
            }
            item {
                SettingSwitch(
                    title = "屏蔽带货评论",
                    subtitle = "过滤包含购买引导与价格/链接的评论",
                    icon = Icons.Default.ShoppingCart,
                    checked = state.replyHideGoods,
                    onChange = { viewModel.setReplyHideGoods(it) }
                )
            }
        }
        fun LazyListScope.dmSection() {
            // ===== 弹幕设置 =====
            item { SectionTitle("弹幕设置") }

            item {
                SettingSwitch(
                    title = "弹幕显示",
                    subtitle = "显示视频弹幕",
                    icon = Icons.Default.Movie,
                    checked = state.danmakuEnabled,
                    onChange = { viewModel.setDanmakuEnabled(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "弹弹play 弹幕源",
                    subtitle = if (com.example.piliai.danmaku.DandanApi.isEnabled) "补充番剧弹幕（已配置凭据）" else "需先在 BuildConfig 配置 API 凭据",
                    icon = Icons.Default.PlayCircle,
                    checked = state.dandanDanmakuEnabled,
                    onChange = { viewModel.setDandanDanmakuEnabled(it) }
                )
            }
            item {
                SettingSlider(
                    title = "弹幕不透明度",
                    icon = Icons.Default.Opacity,
                    value = state.danmakuOpacity,
                    onValueChange = { viewModel.setDanmakuOpacity(it) }
                )
            }
            item {
                SettingSlider(
                    title = "弹幕字号",
                    icon = Icons.Default.TextFields,
                    value = state.danmakuFontSize.toFloat(),
                    valueRange = 12f..40f,
                    steps = 13,
                    displayValue = "${state.danmakuFontSize}",
                    onValueChange = { viewModel.setDanmakuFontSize(it.toInt()) }
                )
            }
            item {
                SettingSlider(
                    title = "弹幕速度",
                    icon = Icons.Default.Speed,
                    value = state.danmakuSpeed,
                    valueRange = 0.5f..2.5f,
                    displayValue = String.format(Locale.ROOT, "%.1fx", state.danmakuSpeed),
                    onValueChange = { viewModel.setDanmakuSpeed(it) }
                )
            }
            item {
                // 审核222：Flutter enableDmCount
                SettingSwitch(
                    title = "显示弹幕总数",
                    subtitle = "播放页展示该视频的弹幕总量",
                    icon = Icons.Default.Numbers,
                    checked = state.dmCountEnabled,
                    onChange = { viewModel.setDmCountEnabled(it) }
                )
            }
            item {
                SettingRow(
                    title = "弹幕屏蔽",
                    subtitle = "关键词 / 正则 / 用户 UID 规则",
                    icon = Icons.Default.Block,
                    onClick = onDanmakuBlockClick
                )
            }
            item {
                // 审核轮204：黑名单管理（PiliPlus blacklist 对齐）
                var showBlacklist by remember { mutableStateOf(false) }
                SettingRow(
                    title = "黑名单管理",
                    subtitle = "查看/移除已拉黑用户",
                    icon = Icons.Default.GppBad,
                    onClick = { showBlacklist = true }
                )
                if (showBlacklist) {
                    BlacklistDialog(onDismiss = { showBlacklist = false })
                }
            }
            // ===== 审核233：弹幕细节（PiliPlus 对齐）=====
            item {
                SettingSlider(
                    title = "弹幕显示区域",
                    subtitle = "限制弹幕占用屏幕高度比例",
                    icon = Icons.Default.VerticalAlignTop,
                    value = state.dmShowArea,
                    valueRange = 0.1f..1f,
                    displayValue = "${(state.dmShowArea * 100).toInt()}%",
                    onValueChange = { viewModel.setDmShowArea(it) }
                )
            }
            item {
                SettingSlider(
                    title = "弹幕描边宽度",
                    subtitle = "黑描边提升可读性（0 关闭）",
                    icon = Icons.Default.BorderColor,
                    value = state.dmStrokeWidth,
                    valueRange = 0f..5f,
                    displayValue = String.format(java.util.Locale.ROOT, "%.1f", state.dmStrokeWidth),
                    onValueChange = { viewModel.setDmStrokeWidth(it) }
                )
            }
            item {
                SettingSlider(
                    title = "弹幕行高",
                    icon = Icons.Default.FormatLineSpacing,
                    value = state.dmLineHeight,
                    valueRange = 1f..3f,
                    displayValue = String.format(java.util.Locale.ROOT, "%.1f", state.dmLineHeight),
                    onValueChange = { viewModel.setDmLineHeight(it) }
                )
            }
            item {
                SettingSlider(
                    title = "弹幕字重",
                    icon = Icons.Default.FormatBold,
                    value = state.dmFontWeight.toFloat(),
                    valueRange = 1f..9f,
                    steps = 7,
                    displayValue = "${state.dmFontWeight}",
                    onValueChange = { viewModel.setDmFontWeight(it.toInt()) }
                )
            }
            item {
                SettingSwitch(
                    title = "弹幕海量模式",
                    subtitle = "同屏弹幕过多时仍全部渲染",
                    icon = Icons.Default.Layers,
                    checked = state.dmMassiveMode,
                    onChange = { viewModel.setDmMassiveMode(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "合并重复弹幕",
                    subtitle = "相同内容的弹幕只显示一条",
                    icon = Icons.Default.MergeType,
                    checked = state.mergeDanmaku,
                    onChange = { viewModel.setMergeDanmaku(it) }
                )
            }
            item {
                // 审核295：showVipDanmaku 此前有完整数据链路但既无 UI 也无消费方
                // （纯半成品）。这里补上开关，播放层按 mode=8 过滤。
                SettingSwitch(
                    title = "显示大会员弹幕",
                    subtitle = "关闭后不渲染大会员专属弹幕",
                    icon = Icons.Default.WorkspacePremium,
                    checked = state.showVipDanmaku,
                    onChange = { viewModel.setShowVipDanmaku(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "点击弹幕交互",
                    subtitle = "点击弹幕可暂停/查看详情",
                    icon = Icons.Default.TouchApp,
                    checked = state.enableTapDanmaku,
                    onChange = { viewModel.setEnableTapDanmaku(it) }
                )
            }
            // 审核259：弹幕每秒上限（bv DanmakuRateLimiter）。0 = 不限。
            // 高能时段一秒能涌进几十条，不限制会糊成一片。
            item {
                SettingSlider(
                    title = "弹幕密度上限",
                    subtitle = "每秒最多显示多少条弹幕，0 为不限",
                    icon = Icons.Default.Speed,
                    value = state.danmakuMaxPerSecond.toFloat(),
                    valueRange = 0f..60f,
                    steps = 59,
                    displayValue = if (state.danmakuMaxPerSecond == 0) "不限"
                        else "${state.danmakuMaxPerSecond} 条/秒",
                    onValueChange = { viewModel.setDanmakuMaxPerSecond(it.toInt()) }
                )
            }
        }
        fun LazyListScope.subscribeSection() {
            item { SectionTitle("订阅源") }
            item {
                SettingSwitch(
                    title = "只显示有集数的条目",
                    subtitle = "过滤掉标题里没有「第x集」的 RSS 条目（OVA/特典/合集）",
                    icon = Icons.Default.FilterAlt,
                    checked = state.rssEpisodeOnly,
                    onChange = { viewModel.setRssEpisodeOnly(it) }
                )
            }
        }

        fun LazyListScope.styleSection() {
            // ===== 主题 =====
            item { SectionTitle("外观") }

            item {
                SettingRow(
                    title = "主题模式",
                    subtitle = themeLabel(state.themeMode),
                    icon = if (state.themeMode == "dark") Icons.Default.DarkMode else Icons.Default.LightMode,
                    onClick = { dialogs.showThemeDialog = true }
                )
            }
            item {
                SettingRow(
                    title = "主题颜色",
                    subtitle = accentFromHex(state.accentColor).label,
                    icon = Icons.Default.Palette,
                    onClick = { dialogs.showAccentDialog = true }
                )
            }
            item {
                SettingSwitch(
                    title = "AMOLED 纯黑",
                    subtitle = "暗色下使用纯黑背景，省电",
                    icon = Icons.Default.DarkMode,
                    checked = state.amoled,
                    onChange = { viewModel.setAmoled(it) }
                )
            }
            item { SectionTitle("底栏") }
            item {
                SettingSwitch(
                    title = "显示动态",
                    subtitle = "关闭后底栏不再显示动态",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    checked = "dynamics" in state.bottomTabs.split(','),
                    onChange = { viewModel.setBottomTabVisible("dynamics", it) }
                )
            }
            item {
                SettingSwitch(
                    title = "显示直播",
                    subtitle = "关闭后底栏不再显示直播",
                    icon = Icons.Default.LiveTv,
                    checked = "live" in state.bottomTabs.split(','),
                    onChange = { viewModel.setBottomTabVisible("live", it) }
                )
            }
            // 审核235：图片质量（B站图床 @Nq.webp 压缩，省流量加快加载）
            item {
                SettingRow(
                    title = "图片质量",
                    subtitle = when (state.picQuality) {
                        0 -> "原图"
                        100 -> "原图"
                        else -> "${state.picQuality}% 压缩"
                    },
                    icon = Icons.Default.Image,
                    onClick = { dialogs.showPicQualityDialog = true }
                )
            }
        }
        fun LazyListScope.subtitleSection() {
            // ===== 审核235：字幕细节（PiliPlus 对齐，真实作用于播放页字幕渲染）=====
            item { SectionTitle("字幕") }
            item {
                SettingSlider(
                    title = "字幕字号缩放",
                    icon = Icons.Default.TextFields,
                    value = state.subtitleScale,
                    valueRange = 0.5f..2f,
                    steps = 5,
                    displayValue = String.format(java.util.Locale.ROOT, "%.1fx", state.subtitleScale),
                    onValueChange = { viewModel.setSubtitleScale(it) }
                )
            }
            item {
                SettingSlider(
                    title = "字幕水平边距",
                    icon = Icons.Default.SettingsOverscan,
                    value = state.subtitlePaddingH,
                    valueRange = 0f..40f,
                    displayValue = "${state.subtitlePaddingH.toInt()}dp",
                    onValueChange = { viewModel.setSubtitlePaddingH(it) }
                )
            }
            item {
                SettingSlider(
                    title = "字幕底部边距",
                    icon = Icons.Default.VerticalAlignBottom,
                    value = state.subtitlePaddingB,
                    valueRange = 0f..200f,
                    displayValue = "${state.subtitlePaddingB.toInt()}dp",
                    onValueChange = { viewModel.setSubtitlePaddingB(it) }
                )
            }
            item {
                SettingSlider(
                    title = "字幕背景不透明度",
                    icon = Icons.Default.Layers,
                    value = state.subtitleBgOpacity,
                    valueRange = 0f..1f,
                    displayValue = "${(state.subtitleBgOpacity * 100).toInt()}%",
                    onValueChange = { viewModel.setSubtitleBgOpacity(it) }
                )
            }
            item {
                SettingSlider(
                    title = "字幕阴影强度",
                    icon = Icons.Default.WbSunny,
                    value = state.subtitleStroke,
                    valueRange = 0f..3f,
                    steps = 5,
                    displayValue = String.format(java.util.Locale.ROOT, "%.1f", state.subtitleStroke),
                    onValueChange = { viewModel.setSubtitleStroke(it) }
                )
            }
            item {
                SettingSwitch(
                    title = "字幕加粗",
                    icon = Icons.Default.FormatBold,
                    checked = state.subtitleBold,
                    onChange = { viewModel.setSubtitleBold(it) }
                )
            }
        }
        fun LazyListScope.aboutSection() {
            // ===== 关于 =====
            item { SectionTitle("关于") }
            item {
                SettingRow(
                    title = "关于 PiliNara",
                    subtitle = "Kotlin+Rust 版 v1.0 · Flutter 版功能移植中",
                    icon = Icons.Default.Info,
                    onClick = { dialogs.showAboutDialog = true }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            // 审核234：胶囊分段分类（与首页一致）
            com.example.piliai.ui.components.SegmentedPillRow(
                labels = categories,
                selectedIndex = category,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onSelect = { category = it },
            )
            // 审核307：分类切换后回到顶部。原来切到「播放」时列表停在
            // 「全部」的滚动位置（可能已经在很下面），用户看不到播放设置
            // 的第一项，以为「没跳过去」。
            val settingsListState = rememberLazyListState()
            LaunchedEffect(category) {
                if (settingsListState.firstVisibleItemIndex > 0) {
                    settingsListState.animateScrollToItem(0)
                }
            }
            LazyColumn(
                state = settingsListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (category) {
                    1 -> playSection()
                    2 -> { dmSection(); subtitleSection() }
                    3 -> styleSection()
                    4 -> aboutSection()
                    else -> {
                        // 审核252：订阅源 section 只在「全部」里出现
                        // （没有单独的订阅分类 tab，避免为一项设加一个分类）
                        playSection(); dmSection(); subtitleSection()
                        subscribeSection(); styleSection(); aboutSection()
                    }
                }
            }
        }
    }

    // 审核314：弹窗统一在 SettingsDialogs.kt（本文件只保留分区与 Scaffold）
    SettingsDialogs(state = state, viewModel = viewModel, dialogs = dialogs)
}

internal fun bufferLabel(ms: Int) = when (ms) {
    8_000 -> "8 秒"; 32_000 -> "32 秒"; else -> "16 秒（默认）"
}

/** 审核313：倍速档位标签（1 →「1」，0.75 →「0.75」；调用方补 x） */
internal fun formatSpeedLabel(v: Float): String {
    val r = Math.round(v * 100f) / 100f
    return if (r % 1.0f == 0.0f) r.toInt().toString() else r.toString()
}

internal fun qualityLabel(v: String) = when (v) {
    "1080p" -> "1080P 高清"; "720p" -> "720P 高清"; "480p" -> "480P 清晰"; else -> "自动"
}

internal fun themeLabel(v: String) = when (v) {
    "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统"
}
