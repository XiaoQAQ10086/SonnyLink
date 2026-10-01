package com.sonnyapp.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyJsonTest {

    private fun methodTypesJson(): String =
        javaClass.getResourceAsStream("/methodtypes-20260930-151608.json")!!.use { it.readBytes() }
            .toString(Charsets.UTF_8)

    @Test
    fun parsesRealMethodTypes() {
        val list = SonyJson.parseMethodTypes(methodTypesJson())

        // 实机 getMethodTypes 返回 67 个方法
        assertEquals(67, list.size)

        // 用正则提取会把返回类型（string / int / bool / double）误当成方法名，这里必须没有
        val names = list.map { it.name }.toSet()
        assertFalse("不应把基本类型当成方法", names.contains("string"))
        assertFalse(names.contains("int"))
        assertFalse(names.contains("bool"))
        assertFalse(names.contains("double"))
    }

    @Test
    fun parsesRepresentativeSignatures() {
        val map = SonyJson.parseMethodTypes(methodTypesJson()).associateBy { it.name }

        // 无参、返回 string*
        val take = map["actTakePicture"]
        assertNotNull(take)
        assertEquals(emptyList<String>(), take!!.params)
        assertEquals(listOf("string*"), take.returns)

        // 两个 String 参数、返回 int
        val zoom = map["actZoom"]
        assertNotNull(zoom)
        assertEquals(listOf("string", "string"), zoom!!.params)
        assertEquals(listOf("int"), zoom.returns)

        // 布尔参数（长轮询）
        val ev = map["getEvent"]
        assertNotNull(ev)
        assertEquals(listOf("bool"), ev!!.params)

        assertNotNull(map["setIsoSpeedRate"])
        assertNotNull(map["setShutterSpeed"])
        assertNotNull(map["startMovieRec"])
    }

    @Test
    fun parsesApiListsFromRealFixtures() {
        val before = javaClass.getResourceAsStream("/apilist-before.json")!!.use { it.readBytes() }
            .toString(Charsets.UTF_8)
        val after = javaClass.getResourceAsStream("/apilist-after.json")!!.use { it.readBytes() }
            .toString(Charsets.UTF_8)

        val b = SonyJson.parseApiList(before)
        assertEquals(7, b.size)
        assertTrue(b.contains("startRecMode"))
        assertFalse(b.contains("startLiveview"))

        val a = SonyJson.parseApiList(after)
        assertTrue("startRecMode 后应出现 startLiveview", a.contains("startLiveview"))
        assertTrue(a.size > b.size)
    }

    @Test
    fun parsesApplicationInfoAndVersionGate() {
        val info = SonyJson.parseApplicationInfo(MockCameraServer.APP_INFO)
        assertNotNull(info)
        assertEquals("Smart Remote Control SR/3.31 __SAK__", info!!.first)
        assertEquals("2.0.1", info.second)

        assertTrue(SonyCameraApi.compareVersions("2.0.1", "2.0.0") >= 0)
        assertTrue(SonyCameraApi.compareVersions("2.0.0", "2.0.0") == 0)
        assertTrue(SonyCameraApi.compareVersions("1.9.9", "2.0.0") < 0)
        assertTrue(SonyCameraApi.compareVersions("2.1", "2.0.0") > 0)
    }

    @Test
    fun distinguishesThreeErrorCodes() {
        val notAvail = try {
            SonyJson.throwIfError(MockCameraServer.ERR_NOT_AVAILABLE, "getAvailableLiveviewSize")
            null
        } catch (e: SonyApiException) { e }

        val illegal = try {
            SonyJson.throwIfError(MockCameraServer.ERR_ILLEGAL_ARGUMENT, "setSelfTimer")
            null
        } catch (e: SonyApiException) { e }

        val missing = try {
            SonyJson.throwIfError("{\"id\":1,\"error\":[12,\"getNonexistentMethodXyz\"]}", "getNonexistentMethodXyz")
            null
        } catch (e: SonyApiException) { e }

        assertNotNull(notAvail)
        assertTrue(notAvail!!.isNotAvailableNow)
        assertFalse(notAvail.isIllegalArgument)

        assertNotNull(illegal)
        assertTrue("setSelfTimer 的参数错是非法参数，不是不可用", illegal!!.isIllegalArgument)
        assertFalse(illegal.isNotAvailableNow)

        assertNotNull(missing)
        assertTrue(missing!!.isMethodNotFound)
    }
}
