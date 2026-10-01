package com.sonnyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.camera.WifiState
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.WarnAmber

/** 检测成功用的绿色。 */
val OkGreen = Color(0xFF4ADE80)

/**
 * 两个连接页共用的 Wi-Fi 区。
 *
 * 设计要点：
 *  1. **先判断当前 Wi-Fi 是否为相机**，若是则只显示「连接相机」，无需两次操作
 *  2. **仅一个主按钮**，尺寸统一 54dp / 16sp，保证视觉一致
 *  3. 不是相机时才给「打开系统 Wi-Fi 设置」，并用一行小字提供「重试」
 */
@Composable
fun WifiConnectSection(
    wifi: WifiState,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {

        // ——— 状态卡 ———
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Ink1)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                wifi.ssid?.let { "当前 Wi-Fi：" + it }
                    ?: if (wifi.connected) "已连接 Wi-Fi（系统未提供名称）" else "未连接 Wi-Fi",
                color = TextHi,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                when {
                    wifi.looksLikeCamera -> "✓ 检测到相机热点"
                    wifi.connected -> "✗ 这不是相机热点"
                    else -> "请先连接相机的热点"
                },
                color = if (wifi.looksLikeCamera) OkGreen else WarnAmber,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ——— 唯一的主按钮（尺寸/字号统一） ———
        Button(
            onClick = { if (wifi.looksLikeCamera) onConnect() else onOpenSettings() },
            enabled = !busy,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Text(
                when {
                    busy -> "连接中…"
                    wifi.looksLikeCamera -> "连接相机"
                    else -> "打开系统 Wi-Fi 设置"
                },
                fontSize = 16.sp,
            )
        }

        // 不是相机时，额外给一个「重新检测」入口
        if (!wifi.looksLikeCamera) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton("已在系统设置里连好相机热点 · 点此重新检测") { onRetry() }
            Spacer(Modifier.height(10.dp))
            Text(
                "相机的热点名形如 DIRECT-xxxx:CameraModel。\n" +
                    "系统会提示「无法访问互联网」—— 这是正常的，相机热点本来就不提供上网。",
                color = TextLo,
                fontSize = 10.sp,
            )
        }
    }
}
