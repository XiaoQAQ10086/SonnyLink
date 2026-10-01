package com.sonnyapp.core.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpAndSoapTest {

    // 实机抓到的 SSDP 响应原文（http://192.168.122.1:64321/dd.xml）
    private val realSsdp = "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "EXT:\r\n" +
        "LOCATION: http://192.168.122.1:64321/dd.xml\r\n" +
        "SERVER: UPnP/1.0 SonyImagingDevice/1.0\r\n" +
        "ST: urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
        "USN: uuid:00000000-0000-0010-8000-a0c9a09ac028::urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
        "\r\n"

    @Test
    fun parsesRealSsdpResponse() {
        val r = SsdpMessages.parse(realSsdp)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:1", r.st)
        assertEquals("http://192.168.122.1:64321/dd.xml", r.location)
        assertEquals("UPnP/1.0 SonyImagingDevice/1.0", r.server)
        assertTrue(r.usn.startsWith("uuid:"))
    }

    /** 字段名大小写不敏感 —— 不同实现的头部大小写不一致。 */
    @Test
    fun ssdpHeadersAreCaseInsensitive() {
        val lower = realSsdp.replace("LOCATION:", "location:").replace("ST:", "st:")
        val r = SsdpMessages.parse(lower)
        assertEquals("http://192.168.122.1:64321/dd.xml", r.location)
        assertEquals("urn:schemas-upnp-org:service:ContentDirectory:1", r.st)
    }

    @Test
    fun mSearchHasRequiredHeaders() {
        val s = String(SsdpMessages.mSearchBytes("ssdp:all"), Charsets.US_ASCII)
        assertTrue(s.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(s.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(s.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(s.contains("ST: ssdp:all\r\n"))
        assertTrue(s.endsWith("\r\n\r\n"))
    }

    @Test
    fun browseSoapCarriesObjectIdAndPaging() {
        val s = SoapMessages.browse("PhotoRoot", startIndex = 50, requestedCount = 25)
        assertTrue(s.contains("<ObjectID>PhotoRoot</ObjectID>"))
        assertTrue(s.contains("<StartingIndex>50</StartingIndex>"))
        assertTrue(s.contains("<RequestedCount>25</RequestedCount>"))
        assertTrue(s.contains("<BrowseFlag>BrowseDirectChildren</BrowseFlag>"))
        assertTrue(s.contains("xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\""))
    }

    @Test
    fun soapActionIsQuoted() {
        val a = SoapMessages.soapAction(SoapMessages.CONTENT_DIRECTORY, "Browse")
        assertEquals("\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"", a)
    }

    /** ObjectID 里可能有特殊字符，必须转义。 */
    @Test
    fun browseEscapesObjectId() {
        val s = SoapMessages.browse("a&b<c")
        assertTrue(s.contains("<ObjectID>a&amp;b&lt;c</ObjectID>"))
    }

    @Test
    fun getProtocolInfoUsesConnectionManager() {
        val s = SoapMessages.getProtocolInfo()
        assertTrue(s.contains("GetProtocolInfo"))
        assertTrue(s.contains("urn:schemas-upnp-org:service:ConnectionManager:1"))
    }
}
