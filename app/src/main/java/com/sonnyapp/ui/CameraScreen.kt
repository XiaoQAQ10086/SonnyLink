package com.sonnyapp.ui

import android.content.res.Configuration
import android.content.Intent
import android.opengl.GLSurfaceView
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonnyapp.CameraViewModel
import com.sonnyapp.liveview.LiveviewScaleMode
import com.sonnyapp.liveview.SharpenLevel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.ErrorRed
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink3
import com.sonnyapp.theme.OkGreen
import com.sonnyapp.theme.ScrimBottom
import com.sonnyapp.theme.ScrimTop
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import com.sonnyapp.theme.WarnAmber
import kotlinx.coroutines.delay

/** 控件压在取景画面上时的文字阴影 —— 替代不透明的底衬。 */
private val HUD_SHADOW = Shadow(color = Color(0xE6000000), blurRadius = 8f)

/** 曝光滑杆容器的固定尺寸（用于把它精确对准画面右缘中点）。 */
private val SLIDER_BOX_W = 58.dp
private val SLIDER_BOX_H = 240.dp

@Composable
fun CameraScreen(vm: CameraViewModel = viewModel(), onBack: () -> Unit = {}) {
    val connected by vm.connected.collectAsState()
    if (connected) LiveviewScreen(vm) else ConnectScreen(vm, onBack)
}

// =====================================================================
//  未连接：连接页
// =====================================================================

@Composable
private fun ConnectScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val log by vm.log.collectAsState()
    val dlna by vm.dlnaReport.collectAsState()
    val ctx = LocalContext.current
    val wifi by vm.wifiState.collectAsState()
    var ssid by remember { mutableStateOf(vm.savedSsid) }
    var pass by remember { mutableStateOf(vm.savedPass) }
    var advanced by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Ink0).safeDrawingPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("← 返回", color = TextMid, fontSize = 13.sp,
            modifier = Modifier.clickable {
                // 返回菜单前先断开（相机一次只能跑一种模式）
                vm.disconnect(); onBack()
            })
        Spacer(Modifier.height(20.dp))
        Text("遥控监看", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = TextHi)
        Spacer(Modifier.height(6.dp))
        Text("索尼相机 Wi-Fi 遥控 · 轻量取景", fontSize = 13.sp, color = TextLo)

        Spacer(Modifier.height(20.dp))

        // 进入页面时自动判断：当前 Wi-Fi 为相机热点则直接连接。
        LaunchedEffect(Unit) { vm.autoConnect() }

        WifiConnectSection(
            wifi = wifi,
            busy = busy,
            onOpenSettings = {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (e: Exception) {
                    // 个别系统没有这个设置页
                }
            },
            onConnect = { vm.connectViaSystemWifi() },
            onRetry = { vm.autoConnect() },
        )

        Spacer(Modifier.height(20.dp))

        // ——— 备用：手动输入 SSID / 密码 ———
        Text(
            if (advanced) "收起手动连接" else "手动输入 SSID / 密码（备用）",
            color = Accent, fontSize = 11.sp,
            modifier = Modifier.clickable { advanced = !advanced },
        )
        if (advanced) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("相机 SSID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("密码（相机屏幕上显示的）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.connect(ssid.trim(), pass.trim()) },
                enabled = !busy && ssid.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("用 SSID / 密码连接", fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.runDlnaProbe(ssid.trim(), pass.trim()) },
                enabled = !busy && ssid.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) {
                Text("相册可行性探测", fontSize = 12.sp)
            }
        }

        if (status.startsWith("连接失败") || status.contains("找不到")) {
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Ink1).padding(14.dp),
            ) {
                Text("连接失败", color = ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(status.removePrefix("连接失败："), color = TextMid, fontSize = 12.sp)
            }
        }

        if (dlna.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                    .background(Ink1).padding(12.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(dlna, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = TextMid)
            }
        }

        if (log.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(12.dp))
                    .background(Ink1).padding(12.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(log, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextMid)
            }
        }
    }
}

// =====================================================================
//  已连接：全屏取景
// =====================================================================

@Composable
private fun LiveviewScreen(vm: CameraViewModel) {
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val stats by vm.stats.collectAsState()
    val log by vm.log.collectAsState()
    val photo by vm.photo.collectAsState()
    val photoInfo by vm.photoInfo.collectAsState()
    val camInfo by vm.camInfo.collectAsState()
    val scaleMode by vm.scaleMode.collectAsState()
    val sharpen by vm.sharpen.collectAsState()
    val probeReport by vm.probeReport.collectAsState()
    val probeBusy by vm.probeBusy.collectAsState()

    // 极简为默认：常驻只有 取景画面 + 快门 + 相机参数。
    // 「取景中」状态、fps 统计、断开、实验、日志 都收进 showExtras，点一下画面才出现。
    // 竖屏时取景画面是中间一条，左右缘放按钮必然压在画面上 —— 挪到底部黑区
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var showExtras by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var logOpen by remember { mutableStateOf(false) }
    var photoOpen by remember { mutableStateOf(false) }
    var probeOpen by remember { mutableStateOf(false) }

    // 控件常驻：快门与 f/ISO 信息需始终可见。
    // 想看纯净画面时点一下画面手动隐藏，再点恢复。

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val viewW = maxWidth
        val viewH = maxHeight

        // ---------- 取景画面：铺满整个窗口，直绘，不经过 Compose ----------
        AndroidView(
            factory = { ctx -> GLSurfaceView(ctx).also { sv -> vm.renderer.attach(sv) } },
            modifier = Modifier.fillMaxSize(),
        )

        // ---------- 点画面切换控件显隐（将来这里是触摸对焦） ----------
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                showExtras = !showExtras
                tick++
            }
        )

        // ---------- 顶部：状态 + HUD —— 【常驻，不参与淡出】 ----------
        Box(Modifier.align(Alignment.TopStart)) {
            // 不使用渐变遮罩：会在画面上下形成暗带。
            // 可读性交给文字阴影解决，不占画面。
            Column(
                Modifier.fillMaxWidth()
                    .safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                if (showExtras) {
                    Text(
                        status,
                        color = TextHi,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = HUD_SHADOW),
                    )
                    Spacer(Modifier.height(2.dp))
                    // 竖屏窄，一行放不下"跳过" —— 拆两行；横屏一行足够
                    if (isPortrait) {
                        Text(
                            String.format("%.1f", stats.fps) + " fps   " +
                                stats.width + "x" + stats.height + "   " +
                                "帧 " + (stats.jpegBytes / 1024) + " KB",
                            color = TextMid,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            style = TextStyle(shadow = HUD_SHADOW),
                        )
                        Text(
                            "解码 " + stats.decodeMs + "ms   " +
                                "绘制 " + stats.drawMs + "ms   " +
                                "跳过 " + stats.skipped,
                            color = TextMid,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            style = TextStyle(shadow = HUD_SHADOW),
                        )
                    } else {
                        Text(
                            String.format("%.1f", stats.fps) + " fps   " +
                                stats.width + "x" + stats.height + "   " +
                                "帧 " + (stats.jpegBytes / 1024) + " KB   " +
                                "解码 " + stats.decodeMs + "ms   " +
                                "绘制 " + stats.drawMs + "ms   " +
                                "跳过 " + stats.skipped,
                            color = TextMid,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(shadow = HUD_SHADOW),
                        )
                    }
                }
                if (camInfo.hasInfo) {
                    // 允许两行：宁可占一点高度，也不要丢掉信息（"Man…" 就是这么来的）
                    Text(
                        camInfo.summary(),
                        color = TextHi,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = HUD_SHADOW),
                    )
                } else if (!showExtras) {
                    // 相机参数还没读到时不至于顶部全空
                    Text(
                        status,
                        color = TextMid,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showExtras && camInfo.evWriteFailed) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "曝光补偿写入被相机拒绝（若持续失败可更换曝光档位后重试）",
                        color = WarnAmber,
                        fontSize = 9.sp,
                    )
                }
                if (showExtras && scaleMode == LiveviewScaleMode.CROP) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "铺满：画面上下各裁掉约 1/6（成片会包含更多内容）· 点右上角切回适应",
                        color = WarnAmber,
                        fontSize = 9.sp,
                    )
                }
            }
        }

        // ---------- 左缘：显示模式 / 锐化 / 定时 ----------
        // 刻意放左缘而不是右上：右侧要让给曝光补偿滑杆，
        // 两者同时靠右在横屏下会重叠 56dp。
        if (!isPortrait) {
            // start 从 12dp 收到 6dp，配合下面的小字号 —— 
            // 横屏「适应」时左侧黑区约 128dp，胶囊右缘会贴近画面边缘
            Box(Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(start = 6.dp)) {
                ControlChips(
                    scaleMode = scaleMode,
                    sharpen = sharpen,
                    canSelfTimer = camInfo.canSelfTimer,
                    selfTimer = camInfo.selfTimer,
                    vertical = true,
                    onScale = { vm.cycleScaleMode(); tick++ },
                    onSelfTimer = { vm.cycleSelfTimer(); tick++ },
                    onSharpen = { vm.cycleSharpen(); tick++ },
                )
            }
        }

        // ---------- 曝光补偿竖向滑杆 ----------
        // 位置规则：**右缘贴住取景画面的右缘，垂直居中于画面**。
        //
        // 不能用 Alignment.CenterEnd —— 那是贴屏幕右缘，而「适应」模式下
        // 画面左右存在黑边，滑杆需对齐画面边缘而非容器边缘。
        // 所以这里按渲染器算出的画面矩形（归一化 0..1）来定位。
        if (camInfo.canSetEv) {
            val rightX = viewW * stats.imgRight.coerceIn(0.30f, 1f)
            val centerY = viewH * ((stats.imgTop + stats.imgBottom) / 2f)
                .coerceIn(0.15f, 0.85f)
            // 高度按可用高度收缩：折叠屏/小屏横屏时别让滑杆顶到上下边缘
            val sliderH = minOf(SLIDER_BOX_H, viewH * 0.55f)

            // 横向位置：**优先放进画面右侧的黑区**，黑区不够宽才贴画面右缘叠加。
            //
            // 「适应」横屏时画面右侧有约 385px 黑区（2400 宽、画面 1630），
            // 放那里不挡画面；竖屏时画面占满宽度，黑区为 0，就只能贴右缘。
            val spaceRight = viewW - rightX
            val sliderX = if (spaceRight >= SLIDER_BOX_W) {
                rightX + (spaceRight - SLIDER_BOX_W) / 2      // 居中于黑区
            } else {
                (rightX - SLIDER_BOX_W).coerceAtLeast(0.dp)   // 贴画面右缘
            }
            Box(
                Modifier.offset(
                    x = sliderX.coerceIn(0.dp, (viewW - SLIDER_BOX_W).coerceAtLeast(0.dp)),
                    y = (centerY - sliderH / 2)
                        .coerceIn(0.dp, (viewH - sliderH).coerceAtLeast(0.dp)),
                ).width(SLIDER_BOX_W).height(sliderH),
                contentAlignment = Alignment.Center,
            ) {
                // 不加底衬：半透明深色板会遮挡取景画面。
                // 可读性改用文字阴影解决，不占面积。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        (if (camInfo.ev > 0) "+" else "") + camInfo.ev,
                        color = if (camInfo.ev == 0) TextMid else TextHi,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        style = TextStyle(shadow = HUD_SHADOW),
                    )
                    Spacer(Modifier.height(6.dp))
                    VerticalEvSlider(
                        value = camInfo.ev,
                        min = camInfo.evMin,
                        max = camInfo.evMax,
                        step = camInfo.evStep,
                    ) { vm.setEv(it) }
                    Spacer(Modifier.height(6.dp))
                    Text("EV", color = TextLo, fontSize = 9.sp, style = TextStyle(shadow = HUD_SHADOW))
                }
            }
        }

        // ---------- 底部：快门行 —— 【常驻，不参与淡出】 ----------
        Box(Modifier.align(Alignment.BottomCenter)) {
            // 这里**不能**用 safeDrawingPadding()：横屏时左右安全区不对称
            // （左 inset 40dp、右 0），会将整行内容整体右移 20dp，
            // 导致"居中"的快门实际偏移 55px。
            // 改成：这里只管上下；左右的安全区交给两侧按钮自己处理。
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
            ) {
                if (showExtras && photoInfo.isNotEmpty()) {
                    Text(
                        photoInfo,
                        color = OkGreen,
                        fontSize = 11.sp,
                        style = TextStyle(shadow = HUD_SHADOW),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // 竖屏：胶囊放在这里 —— 位于取景画面下方的黑区，不遮画面
                if (isPortrait) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ControlChips(
                            scaleMode = scaleMode,
                            sharpen = sharpen,
                            canSelfTimer = camInfo.canSelfTimer,
                            selfTimer = camInfo.selfTimer,
                            vertical = false,
                            onScale = { vm.cycleScaleMode(); tick++ },
                            onSelfTimer = { vm.cycleSelfTimer(); tick++ },
                            onSharpen = { vm.cycleSharpen(); tick++ },
                        )
                    }
                }
                // 用 Box + 三个独立锚点，而不是 Row+SpaceBetween：
                // SpaceBetween 下中间项只有在左右等宽时才居中，右侧两个按钮
                // （52+8+52=112dp）比左槽 108dp 宽，快门因此偏移。
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                ) {
                    // 左：ContentStart（自己吃左右安全区）
                    Box(
                        Modifier.align(Alignment.CenterStart)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(start = 20.dp)
                    ) {
                        val p = photo
                        if (p != null) {
                            Image(
                                bitmap = p.asImageBitmap(),
                                contentDescription = "最近拍摄",
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, Ink3, RoundedCornerShape(10.dp))
                                    .clickable { photoOpen = true; tick++ },
                                contentScale = ContentScale.Crop,
                            )
                        } else if (showExtras) {
                            IconButtonBox("断开") { vm.disconnect(); tick++ }
                        }
                    }

                    // 中：快门 —— 绝对居中，与左右内容宽度无关
                    Box(Modifier.align(Alignment.Center)) {
                        ShutterButton(enabled = !busy) { vm.takePicture(); tick++ }
                    }

                    // 右：ContentEnd（自己吃左右安全区）
                    Box(
                        Modifier.align(Alignment.CenterEnd)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(end = 20.dp)
                    ) {
                        if (showExtras) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButtonBox("实验") { probeOpen = true; tick++ }
                                IconButtonBox("日志") { logOpen = !logOpen; tick++ }
                            }
                        }
                    }
                }
            }
        }

        // ---------- 日志面板 ----------
        if (logOpen) {
            Box(
                Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { logOpen = false }
            ) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(Ink1)
                        .safeDrawingPadding()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("连接日志", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (log.isEmpty()) "（暂无）" else log,
                        color = TextMid,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // ---------- 实验面板 ----------
        if (probeOpen) {
            Box(
                Modifier.fillMaxSize().background(Color(0xE6000000)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { probeOpen = false }
            ) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(Ink1)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { }
                        .safeDrawingPadding()
                        .padding(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("实验功能", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("关闭", color = TextMid, fontSize = 12.sp,
                            modifier = Modifier.clickable { probeOpen = false })
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton(if (probeBusy) "运行中…" else "运行探测", !probeBusy) { vm.runProbe() }
                        SmallButton("影片模式", !probeBusy) { vm.switchShootMode("movie") }
                        SmallButton("拍照模式", !probeBusy) { vm.switchShootMode("still") }
                        SmallButton("渲染诊断", true) { vm.dumpRenderDiag() }
                    }

                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier.fillMaxWidth().weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            probeReport.ifEmpty {
                                "点「运行探测」：会依次尝试\n" +
                                    "  · postview 全尺寸（能否回传原图）\n" +
                                    "  · 触摸对焦（P4 前置验证）\n" +
                                    "  · 影片模式（取景规格是否不同）"
                            },
                            color = if (probeReport.isEmpty()) TextMid else TextHi,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        // ---------- 全屏看图 ----------
        val pp = photo
        if (photoOpen && pp != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { photoOpen = false }
            ) {
                Image(
                    bitmap = pp.asImageBitmap(),
                    contentDescription = "拍摄结果",
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit,
                )
                Text(
                    photoInfo,
                    color = TextMid,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .safeDrawingPadding().padding(bottom = 16.dp),
                )
            }
        }
    }
}

// =====================================================================
//  小组件
// =====================================================================

/**
 * 三个显示类开关。横屏竖屏共用，只是排布方向不同。
 *
 * 文案说明：
 *  - 「预览锐化」明确是**手机取景预览**的锐化，**不影响相机存到卡上的照片**
 *    （相机给的是 640x424 的低码率 JPEG，放大 3.75 倍会糊，锐化只是为了看着清楚些）
 *  - 「适应 / 铺满」是画面怎么填屏幕，同样只影响预览
 *  - 「定时」才是真正写到相机上的参数
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlChips(
    scaleMode: LiveviewScaleMode,
    sharpen: SharpenLevel,
    canSelfTimer: Boolean,
    selfTimer: Int,
    vertical: Boolean,
    onScale: () -> Unit,
    onSelfTimer: () -> Unit,
    onSharpen: () -> Unit,
) {
    val scaleLabel = if (scaleMode == LiveviewScaleMode.FIT) "适应" else "铺满"
    val sharpenLabel = when (sharpen) {
        SharpenLevel.OFF -> "预览锐化 关"
        SharpenLevel.WEAK -> "预览锐化 弱"
        SharpenLevel.STRONG -> "预览锐化 强"
    }
    val timerLabel = "定时 " + selfTimer + "s"

    if (vertical) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(text = scaleLabel, onClick = onScale)
            if (canSelfTimer) Chip(text = timerLabel, active = selfTimer > 0, onClick = onSelfTimer)
            Chip(text = sharpenLabel, onClick = onSharpen)
        }
    } else {
        // FlowRow：窄屏（360dp 以下）放不下三个胶囊时自动换到第二行，
        // 而不是溢出到屏幕外。Row 做不到这件事。
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Chip(text = scaleLabel, onClick = onScale)
            if (canSelfTimer) {
                Chip(text = timerLabel, active = selfTimer > 0, onClick = onSelfTimer)
            }
            Chip(text = sharpenLabel, onClick = onSharpen)
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (active) Accent else Color(0xCC1E1E22))
            .border(1.dp, if (active) Accent else Ink3, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (active) Color.White else TextMid, fontSize = 10.sp)
    }
}

/**
 * 竖向曝光补偿滑杆（专业相机范式，放屏幕右缘）。
 *
 * 用手势而不是 Material Slider：拖动要跟手，而且必须支持"按住直接拖到某处"。
 */
@Composable
private fun VerticalEvSlider(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
) {
    val range = (max - min).coerceAtLeast(1)
    val frac = ((value - min).toFloat() / range).coerceIn(0f, 1f)
    val stepSafe = if (step <= 0) 1 else step

    BoxWithConstraints(Modifier.width(52.dp).fillMaxHeight(0.72f)) {
        val h = maxHeight
        val thumbY = h * (1f - frac)
        val centerY = h / 2f
        val barTop = minOf(thumbY, centerY)
        val barH = (maxOf(thumbY, centerY) - barTop)

        // 轨道
        Box(
            Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight()
                .shadow(2.dp, RoundedCornerShape(2.dp))
                .clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF))
        )
        // 从 0 EV 到当前值的填充
        if (barH > 0.5.dp) {
            Box(
                Modifier.align(Alignment.TopCenter).offset(y = barTop)
                    .width(3.dp).height(barH)
                    .clip(RoundedCornerShape(2.dp)).background(Accent)
            )
        }
        // 滑块
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = thumbY - 9.dp)
                .size(18.dp)
                .shadow(3.dp, CircleShape)      // 亮背景（比如白墙、天空）上也能看见
                .background(Color.White, CircleShape)
        )
        // 手势层：覆盖整个 52dp 宽，否则这么细的轨道根本抓不住
        Box(
            Modifier.matchParentSize().pointerInput(min, max, stepSafe) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(y: Float) {
                        val f = 1f - (y / size.height.toFloat()).coerceIn(0f, 1f)
                        val raw = min + f * range
                        val snapped = (Math.round(raw / stepSafe) * stepSafe).coerceIn(min, max)
                        onChange(snapped)
                    }
                    apply(down.position.y)
                    down.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        apply(c.position.y)
                        c.consume()
                    }
                }
            }
        )
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (enabled) TextHi else TextLo, fontSize = 11.sp)
    }
}

@Composable
private fun IconButtonBox(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TextHi, fontSize = 12.sp)
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "shutterRing")
    val innerScale by animateFloatAsState(if (pressed) 0.84f else 1f, label = "shutterInner")
    val haptics = LocalHapticFeedback.current

    // 相机快门的经典形态：白色细外环 + 白色实心内圆。
    // "红点套白环"是录像键的视觉语言，容易误认。
    Box(
        Modifier.size(78.dp).scale(scale).clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(78.dp).border(2.dp, Color(0xB3FFFFFF), CircleShape))
        Box(
            Modifier.size(60.dp).scale(innerScale)
                .background(if (enabled) Color(0xF2FFFFFF) else Color(0x59FFFFFF), CircleShape)
        )
    }
}
