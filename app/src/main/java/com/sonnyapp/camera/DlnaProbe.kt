package com.sonnyapp.camera

import android.net.Network
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * DLNA / UPnP 探测。
 *
 * ## 为什么需要它
 *
 * 相机在「发送到智能手机」模式下会变成一个 **UPnP 媒体服务器**
 * （遥控模式的 DD.xml 里就能看到 modelDescription = SonyDigitalMediaServer）。
 * 但**传照片不能走遥控模式那套 ScalarWebAPI** —— 那套只有 guide / accessControl / camera，
 * **没有 avContent**，读不到卡里的文件列表。
 *
 * 所以相册功能必须探明：相机到底提供了哪一种内容浏览协议。
 *
 * ## 探测顺序
 *
 * 1. SSDP M-SEARCH（ssdp:all 等）-> 拿到若干 LOCATION
 * 2. 抓每个 LOCATION 的设备描述 -> 列出 serviceType / controlURL
 * 3. 若有 ContentDirectory -> **真的发一次 SOAP Browse**，看能不能列出文件
 *
 * 第 3 步是决定性的：能列出文件名，相册功能就成立。
 */
class DlnaProbe(private val network: Network) {

    private val sb = StringBuilder()

    private fun line(s: String) {
        sb.append(s).append('\n')
    }

    // ------------------------------------------------------------ SSDP

    private fun ssdp(st: String, timeoutMs: Int): List<String> {
        val out = ArrayList<String>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = 1200
            try {
                network.bindSocket(socket)
            } catch (e: Exception) {
                line("    [bind 失败] " + e.message)
            }
            val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"" + "ssdp:discover" + "\"\r\n" +
                "MX: 3\r\n" +
                "ST: " + st + "\r\n\r\n").toByteArray(Charsets.US_ASCII)
            val group = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(msg, msg.size, group, 1900))

            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    out.add(String(p.data, 0, p.length, Charsets.US_ASCII))
                } catch (e: SocketTimeoutException) {
                    // 继续等到 deadline
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            line("    [SSDP 异常] " + (e.message ?: ""))
        } finally {
            try { socket?.close() } catch (e: Exception) { }
        }
        return out
    }

    private fun header(text: String, key: String): String {
        for (raw in text.split("\n")) {
            val s = raw.trim()
            val i = s.indexOf(':')
            if (i > 0 && s.substring(0, i).trim().equals(key, ignoreCase = true)) {
                return s.substring(i + 1).trim()
            }
        }
        return ""
    }

    /**
     * 从 SOAP 响应里抽出 DIDL-Lite 并**反转义**。
     *
     * DLNA 把内容放在 <Result> 里并做了 XML 转义：
     *    <Result>&lt;DIDL-Lite&gt;...&lt;/DIDL-Lite&gt;</Result>
     * 不反转义的话，所有 <dc:title> / <container> 之类的正则都匹配不到 ——
     * 因此若不做反转义，会出现 TotalMatches 有值却列不出条目的情况。
     *
     * 反转义顺序：先处理具体实体，&amp; 必须**最后**处理，
     * 否则 &amp;lt; 会被错误地还原成 < 。
     */
    private fun extractDidl(body: String): String {
        val i = body.indexOf("<Result>")
        val j = body.indexOf("</Result>")
        if (i < 0 || j < 0 || j <= i) return body
        var s = body.substring(i + "<Result>".length, j)
        s = s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        return s
    }

    private fun tags(xml: String, tag: String): List<String> =
        Regex("<" + tag + "[^>]*>([^<]*)</" + tag + ">")
            .findAll(xml).map { it.groupValues[1] }.toList()

    /** 把可能的相对 URL 补成绝对 URL。 */
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

    // ------------------------------------------------------------ 主流程

    fun run(): String {
        line("=== DLNA 探测 ===")
        val locations = LinkedHashSet<String>()

        for (st in listOf(
            "ssdp:all",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "urn:schemas-sony-com:service:ScalarWebAPI:1",
        )) {
            val rs = ssdp(st, 4000)
            line("[SSDP] ST=" + st + "  -> " + rs.size + " 个响应")
            for (x in rs) {
                val loc = header(x, "LOCATION")
                val s = header(x, "ST")
                val sv = header(x, "SERVER")
                line("   ST=" + s)
                if (sv.isNotEmpty()) line("     SERVER=" + sv)
                if (loc.isNotEmpty()) {
                    line("     LOCATION=" + loc)
                    locations.add(loc)
                }
            }
        }

        if (locations.isEmpty()) {
            line("")
            line(">>> 没有发现任何 UPnP 设备。")
            line(">>> 可能原因：相机不在「发送到智能手机」模式；或该模式用的不是 UPnP。")
            return sb.toString()
        }

        line("")
        line("=== 设备描述 ===")
        for (loc in locations) {
            line("--- " + loc)
            val xml = try {
                httpGet(loc)
            } catch (e: Exception) {
                line("   抓取失败: " + (e.message ?: ""))
                continue
            }
            if (xml == null) {
                line("   空响应")
                continue
            }
            val model = tags(xml, "modelDescription").firstOrNull() ?: ""
            if (model.isNotEmpty()) line("   modelDescription: " + model)
            val friendly = tags(xml, "friendlyName").firstOrNull() ?: ""
            if (friendly.isNotEmpty()) line("   friendlyName: " + friendly)

            val types = tags(xml, "serviceType")
            val ctrls = tags(xml, "controlURL")
            line("   serviceType (" + types.size + "):")
            for (t in types) line("     " + t)
            line("   controlURL (" + ctrls.size + "):")
            for (c in ctrls) line("     " + c)

            // ---- 索尼私有 DigitalImaging 服务的能力描述 ----
            val scpdUrls = tags(xml, "SCPDURL")
            for (su in scpdUrls) {
                if (su.isBlank()) continue
                val u = absolute(loc, su)
                line("   SCPD: " + u)
                try {
                    val s = httpGet(u)
                    if (s == null) {
                        line("     空")
                    } else {
                        val acts = Regex("<action>(.*?)</action>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(s).mapNotNull {
                                Regex("<name>([^<]*)</name>").find(it.groupValues[1])
                                    ?.groupValues?.get(1)
                            }.toList()
                        line("     actions(" + acts.size + "): " + acts.joinToString(", ").take(500))
                    }
                } catch (e: Exception) {
                    line("     失败: " + (e.message ?: ""))
                }
            }

            // ---- ConnectionManager.GetProtocolInfo：服务器声明它能提供哪些格式 ----
            for (i2 in types.indices) {
                if (!types[i2].contains("ConnectionManager")) continue
                val cu = if (i2 < ctrls.size) absolute(loc, ctrls[i2]) else ""
                if (cu.isEmpty()) continue
                line("   >>> GetProtocolInfo: " + cu)
                try {
                    val body = soapCall(
                        cu, types[i2], "GetProtocolInfo", ""
                    )
                    val src = Regex("<Source>([\\s\\S]*?)</Source>").find(body ?: "")
                        ?.groupValues?.get(1) ?: ""
                    val protos = src.replace("&lt;", "<").split(",").map { it.trim() }
                        .filter { it.isNotEmpty() }
                    line("     服务器支持的协议 " + protos.size + " 条:")
                    for (p in protos.take(30)) line("       " + p)
                } catch (e: Exception) {
                    line("     失败: " + (e.message ?: ""))
                }
            }

            // 有 ContentDirectory 就真的 Browse 一次
            for (i in types.indices) {
                val t = types[i]
                if (!t.contains("ContentDirectory")) continue
                val ctrl = if (i < ctrls.size) absolute(loc, ctrls[i]) else ""
                if (ctrl.isEmpty()) continue
                line("   >>> ContentDirectory 控制地址: " + ctrl)
                try {
                    browseInto(ctrl, t, "0", 0)
                } catch (e: Exception) {
                    line("   Browse 失败: " + (e.message ?: ""))
                }
            }
        }

        line("")
        line("=== 探测结束 ===")
        return sb.toString()
    }

    /**
     * 递归浏览。第一层往往只是个容器（文件夹），必须进去才能看到文件。
     * 顺便把**原始响应**打出来 —— 只有看到真实 XML 才知道字段名和命名空间。
     */
    private var browseCount = 0

    private fun browseInto(ctrl: String, serviceType: String, objectId: String, depth: Int) {
        // 放开到 3 层：0=根 1=PhotoRoot 2=Date 3=日期文件夹(真正的文件在这一层)
        if (depth > 3) return
        // 限流：日期文件夹有 14 个，每个都进去会打出上千行；只抽查前几个
        if (browseCount >= 12) {
            line("  ".repeat(depth + 1) + "(已达浏览上限，其余省略)")
            return
        }
        browseCount++
        val pad = "   " + "  ".repeat(depth)
        line(pad + "[Browse] ObjectID=" + objectId + "  depth=" + depth)

        val raw = soapBrowse(ctrl, serviceType, objectId) ?: run {
            line(pad + "  空响应")
            return
        }
        val body = extractDidl(raw)
        line(pad + "  SOAP " + raw.length + " 字符 -> DIDL " + body.length + " 字符")
        val total = Regex("<TotalMatches>(\\d+)</TotalMatches>").find(raw)?.groupValues?.get(1)
        val returned = Regex("<NumberReturned>(\\d+)</NumberReturned>").find(raw)?.groupValues?.get(1)
        line(pad + "  TotalMatches=" + (total ?: "?") + "  NumberReturned=" + (returned ?: "?"))

        if (depth == 0) {
            line(pad + "  ---- 反转义后的 DIDL ----")
            for (chunk in body.take(900).chunked(110)) line(pad + "  | " + chunk)
            line(pad + "  ---- DIDL 结束 ----")
        }

        // 容器
        val containerRe = Regex("<container\\b[^>]*\\bid=\"([^\"]*)\"[^>]*>(.*?)</container>", RegexOption.DOT_MATCHES_ALL)
        val containers = containerRe.findAll(body).toList()
        line(pad + "  容器 " + containers.size + " 个")
        for (c in containers) {
            val id = c.groupValues[1]
            val inner = c.groupValues[2]
            val title = Regex("<dc:title>([^<]*)</dc:title>").find(inner)?.groupValues?.get(1)
                ?: Regex("<title>([^<]*)</title>").find(inner)?.groupValues?.get(1) ?: "?"
            val mediaClass = Regex("<av:mediaClass>([^<]*)</av:mediaClass>").find(inner)?.groupValues?.get(1) ?: ""
            val childCount = Regex("childCount=\"(\\d+)\"").find(c.value)?.groupValues?.get(1) ?: "?"
            line(pad + "    [" + id + "] " + title +
                "   childCount=" + childCount +
                (if (mediaClass.isNotEmpty()) "   mediaClass=" + mediaClass else ""))
            // 日期文件夹有 14 个，只抽查前 3 个；再深一层就到文件了
            // 抽查前 3 个 + 第 11 个（81 项，最可能混有视频）
            val idx = containers.indexOf(c)
            val descend = if (depth >= 2) (idx < 3 || idx == 10) else true
            if (descend) {
                browseInto(ctrl, serviceType, id, depth + 1)
            } else if (idx == 3) {
                line(pad + "    ...(其余日期文件夹省略)")
            }
        }

        // 文件
        val itemRe = Regex("<item\\b[^>]*\\bid=\"([^\"]*)\"[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        val items = itemRe.findAll(body).toList()
        line(pad + "  文件 " + items.size + " 个")
        // 扩展名分布 —— 一眼看出这个文件夹有没有 RAW / 视频
        if (items.isNotEmpty()) {
            val hist = LinkedHashMap<String, Int>()
            for (it in items) {
                val inner0 = it.groupValues[2]
                val t0 = Regex("<dc:title>([^<]*)</dc:title>").find(inner0)?.groupValues?.get(1) ?: ""
                val e = t0.substringAfterLast('.', "(无)").uppercase()
                hist[e] = (hist[e] ?: 0) + 1
            }
            line(pad + "  扩展名分布: " + hist.entries.joinToString(", ") { it.key + "×" + it.value })
        }

        // 关键测试：RAW 文件到底能不能拿到**.ARW 原文件**
        // res 里只有转码 JPEG，但也许 ORG_ 直链照样存在（JPEG 就是 ORG_ 形式）
        for (it in items) {
            val inner0 = it.groupValues[2]
            val t0 = Regex("<dc:title>([^<]*)</dc:title>").find(inner0)?.groupValues?.get(1) ?: ""
            if (!t0.uppercase().endsWith(".ARW") && !t0.uppercase().endsWith(".MP4")) continue
            val anyRes = Regex("<res[^>]*>([^<]*)</res>", RegexOption.DOT_MATCHES_ALL)
                .find(inner0)?.groupValues?.get(1)?.trim() ?: continue
            val orgUrl = anyRes.replace("/SM_", "/ORG_")
                .replace("/LRG_", "/ORG_").replace("/TN_", "/ORG_")
            line(pad + "  ★测试原始文件: " + t0)
            line(pad + "     " + orgUrl.take(150))
            line(pad + "     " + probeUrl(orgUrl))
            break   // 只测第一个，避免下载太多
        }

        // 打印前几个文件的完整信息（含所有 res，看有哪些 profile：原图 / 缩略图）
        for (it in items.take(6)) {
            val inner = it.groupValues[2]
            val title = Regex("<dc:title>([^<]*)</dc:title>").find(inner)?.groupValues?.get(1)
                ?: Regex("<title>([^<]*)</title>").find(inner)?.groupValues?.get(1) ?: "?"
            val cls = Regex("<upnp:class>([^<]*)</upnp:class>").find(inner)?.groupValues?.get(1) ?: "?"
            val date = Regex("<dc:date>([^<]*)</dc:date>").find(inner)?.groupValues?.get(1) ?: ""
            line(pad + "    " + title + "   [" + cls + "]")
            if (date.isNotEmpty()) line(pad + "      date=" + date)
            val resAll = Regex("<res[^>]*>([^<]*)</res>", RegexOption.DOT_MATCHES_ALL)
                .findAll(inner).toList()
            line(pad + "      res 数量=" + resAll.size)
            for (r in resAll.take(4)) {
                val attrs = Regex("<res([^>]*)>").find(r.value)?.groupValues?.get(1) ?: ""
                val url = r.groupValues[1].trim()
                line(pad + "        " + attrs.trim())
                line(pad + "          -> " + url.take(120))
            }
        }
        if (items.size > 6) line(pad + "    ...(其余 " + (items.size - 6) + " 个省略)")
    }

    /**
     * 只取第一个字节，看服务器认不认这个 URL。
     * 用来验证"没列在 res 里的 ORG_ 原始文件"到底存不存在。
     */
    private fun probeUrl(url: String): String {
        return try {
            val conn = network.openConnection(URL(url)) as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-0")
            val code = conn.responseCode
            val len = conn.getHeaderField("Content-Length") ?: "?"
            val cr = conn.getHeaderField("Content-Range") ?: ""
            val ct = conn.getHeaderField("Content-Type") ?: ""
            try { conn.inputStream?.close() } catch (e: Exception) { }
            conn.disconnect()
            "HTTP " + code + "  len=" + len + "  type=" + ct +
                (if (cr.isNotEmpty()) "  range=" + cr else "")
        } catch (e: Exception) {
            "异常 " + (e.message ?: "")
        }
    }

    private fun httpGet(url: String): String? {
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 10000
        conn.requestMethod = "GET"
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    /** 通用 SOAP 调用（无参动作）。 */
    private fun soapCall(
        controlUrl: String,
        serviceType: String,
        action: String,
        innerXml: String,
    ): String? {
        val soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">" +
            innerXml + "</u:" + action + "></s:Body></s:Envelope>"
        val conn = network.openConnection(URL(controlUrl)) as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 20000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"")
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(soap) }
            val code = conn.responseCode
            line("     HTTP " + code)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun soapBrowse(controlUrl: String, serviceType: String, objectId: String = "0"): String? {
        val soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Browse xmlns:u=\"" + serviceType + "\">" +
            "<ObjectID>" + objectId + "</ObjectID>" +
            "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter>" +
            "<StartingIndex>0</StartingIndex>" +
            "<RequestedCount>50</RequestedCount>" +
            "<SortCriteria></SortCriteria>" +
            "</u:Browse></s:Body></s:Envelope>"

        val conn = network.openConnection(URL(controlUrl)) as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 20000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPAction", "\"" + serviceType + "#Browse\"")
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(soap) }
            val code = conn.responseCode
            line("   HTTP " + code)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.bufferedReader()?.use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }
}
