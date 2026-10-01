package com.sonnyapp.core.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 ILCE-6300 实机 DLNA 响应做样本。
 *
 * 这些 fixture 是从 App 的 dlna.txt 报告里**原样抄下来的**，
 * 包括 <Result> 里的 XML 转义 —— 那正是最初解析失败的根因。
 */
class DidlParserTest {

    // ---------- 真实样本 1：根目录（PhotoRoot 容器）----------

    private val rootSoap =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
        "<u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
        "<Result>&lt;DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
        "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\" " +
        "xmlns:arib=\"urn:schemas-arib-or-jp:elements-1-0/\" " +
        "xmlns:av=\"urn:schemas-sony-com:av\"&gt;" +
        "&lt;container id=\"PhotoRoot\" restricted=\"1\" parentID=\"0\" childCount=\"1\"&gt;" +
        "&lt;dc:title&gt;PhotoRoot&lt;/dc:title&gt;" +
        "&lt;upnp:class&gt;object.container&lt;/upnp:class&gt;" +
        "&lt;av:mediaClass&gt;P,V&lt;/av:mediaClass&gt;" +
        "&lt;/container&gt;&lt;/DIDL-Lite&gt;</Result>" +
        "<NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches>" +
        "<UpdateID>636251077</UpdateID></u:BrowseResponse></s:Body></s:Envelope>"

    // ---------- 真实样本 2：一个含 ARW 的日期文件夹 ----------

    private val arwSoap =
        "<?xml version=\"1.0\"?><s:Envelope><s:Body>" +
        "<u:BrowseResponse><Result>&lt;DIDL-Lite&gt;" +
        "&lt;item id=\"03_01_000001_000001\" restricted=\"1\" parentID=\"03_01_000001\"&gt;" +
        "&lt;dc:title&gt;DSC09394.ARW&lt;/dc:title&gt;" +
        "&lt;upnp:class&gt;object.item.imageItem.photo&lt;/upnp:class&gt;" +
        "&lt;dc:date&gt;2026-03-27T14:47:50&lt;/dc:date&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM;DLNA.ORG_CI=1\"&gt;" +
        "http://192.168.122.1:60151/SM_DSC09394.ARW?q1&lt;/res&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_CI=1\"&gt;" +
        "http://192.168.122.1:60151/LRG_DSC09394.ARW?q1&lt;/res&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;DLNA.ORG_CI=1\"&gt;" +
        "http://192.168.122.1:60151/TN_DSC09394.ARW?q1&lt;/res&gt;" +
        "&lt;/item&gt;" +
        "&lt;item id=\"03_01_000001_000002\" restricted=\"1\" parentID=\"03_01_000001\"&gt;" +
        "&lt;dc:title&gt;DSC09402.ARW&lt;/dc:title&gt;" +
        "&lt;upnp:class&gt;object.item.imageItem.photo&lt;/upnp:class&gt;" +
        "&lt;dc:date&gt;2026-03-27T20:05:25&lt;/dc:date&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;DLNA.ORG_CI=1\"&gt;" +
        "http://192.168.122.1:60151/TN_DSC09402.ARW?q2&lt;/res&gt;" +
        "&lt;/item&gt;" +
        "&lt;/DIDL-Lite&gt;</Result>" +
        "<NumberReturned>2</NumberReturned><TotalMatches>4</TotalMatches>" +
        "</u:BrowseResponse></s:Body></s:Envelope>"

    // ---------- 真实样本 3：一个 JPG（有 ORG_ 原文件）----------

    private val jpgSoap =
        "<?xml version=\"1.0\"?><s:Envelope><s:Body><u:BrowseResponse><Result>" +
        "&lt;DIDL-Lite&gt;" +
        "&lt;item id=\"03_01_000003_000001\" restricted=\"1\" parentID=\"03_01_000003\"&gt;" +
        "&lt;dc:title&gt;DSC09501.JPG&lt;/dc:title&gt;" +
        "&lt;upnp:class&gt;object.item.imageItem.photo&lt;/upnp:class&gt;" +
        "&lt;dc:date&gt;2026-04-10T14:45:35&lt;/dc:date&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_CI=1\"&gt;" +
        "http://192.168.122.1:60151/LRG_DSC09501.JPG?q&lt;/res&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM;DLNA.ORG_CI=1\"&gt;" +
        "http://192.168.122.1:60151/SM_DSC09501.JPG?q&lt;/res&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;DLNA.ORG_CI=1\"&gt;" +
        "http://192.168.122.1:60151/TN_DSC09501.JPG?q&lt;/res&gt;" +
        "&lt;res protocolInfo=\"http-get:*:image/jpeg:*\" size=\"13729792\" resolution=\"6000x4000\"&gt;" +
        "http://192.168.122.1:60151/ORG_DSC09501.JPG?q&lt;/res&gt;" +
        "&lt;/item&gt;&lt;/DIDL-Lite&gt;</Result>" +
        "<NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches>" +
        "</u:BrowseResponse></s:Body></s:Envelope>"

    // ============================================================ 测试

    @Test
    fun parsesRootContainerFromRealResponse() {
        val r = DidlParser.parseSoap(rootSoap)
        assertEquals(1, r.items.size)
        assertEquals(1, r.numberReturned)
        assertEquals(1, r.totalMatches)
        assertEquals("636251077", r.updateId)

        val c = r.items[0]
        assertTrue(c.isContainer)
        assertEquals("PhotoRoot", c.id)
        assertEquals("PhotoRoot", c.title)
        assertEquals("0", c.parentId)
        assertEquals(1, c.childCount)
        assertEquals("object.container", c.upnpClass)
        // 索尼扩展：P=照片 V=视频
        assertEquals("P,V", c.mediaClass)
    }

    /** 不做反转义时无法解析出任何条目。 */
    @Test
    fun unescapesResultBeforeParsing() {
        val didl = DidlParser.extractDidl(rootSoap)
        assertTrue("反转义后应含真正的尖括号", didl.contains("<container id=\"PhotoRoot\""))
        assertFalse("不应残留 &lt;", didl.contains("&lt;"))
    }

    /** 真实样本里 RAW 只有 3 个转码 res，**没有 ORG_** —— 也就是拿不到原文件。 */
    @Test
    fun rawItemHasNoDownloadableOriginal() {
        val r = DidlParser.parseSoap(arwSoap)
        assertEquals(2, r.items.size)
        val arw = r.items[0]
        assertEquals("DSC09394.ARW", arw.title)
        assertTrue(arw.isRaw)
        assertFalse(arw.isVideo)
        assertEquals(3, arw.resources.size)
        assertNull("RAW 不应有原文件地址", arw.originalUrl)
        assertFalse(arw.canDownloadOriginal)
        assertEquals(-1L, arw.originalSize)
        // 但缩略图是有的 —— 相册网格靠它
        assertNotNull(arw.thumbnailUrl)
        assertTrue(arw.thumbnailUrl!!.contains("/TN_"))
    }

    /** JPEG 有第 4 个 res：ORG_ 原文件，带 size 和 resolution。 */
    @Test
    fun jpegItemHasDownloadableOriginalWithSize() {
        val r = DidlParser.parseSoap(jpgSoap)
        val jpg = r.items[0]
        assertEquals("DSC09501.JPG", jpg.title)
        assertFalse(jpg.isRaw)
        assertEquals(4, jpg.resources.size)
        assertNotNull(jpg.originalUrl)
        assertTrue(jpg.canDownloadOriginal)
        assertTrue(jpg.originalUrl!!.contains("/ORG_"))
        assertEquals(13729792L, jpg.originalSize)
    }

    /** RAW 没有原文件，但**仍然能下载大预览** —— 不能让它完全没得下。 */
    @Test
    fun rawFallsBackToLargePreviewForDownload() {
        val arw = DidlParser.parseSoap(arwSoap).items[0]
        assertFalse(arw.downloadIsOriginal)
        assertNotNull(arw.bestDownloadUrl)
        assertTrue("应回退到 LRG_ 大预览", arw.bestDownloadUrl!!.contains("/LRG_"))
    }

    /** JPEG 优先给原文件，而不是预览。 */
    @Test
    fun jpegPrefersOriginalForDownload() {
        val jpg = DidlParser.parseSoap(jpgSoap).items[0]
        assertTrue(jpg.downloadIsOriginal)
        assertTrue(jpg.bestDownloadUrl!!.contains("/ORG_"))
    }

    @Test
    fun parsesResourceAttributes() {
        val jpg = DidlParser.parseSoap(jpgSoap).items[0]
        val org = jpg.resources.first { it.isOriginal }
        assertEquals("6000x4000", org.resolution)
        assertEquals("", org.profile)
        assertFalse(org.isConverted)

        val tn = jpg.resources.first { it.isThumbnail }
        assertEquals("JPEG_TN", tn.profile)
        assertTrue(tn.isConverted)
        assertEquals(-1L, tn.sizeBytes)
    }

    @Test
    fun largePreviewPrefersLrgOverSmall() {
        val jpg = DidlParser.parseSoap(jpgSoap).items[0]
        assertTrue("应优先选 JPEG_LRG", jpg.previewUrl!!.contains("/LRG_"))
    }

    /** 分页：NumberReturned=2 但 TotalMatches=4，说明还有下一页。 */
    @Test
    fun detectsMorePages() {
        val r = DidlParser.parseSoap(arwSoap)
        assertEquals(4, r.totalMatches)
        assertEquals(2, r.items.size)
        assertTrue(r.hasMore)
    }

    @Test
    fun emptyOrBrokenInputDoesNotCrash() {
        assertEquals(0, DidlParser.parseSoap("").items.size)
        assertEquals(0, DidlParser.parseSoap("<html>not dlna</html>").items.size)
        assertEquals(0, DidlParser.parse("<DIDL-Lite></DIDL-Lite>").items.size)
    }

    @Test
    fun unescapeHandlesAmpLast() {
        // &amp;lt; 应该还原成字面量 &lt;，而不是 <
        assertEquals("&lt;", DidlParser.unescape("&amp;lt;"))
        assertEquals("<a>", DidlParser.unescape("&lt;a&gt;"))
        assertEquals("\"q\"", DidlParser.unescape("&quot;q&quot;"))
    }

    @Test
    fun splitsContainersAndFiles() {
        val r = DidlParser.parseSoap(arwSoap)
        assertEquals(0, r.containers.size)
        assertEquals(2, r.files.size)
    }
}
