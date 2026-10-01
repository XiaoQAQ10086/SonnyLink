package com.sonnyapp.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 相机 App 固定使用深色主题：
 *  - 深色不干扰对画面的判断
 *  - OLED 省电（取景时屏幕长时间常亮）
 *
 * 刻意**不用** Material You 动态取色 —— 它会从壁纸取色，界面可能变成彩色，
 * 干扰用户看画面。
 */
private val SonnyDarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentPressed,
    onPrimaryContainer = Color.White,
    secondary = TextMid,
    onSecondary = Ink0,
    background = Ink0,
    onBackground = TextHi,
    surface = Ink1,
    onSurface = TextHi,
    surfaceVariant = Ink2,
    onSurfaceVariant = TextMid,
    surfaceContainer = Ink1,
    surfaceContainerHigh = Ink2,
    outline = Ink3,
    outlineVariant = Ink3,
    error = ErrorRed,
    onError = Color.White,
)

@Composable
fun SonnyAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SonnyDarkScheme, typography = Typography, content = content)
}
