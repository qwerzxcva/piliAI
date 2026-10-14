package com.example.piliai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.piliai.utils.StorageManager

// ==================== 批次L：Kototoro 风格主题 ====================

// PiliNara brand colors（保留旧引用兼容）
val PinkPrimary = Color(0xFFFB7299)
val PinkPrimaryDark = Color(0xFFD6497A)

/** 主题强调色选项（对齐 Flutter colorThemeTypes） */
data class AccentOption(val color: Color, val label: String, val hex: String)

val ACCENT_OPTIONS: List<AccentOption> = listOf(
    AccentOption(Color(0xFF5CB67B), "默认绿", "#5CB67B"),
    AccentOption(Color(0xFFFB7299), "哔哩粉", "#FB7299"),
    AccentOption(Color(0xFFF44336), "红色", "#F44336"),
    AccentOption(Color(0xFFFF9800), "橙色", "#FF9800"),
    AccentOption(Color(0xFFFFC107), "琥珀色", "#FFC107"),
    AccentOption(Color(0xFFFFEB3B), "黄色", "#FFEB3B"),
    AccentOption(Color(0xFFCDDC39), "酸橙色", "#CDDC39"),
    AccentOption(Color(0xFF8BC34A), "浅绿色", "#8BC34A"),
    AccentOption(Color(0xFF4CAF50), "绿色", "#4CAF50"),
    AccentOption(Color(0xFF009688), "青色", "#009688"),
    AccentOption(Color(0xFF00BCD4), "蓝绿色", "#00BCD4"),
    AccentOption(Color(0xFF03A9F4), "浅蓝色", "#03A9F4"),
    AccentOption(Color(0xFF2196F3), "蓝色", "#2196F3"),
    AccentOption(Color(0xFF3F51B5), "靛蓝色", "#3F51B5"),
    AccentOption(Color(0xFF9C27B0), "紫色", "#9C27B0"),
    AccentOption(Color(0xFF673AB7), "深紫色", "#673AB7"),
    AccentOption(Color(0xFF607D8B), "蓝灰色", "#607D8B"),
    AccentOption(Color(0xFF795548), "棕色", "#795548"),
    AccentOption(Color(0xFF9E9E9E), "灰色", "#9E9E9E"),
)

/** 解析存储的十六进制强调色（空/未知 = 默认） */
fun accentFromHex(hex: String): AccentOption =
    ACCENT_OPTIONS.firstOrNull { it.hex.equals(hex, ignoreCase = true) } ?: ACCENT_OPTIONS[0]

private fun Color.darken(f: Float = 0.25f) = Color(
    red = (red * (1 - f)).coerceIn(0f, 1f),
    green = (green * (1 - f)).coerceIn(0f, 1f),
    blue = (blue * (1 - f)).coerceIn(0f, 1f),
    alpha = alpha
)

private fun Color.lighten(f: Float = 0.25f) = Color(
    red = (red + (1 - red) * f).coerceIn(0f, 1f),
    green = (green + (1 - green) * f).coerceIn(0f, 1f),
    blue = (blue + (1 - blue) * f).coerceIn(0f, 1f),
    alpha = alpha
)

private fun Color.compositeOver(background: Color): Color {
    val a = alpha + background.alpha * (1 - alpha)
    if (a == 0f) return Color.Transparent
    return Color(
        red = (red * alpha + background.red * background.alpha * (1 - alpha)) / a,
        green = (green * alpha + background.green * background.alpha * (1 - alpha)) / a,
        blue = (blue * alpha + background.blue * background.alpha * (1 - alpha)) / a,
        alpha = a
    )
}

// ==================== Kototoro 风格语义色 token ====================

/**
 * Kototoro 语义色 / 分层 surface 常量。
 * 来源：上游 Kototoro-app/Kototoro colors.xml。
 */
object PiliSemantic {
    // 语义色
    val green = Color(0xFF388E3C)
    val red = Color(0xFFD32F2F)
    val yellow = Color(0xFFFBC02D)
    val warning = Color(0xFFE65100)
    val grey = Color(0xFF424242)

    // 分级标记
    val nsfw18 = Color(0xFFFF8A65)
    val nsfw16 = Color(0xFFFFD54F)

    // iOS 蓝（基线 primary）
    val iosBlue = Color(0xFF007AFF)

    // ---- AMOLED 纯黑分层 surface 容器 ----
    object Amoled {
        val background = Color(0xFF000000)
        val surface = Color(0xFF000000)
        val surfaceContainerLowest = Color(0xFF121212)
        val surfaceContainerLow = Color(0xFF1A1A1A)
        val surfaceContainer = Color(0xFF222222)
        val surfaceContainerHigh = Color(0xFF2A2A2A)
        val surfaceContainerHighest = Color(0xFF303030)
        val surfaceVariant = Color(0xFF242424)
        val surfaceBright = Color(0xFF303030)
        val surfaceDim = Color(0xFF000000)
    }

    // ---- 普通暗色分层 surface 容器（MD3 柔和深色，表面约 #16121A）----
    object Dark {
        val background = Color(0xFF16121A)
        val surface = Color(0xFF16121A)
        val surfaceContainerLowest = Color(0xFF0F0D13)
        val surfaceContainerLow = Color(0xFF1D1B20)
        val surfaceContainer = Color(0xFF211F26)
        val surfaceContainerHigh = Color(0xFF2B2930)
        val surfaceContainerHighest = Color(0xFF36343B)
        val surfaceVariant = Color(0xFF49454F)
        val surfaceBright = Color(0xFF3B3841)
        val surfaceDim = Color(0xFF16121A)
    }

    // ---- 亮色分层 surface 容器（MD3 基线）----
    object Light {
        val background = Color(0xFFFFFBFF)
        val surface = Color(0xFFFFFBFF)
        val surfaceContainerLowest = Color(0xFFFFFFFF)
        val surfaceContainerLow = Color(0xFFF7F3FA)
        val surfaceContainer = Color(0xFFF3EDF7)
        val surfaceContainerHigh = Color(0xFFEDE7F1)
        val surfaceContainerHighest = Color(0xFFE6E0E9)
        val surfaceVariant = Color(0xFFE7E0EC)
        val surfaceBright = Color(0xFFFFFBFF)
        val surfaceDim = Color(0xFFDED8E1)
    }
}

/** 由强调色派生 Material3 亮色 scheme（补全 MD3 全部分层 surface 容器字段） */
private fun lightSchemeOf(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.16f).compositeOver(Color.White),
    onPrimaryContainer = accent.darken(),
    secondary = accent.darken(0.15f),
    onSecondary = Color.White,
    secondaryContainer = accent.copy(alpha = 0.12f).compositeOver(Color.White),
    onSecondaryContainer = accent.darken(),
    tertiary = PiliSemantic.green,
    onTertiary = Color.White,
    tertiaryContainer = PiliSemantic.green.copy(alpha = 0.14f).compositeOver(Color.White),
    onTertiaryContainer = PiliSemantic.green.darken(),
    error = PiliSemantic.red,
    onError = Color.White,
    errorContainer = PiliSemantic.red.copy(alpha = 0.12f).compositeOver(Color.White),
    onErrorContainer = PiliSemantic.red.darken(),
    background = PiliSemantic.Light.background,
    onBackground = Color(0xFF1D1B20),
    surface = PiliSemantic.Light.surface,
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = PiliSemantic.Light.surfaceVariant,
    onSurfaceVariant = Color(0xFF49454F),
    surfaceDim = PiliSemantic.Light.surfaceDim,
    surfaceBright = PiliSemantic.Light.surfaceBright,
    surfaceContainerLowest = PiliSemantic.Light.surfaceContainerLowest,
    surfaceContainerLow = PiliSemantic.Light.surfaceContainerLow,
    surfaceContainer = PiliSemantic.Light.surfaceContainer,
    surfaceContainerHigh = PiliSemantic.Light.surfaceContainerHigh,
    surfaceContainerHighest = PiliSemantic.Light.surfaceContainerHighest,
    outline = Color(0xFF79767F),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF7),
    inversePrimary = accent.lighten(0.35f),
    surfaceTint = accent,
)

/**
 * 由强调色派生 Material3 暗色 scheme。
 * @param amoled true = AMOLED 纯黑（surface/background #000000 + #121212~#303030 梯度容器）；
 *               false = 普通 MD3 柔和暗色（表面约 #16121A）。
 */
private fun darkSchemeOf(accent: Color, amoled: Boolean = false) = darkColorScheme(
    primary = accent.lighten(),
    onPrimary = if (amoled) Color(0xFF000000) else Color(0xFF16121A),
    primaryContainer = accent.copy(alpha = 0.32f)
        .compositeOver(if (amoled) Color(0xFF121212) else Color(0xFF1D1B20)),
    onPrimaryContainer = accent.lighten(0.4f),
    secondary = accent.lighten(0.2f),
    onSecondary = if (amoled) Color(0xFF000000) else Color(0xFF16121A),
    secondaryContainer = accent.copy(alpha = 0.24f)
        .compositeOver(if (amoled) Color(0xFF1A1A1A) else Color(0xFF211F26)),
    onSecondaryContainer = accent.lighten(0.35f),
    tertiary = PiliSemantic.green.lighten(0.2f),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = PiliSemantic.green.copy(alpha = 0.28f)
        .compositeOver(if (amoled) Color(0xFF1A1A1A) else Color(0xFF211F26)),
    onTertiaryContainer = PiliSemantic.green.lighten(0.45f),
    error = PiliSemantic.red.lighten(0.15f),
    onError = Color(0xFF000000),
    errorContainer = PiliSemantic.red.copy(alpha = 0.30f)
        .compositeOver(if (amoled) Color(0xFF1A1A1A) else Color(0xFF211F26)),
    onErrorContainer = PiliSemantic.red.lighten(0.45f),
    background = if (amoled) PiliSemantic.Amoled.background else PiliSemantic.Dark.background,
    onBackground = if (amoled) Color(0xFFE6E1E5) else Color(0xFFE6E0E9),
    surface = if (amoled) PiliSemantic.Amoled.surface else PiliSemantic.Dark.surface,
    onSurface = if (amoled) Color(0xFFE6E1E5) else Color(0xFFE6E0E9),
    surfaceVariant = if (amoled) PiliSemantic.Amoled.surfaceVariant else PiliSemantic.Dark.surfaceVariant,
    onSurfaceVariant = if (amoled) Color(0xFFC8C4C9) else Color(0xFFCAC4D0),
    surfaceDim = if (amoled) PiliSemantic.Amoled.surfaceDim else PiliSemantic.Dark.surfaceDim,
    surfaceBright = if (amoled) PiliSemantic.Amoled.surfaceBright else PiliSemantic.Dark.surfaceBright,
    surfaceContainerLowest = if (amoled) PiliSemantic.Amoled.surfaceContainerLowest else PiliSemantic.Dark.surfaceContainerLowest,
    surfaceContainerLow = if (amoled) PiliSemantic.Amoled.surfaceContainerLow else PiliSemantic.Dark.surfaceContainerLow,
    surfaceContainer = if (amoled) PiliSemantic.Amoled.surfaceContainer else PiliSemantic.Dark.surfaceContainer,
    surfaceContainerHigh = if (amoled) PiliSemantic.Amoled.surfaceContainerHigh else PiliSemantic.Dark.surfaceContainerHigh,
    surfaceContainerHighest = if (amoled) PiliSemantic.Amoled.surfaceContainerHighest else PiliSemantic.Dark.surfaceContainerHighest,
    outline = if (amoled) Color(0xFF8A8A8A) else Color(0xFF938F99),
    outlineVariant = if (amoled) Color(0xFF3A3A3A) else Color(0xFF49454F),
    scrim = Color(0xFF000000),
    inverseSurface = if (amoled) Color(0xFFE6E1E5) else Color(0xFFE6E0E9),
    inverseOnSurface = if (amoled) Color(0xFF000000) else Color(0xFF322F35),
    inversePrimary = accent.darken(0.1f),
    surfaceTint = accent.lighten(),
)

/**
 * 审核238：Kototoro 几何 token（SharedInterfaceStyleTokens.kt IOS 档移植）。
 * 全 App 布局统一从此取值，不再散落魔法数。
 *
 * 审核257：圆角值原本照搬 IOS 档（group=18/section=12），但实际 UI 用的是
 * BiliPai 卡片体系（14/10/8/6 四级）。token 与实际值不一致等于没有 token ——
 * 照着它写的代码会和周边硬编码的圆角打架。这里按实际使用情况重定值。
 */
data class InterfaceStyleTokens(
    val screenHorizontalPadding: androidx.compose.ui.unit.Dp,
    val sectionVerticalSpacing: androidx.compose.ui.unit.Dp,
    /** 卡片圆角（BiliPai 规格，审核234 定）——最常用，单独一个 token */
    val cardCornerRadius: androidx.compose.ui.unit.Dp,
    /** 分组容器圆角（设置分组、底部 dock 等大容器） */
    val groupCornerRadius: androidx.compose.ui.unit.Dp,
    /** 小控件圆角（chip、小浮标） */
    val chipCornerRadius: androidx.compose.ui.unit.Dp,
    val settingsGroupOuterCornerRadius: androidx.compose.ui.unit.Dp,
    val settingsItemGap: androidx.compose.ui.unit.Dp,
    val settingsItemMinHeight: androidx.compose.ui.unit.Dp,
    val settingsItemIconContainerSize: androidx.compose.ui.unit.Dp,
    val controlCornerRadius: androidx.compose.ui.unit.Dp,
    val controlHeight: androidx.compose.ui.unit.Dp,
    val minimumTouchTarget: androidx.compose.ui.unit.Dp,
    val mainTopBarHeight: androidx.compose.ui.unit.Dp,
    val topBarIconSize: androidx.compose.ui.unit.Dp,
    val sheetCornerRadius: androidx.compose.ui.unit.Dp,
)

val PiliTokens = InterfaceStyleTokens(
    screenHorizontalPadding = 16.dp,
    sectionVerticalSpacing = 20.dp,
    cardCornerRadius = 14.dp,
    groupCornerRadius = 16.dp,
    chipCornerRadius = 8.dp,
    settingsGroupOuterCornerRadius = 16.dp,
    settingsItemGap = 0.dp,
    settingsItemMinHeight = 56.dp,
    settingsItemIconContainerSize = 40.dp,
    controlCornerRadius = 12.dp,
    controlHeight = 50.dp,
    minimumTouchTarget = 48.dp,
    mainTopBarHeight = 64.dp,
    topBarIconSize = 22.dp,
    sheetCornerRadius = 28.dp,
)

/**
 * 审核257：卡片圆角快捷取值（BiliPai 规格）。
 * UI 层统一用 `PiliCardShape` 而不是各写一个 RoundedCornerShape(14.dp)，
 * 后续要整体调圆角只改这里。
 */
val PiliCardShape = RoundedCornerShape(PiliTokens.cardCornerRadius)

/**
 * 审核280：封面「rim」描边刷（Kototoro rememberCoverRimBorderBrush 移植，
 * SharedContentCardBadges.kt:246）。0.5dp 竖向渐变描边让封面在任意底色上都有
 * 边缘，这是 Kototoro 卡片「不显廉价」的关键之一——只有圆角没有描边时，
 * 封面与背景交界处发糊。
 */
@Composable
fun rememberCoverRimBrush(): Brush {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    return remember(isDark, outlineVariant) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = if (isDark) 0.22f else 0.55f),
            0.4f to Color.White.copy(alpha = if (isDark) 0.08f else 0.20f),
            1.0f to Color.White.copy(alpha = if (isDark) 0.04f else 0.10f),
        )
    }
}

/** Kototoro compact grid 标题遮罩色标（SharedPosterCover.kt:52-60） */
val PiliCoverScrimStops = arrayOf(
    0.0f to Color.Transparent,
    0.20f to Color.Black.copy(alpha = 0.05f),
    0.40f to Color.Black.copy(alpha = 0.16f),
    0.60f to Color.Black.copy(alpha = 0.34f),
    0.80f to Color.Black.copy(alpha = 0.54f),
    1.0f to Color.Black.copy(alpha = 0.72f),
)

/** Kototoro 风格形状 token（Shapes 由 token 派生，KototoroShapes.kt） */
val PiliShapes = Shapes(
    extraSmall = RoundedCornerShape(PiliTokens.controlCornerRadius.coerceAtMost(14.dp)),
    small = RoundedCornerShape(PiliTokens.controlCornerRadius),
    medium = RoundedCornerShape(PiliTokens.groupCornerRadius),
    large = RoundedCornerShape(PiliTokens.groupCornerRadius),
    extraLarge = RoundedCornerShape(PiliTokens.groupCornerRadius),
)

/** 品牌渐变 token（头图/封面遮罩/横幅） */
object PiliGradients {
    fun brand(accent: Color) = Brush.verticalGradient(
        colors = listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.15f))
    )

    fun coverScrim() = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
    )

    val bilibili = Brush.linearGradient(
        colors = listOf(Color(0xFFFB7299), Color(0xFF23ADE5))
    )
}

@Composable
fun PiliNaraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // DataStore 消费：themeMode（system/light/dark）+ accentColor 真实作用于全局主题
    val context = LocalContext.current
    val storage = remember(context) { StorageManager.getInstance(context) }
    val themeMode by storage.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val accentHex by storage.accentColorFlow.collectAsStateWithLifecycle(initialValue = "")
    val amoled by storage.amoledFlow.collectAsStateWithLifecycle(initialValue = false)

    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> darkTheme
    }
    val accent = accentFromHex(accentHex).color

    val colorScheme = when {
        dynamicColor -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> darkSchemeOf(accent, amoled = amoled)
        else -> lightSchemeOf(accent)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = PiliShapes,
        content = content
    )
}