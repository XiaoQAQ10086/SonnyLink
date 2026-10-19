package com.sonnyapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.liveview.AssistState
import com.sonnyapp.liveview.GridMode
import com.sonnyapp.liveview.PeakingColor
import com.sonnyapp.liveview.PeakingLevel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink3
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import kotlin.math.sqrt

/**
 * 构图网格。
 *
 * 只画在**画面矩形内**（imgLeft..imgBottom 由渲染器按实际缩放算出）。
 * 画到整屏上的话，「适应」模式下网格会跑到上下黑边里，反而干扰构图判断。
 */
@Composable
fun GridOverlay(
    mode: GridMode,
    imgLeft: Float,
    imgTop: Float,
    imgRight: Float,
    imgBottom: Float,
    color: Color = Color(0x59FFFFFF),
) {
    if (mode == GridMode.OFF) return
    Canvas(Modifier.fillMaxSize()) {
        val l = size.width * imgLeft
        val t = size.height * imgTop
        val r = size.width * imgRight
        val b = size.height * imgBottom
        val w = r - l
        val h = b - t
        if (w <= 1f || h <= 1f) return@Canvas

        val stroke = 1.dp.toPx()

        fun vLine(f: Float) = drawLine(color, Offset(l + w * f, t), Offset(l + w * f, b), stroke)
        fun hLine(f: Float) = drawLine(color, Offset(l, t + h * f), Offset(r, t + h * f), stroke)

        when (mode) {
            GridMode.THIRDS -> {
                vLine(1f / 3f); vLine(2f / 3f)
                hLine(1f / 3f); hLine(2f / 3f)
            }

            GridMode.GOLDEN -> {
                vLine(0.382f); vLine(0.618f)
                hLine(0.382f); hLine(0.618f)
            }

            GridMode.CROSS -> {
                vLine(0.5f); hLine(0.5f)
                // 中央方框：手持对准主体时比十字线更好用
                val bw = w * 0.18f
                val bh = h * 0.18f
                drawRect(
                    color = color,
                    topLeft = Offset(l + w / 2f - bw / 2f, t + h / 2f - bh / 2f),
                    size = Size(bw, bh),
                    style = Stroke(stroke),
                )
            }

            GridMode.OFF -> Unit
        }
    }
}

/**
 * 亮度直方图。
 *
 * 纵向用**平方根**压缩：真实直方图里单峰常常占 30% 以上（大片天空、白墙），
 * 线性缩放会把其余 200 多档全压成一条平线，等于什么都没显示。
 */
@Composable
fun HistogramOverlay(bins: IntArray, peak: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRoundRect(
            color = Color(0x99000000),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        if (bins.size != 256 || peak <= 0) return@Canvas

        val w = size.width
        val h = size.height
        val pad = 2.dp.toPx()
        val usable = (h - pad * 2f).coerceAtLeast(1f)

        val path = Path()
        path.moveTo(0f, h)
        for (i in 0 until 256) {
            val x = w * i / 255f
            val ratio = (bins[i].toFloat() / peak).coerceIn(0f, 1f)
            val y = h - pad - sqrt(ratio) * usable
            path.lineTo(x, y)
        }
        path.lineTo(w, h)
        path.close()
        drawPath(path, Color(0x99EDEDF0))
        // 基线
        drawLine(Color(0x33FFFFFF), Offset(0f, h), Offset(w, h), 1f)
    }
}

/**
 * 监看辅助面板。
 *
 * 采用「点一下切换、常驻可见当前值」的形式，而不是滑杆：
 * 拍摄中手是稳不住的，滑杆要精确拖动反而更难用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.AssistPanel(
    state: AssistState,
    onGrid: () -> Unit,
    onPeaking: () -> Unit,
    onPeakColor: (PeakingColor) -> Unit,
    onHistogram: () -> Unit,
    onResetZoom: () -> Unit,
    onCapture: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.align(Alignment.TopEnd)
            .padding(top = 8.dp, end = 8.dp)
            .widthIn(max = 268.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6141417))
            .border(1.dp, Ink3, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("监看辅助", color = TextHi, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                "收起",
                color = TextMid,
                fontSize = 11.sp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClose() },
            )
        }

        Spacer(Modifier.height(8.dp))

        AssistRow(label = "网格") {
            AssistChip(state.grid.label.removePrefix("网格 "), state.grid != GridMode.OFF, onGrid)
        }

        Spacer(Modifier.height(6.dp))

        AssistRow(label = "峰值") {
            AssistChip(state.peaking.label.removePrefix("峰值 "), state.peaking != PeakingLevel.OFF, onPeaking)
        }

        // 颜色单独一行：峰值档位 4 颗 + 颜色 4 颗塞不进 268dp，会被裁掉
        if (state.peaking != PeakingLevel.OFF) {
            Spacer(Modifier.height(6.dp))
            AssistRow(label = "颜色") {
                for (c in PeakingColor.entries) {
                    AssistChip(c.label, state.peakColor == c) { onPeakColor(c) }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 一行三颗：直方图 / 复位变焦 / 截图
        Box(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip("直方图", state.histogram, onHistogram)
                Spacer(Modifier.width(6.dp))
                AssistChip(
                    if (state.isZoomed) String.format("变焦 %.1f×", state.zoom) else "变焦",
                    state.isZoomed,
                ) { if (state.isZoomed) onResetZoom() }
                Spacer(Modifier.width(6.dp))
                AssistChip("截图", false, onCapture)
            }
        }
    }
}

@Composable
private fun AssistRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextLo, fontSize = 10.sp, modifier = Modifier.width(30.dp))
        content()
    }
}

/** 辅助面板里的小胶囊：比取景用的 Chip 更紧凑，字号也更小。 */
@Composable
fun AssistChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (active) Accent else Color(0x33FFFFFF))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            color = if (active) Color.White else TextMid,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/**
 * 底部浮出的短提示（截图结果等）。
 *
 * 不用 Toast：系统 Toast 在 MIUI 上会出现两套样式，而且会盖住快门。
 */
@Composable
fun BoxScope.ToastPill(text: String, bottomPadding: androidx.compose.ui.unit.Dp) {
    if (text.isEmpty()) return
    Box(
        Modifier.align(Alignment.BottomCenter)
            .padding(bottom = bottomPadding)
            .clip(RoundedCornerShape(50))
            .background(Color(0xD91E1E22))
            .border(1.dp, Ink3, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = TextHi, fontSize = 11.sp)
    }
}
