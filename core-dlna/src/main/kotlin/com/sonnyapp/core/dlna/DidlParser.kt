package com.sonnyapp.core.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DIDL-Lite 解析。
 *
 * ## 要点：内容位于 Result 元素内，且经过 XML 转义
 *
 * DLNA 的 SOAP 响应长这样：
 *
 *   <u:BrowseResponse ...>
 *     <Result>&lt;DIDL-Lite ...&gt;...&lt;/DIDL-Lite&gt;</Result>
 *     <NumberReturned>14</NumberReturned>
 *     <TotalMatches>14</TotalMatches>
 *   </u:BrowseResponse>
 *
 * **必须先把 Result 里的内容反转义**，否则所有 container / dc:title 都匹配不到。
 * 最初用正则直接扫响应体，结果「TotalMatches=14 却列出 0 个条目」。
 */
object DidlParser {

    /** 反转义 XML 实体。&amp; 必须最后处理，否则 &amp;lt; 会被错误还原成 <。 */
    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return s
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /** 从 SOAP 响应里取出 DIDL-Lite 并反转义。取不到就整段反转义（宽容处理）。 */
    fun extractDidl(soap: String): String {
        val i = soap.indexOf("<Result>")
        val j = soap.indexOf("</Result>")
        if (i < 0 || j <= i) return unescape(soap)
        return unescape(soap.substring(i + "<Result>".length, j))
    }

    /** 解析完整的 SOAP Browse 响应。 */
    fun parseSoap(soap: String): DidlResult =
        parse(
            extractDidl(soap),
            numberReturned = tagInt(soap, "NumberReturned"),
            totalMatches = tagInt(soap, "TotalMatches"),
            updateId = tagText(soap, "UpdateID"),
        )

    fun parse(
        didl: String,
        numberReturned: Int = 0,
        totalMatches: Int = 0,
        updateId: String = "",
    ): DidlResult {
        val doc = parseXml(didl) ?: return DidlResult(emptyList(), numberReturned, totalMatches, updateId)
        val out = ArrayList<DlnaItem>()

        val containers = doc.getElementsByTagName("container")
        for (i in 0 until containers.length) {
            (containers.item(i) as? Element)?.let { out.add(toItem(it, true)) }
        }
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            (items.item(i) as? Element)?.let { out.add(toItem(it, false)) }
        }
        return DidlResult(out, numberReturned, totalMatches, updateId)
    }

    // ------------------------------------------------------------ 内部

    private fun toItem(e: Element, isContainer: Boolean): DlnaItem {
        val resources = ArrayList<DlnaResource>()
        val resNodes = e.getElementsByTagName("res")
        for (i in 0 until resNodes.length) {
            val r = resNodes.item(i) as? Element ?: continue
            resources.add(
                DlnaResource(
                    url = r.textContent?.trim().orEmpty(),
                    protocolInfo = r.getAttribute("protocolInfo").orEmpty(),
                    sizeBytes = r.getAttribute("size").toLongOrNull() ?: -1L,
                    resolution = r.getAttribute("resolution").orEmpty(),
                    duration = r.getAttribute("duration").orEmpty(),
                )
            )
        }
        return DlnaItem(
            id = e.getAttribute("id").orEmpty(),
            parentId = e.getAttribute("parentID").orEmpty(),
            title = childText(e, "dc:title") ?: childText(e, "title") ?: "",
            isContainer = isContainer,
            childCount = e.getAttribute("childCount").toIntOrNull() ?: -1,
            upnpClass = childText(e, "upnp:class") ?: childText(e, "class") ?: "",
            date = childText(e, "dc:date") ?: "",
            mediaClass = childText(e, "av:mediaClass") ?: "",
            resources = resources,
        )
    }

    private fun childText(e: Element, tag: String): String? {
        val n = e.getElementsByTagName(tag)
        if (n.length == 0) return null
        val t = n.item(0).textContent?.trim()
        return if (t.isNullOrEmpty()) null else t
    }

    private fun tagText(xml: String, tag: String): String {
        val m = Regex("<" + tag + ">([^<]*)</" + tag + ">").find(xml) ?: return ""
        return m.groupValues[1].trim()
    }

    private fun tagInt(xml: String, tag: String): Int = tagText(xml, tag).toIntOrNull() ?: 0

    private fun parseXml(xml: String): Document? {
        return try {
            val dbf = DocumentBuilderFactory.newInstance()
            // 不做命名空间感知：这样 getElementsByTagName 能按字面名（含 dc: 前缀）匹配
            dbf.isNamespaceAware = false
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) { }
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (e: Exception) { }
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (e: Exception) { }
            dbf.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            null
        }
    }
}
