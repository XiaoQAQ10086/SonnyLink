package com.sonnyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonnyapp.sync.BackupConfig
import com.sonnyapp.sync.BackupService
import com.sonnyapp.theme.Accent
import com.sonnyapp.theme.Ink0
import com.sonnyapp.theme.Ink1
import com.sonnyapp.theme.Ink3
import com.sonnyapp.theme.OkGreen
import com.sonnyapp.theme.TextHi
import com.sonnyapp.theme.TextLo
import com.sonnyapp.theme.TextMid
import com.sonnyapp.theme.WarnAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份页：WebDAV 配置 + 待归档队列。
 *
 * 这一页的存在理由是**相机热点没有外网**：
 * 下载完的照片不可能当场推上 NAS，只能排队等回到有网的环境。
 * 所以界面上必须让用户看得见「队列里还有几张、在等什么」，
 * 否则会以为备份失败了。
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by BackupService.items.collectAsState()
    val status by BackupService.status.collectAsState()
    val running by BackupService.running.collectAsState()

    val initial = remember { BackupService.config() }
    var url by remember { mutableStateOf(initial.url) }
    var user by remember { mutableStateOf(initial.user) }
    var pass by remember { mutableStateOf(initial.pass) }
    var dir by remember { mutableStateOf(initial.dir) }
    var auto by remember { mutableStateOf(initial.auto) }
    var probeMsg by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        BackupService.init(ctx)
        BackupService.maybeAutoUpload()
    }

    fun persist() {
        BackupService.saveConfig(BackupConfig(url = url, user = user, pass = pass, dir = dir, auto = auto))
    }

    Column(
        Modifier.fillMaxSize().background(Ink0).safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Text(
            "← 返回",
            color = TextMid,
            fontSize = 13.sp,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onBack() },
        )
        Spacer(Modifier.height(16.dp))
        Text("自动归档到 NAS", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TextHi)
        Spacer(Modifier.height(6.dp))
        Text(
            "相机热点没有外网，照片会先落到本机相册并排队；\n回到有网络的 Wi-Fi 后自动上传到 NAS（WebDAV）。",
            fontSize = 12.sp,
            color = TextLo,
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; persist() },
            label = { Text("WebDAV 地址") },
            placeholder = { Text("http://NAS-IP:5006/sonnylink") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it; persist() },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it; persist() },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = dir,
            onValueChange = { dir = it; persist() },
            label = { Text("远程目录") },
            placeholder = { Text("SonnyLink") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink1).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("下载后自动归档", color = TextHi, fontSize = 13.sp)
                Text("关闭时只入队，需手动点上传", color = TextLo, fontSize = 11.sp)
            }
            Switch(checked = auto, onCheckedChange = { auto = it; persist() })
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupAction("测试连接", enabled = !probing) {
                probing = true
                probeMsg = "连接中…"
                val cfg = BackupConfig(url = url, user = user, pass = pass, dir = dir, auto = auto)
                persist()
                // 网络请求必须离开主线程；结果回到主线程再写状态
                scope.launch {
                    val err = withContext(Dispatchers.IO) { BackupService.probe(cfg) }
                    probeMsg = err ?: "连接成功，目录可写"
                    probing = false
                }
            }
            BackupAction("立即上传", enabled = !running && items.isNotEmpty()) {
                BackupService.uploadNow()
            }
            BackupAction("清空队列", enabled = items.isNotEmpty() && !running) {
                BackupService.clearQueue(ctx)
            }
        }

        if (probeMsg.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                probeMsg,
                color = if (probeMsg.contains("成功")) OkGreen else WarnAmber,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "待归档 " + items.size + " 张" + (if (running) " · 上传中…" else ""),
            color = TextMid,
            fontSize = 12.sp,
        )
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(status, color = TextLo, fontSize = 11.sp)
        }

        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            Text("队列为空", color = TextLo, fontSize = 12.sp)
        } else {
            for (it in items) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        it.name,
                        color = TextMid,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        if (it.tries > 0) "重试 " + it.tries else "待传",
                        color = if (it.tries > 0) WarnAmber else TextLo,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "移除",
                        color = TextLo,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { BackupService.removeItem(ctx, it.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun BackupAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Accent else Ink1)
            .border(1.dp, if (enabled) Accent else Ink3, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (enabled) Color.White else TextLo, fontSize = 11.sp)
    }
}
