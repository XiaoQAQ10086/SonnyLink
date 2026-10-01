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

    /** 当前 Wi-Fi 状态（判断用户连的是不是相机）。 */
    fun wifiState(): WifiState = net.wifiState()

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

    suspend fun setSelfTimer(seconds: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            api?.setSelfTimer(seconds) ?: false
        } catch (e: Exception) {
            false
        }
    }

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
