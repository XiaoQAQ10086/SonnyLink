package com.sonnyapp.theme

import androidx.compose.ui.graphics.Color

/*
 * 深色中性配色 —— 相机 App 的行业惯例。
 *
 * 为什么不用 Material You 动态取色：它会从壁纸取色，界面可能变成粉的绿的，
 * **干扰用户对画面的判断**。相机界面应当尽量"消失"，只留一个强调色。
 */

/* 背景层级：从最底到浮层 */
val Ink0 = Color(0xFF0A0A0B)
val Ink1 = Color(0xFF141417)
val Ink2 = Color(0xFF1E1E22)
val Ink3 = Color(0xFF2A2A30)

/* 文字层级 */
val TextHi = Color(0xFFEDEDF0)
val TextMid = Color(0xFF9A9AA3)
val TextLo = Color(0xFF6A6A74)

/* 强调色：只给快门和关键动作 */
val Accent = Color(0xFFFF5A36)
val AccentPressed = Color(0xFFCC4527)

/* 状态色 */
val OkGreen = Color(0xFF4ADE80)
val WarnAmber = Color(0xFFFBBF24)
val ErrorRed = Color(0xFFFF6B6B)

/* 半透明浮层底色（压在取景画面上） */
val ScrimTop = Color(0xCC000000)
val ScrimBottom = Color(0xB3000000)
