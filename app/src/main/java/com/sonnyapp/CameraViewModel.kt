package com.sonnyapp

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonnyapp.camera.CameraRepository
import com.sonnyapp.diag.Diagnostics
import com.sonnyapp.liveview.LiveviewEngine
import com.sonnyapp.liveview.LiveviewRenderer
import com.sonnyapp.liveview.LiveviewScaleMode
import com.sonnyapp.liveview.SharpenLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CameraStats(
    val fps: Float = 0f,
    val jpegBytes: Int = 0,
    val decodeMs: Long = 0,
    val drawMs: Long = 0,
    val skipped: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** 取景画面在 View 里的矩形（归一化 0..1），UI 按它来贴控件 */
    val imgLeft: Float = 0f,
    val imgTop: Float = 0f,
    val imgRight: Float = 1f,
    val imgBottom: Float = 1f,
)

/**
 * 从 getEvent 读到的相机状态。**全部只读**（这台相机只有曝光补偿和定时自拍可写）。
 *
 * canXxx 来自 getAvailableApiList —— 相机说不能调的，UI 就不出现。
 */
data class CameraInfo(
    val ev: Int = 0,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Int = 1,
    val selfTimer: Int = 0,
    val selfTimerCandidates: List<Int> = emptyList(),
    val fNumber: String = "",
    val iso: String = "",
    val focusMode: String = "",
    val exposureMode: String = "",
    val shutter: String = "",
    /**
     * 是否显示曝光滑杆。
     *
     * **不能用 getAvailableApiList 判断**。实测该列表不可靠：
     * M 档下它不列出 setExposureCompensation，但相机实际是接受调用的。
     * 所以改成看 getEvent 是否报告了有效的 EV 范围（相机"知道"这个参数）。
     */
    val canSetEv: Boolean = false,
    val canSelfTimer: Boolean = false,
    val canZoom: Boolean = false,
    /** 最近一次曝光写入是否被相机拒绝；仅此时显示提示。 */
    val evWriteFailed: Boolean = false,
) {
    val hasInfo: Boolean
        get() = fNumber.isNotEmpty() || iso.isNotEmpty() || focusMode.isNotEmpty() || shutter.isNotEmpty()

    /** 顶部状态行："f/3.5 · ISO 400 · 1/125 · AF-S · M" */
    fun summary(): String {
        val parts = ArrayList<String>(5)
        if (fNumber.isNotEmpty()) parts.add("f/" + fNumber)
        if (iso.isNotEmpty()) parts.add("ISO " + iso)
        if (shutter.isNotEmpty()) parts.add(shutter)
        if (focusMode.isNotEmpty()) parts.add(focusMode)
        if (exposureMode.isNotEmpty()) parts.add(shortExposureMode(exposureMode))
        return parts.joinToString("  ·  ")
    }

    /**
     * 曝光模式用相机界的标准缩写。
     *
     * 写全称会在窄屏上被截断成 "Man…"，
     * "M" 同样是相机拨盘上的写法，更易识别。
     */
    private fun shortExposureMode(m: String): String = when (m) {
        "Manual" -> "M"
        "Aperture" -> "A"
        "Shutter" -> "S"
        "Program" -> "P"
        "Intelligent Auto" -> "AUTO"
        "Superior Auto" -> "AUTO+"
        "Movie" -> "MOV"
        else -> m
    }
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CameraRepository(app)
    private val prefs = app.getSharedPreferences("sonnyapp", 0)
    private var engine: LiveviewEngine? = null
    private var statsJob: Job? = null
    private var statusJob: Job? = null
    private var evJob: Job? = null

    /** 最近一次参数写入的结果 —— 写进诊断文件，因为 logcat 被 MIUI 屏蔽。 */
    @Volatile
    private var lastWriteResult: String = "(尚无)"

    /** 写入后的保护窗口：这段时间内轮询不得覆盖刚写入的值，
     *  否则相机会用旧值把 UI 无声地回滚。 */
    @Volatile
    private var evHoldUntil: Long = 0L
    @Volatile
    private var selfTimerHoldUntil: Long = 0L

    /** 取景渲染器：UI 把 SurfaceView 交给它，取景线程直接往上画 */
    val renderer = LiveviewRenderer()

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _stats = MutableStateFlow(CameraStats())
    val stats: StateFlow<CameraStats> = _stats.asStateFlow()
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log.asStateFlow()
    private val _photo = MutableStateFlow<Bitmap?>(null)
    val photo: StateFlow<Bitmap?> = _photo.asStateFlow()
    private val _photoInfo = MutableStateFlow("")
    val photoInfo: StateFlow<String> = _photoInfo.asStateFlow()
    private val _scaleMode = MutableStateFlow(readScaleMode())
    val scaleMode: StateFlow<LiveviewScaleMode> = _scaleMode.asStateFlow()
    private val _sharpen = MutableStateFlow(readSharpen())
    val sharpen: StateFlow<SharpenLevel> = _sharpen.asStateFlow()
    private val _camInfo = MutableStateFlow(CameraInfo())
    val camInfo: StateFlow<CameraInfo> = _camInfo.asStateFlow()

    val savedSsid: String get() = prefs.getString(KEY_SSID, "") ?: ""
    val savedPass: String get() = prefs.getString(KEY_PASS, "") ?: ""

    init {
        renderer.scaleMode = _scaleMode.value
        renderer.sharpen = _sharpen.value
    }

    private fun readSharpen(): SharpenLevel {
        val n = prefs.getString(KEY_SHARPEN, null) ?: return SharpenLevel.WEAK
        return try {
            SharpenLevel.valueOf(n)
        } catch (e: Exception) {
            SharpenLevel.WEAK
        }
    }

    fun cycleSharpen() {
        val next = when (_sharpen.value) {
            SharpenLevel.OFF -> SharpenLevel.WEAK
            SharpenLevel.WEAK -> SharpenLevel.STRONG
            SharpenLevel.STRONG -> SharpenLevel.OFF
        }
        _sharpen.value = next
        renderer.sharpen = next
        prefs.edit().putString(KEY_SHARPEN, next.name).apply()
    }

    // ------------------------------------------------------------ 显示模式

    private fun readScaleMode(): LiveviewScaleMode {
        val n = prefs.getString(KEY_SCALE, null) ?: return LiveviewScaleMode.FIT
        return try {
            // 旧版本可能存了已删除的 STRETCH —— valueOf 会抛异常，回落到 FIT
            LiveviewScaleMode.valueOf(n)
        } catch (e: Exception) {
            LiveviewScaleMode.FIT
        }
    }

    fun setScaleMode(m: LiveviewScaleMode) {
        _scaleMode.value = m
        renderer.scaleMode = m
        prefs.edit().putString(KEY_SCALE, m.name).apply()
    }

    /** 只两种模式，来回切。 */
    fun cycleScaleMode() {
        setScaleMode(
            if (_scaleMode.value == LiveviewScaleMode.FIT) LiveviewScaleMode.CROP
            else LiveviewScaleMode.FIT
        )
    }

    // ---------------------------------------------------------------- 连接

    /** 手动路径：在 App 里输入 SSID/密码（会弹系统对话框确认）。 */
    fun connect(ssid: String, pass: String) {
        doConnect("正在连接 " + ssid + " …") {
            prefs.edit().putString(KEY_SSID, ssid).putString(KEY_PASS, pass).apply()
            repo.connect(ssid, pass.ifEmpty { null })
        }
    }

    /**
     * 默认路径：相机热点已在**系统设置**中连接，App 直接接管该 Wi-Fi。
     *
     * 比在 App 里输密码合理得多 —— 不需要 SSID/密码，也不弹系统对话框。
     */
    fun connectViaSystemWifi() {
        doConnect("正在检查已连接的 Wi-Fi …") {
            repo.connectViaSystemWifi()
        }
    }

    private val _wifi = MutableStateFlow(com.sonnyapp.camera.WifiState())
    val wifiState: StateFlow<com.sonnyapp.camera.WifiState> = _wifi.asStateFlow()

    /**
     * 进入连接页时自动判断：当前 Wi-Fi 为相机热点则直接连接，无需手动操作。
     * 否则仅刷新状态，由界面引导前往系统设置。
     */
    fun autoConnect() {
        if (_connected.value || _busy.value) return
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { repo.wifiState() }
            _wifi.value = st
            if (st.looksLikeCamera) connectViaSystemWifi()
        }
    }

    /** 只刷新 Wi-Fi 状态，不连接。 */
    fun refreshWifiState() {
        viewModelScope.launch {
            _wifi.value = withContext(Dispatchers.IO) { repo.wifiState() }
        }
    }

    private fun doConnect(initialStatus: String, action: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _photo.value = null
            _photoInfo.value = ""
            _status.value = initialStatus
            try {
                action()
                _log.value = repo.logText()
                _connected.value = true

                _status.value = "启动取景…"
                val url = repo.startLiveview()
                _log.value = repo.logText()

                val net = repo.network ?: throw IllegalStateException("相机网络已断开")
                val e = LiveviewEngine(net, renderer)
                engine = e
                e.start(viewModelScope, url)
                startStatsTicker(e)
                startStatusTicker()
                // 取景流只有 ~17fps，屏幕跑高刷没意义 —— 降到 60Hz 省电
                RefreshRate.setLowPower(true)
                _status.value = "取景中"
            } catch (ex: Exception) {
                _log.value = repo.logText()
                _status.value = "连接失败：" + (ex.message ?: ex.toString())
                _connected.value = false
            } finally {
                _busy.value = false
            }
        }
    }

    /** 统计每 500 ms 采一次 —— 避免以 17 Hz 触发 Compose 重组。 */
    private fun startStatsTicker(e: LiveviewEngine) {
        statsJob?.cancel()
        var tick = 0
        statsJob = viewModelScope.launch {
            while (isActive) {
                tick++
                // 每 10 秒采一次「取景运行中的可用方法集」（只读）。
                // 周期任务绝不写入相机状态：写操作会覆盖用户刚设的值。
                if (tick % 20 == 1) {
                    liveApiList = repo.availableMethods()
                }
                _stats.value = CameraStats(
                    fps = e.fps,
                    jpegBytes = e.jpegBytes,
                    decodeMs = e.decodeMs,
                    drawMs = e.drawMs,
                    skipped = e.skipped,
                    width = e.width,
                    height = e.height,
                    imgLeft = renderer.imageLeft,
                    imgTop = renderer.imageTop,
                    imgRight = renderer.imageRight,
                    imgBottom = renderer.imageBottom,
                )
                val err = e.lastError
                if (err != null) _status.value = "取景中断：" + err
                delay(500)
            }
        }
    }

    // ------------------------------------------------- 相机状态轮询（只读）

    /**
     * 每 2 秒拉一次 getEvent。
     *
     * 为什么 2 秒而不是更快：取景期间一次 getEvent 往返约 384 ms，
     * 且与取景流共用同一个 HTTP 端点。2 秒足以反映参数变化，又不会过多占用带宽。
     */
    private fun startStatusTicker() {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            var n = 0
            while (isActive) {
                n++
                // 可用方法集只在状态变化时变，10 秒刷一次就够
                if (n % 5 == 1) {
                    val list = repo.availableMethods()
                    if (list.isNotEmpty()) liveApiList = list
                }
                val e = repo.readCameraEvent()
                if (e != null) {
                    val now = System.currentTimeMillis()
                    val keepEv = now < evHoldUntil
                    val keepTimer = now < selfTimerHoldUntil
                    _camInfo.value = CameraInfo(
                        ev = if (keepEv) _camInfo.value.ev else e.exposureCompensation,
                        evMin = e.exposureCompensationMin,
                        evMax = e.exposureCompensationMax,
                        evStep = if (e.exposureCompensationStep <= 0) 1 else e.exposureCompensationStep,
                        selfTimer = if (keepTimer) _camInfo.value.selfTimer else e.selfTimer,
                        selfTimerCandidates = e.selfTimerCandidates,
                        fNumber = e.fNumber,
                        iso = e.isoSpeedRate,
                        focusMode = e.focusMode,
                        exposureMode = e.exposureMode,
                        shutter = e.shutterSpeed,
                        // 用 EV 范围判断，而不是可用列表（见 CameraInfo.canSetEv 的说明）
                        canSetEv = e.exposureCompensationMax > e.exposureCompensationMin,
                        canSelfTimer = liveApiList.contains("setSelfTimer"),
                        canZoom = liveApiList.contains("actZoom"),
                        evWriteFailed = _camInfo.value.evWriteFailed,
                    )
                }
                delay(2000)
            }
        }
    }

    // ------------------------------------------------------------ 参数写入

    /**
     * 设置曝光补偿。UI 立刻更新（拖动要跟手），网络调用做 150 ms 防抖 ——
     * 否则拖一次滑杆会打出几十个 HTTP 请求。
     */
    fun setEv(v: Int) {
        val info = _camInfo.value
        val clamped = v.coerceIn(info.evMin, info.evMax)
        if (clamped == info.ev) return
        _camInfo.value = info.copy(ev = clamped)
        evHoldUntil = System.currentTimeMillis() + HOLD_MS
        evJob?.cancel()
        evJob = viewModelScope.launch {
            delay(150)
            val ok = repo.setExposureCompensation(clamped)
            lastWriteResult = "setExposureCompensation(" + clamped + ") -> " + ok
            _camInfo.value = _camInfo.value.copy(evWriteFailed = !ok)
            if (ok) {
                // 成功后必须清除错误状态，否则提示会持续显示。
                if (_status.value.startsWith(ERR_EV)) _status.value = "取景中"
            } else {
                evHoldUntil = 0L
                _status.value = ERR_EV + _camInfo.value.exposureMode + "）"
            }
        }
    }

    fun cycleSelfTimer() {
        val info = _camInfo.value
        val cands = info.selfTimerCandidates.ifEmpty { listOf(0, 2) }
        val idx = cands.indexOf(info.selfTimer)
        val next = cands[(idx + 1) % cands.size]
        _camInfo.value = info.copy(selfTimer = next)
        selfTimerHoldUntil = System.currentTimeMillis() + HOLD_MS
        viewModelScope.launch {
            val ok = repo.setSelfTimer(next)
            lastWriteResult = "setSelfTimer(" + next + ") -> " + ok
            if (!ok) {
                selfTimerHoldUntil = 0L
                _status.value = "定时自拍写入失败（相机当前不允许）"
            }
        }
    }

    fun zoom(direction: String, movement: String) {
        viewModelScope.launch { repo.actZoom(direction, movement) }
    }

    fun takePicture() {
        if (_busy.value || !_connected.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "拍照中…"
            try {
                val t0 = System.currentTimeMillis()
                val url = repo.takePicture()
                val ms = System.currentTimeMillis() - t0
                if (url == null) {
                    _status.value = "拍照失败：相机没返回图片地址"
                } else {
                    val bytes = repo.downloadPostview(url)
                    if (bytes == null) {
                        _status.value = "图片下载失败"
                    } else {
                        val bmp = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        _photo.value = bmp
                        val w = bmp?.width ?: 0
                        val h = bmp?.height ?: 0
                        _photoInfo.value = w.toString() + "x" + h + "  " +
                            (bytes.size / 1024) + " KB  " + ms + " ms"
                    }
                }
            } catch (ex: Exception) {
                _status.value = "拍照失败：" + (ex.message ?: "")
            } finally {
                _busy.value = false
                if (_connected.value) _status.value = "取景中"
            }
        }
    }

    /**
     * 取景运行中相机允许调用的方法集，每 10 秒刷新一次。
     * 只在状态变化时更新，供 CameraInfo 判断变焦/定时自拍是否可用。
     */
    @Volatile
    private var liveApiList: List<String> = emptyList()

    // ------------------------------------------------------------ 诊断

    /**
     * 生成诊断报告。
     *
     * 用户的机型、Android 版本、相机固件各不相同，报告必须包含完整环境信息，
     * 否则只凭「连不上」无法定位问题。用户可在日志面板一键复制后发出。
     */
    fun buildDiag(): String {
        val s = _stats.value
        val c = _camInfo.value
        val w = _wifi.value
        val extras = listOf(
            "状态" to _status.value,
            "连接" to if (_connected.value) "已连接" else "未连接",
            "Wi-Fi SSID" to (w.ssid ?: if (w.connected) "(系统未提供)" else "(未连接)"),
            "判为相机热点" to if (w.looksLikeCamera) "是" else "否",
            "取景规格" to if (s.width > 0) (s.width.toString() + "x" + s.height) else "(未出图)",
            "帧率" to (String.format("%.1f", s.fps) + " fps"),
            "帧尺寸" to (s.jpegBytes / 1024).toString() + " KB",
            "解码耗时" to s.decodeMs.toString() + " ms",
            "绘制耗时" to s.drawMs.toString() + " ms",
            "跳帧" to s.skipped.toString(),
            "曝光模式" to c.exposureMode,
            "可写 EV" to (c.canSetEv.toString() + "  (范围 " + c.evMin + " .. " + c.evMax + ")"),
            "可写定时自拍" to c.canSelfTimer.toString(),
            "最近参数写入" to lastWriteResult,
        )
        // 渲染器自检（GL 版本、着色器编译结果、帧统计）也一并附上
        val render = try {
            renderer.diagnostics()
        } catch (e: Exception) {
            "(渲染器未就绪)"
        }
        return Diagnostics.build(
            getApplication<Application>(),
            repo.logText() + "\n--- 渲染自检 ---\n" + render,
            extras,
        )
    }

    fun disconnect() {
        viewModelScope.launch {
            statsJob?.cancel()
            statusJob?.cancel()
            evJob?.cancel()
            engine?.stop()
            engine = null
            RefreshRate.setLowPower(false)
            repo.disconnect()
            _connected.value = false
            _stats.value = CameraStats()
            _camInfo.value = CameraInfo()
            _photo.value = null
            _status.value = "已断开"
        }
    }

    override fun onCleared() {
        statsJob?.cancel()
        statusJob?.cancel()
        evJob?.cancel()
        engine?.stop()
        repo.disconnect()
        super.onCleared()
    }

    companion object {
        private const val KEY_SSID = "ssid"
        private const val KEY_PASS = "pass"
        private const val HOLD_MS = 4000L
        private const val ERR_EV = "曝光补偿写入被相机拒绝（曝光模式 "
        private const val KEY_SCALE = "scaleMode"
        private const val KEY_SHARPEN = "sharpen"
    }
}
