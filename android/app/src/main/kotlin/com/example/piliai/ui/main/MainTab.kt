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

/** 底栏项目的稳定 ID 与路由映射。 */
enum class MainTab(
    val id: String,
    @param:StringRes val labelRes: Int,
    val iconOutlined: ImageVector,
    val iconFilled: ImageVector,
) {
    HOME("home", R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    DYNAMICS("dynamics", R.string.tab_dynamics, Icons.AutoMirrored.Outlined.ArrowForward, Icons.AutoMirrored.Filled.ArrowForward),
    LIVE("live", R.string.tab_live, Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    MINE("mine", R.string.tab_mine, Icons.Outlined.Person, Icons.Filled.Person),

    ;

    companion object {
        val defaultIds = entries.map { it.id }
        val requiredIds = setOf(HOME.id, MINE.id)

        fun parse(raw: String): List<MainTab> {
            val requested = raw.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
            val result = entries.filter { it.id in requested || it.id in requiredIds }
            return if (result.isEmpty()) entries.toList() else result
        }
    }
}
