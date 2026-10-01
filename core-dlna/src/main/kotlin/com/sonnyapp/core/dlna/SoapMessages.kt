package com.sonnyapp.core.dlna

/**
 * DLNA 的 SOAP 报文构造。
 *
 * 服务类型（实机 DD.xml 里声明的原文）：
 *   urn:schemas-upnp-org:service:ContentDirectory:1
 *   urn:schemas-upnp-org:service:ConnectionManager:1
 */
object SoapMessages {

    const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    private const val ENVELOPE_PREFIX =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"

    /** SOAPAction 头部的值（注意要带引号）。 */
    fun soapAction(serviceType: String, action: String): String =
        "\"" + serviceType + "#" + action + "\""

    /**
     * Browse。
     *
     * BrowseFlag 用 BrowseDirectChildren（列子项）而不是 BrowseMetadata。
     * RequestedCount 实测有效：81 项的文件夹只返回 50 项，靠 StartingIndex 翻页。
     */
    fun browse(
        objectId: String = "0",
        startIndex: Int = 0,
        requestedCount: Int = 50,
        browseFlag: String = "BrowseDirectChildren",
    ): String {
        val inner = StringBuilder()
            .append("<ObjectID>").append(escape(objectId)).append("</ObjectID>")
            .append("<BrowseFlag>").append(browseFlag).append("</BrowseFlag>")
            .append("<Filter>*</Filter>")
            .append("<StartingIndex>").append(startIndex).append("</StartingIndex>")
            .append("<RequestedCount>").append(requestedCount).append("</RequestedCount>")
            .append("<SortCriteria></SortCriteria>")
            .toString()
        return ENVELOPE_PREFIX + "<u:Browse xmlns:u=\"" + CONTENT_DIRECTORY + "\">" +
            inner + "</u:Browse></s:Body></s:Envelope>"
    }

    /** GetProtocolInfo —— 服务器自报「我能提供哪些格式」。相册可行性判断的关键依据。 */
    fun getProtocolInfo(): String =
        ENVELOPE_PREFIX + "<u:GetProtocolInfo xmlns:u=\"" + CONNECTION_MANAGER +
            "\"></u:GetProtocolInfo></s:Body></s:Envelope>"

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

/**
 * SSDP 报文。
 *
 * 实机注意：ssdp:all 有时一次收不到响应（组播锁刚拿到、网卡刚上线的抖动），
 * 所以调用方应该对同一 ST 重试，或者依次问几个具体的 ST。
 * 实测第一次 ssdp:all 返回 0 条，紧接着再问一次返回 6 条。
 */
object SsdpMessages {

    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900

    fun mSearchBytes(st: String, mx: Int = 3): ByteArray {
        val crlf = "\r\n"
        val s = "M-SEARCH * HTTP/1.1" + crlf +
            "HOST: " + ADDRESS + ":" + PORT + crlf +
            "MAN: \"ssdp:discover\"" + crlf +
            "MX: " + mx + crlf +
            "ST: " + st + crlf + crlf
        return s.toByteArray(Charsets.US_ASCII)
    }

    /** 解析一条 SSDP 响应。字段名大小写不敏感。 */
    fun parse(text: String): SsdpResponse {
        var st = ""
        var location = ""
        var server = ""
        var usn = ""
        for (raw in text.split("\n")) {
            val line = raw.trim()
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim()
            when {
                key.equals("ST", true) -> st = value
                key.equals("LOCATION", true) -> location = value
                key.equals("SERVER", true) -> server = value
                key.equals("USN", true) -> usn = value
            }
        }
        return SsdpResponse(st, location, server, usn)
    }
}

data class SsdpResponse(
    val st: String = "",
    val location: String = "",
    val server: String = "",
    val usn: String = "",
)
