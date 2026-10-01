package com.sonnyapp.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink2
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import kotlinx.coroutines.delay

/**
 * 诊断日志面板。
 *
 * 用户遇到问题时，这里的信息应当**足以定位问题**，并提供一键复制 / 分享。
 * 内容由 Diagnostics 生成，包含应用版本、设备型号、Android 版本、屏幕规格、
 * 当前运行状态与逐行连接日志。
 */
@Composable
fun LogPanel(
    report: String,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // 复制后的提示 2 秒后自动消失
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    // 系统返回键也能关 —— 面板是全屏浮层，没有返回路径会很慌
    BackHandler(enabled = true) { onClose() }

    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClose() },
    ) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // 用屏高比例而不是固定 520dp：横屏时屏幕只有约 393dp 高，
                // 固定的 520dp 会把面板撑满全屏，蒙层没地方可点就退不出去了。
                .fillMaxHeight(0.85f)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Ink1)
                // 面板自身吃掉点击，避免点到面板就关闭
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("诊断日志", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (copied) "已复制到剪贴板" else "复制后发给开发者即可定位问题",
                        color = if (copied) Color(0xFF4ADE80) else TextLo,
                        fontSize = 10.sp,
                    )
                }
                // 明确的按钮外观 —— 纯文字用户会以为不可点
                PanelButton("复制") {
                    clipboard.setText(AnnotatedString(report))
                    copied = true
                }
                Spacer(Modifier.padding(horizontal = 3.dp))
                PanelButton("分享") {
                    try {
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "SonnyLink 诊断报告")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        ctx.startActivity(Intent.createChooser(i, "分享诊断报告"))
                    } catch (e: Exception) {
                        // 没有可分享的应用
                    }
                }
                Spacer(Modifier.padding(horizontal = 3.dp))
                // 必须有一个显式的关闭入口，不能只靠点蒙层
                PanelButton("关闭") { onClose() }
            }

            Spacer(Modifier.height(10.dp))

            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    report.ifEmpty { "（暂无内容）" },
                    color = TextMid,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** 面板里的小按钮，带背景和边框，外观上明确可点。 */
@Composable
private fun PanelButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Ink2)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Accent, fontSize = 12.sp)
    }
}

/**
 * 文字链替代品 —— 用在需要「次要操作」但必须看得出是按钮的地方。
 *
 * 之前这里用的是纯 Text + clickable，用户反馈「以为只是一行字」。
 */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink2)
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = TextMid, fontSize = 13.sp)
    }
}
