package com.example.piliai.ui.main

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.piliai.R

enum class MainTab(
    @param:StringRes val labelRes: Int,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
) {
    HOME(R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    DYNAMICS(R.string.tab_dynamics, Icons.AutoMirrored.Outlined.ArrowForward, Icons.AutoMirrored.Filled.ArrowForward),
    MINE(R.string.tab_mine, Icons.Outlined.Person, Icons.Filled.Person),
}
