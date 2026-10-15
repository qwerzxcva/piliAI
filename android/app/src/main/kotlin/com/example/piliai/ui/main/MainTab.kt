package com.example.piliai.ui.main

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.piliai.R

/**
 * 底部导航 tab。
 *
 * 审核318：新增 LIVE。Screen.LiveList 路由早在上上个版本就注册好了，
 * 但全仓库没有任何地方 navigate 过去——直播列表页写完了却点不进去。
 * 参考 BiliPai BottomNavItem（11 个可配置项，直播是一级 tab），
 * piliai 目前 4 个固定 tab，直播放在 Home 与 Mine 之间。
 */
enum class MainTab(
    @param:StringRes val labelRes: Int,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
) {
    HOME(R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    DYNAMICS(R.string.tab_dynamics, Icons.AutoMirrored.Outlined.ArrowForward, Icons.AutoMirrored.Filled.ArrowForward),
    LIVE(R.string.tab_live, Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    MINE(R.string.tab_mine, Icons.Outlined.Person, Icons.Filled.Person),
}
