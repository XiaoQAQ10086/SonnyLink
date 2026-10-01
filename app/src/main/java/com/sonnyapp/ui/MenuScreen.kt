package com.sonnyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink3
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid

/**
 * 启动页：选模式。
 *
 * 为什么必须选：**相机一次只能跑一种模式**。
 *   「嵌入式智能遥控」 -> ScalarWebAPI，只有取景/拍照
 *   「发送到智能手机」 -> DLNA 媒体服务器，只能浏览/下载
 * 两者互斥，由启动页选择进入哪个功能。
 */
@Composable
fun MenuScreen(onRemote: () -> Unit, onGallery: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink0).safeDrawingPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(com.sonnyapp.R.string.app_name),
            fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = TextHi,
        )
        Spacer(Modifier.height(6.dp))
        Text("索尼相机 Wi-Fi 工具", fontSize = 13.sp, color = TextLo)
        Spacer(Modifier.height(6.dp))
        Text(
            "第三方非官方软件 · 与索尼公司无任何关联\n仅用于连接你自己拥有的相机",
            fontSize = 10.sp, color = TextLo,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(44.dp))

        ModeCard(
            title = "遥控监看",
            subtitle = "实时取景 · 拍照 · 曝光补偿 · 定时自拍",
            hint = "相机需切到「嵌入式智能遥控」",
            onClick = onRemote,
        )
        Spacer(Modifier.height(14.dp))
        ModeCard(
            title = "传输照片",
            subtitle = "浏览卡内照片 · 下载全尺寸原图",
            hint = "相机需切到「发送到智能手机」",
            onClick = onGallery,
        )

        Spacer(Modifier.height(28.dp))
        Text(
            "相机的 DLNA 服务器只提供 JPEG。\nRAW 与视频这台机型无法通过 Wi-Fi 传输（官方 App 同样不行）。",
            color = TextLo,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    hint: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ink1)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(title, color = TextHi, fontSize = 19.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, color = TextMid, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text(hint, color = Accent, fontSize = 10.sp)
    }
}
