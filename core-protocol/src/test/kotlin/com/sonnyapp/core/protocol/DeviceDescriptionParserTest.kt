package com.sonnyapp.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDescriptionParserTest {

    private fun dd(): String =
        javaClass.getResourceAsStream("/dd-20260930-151210.xml")!!.use { it.readBytes() }
            .toString(Charsets.UTF_8)

    @Test
    fun parsesRealDeviceDescription() {
        val d = DeviceDescriptionParser.parse(dd())

        assertEquals("ILCE-6300", d.friendlyName)
        assertEquals("SonyImagingDevice", d.modelName)
        assertEquals("Sony Corporation", d.manufacturer)
        assertEquals("uuid:000000001000-1010-8000-A2C9A09A4028", d.udn)
    }

    @Test
    fun readsServicesDespiteNamespacePrefix() {
        // 真实 DD.xml 用的是 <av:X_ScalarWebAPI_ServiceType>，
        // 按字面量匹配的正则会命中 0 条。
        val d = DeviceDescriptionParser.parse(dd())

        assertEquals(listOf("guide", "accessControl", "camera"), d.serviceTypes)
        assertTrue(d.hasService("camera"))
        assertFalse("本机型没有 system 服务", d.hasService("system"))
        assertFalse("本机型没有 avContent 服务", d.hasService("avContent"))
    }

    @Test
    fun buildsEndpointsFromDescriptionNotHardcoded() {
        val d = DeviceDescriptionParser.parse(dd())

        assertEquals("http://192.168.122.1:8080/sony", d.actionListUrl("camera"))
        assertEquals("http://192.168.122.1:8080/sony/camera", d.cameraEndpoint)
        assertEquals("http://192.168.122.1:8080/sony/guide", d.endpoint("guide"))
        assertNull("不存在的服务应返回 null", d.endpoint("avContent"))
    }

    @Test
    fun handlesEndpointWithTrailingSlash() {
        val xml = dd().replace("http://192.168.122.1:8080/sony<", "http://192.168.122.1:8080/sony/<")
        val d = DeviceDescriptionParser.parse(xml)
        assertEquals("http://192.168.122.1:8080/sony/camera", d.cameraEndpoint)
    }
}
