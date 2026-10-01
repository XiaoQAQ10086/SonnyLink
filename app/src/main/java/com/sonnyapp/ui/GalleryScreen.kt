package com.sonnyapp.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.sonnyapp.core.dlna.DlnaItem
import com.sonnyapp.gallery.DlState
import com.sonnyapp.gallery.FilterMode
import com.sonnyapp.gallery.GalleryViewModel
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink2
import com.sonnyapp.theme.Ink3
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import com.sonnyapp.theme.WarnAmber

@Composable
fun GalleryScreen(vm: GalleryViewModel = viewModel(), onBack: () -> Unit) {
    val connected by vm.connected.collectAsState()
    if (!connected) GalleryConnect(vm, onBack) else GalleryBrowse(vm, onBack)
}

// =====================================================================
//  连接页
// =====================================================================

@Composable
private fun GalleryConnect(vm: GalleryViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val wifi by vm.wifiState.collectAsState()
    var ssid by remember { mutableStateOf(vm.savedSsid) }
    var pass by remember { mutableStateOf(vm.savedPass) }
    var advanced by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Ink0).safeDrawingPadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("← 返回", color = TextMid, fontSize = 13.sp,
            modifier = Modifier.clickable { vm.disconnect(); onBack() })
        Spacer(Modifier.height(24.dp))
        Text("传输照片", color = TextHi, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "相机会变成 DLNA 媒体服务器。\n请先在相机上进入「发送到智能手机」，再把新的 SSID/密码填在下面。",
            color = TextLo, fontSize = 11.sp,
        )
        Spacer(Modifier.height(18.dp))

        // 进入页面时自动判断：当前 Wi-Fi 为相机热点则直接连接。
        LaunchedEffect(Unit) { vm.autoConnect() }

        WifiConnectSection(
            wifi = wifi,
            busy = busy,
            onOpenSettings = {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (e: Exception) {
                }
            },
            onConnect = { vm.connectViaSystemWifi() },
            onRetry = { vm.autoConnect() },
        )

        Spacer(Modifier.height(18.dp))
        Text(
            if (advanced) "收起手动连接" else "手动输入 SSID / 密码（备用）",
            color = Accent, fontSize = 11.sp,
            modifier = Modifier.clickable { advanced = !advanced },
        )
        if (advanced) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it },
                label = { Text("相机 SSID") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("密码") }, singleLine = true,
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
        }

        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Ink1).padding(14.dp)
                .heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(status, color = TextMid, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// =====================================================================
//  浏览页
// =====================================================================

@Composable
private fun GalleryBrowse(vm: GalleryViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val groups by vm.groups.collectAsState()
    val allFiles by vm.allFiles.collectAsState()
    val filter by vm.filter.collectAsState()
    val selected by vm.selected.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val saveUri by vm.saveTreeUri.collectAsState()
    val conc by vm.concurrency.collectAsState()

    var showTasks by remember { mutableStateOf(false) }
    LaunchedEffect(tasks.isNotEmpty()) { if (tasks.isNotEmpty()) showTasks = true }

    // SAF 目录选择：Android 10+ 唯一能"选任意目录"的正规做法
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // 有些 provider 不支持持久权限，忽略
            }
            vm.setSaveTreeUri(uri.toString())
        }
    }

    val shownCount = groups.sumOf { it.items.size }
    val selectedBytes = allFiles.filter { selected.contains(it.id) }
        .sumOf { if (it.originalSize > 0) it.originalSize else 0L }

    Box(Modifier.fillMaxSize().background(Ink0)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {

            // 顶栏
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("←", color = TextMid, fontSize = 20.sp,
                    modifier = Modifier.clickable { vm.disconnect(); onBack() }.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "全部照片（按拍摄日期分组）",
                        color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(status, color = TextMid, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Text("返回", color = TextMid, fontSize = 12.sp,
                    modifier = Modifier.clickable { vm.disconnect(); onBack() }.padding(8.dp))
            }

            // 筛选 + 批量选择 + 保存位置
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (filter == FilterMode.ORIGINALS_ONLY) "仅 JPEG 原图" else "全部（含 RAW 预览）",
                    color = if (filter == FilterMode.ORIGINALS_ONLY) Color.White else TextMid,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (filter == FilterMode.ORIGINALS_ONLY) Accent else Ink2)
                        .clickable {
                            vm.setFilter(
                                if (filter == FilterMode.ORIGINALS_ONLY) FilterMode.ALL
                                else FilterMode.ORIGINALS_ONLY
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Text(
                    "全选",
                    color = TextMid, fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50)).background(Ink2)
                        .clickable { vm.selectAll() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Text(
                    if (selected.isEmpty()) "清空选择" else "取消选择",
                    color = TextMid, fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50)).background(Ink2)
                        .clickable { vm.clearSelection() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            // 保存位置
            Text(
                "保存到：" + (saveUri?.let { "自选目录" } ?: "系统相册 Pictures/SonnyApp") + "   （点击更改）",
                color = Accent, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                    .clickable { picker.launch(null) },
            )

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp))
            }

            if (groups.isEmpty() && !busy) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (allFiles.isEmpty()) "没有读到照片"
                        else "当前筛选下没有可显示的照片\n（点上面的按钮切换到「全部」）",
                        color = TextLo, fontSize = 12.sp,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    // 底部要给悬浮的下载栏留出空间，否则最后一行被挡住、点不到
                    contentPadding = PaddingValues(
                        top = 4.dp,
                        bottom = if (groups.isNotEmpty()) 132.dp else 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (g in groups) {
                        // 日期标题：占满整行
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h" + g.date) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    g.label,
                                    color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    g.items.size.toString() + " 张",
                                    color = TextLo, fontSize = 10.sp,
                                )
                            }
                        }
                        items(g.items, key = { "i" + it.id }) { it ->
                            FileCell(
                                item = it,
                                selected = selected.contains(it.id),
                                vm = vm,
                                onClick = { vm.toggle(it.id) },
                            )
                        }
                    }
                }
            }
        }

        // 底部：下载
        if (groups.isNotEmpty()) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Ink1).safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selected.isEmpty()) "显示 " + shownCount + " 张 · 已全选可选项"
                        else "已选 " + selected.size + " 张" +
                            (if (selectedBytes > 0) "  " + GalleryViewModel.humanSize(selectedBytes) else ""),
                        color = TextHi, fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("并发 ", color = TextLo, fontSize = 9.sp)
                        for (n in listOf(1, 3, 6)) {
                            Text(
                                n.toString(),
                                color = if (conc == n) Color.White else TextMid,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (conc == n) Accent else Ink2)
                                    .clickable { vm.setConcurrency(n) }
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Button(
                    onClick = { vm.downloadSelected() },
                    enabled = selected.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("下载 " + selected.size + " 张", fontSize = 13.sp)
                }
            }
        }

        // 下载进度浮层
        if (showTasks && tasks.isNotEmpty()) {
            val doneAll = tasks.all { it.state != DlState.RUNNING }
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000))
                    .clickable { if (doneAll) { showTasks = false; vm.clearTasks() } },
            ) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .background(Ink1)
                        .clickable(enabled = false) { }
                        .safeDrawingPadding()
                        .padding(16.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (doneAll) "下载完成" else "正在下载（3 路并发）…",
                            color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (doneAll) "关闭" else "",
                            color = Accent, fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                if (doneAll) { showTasks = false; vm.clearTasks() }
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (t in tasks) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(
                                    t.title + "   " + when (t.state) {
                                        DlState.RUNNING -> GalleryViewModel.humanSize(t.done) +
                                            (if (t.total > 0) " / " + GalleryViewModel.humanSize(t.total) else "") +
                                            (if (t.rate > 0) "   " + GalleryViewModel.humanSize(t.rate) + "/s" else "")
                                        DlState.DONE -> "已完成  " + t.note
                                        DlState.FAILED -> "失败  " + t.note
                                    },
                                    color = when (t.state) {
                                        DlState.DONE -> Color(0xFF4ADE80)
                                        DlState.FAILED -> Color(0xFFFF6B6B)
                                        else -> TextMid
                                    },
                                    fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (t.state == DlState.RUNNING) {
                                    Spacer(Modifier.height(3.dp))
                                    val f = t.fraction
                                    if (f >= 0f) {
                                        LinearProgressIndicator(
                                            progress = { f },
                                            modifier = Modifier.fillMaxWidth().height(3.dp),
                                        )
                                    } else {
                                        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCell(
    item: DlnaItem,
    selected: Boolean,
    vm: GalleryViewModel,
    onClick: () -> Unit,
) {
    // 首帧就同步从缓存取 —— 缓存里有图就立刻显示，不会闪灰块
    var bmp by remember(item.id) { mutableStateOf(vm.peekThumb(item.id)) }
    LaunchedEffect(item.id) {
        if (bmp != null) return@LaunchedEffect   // 已经有了，什么都不做
        // 防抖：手指快速滑过时 item 很快离开组合，effect 被取消 ——
        // 避免为快速划过的格子发起请求，把带宽留给停留位置。
        delay(90)
        bmp = vm.loadThumb(item)
    }
    // asImageBitmap 每次重组都会新建一个包装对象，用 remember 固定住
    val imageBitmap = remember(bmp) { bmp?.asImageBitmap() }

    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Ink2)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Accent else Ink3,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        val b = imageBitmap
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(Ink3))
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(item.title, color = TextHi, fontSize = 8.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                if (item.canDownloadOriginal) GalleryViewModel.humanSize(item.originalSize) + " 原图"
                else item.extension + " 预览",
                color = if (item.canDownloadOriginal) Color(0xFF9AE6B4) else WarnAmber,
                fontSize = 8.sp,
            )
        }

        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                    .background(Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White, fontSize = 11.sp)
            }
        }
    }
}
