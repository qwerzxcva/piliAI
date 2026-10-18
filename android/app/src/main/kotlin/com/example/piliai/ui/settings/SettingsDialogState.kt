package com.example.piliai.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 审核314：设置页 19 个弹窗的可见性状态。
 *
 * 原来这 19 个 `var showXxxDialog by remember { mutableStateOf(false) }`
 * 直接声明在 SettingsScreen 的 Composable 体内，导致：
 * - SettingsScreen 1539 行，其中 521 行是 dialog，主体被稀释
 * - dialog 想拆到独立文件时必须传 19 个参数
 *
 * 收成一个类后，拆出去的文件只多一个参数。
 */
class SettingsDialogState {
    var showAboutDialog by mutableStateOf(false)
    var showAccentDialog by mutableStateOf(false)
    var showAudioQaDialog by mutableStateOf(false)
    var showBackendDialog by mutableStateOf(false)
    var showBanDialog by mutableStateOf(false)
    // 审核368：评论过滤弹窗
    var showReplyBanDialog by mutableStateOf(false)
    var showReplyBlockDialog by mutableStateOf(false)
    var showBufferDialog by mutableStateOf(false)
    var showCellularAudioDialog by mutableStateOf(false)
    var showCellularCodecDialog by mutableStateOf(false)
    var showCellularQaDialog by mutableStateOf(false)
    var showCodecDialog by mutableStateOf(false)
    var showCompletionDialog by mutableStateOf(false)
    var showFeedSourceDialog by mutableStateOf(false)
    var showHwDecodeDialog by mutableStateOf(false)
    var showPicQualityDialog by mutableStateOf(false)
    var showQualityDialog by mutableStateOf(false)
    var showRendererDialog by mutableStateOf(false)
    var showSkipDialog by mutableStateOf(false)
    var showSpeedListDialog by mutableStateOf(false)
    var showSuperResolutionDialog by mutableStateOf(false)
    var showThemeDialog by mutableStateOf(false)

    /** 全部关闭（离开设置页时用，避免返回后弹窗仍开着） */
    fun dismissAll() {
        showAboutDialog = false
        showAccentDialog = false
        showAudioQaDialog = false
        showBackendDialog = false
        showBanDialog = false
        showReplyBanDialog = false
        showReplyBlockDialog = false
        showBufferDialog = false
        showCellularAudioDialog = false
        showCellularCodecDialog = false
        showCellularQaDialog = false
        showCodecDialog = false
        showCompletionDialog = false
        showFeedSourceDialog = false
        showHwDecodeDialog = false
        showPicQualityDialog = false
        showQualityDialog = false
        showRendererDialog = false
        showSkipDialog = false
        showSpeedListDialog = false
        showSuperResolutionDialog = false
        showThemeDialog = false
    }
}
