package com.sonnyapp.camera

import android.content.Context
import android.net.Network
import android.util.Log
import com.sonnyapp.core.protocol.DeviceDescription
import com.sonnyapp.core.protocol.DeviceDescriptionParser
import com.sonnyapp.core.protocol.HttpConnectionFactory
import com.sonnyapp.core.protocol.CameraEvent
import com.sonnyapp.core.protocol.SonyApiException
import com.sonnyapp.core.protocol.SonyCameraApi
import com.sonnyapp.core.protocol.SonyJsonRpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection

/**
 * 把 core-protocol 接到真实 Android 网络上。
 *
 * 这是"句柄级绑定"落地的地方：所有 HTTP 请求都通过 network.openConnection(url) 发起，
 * 而不是 bindProcessToNetwork() 改道整个进程。
 *
 * 整个 connect() 必须运行在 Dispatchers.IO 上。
 *    否则任何一个阻塞 socket 调用都会抛 NetworkOnMainThreadException，
 *    而错误信息会被误读成"相机没响应"。
 */
class CameraRepository(private val context: Context) {

    private val net = CameraNetwork(context)

    private var client: SonyJsonRpcClient? = null
    var api: SonyCameraApi? = null
        private set
    var description: DeviceDescription? = null
        private set
    var ddLocation: String? = null
        private set

    val network: Network? get() = net.network

    private val log = StringBuilder()

    private fun note(msg: String) {
        Log.i(TAG, msg)
        synchronized(log) { log.append(msg).append('\n') }
    }

    fun logText(): String = synchronized(log) { log.toString() }

    /**
     * 只连相机热点、**不做 ScalarWebAPI 握手**，然后跑 DLNA 探测。
     *
     * 单独一条路的原因：相机在「发送到智能手机」模式下**不提供遥控 API**
     * （那套只有 guide/accessControl/camera，没有 avContent）。
     * 如果走 connect() 会在版本闸门那步失败，而我们要的是另一个协议。
     */
    /** 当前 Wi-Fi 状态（判断用户连的是不是相机）。 */
    fun wifiState(): WifiState = net.wifiState()

    suspend fun runDlnaProbe(ssid: String, passphrase: String?): String =
        withContext(Dispatchers.IO) {
            synchronized(log) { log.setLength(0) }
            note("[DLNA] 连接热点 " + ssid)
            try {
                val n = net.connect(ssid, passphrase)
                note("      已连接，Network = " + n)
                net.acquireMulticastLock()
                val report = DlnaProbe(n).run()
                note("      探测完成")
                report
            } catch (e: Exception) {
                note("      连接失败: " + (e.message ?: ""))
                "连接相机热点失败：" + (e.message ?: e.toString())
            }
        }

    /**
     * 完整连接流程：连热点 -> SSDP -> DD.xml -> 建 API -> 版本闸门 -> 进遥控模式。
     * 整个流程都在 IO 线程上执行。
     */
    suspend fun connect(ssid: String, passphrase: String?): Unit = withContext(Dispatchers.IO) {
        synchronized(log) { log.setLength(0) }
        note("[1/6] 连接相机热点 " + ssid)
        val network = net.connect(ssid, passphrase)
        note("      已连接，Network = " + network)
        handshake(network)
    }

    /**
     * 用**系统已经连上**的 Wi-Fi（用户在系统设置里连的相机热点）。
     *
     * 不弹系统对话框、也不需要 SSID/密码 —— 这是默认路径。
     * 句柄级绑定照样成立：adoptSystemWifi 拿到的就是系统那个 Wi-Fi 的 Network。
     */
    suspend fun connectViaSystemWifi(): Unit = withContext(Dispatchers.IO) {
        synchronized(log) { log.setLength(0) }
        note("[1/6] 使用系统已连接的 Wi-Fi")
        val network = net.adoptSystemWifi()
            ?: throw IllegalStateException(
                "没有找到已连接的 Wi-Fi。\n请先在系统设置里连上相机热点，再回来点「已连接，开始」。"
            )
        handshake(network)
    }

    /** 网络就绪后的握手：SSDP -> DD.xml -> 版本闸门 -> 进遥控模式。 */
    private suspend fun handshake(network: Network) {
        net.acquireMulticastLock()

        note("[2/6] SSDP 发现相机…")
        var location = SsdpDiscovery.discover(network, net.localAddress(), 6000)
        if (location.isNullOrEmpty()) {
            note("      SSDP 无响应，改用网关端口探测")
            location = probeKnownLocations(network)
        }
        if (location.isNullOrEmpty()) {
            throw IllegalStateException(
                "找不到相机：SSDP 组播与端口探测都没有响应。\n" +
                    "请确认相机仍停留在「嵌入式智能遥控」界面（显示 SSID/密码）。"
            )
        }
        ddLocation = location
        note("      LOCATION = " + location)

        note("[3/6] 下载设备描述 DD.xml")
        val xml = NetHttp.getString(network, location, 8000, 12000)
        if (xml == null) {
            val why = NetHttp.lastError ?: "未知"
            var hint = ""
            val looksLikeConnectProblem = why.contains("Timeout") || why.contains("Connect")
            if (looksLikeConnectProblem) {
                hint = "\n提示：热点还在但相机端遥控服务可能已退出（索尼会在无人连接时自动退出）。\n" +
                    "请在相机上重新进入「嵌入式智能遥控」，保持屏幕显示 SSID/密码，再重试。"
            }
            throw IllegalStateException(
                "下载 DD.xml 失败\n地址：" + location + "\n原因：" + why + hint
            )
        }
        val desc = try {
            DeviceDescriptionParser.parse(xml)
        } catch (e: Exception) {
            throw IllegalStateException("解析 DD.xml 失败：" + e.message)
        }
        description = desc
        note("      机型 = " + desc.friendlyName + "  服务 = " + desc.serviceTypes)

        val endpoint = desc.cameraEndpoint
            ?: throw IllegalStateException("DD.xml 里没有 camera 服务")

        note("[4/6] 建立 JSON-RPC 通道")
        val factory = HttpConnectionFactory { url ->
            network.openConnection(url) as HttpURLConnection
        }
        val c = SonyJsonRpcClient(factory)
        client = c
        val a = SonyCameraApi(c, desc)
        api = a

        note("[5/6] 读取服务端信息（版本闸门 >= 2.0.0）")
        val info = a.getApplicationInfo()
        note("      " + info.first + "  /  版本 " + info.second)
        if (!a.isServerVersionSupported) {
            throw IllegalStateException("相机服务端版本过低（" + info.second + " < 2.0.0），本机不受支持")
        }

        note("[6/6] 进入遥控拍摄模式")
        a.refreshMethodTypes()
        a.refreshCapabilities()
        a.startRecMode()

        val caps = a.capabilities
        note("      可用方法 " + caps.available.size + " 个 / 固件支持 " + caps.supported.size + " 个")
        note("      取景=" + caps.canLiveview + "  拍照=" + caps.canTakePicture +
            "  变焦=" + caps.canZoom + "  曝光补偿=" + caps.canSetExposureCompensation)
        note("      端点 " + endpoint)
    }

    suspend fun startLiveview(): String = withContext(Dispatchers.IO) {
        val a = api ?: throw IllegalStateException("尚未连接")
        val url = a.startLiveview()
        note("取景流 " + url)
        url
    }

    suspend fun awaitLiveviewReady(): Boolean {
        val a = api ?: return false
        return withContext(Dispatchers.IO) { a.awaitLiveviewReady(6000, 500) }
    }

    suspend fun takePicture(): String? {
        val a = api ?: return null
        return withContext(Dispatchers.IO) { a.actTakePicture() }
    }

    /** 下载 postview 图片（走同一个相机 Network）。 */
    suspend fun downloadPostview(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val n = net.network ?: return@withContext null
        val s = NetHttp.open(n, url, "image/jpeg", 8000, 15000) ?: return@withContext null
        try {
            val out = java.io.ByteArrayOutputStream(256 * 1024)
            val buf = ByteArray(32 * 1024)
            while (true) {
                val r = s.input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
            }
            out.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "downloadPostview failed: " + e.message)
            null
        } finally {
            s.close()
        }
    }

    suspend fun stopLiveview() {
        val a = api ?: return
        try {
            withContext(Dispatchers.IO) { a.stopLiveview() }
        } catch (e: Exception) {
            Log.w(TAG, "stopLiveview failed: " + e.message)
        }
    }

    // ------------------------------------------------------------ 探测

    /** 双引号字符 —— 用它拼 JSON 关键词，彻底避开转义地狱。 */
    private val DQ: Char = 34.toChar()

    private fun describeResponse(label: String, response: String): String {
        val marker = "" + DQ + "error" + DQ + ":["
        val i = response.indexOf(marker)
        if (i < 0) return "  " + label + " -> OK   " + response.take(110)
        val j = response.indexOf(']', i)
        val body = response.substring(i + marker.length, if (j > i) j else response.length)
        val parts = body.split(',')
        val code = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
        var msg = parts.getOrNull(1)?.trim() ?: ""
        if (msg.startsWith(DQ)) msg = msg.substring(1)
        if (msg.endsWith(DQ)) msg = msg.dropLast(1)
        val tag = when (code) {
            SonyApiException.CODE_NOT_AVAILABLE_NOW -> "  当前状态不可用"
            SonyApiException.CODE_ILLEGAL_ARGUMENT -> "  参数错误"
            SonyApiException.CODE_METHOD_NOT_FOUND -> "  方法不存在"
            else -> ""
        }
        return "  " + label + " -> ERR " + code + " [" + msg + "]" + tag
    }

    /** 从 getEvent 原始文本里抠出某个 type 的对象。 */
    private fun eventField(ev: String, key: String): String? {
        val marker = "" + DQ + "type" + DQ + ":" + DQ + key + DQ
        val i = ev.indexOf(marker)
        if (i < 0) return null
        val j = ev.indexOf('}', i)
        return if (j > i) ev.substring(i, j + 1) else ev.substring(i)
    }

    /**
     * 一次性探测这台相机还能榨出什么，返回可直接显示的文本。
     * 全程用 callForce 绕过能力门禁 —— 目的是看"到底行不行"，不是走正常功能。
     */
    suspend fun probe(): String = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext "未连接相机"
        val sb = StringBuilder()
        fun line(s: String) { sb.append(s).append('\n') }
        fun attempt(label: String, method: String, params: List<Any?>) {
            try {
                line(describeResponse(label, a.callForce(method, params)))
            } catch (e: Exception) {
                line("  " + label + " -> 异常 " + (e.message ?: e.toString()))
            }
        }

        line("[0] 完整可用方法列表")
        try {
            val list = a.getAvailableApiList().sorted()
            line("  共 " + list.size + " 个:")
            for (m in list) line("    " + m)
        } catch (e: Exception) {
            line("  失败: " + e.message)
        }
        line("[0b] 完整 getEvent 原始 JSON")
        try {
            line(a.getEvent(false))
        } catch (e: Exception) {
            line("  失败: " + e.message)
        }

        line("[1] 能力")
        line("  可用 " + a.capabilities.available.size + " / 固件 " + a.capabilities.supported.size)
        line("  有 StillQuality? " + a.capabilities.supportsEver("setStillQuality") +
            "   有 setTouchAFPosition? " + a.capabilities.supportsEver("setTouchAFPosition") +
            "   有 setFocusArea? " + a.capabilities.supportsEver("setFocusArea"))

        line("[2] 相机事件关键字段")
        try {
            val ev = a.getEvent(false)
            for (k in listOf("shootMode", "postviewImageSize", "focusMode", "touchAFPosition", "exposureMode", "fNumber", "isoSpeedRate", "liveviewStatus")) {
                val v = eventField(ev, k)
                if (v != null) line("  " + v.take(140))
            }
        } catch (e: Exception) {
            line("  getEvent 失败: " + e.message)
        }

        line("[3] postview 尺寸（能否回传全尺寸 JPEG）")
        attempt("setPostviewImageSize [Original]", "setPostviewImageSize", listOf("Original"))
        attempt("getPostviewImageSize", "getPostviewImageSize", emptyList())
        attempt("setPostviewImageSize [2M]", "setPostviewImageSize", listOf("2M"))

        line("[4] 触摸对焦 —— 重点：对焦区域设为「自由点」后会不会开放")
        attempt("getSupportedFocusMode", "getSupportedFocusMode", emptyList())
        attempt("getFocusMode", "getFocusMode", emptyList())
        attempt("getAvailableFocusMode", "getAvailableFocusMode", emptyList())
        attempt("setFocusMode [AF-C]", "setFocusMode", listOf("AF-C"))
        attempt("setFocusMode [AF-S]", "setFocusMode", listOf("AF-S"))
        attempt("setFocusMode [DMF]", "setFocusMode", listOf("DMF"))
        attempt("setTouchAFPosition [320,212]", "setTouchAFPosition", listOf(320.0, 212.0))
        attempt("setTouchAFPosition [50,50]", "setTouchAFPosition", listOf(50.0, 50.0))
        attempt("setTouchAFPosition [5000,5000]", "setTouchAFPosition", listOf(5000.0, 5000.0))
        attempt("getTouchAFPosition", "getTouchAFPosition", emptyList())
        attempt("cancelTouchAFPosition", "cancelTouchAFPosition", emptyList())
        line("  切换后可触焦? 见上方 setTouchAFPosition 的返回")

        line("[5] 拍摄模式（关键：影片模式取景规格是否不同）")
        attempt("setShootMode [movie]", "setShootMode", listOf("movie"))
        try {
            val after = a.getAvailableApiList()
            line("  切换后可用方法 " + after.size + " 个")
            line("  startMovieRec 可用? " + after.contains("startMovieRec") +
                "   actTakePicture 可用? " + after.contains("actTakePicture"))
            a.refreshCapabilities()
        } catch (e: Exception) {
            line("  查询失败: " + e.message)
        }
        line("  （探测结束 —— 取景将自动重启并报告新规格）")

        sb.toString()
    }

    /** 切换拍摄模式（不改回），并刷新能力集。 */
    suspend fun setShootModeRaw(mode: String): String = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext "未连接相机"
        try {
            val r = a.callForce("setShootMode", listOf(mode))
            a.refreshCapabilities()
            val desc = describeResponse("setShootMode [" + mode + "]", r).trim()
            desc + "\n可用方法 " + a.capabilities.available.size + " 个，" +
                (if (mode == "movie") "请断开重连以应用。" else "已切回拍照模式。")
        } catch (e: Exception) {
            "异常: " + (e.message ?: e.toString())
        }
    }

    /** 把响应压成一行：有 error 就只回错误部分。 */
    private fun shortResp(r: String): String {
        val marker = "" + DQ + "error" + DQ + ":["
        val i = r.indexOf(marker)
        if (i >= 0) {
            val j = r.indexOf(']', i)
            return if (j > i) r.substring(i, j + 1) else r.substring(i)
        }
        return "OK " + r.take(70)
    }

    /**
     * **在取景运行中**直接试对焦相关方法。
     *
     * 单独实现的原因：探测若先停止取景会造成画面中断，
     * 结果把"只在取景期间开放"的方法全误判成不可用
     * （setExposureCompensation 就是这么被误判的）。
     */
    suspend fun probeFocusLive(): String = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext "未连接"
        val sb = StringBuilder()
        fun t(label: String, method: String, params: List<Any?>) {
            val r = try {
                shortResp(a.callForce(method, params))
            } catch (e: Exception) {
                "异常 " + (e.message ?: "")
            }
            sb.append("  ").append(label).append(" -> ").append(r).append('\n')
        }
        t("getFocusMode", "getFocusMode", emptyList())
        t("getSupportedFocusMode", "getSupportedFocusMode", emptyList())
        t("setFocusMode[AF-C]", "setFocusMode", listOf("AF-C"))
        t("setFocusMode[AF-S]", "setFocusMode", listOf("AF-S"))
        t("setTouchAFPosition[320,212]", "setTouchAFPosition", listOf(320.0, 212.0))
        t("setTouchAFPosition[50,50]", "setTouchAFPosition", listOf(50.0, 50.0))
        t("setTouchAFPosition[5000,5000]", "setTouchAFPosition", listOf(5000.0, 5000.0))
        t("getTouchAFPosition", "getTouchAFPosition", emptyList())
        t("cancelTouchAFPosition", "cancelTouchAFPosition", emptyList())
        sb.toString()
    }

    // ------------------------------------------------------- 参数读写（能力驱动）

    suspend fun readCameraEvent(): CameraEvent? = withContext(Dispatchers.IO) {
        try {
            api?.readEvent()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun setExposureCompensation(value: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            api?.setExposureCompensation(value) ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setSelfTimer(seconds: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            api?.setSelfTimer(seconds) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 参数写入的**原始**探测：直接看相机到底回什么错。
     *
     * 为什么要这个：normalize 之后的 true/false 分不清是
     * "不在可用列表" / "参数非法" / "相机内部拒绝" —— 这三种修法完全不同。
     */
    suspend fun probeParams(): String = withContext(Dispatchers.IO) {
        val a = api ?: return@withContext "未连接"
        val sb = StringBuilder()
        sb.append("capabilities.can(setExposureCompensation) = ")
            .append(a.capabilities.can("setExposureCompensation")).append('\n')
        sb.append("capabilities.can(setSelfTimer) = ")
            .append(a.capabilities.can("setSelfTimer")).append('\n')
        sb.append("capabilities 可用方法数 = ").append(a.capabilities.available.size).append('\n')
        // 非破坏性：只用当前值试写，不会改变画面或设置。
        // 不要用其它数值试写来"探测可写性"，那样会覆盖已有设置。
        // 结果是周期性覆盖已有设置。
        val evBlock = eventField(a.getEvent(false), "exposureCompensation")
        val curEv = evBlock?.let { b ->
            val i = b.indexOf("\"currentExposureCompensation\":")
            if (i < 0) null else b.substring(i + 28).takeWhile { it.isDigit() || it == '-' }.toIntOrNull()
        } ?: 0
        // 试写一个不同的值，再还原 —— 只有"真的改一下"才能证明可写。
        // 只用当前值试写的话，即使相机忽略这个参数也会返回 OK，证明不了什么。
        val probeEv = if (curEv == 0) 1 else 0
        val r = try {
            a.callForce("setExposureCompensation", listOf(probeEv))
        } catch (e: Exception) {
            "异常 " + (e.message ?: "")
        }
        sb.append("setExposureCompensation[").append(probeEv)
            .append("]（试写） -> ").append(shortResp(r)).append('\n')
        val restored = try {
            a.callForce("setExposureCompensation", listOf(curEv))
        } catch (e: Exception) {
            "异常 " + (e.message ?: "")
        }
        sb.append("  还原为[").append(curEv).append("] -> ").append(shortResp(restored)).append('\n')

        val timerBlock = eventField(a.getEvent(false), "selfTimer")
        val curTimer = timerBlock?.let { b ->
            val i = b.indexOf("\"currentSelfTimer\":")
            if (i < 0) null else b.substring(i + 19).takeWhile { it.isDigit() || it == '-' }.toIntOrNull()
        } ?: 0
        val r2 = try {
            a.callForce("setSelfTimer", listOf(curTimer))
        } catch (e: Exception) {
            "异常 " + (e.message ?: "")
        }
        sb.append("setSelfTimer[").append(curTimer)
            .append("]（当前值，无副作用） -> ").append(shortResp(r2)).append('\n')
        sb.toString()
    }

    suspend fun actZoom(direction: String, movement: String): Boolean = withContext(Dispatchers.IO) {
        try {
            api?.actZoom(direction, movement) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 当前状态下相机允许调用的方法（可能随状态变化）。
     *
     * 使用 refreshCapabilities 而非 getAvailableApiList：前者同时更新缓存。
     * 只取列表不更新缓存会使缓存长期停留在旧状态（实测停在 12 个方法），
     * 把相机本来就接受的参数写入全挡掉了。
     */
    suspend fun availableMethods(): List<String> = withContext(Dispatchers.IO) {
        try {
            api?.refreshCapabilities()?.available?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun disconnect() {
        net.release()
        api = null
        client = null
    }

    /** SSDP 失效时的兜底：拿网关 IP，试已知端口与文件名。 */
    private fun probeKnownLocations(network: Network): String? {
        val gw = net.gatewayAddress()?.hostAddress ?: run {
            note("      拿不到网关地址，放弃探测")
            return null
        }
        note("      网关 = " + gw)
        val ports = listOf(61000, 64321, 8080, 10000, 52323)
        val names = listOf("scalarwebapi_dd.xml", "DmsRmtDesc.xml", "dd.xml")
        for (p in ports) {
            for (n in names) {
                val u = "http://" + gw + ":" + p + "/" + n
                val body = NetHttp.getString(network, u, 1500, 2500)
                if (body != null && body.contains("ScalarWebAPI")) {
                    note("      探测命中 " + u)
                    return u
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "CameraRepository"
    }
}
