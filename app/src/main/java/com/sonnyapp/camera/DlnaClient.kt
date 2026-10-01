package com.sonnyapp.camera

import android.net.Network
import android.util.Log
import com.sonnyapp.core.dlna.DidlParser
import com.sonnyapp.core.dlna.DidlResult
import com.sonnyapp.core.dlna.SoapMessages
import com.sonnyapp.core.dlna.SsdpMessages
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * DLNA 客户端（相机在「发送到智能手机」模式下的媒体服务器）。
 *
 * ## 与遥控模式的关系
 *
 * 相机一次只能跑一种模式：
 *   「嵌入式智能遥控」 -> ScalarWebAPI（只有取景/拍照，**没有 avContent**）
 *   「发送到智能手机」 -> **DLNA 媒体服务器**（相册功能靠这个）
 *
 * 因此两个功能互斥，由启动页选择。
 *
 * ## 实机掌握的关键事实（ILCE-6300）
 *
 *  - 设备描述在 **http://192.168.122.1:64321/dd.xml**（端口和遥控模式不同）
 *  - 服务：ContentDirectory:1 / ConnectionManager:1 / DigitalImaging:1
 *  - 目录：PhotoRoot -> Date -> 按日期分的文件夹 -> 文件
 *  - 文件 res 命名：ORG_ 原文件 / LRG_ 大预览 / SM_ 小预览 / TN_ 缩略图
 *  - **RAW(.ARW) 只有转码预览，没有 ORG_** —— 直接请求 ORG_*.ARW 会得到 HTTP 406
 *  - GetProtocolInfo 只声明 image/jpeg —— **视频也拿不到**
 */
class DlnaClient(private val network: Network) {

    var controlUrl: String = ""
        private set
    var connectionManagerUrl: String = ""
        private set
    var deviceDescriptionUrl: String = ""
        private set
    var serverHeader: String = ""
        private set
    var friendlyName: String = ""
        private set

    val isReady: Boolean get() = controlUrl.isNotEmpty()

    // ------------------------------------------------------------ 发现

    /**
     * SSDP 发现 + 抓取设备描述，定位 ContentDirectory 的控制地址。
     *
     * ssdp:all 有时第一发收不到（组播锁或网卡刚上线时的抖动），因此
     * **对每个 ST 都重试一轮**，实测第一发 0 条、第二发 6 条。
     */
    fun discover(timeoutMs: Int = 2500): Boolean {
        val locations = LinkedHashSet<String>()
        val searchTypes = listOf(
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "ssdp:all",
        )
        for (st in searchTypes) {
            // 收到响应后立即返回；找到设备后不再尝试其余 ST。
            // ssdpSearch 若不提前返回，每轮都会等满超时。
            // 因此这里只对每个 ST 探测一次。
            val rs = ssdpSearch(st, timeoutMs)
            for (text in rs) {
                val r = SsdpMessages.parse(text)
                if (r.location.isNotEmpty()) locations.add(r.location)
                if (serverHeader.isEmpty() && r.server.isNotEmpty()) serverHeader = r.server
            }
            if (locations.isNotEmpty()) break
        }
        if (locations.isEmpty()) {
            Log.w(TAG, "SSDP 没有发现任何设备")
            return false
        }

        // 逐个抓设备描述，找 ContentDirectory
        for (loc in locations) {
            val xml = try { httpGetString(loc) } catch (e: Exception) { null } ?: continue
            deviceDescriptionUrl = loc
            val name = tagOf(xml, "friendlyName")
            if (name.isNotEmpty()) friendlyName = name

            val types = tagsOf(xml, "serviceType")
            val ctrls = tagsOf(xml, "controlURL")
            for (i in types.indices) {
                val t = types[i]
                val c = if (i < ctrls.size) absolute(loc, ctrls[i]) else ""
                if (c.isEmpty()) continue
                when {
                    t.contains("ContentDirectory") -> controlUrl = c
                    t.contains("ConnectionManager") -> connectionManagerUrl = c
                }
            }
            if (controlUrl.isNotEmpty()) {
                Log.i(TAG, "DLNA ready: " + friendlyName + "  control=" + controlUrl)
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------ 浏览

    fun browse(objectId: String = "0", startIndex: Int = 0, count: Int = 50): DidlResult {
        if (controlUrl.isEmpty()) return DidlResult()
        val soap = SoapMessages.browse(objectId, startIndex, count)
        val body = soapPost(controlUrl, SoapMessages.CONTENT_DIRECTORY, "Browse", soap)
            ?: return DidlResult()
        return DidlParser.parseSoap(body)
    }

    /** 服务器自报支持的格式（判断能不能拿 RAW/视频的依据）。 */
    fun readProtocolInfo(): List<String> {
        if (connectionManagerUrl.isEmpty()) return emptyList()
        val soap = SoapMessages.getProtocolInfo()
        val body = soapPost(
            connectionManagerUrl,
            SoapMessages.CONNECTION_MANAGER,
            "GetProtocolInfo",
            soap,
        ) ?: return emptyList()
        val src = Regex("<Source>([\\s\\S]*?)</Source>").find(body)?.groupValues?.get(1) ?: return emptyList()
        return DidlParser.unescape(src).split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------ 下载 / 缩略图

    /** 打开一个资源的字节流。调用方负责关闭。 */
    fun openStream(url: String): InputStream? {
        return try {
            val conn = network.openConnection(URL(url)) as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "下载失败 HTTP " + code + " : " + url.take(120))
                conn.disconnect()
                return null
            }
            conn.inputStream
        } catch (e: Exception) {
            Log.w(TAG, "下载异常: " + (e.message ?: ""))
            null
        }
    }

    /** 带进度回调的下载。返回写入的字节数；失败返回 -1。 */
    fun download(url: String, out: java.io.OutputStream, onProgress: (Long, Long) -> Unit): Long {
        return try {
            val conn = network.openConnection(URL(url)) as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return -1L
            }
            val total = conn.contentLengthLong
            var done = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            out.flush()
            conn.disconnect()
            done
        } catch (e: Exception) {
            Log.w(TAG, "下载中断: " + (e.message ?: ""))
            -1L
        }
    }

    // ------------------------------------------------------------ 内部

    private fun ssdpSearch(st: String, timeoutMs: Int): List<String> {
        val out = ArrayList<String>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 1200
            try { network.bindSocket(socket) } catch (e: Exception) { }
            val bytes = SsdpMessages.mSearchBytes(st)
            val group = InetAddress.getByName(SsdpMessages.ADDRESS)
            socket.send(DatagramPacket(bytes, bytes.size, group, SsdpMessages.PORT))
            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    out.add(String(p.data, 0, p.length, Charsets.US_ASCII))
                    // 拿到第一个响应就够定位设备了，立刻返回 —— 不要再空等
                    break
                } catch (e: SocketTimeoutException) {
                    // soTimeout（1.2s）内没有响应就认为这台设备不存在，不必空转到 deadline
                    break
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP 异常: " + (e.message ?: ""))
        } finally {
            try { socket?.close() } catch (e: Exception) { }
        }
        return out
    }

    private fun soapPost(
        url: String,
        serviceType: String,
        action: String,
        soap: String,
    ): String? {
        return try {
            val conn = network.openConnection(URL(url)) as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 20000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", SoapMessages.soapAction(serviceType, action))
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(soap) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            conn.disconnect()
            body
        } catch (e: Exception) {
            Log.w(TAG, action + " 失败: " + (e.message ?: ""))
            null
        }
    }

    private fun httpGetString(url: String): String? {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun tagsOf(xml: String, tag: String): List<String> =
        Regex("<" + tag + "[^>]*>([^<]*)</" + tag + ">")
            .findAll(xml).map { it.groupValues[1].trim() }.toList()

    private fun tagOf(xml: String, tag: String): String = tagsOf(xml, tag).firstOrNull() ?: ""

    private fun absolute(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return try {
            val b = URL(base)
            val port = if (b.port > 0) ":" + b.port else ""
            val p = if (path.startsWith("/")) path else "/" + path
            b.protocol + "://" + b.host + port + p
        } catch (e: Exception) {
            path
        }
    }

    companion object {
        private const val TAG = "DlnaClient"
    }
}
