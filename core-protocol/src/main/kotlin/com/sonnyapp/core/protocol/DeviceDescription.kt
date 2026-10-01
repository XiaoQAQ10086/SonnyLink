package com.sonnyapp.core.protocol

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** DD.xml 里的一个 ScalarWebAPI 服务。 */
class SonyService(val type: String, val actionListUrl: String) {
    override fun toString(): String = type + " -> " + actionListUrl
}

/**
 * 相机通过 SSDP 的 LOCATION 返回的 UPnP 设备描述（DD.xml）。
 *
 * 实机样本（ILCE-6300）：
 *   friendlyName = ILCE-6300
 *   modelName    = SonyImagingDevice
 *   服务列表     = guide / accessControl / camera，**没有 system、没有 avContent**
 *   三个服务的 ActionList_URL 都指向 http://192.168.122.1:8080/sony
 *
 * 端口/路径/文件名一律来自这里，**绝不硬编码**：
 *   实机 DD.xml 在 61000 端口，控制端点在 8080；
 *   公开案例里还出现过 64321 / 10000 / 60152，DD 文件名有
 *   scalarwebapi_dd.xml / DmsRmtDesc.xml / dd.xml 三种。
 */
class DeviceDescription(
    val friendlyName: String,
    val modelName: String,
    val udn: String,
    val manufacturer: String,
    val services: List<SonyService>,
    val liveViewUrl: String?,
) {
    val serviceTypes: List<String> get() = services.map { it.type }

    fun hasService(type: String): Boolean = services.any { it.type == type }

    fun actionListUrl(type: String): String? =
        services.firstOrNull { it.type == type }?.actionListUrl?.takeIf { it.isNotEmpty() }

    /** 某个服务类型的 JSON-RPC endpoint = ActionList_URL + "/" + ServiceType */
    fun endpoint(type: String): String? {
        val base = actionListUrl(type) ?: return null
        val b = if (base.endsWith("/")) base.dropLast(1) else base
        return b + "/" + type
    }

    /** 相机端点（所有操作的主入口） */
    val cameraEndpoint: String? get() = endpoint("camera")

    override fun toString(): String =
        "DeviceDescription(" + friendlyName + ", services=" + serviceTypes + ")"
}

/**
 * DD.xml 解析器。
 *
 * 注意：**必须按命名空间无关的方式取元素**。
 * 真实 DD.xml 里的字段带 av: 前缀（<av:X_ScalarWebAPI_ServiceType>），
 * 直接写 <X_ScalarWebAPI_ServiceType> 的正则会命中 0 条。
 */
object DeviceDescriptionParser {

    fun parse(xml: String): DeviceDescription {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true
        // XXE 防护；部分实现不支持该特性，失败时忽略
        try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (e: Exception) { }
        try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (e: Exception) { }
        try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (e: Exception) { }

        val doc = dbf.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement

        val services = ArrayList<SonyService>()
        for (se in descendants(root, "X_ScalarWebAPI_Service")) {
            val type = firstText(se, "X_ScalarWebAPI_ServiceType")
            if (type.isNullOrEmpty()) continue
            val url = firstText(se, "X_ScalarWebAPI_ActionList_URL") ?: ""
            services.add(SonyService(type, url))
        }

        return DeviceDescription(
            friendlyName = firstText(root, "friendlyName") ?: "",
            modelName = firstText(root, "modelName") ?: "",
            udn = firstText(root, "UDN") ?: "",
            manufacturer = firstText(root, "manufacturer") ?: "",
            services = services,
            liveViewUrl = firstText(root, "X_ScalarWebAPI_LiveView_URL"),
        )
    }

    fun parse(xml: ByteArray): DeviceDescription = parse(String(xml, Charsets.UTF_8))

    private fun descendants(root: Element, localName: String): List<Element> {
        val list = root.getElementsByTagNameNS("*", localName)
        val out = ArrayList<Element>(list.length)
        for (i in 0 until list.length) out.add(list.item(i) as Element)
        return out
    }

    private fun firstText(root: Element, localName: String): String? {
        val list = root.getElementsByTagNameNS("*", localName)
        if (list.length == 0) return null
        val t = list.item(0).textContent
        return t?.trim()
    }
}
