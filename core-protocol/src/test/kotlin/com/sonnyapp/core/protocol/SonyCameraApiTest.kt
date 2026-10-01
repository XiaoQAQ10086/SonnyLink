package com.sonnyapp.core.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection

/**
 * 端到端验证：客户端 + mock 相机服务器。
 *
 * 服务器回放的是 ILCE-6300 实机抓下来的响应原文，
 * 所以这条测试实际检验的是"我们的客户端能不能正确地跟真机对话"。
 */
class SonyCameraApiTest {

    private lateinit var server: MockCameraServer
    private lateinit var api: SonyCameraApi

    private fun resource(name: String): String =
        javaClass.getResourceAsStream(name)!!.use { it.readBytes() }.toString(Charsets.UTF_8)

    @Before
    fun setUp() {
        val rawDd = resource("/dd-20260930-151210.xml")
        server = MockCameraServer(rawDd)
        server.methodTypes = resource("/methodtypes-20260930-151608.json")
        server.start()

        // 把 DD.xml 里的真实 IP:端口 换成 mock 服务器 —— 顺便证明 endpoint 确实来自 DD.xml
        val patched = rawDd.replace("192.168.122.1:8080", "127.0.0.1:" + server.port)
        val desc = DeviceDescriptionParser.parse(patched)

        val client = SonyJsonRpcClient(connectionFactory = HttpConnectionFactory { u -> u.openConnection() as HttpURLConnection })
        api = SonyCameraApi(client, desc, sleeper = { })
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun readsServerIdentityAndPassesVersionGate() {
        val info = api.getApplicationInfo()
        assertEquals("Smart Remote Control SR/3.31 __SAK__", info.first)
        assertEquals("2.0.1", info.second)
        assertTrue("2.0.1 >= 2.0.0", api.isServerVersionSupported)
    }

    @Test
    fun capabilitiesGrowAfterEnteringRemoteMode() {
        api.getApplicationInfo()
        api.refreshCapabilities()

        assertEquals("未进遥控模式时应只有 7 个方法", 7, api.capabilities.available.size)
        assertFalse("此时不该有取景", api.capabilities.canLiveview)
        assertFalse(api.capabilities.canTakePicture)

        assertTrue("startRecMode 应被调用", api.startRecMode())

        assertEquals("进遥控模式后应变成 24 个方法", 24, api.capabilities.available.size)
        assertTrue(api.capabilities.canLiveview)
        assertTrue(api.capabilities.canTakePicture)
        assertTrue(api.capabilities.canSetExposureCompensation)
        assertTrue(api.capabilities.canZoom)
        assertFalse("本模式下没有 ISO setter", api.capabilities.canSetIso)
        assertFalse(api.capabilities.canSetShutterSpeed)
        assertFalse(api.capabilities.canSetAperture)
    }

    @Test
    fun firmwareSupportsMoreThanItCurrentlyExposes() {
        api.getApplicationInfo()
        api.refreshMethodTypes()
        api.refreshCapabilities()
        api.startRecMode()

        // 固件知道 ISO setter，但此刻不可用 —— 这正是"读能力 vs 写能力"的核心
        assertTrue("固件应声明支持 setIsoSpeedRate", api.capabilities.supportsEver("setIsoSpeedRate"))
        assertFalse("但当前状态下不可用", api.capabilities.can("setIsoSpeedRate"))
        assertFalse(api.capabilities.canSetIso)
        assertTrue("固件方法数应远多于当前可用", api.capabilities.supported.size > api.capabilities.available.size)
    }

    @Test
    fun startLiveviewReturnsStreamUrl() {
        api.getApplicationInfo()
        api.refreshCapabilities()
        api.startRecMode()

        val url = api.startLiveview()
        assertTrue("应返回取景流地址", url.endsWith("/liveview/liveviewstream"))
        assertTrue("应是 http URL", url.startsWith("http://"))
    }

    @Test
    fun notAvailableNowIsSurfaced() {
        api.getApplicationInfo()
        api.refreshCapabilities()

        val ex = try {
            api.tryCall("getAvailableLiveviewSize", emptyList())
            null
        } catch (e: SonyApiException) { e }

        // getAvailableLiveviewSize 不在能力列表里，所以 tryCall 会直接返回 null 不调用
        assertNull("能力门禁应拦住它", ex)
        assertFalse(server.received.contains("getAvailableLiveviewSize"))

        // 直接调则必须把 Not Available Now 抛出来
        val ex2 = try {
            SonyJson.throwIfError(
                SonyJsonRpcClient(connectionFactory = HttpConnectionFactory { u -> u.openConnection() as HttpURLConnection })
                    .call(api.endpoint, "getAvailableLiveviewSize"),
                "getAvailableLiveviewSize"
            )
            null
        } catch (e: SonyApiException) { e }
        assertNotNull(ex2)
        assertTrue(ex2!!.isNotAvailableNow)
    }

    @Test
    fun capabilityGateBlocksUnavailableSetters() {
        api.getApplicationInfo()
        api.refreshMethodTypes()
        api.refreshCapabilities()
        api.startRecMode()

        // ISO 写不了 —— 而且必须**根本没发出去**
        val r = api.tryCall("setIsoSpeedRate", listOf("400"))
        assertNull(r)
        assertFalse("不该向相机发这个请求", server.received.contains("setIsoSpeedRate"))

        // 曝光补偿写得了，会真的发出去
        api.tryCall("setExposureCompensation", listOf(0))
        assertTrue(server.received.contains("setExposureCompensation"))
    }

    @Test
    fun illegalArgumentIsDistinctFromNotAvailable() {
        api.getApplicationInfo()
        api.refreshCapabilities()
        api.startRecMode()

        assertTrue("setSelfTimer 当前可用", api.capabilities.can("setSelfTimer"))

        val ex = try {
            api.tryCall("setSelfTimer", listOf("2"))
            null
        } catch (e: SonyApiException) { e }

        assertNotNull(ex)
        assertTrue("应为非法参数", ex!!.isIllegalArgument)
        assertFalse("不应被当成不可用", ex.isNotAvailableNow)
    }

    @Test
    fun unknownMethodRaisesMethodNotFound() {
        api.getApplicationInfo()
        val ex = try {
            SonyJson.throwIfError(
                SonyJsonRpcClient(connectionFactory = HttpConnectionFactory { u -> u.openConnection() as HttpURLConnection })
                    .call(api.endpoint, "getNonexistentMethodXyz"),
                "getNonexistentMethodXyz"
            )
            null
        } catch (e: SonyApiException) { e }
        assertNotNull(ex)
        assertTrue(ex!!.isMethodNotFound)
    }

    @Test
    fun actTakePictureReturnsPostviewUrl() {
        api.getApplicationInfo()
        api.refreshCapabilities()
        api.startRecMode()

        val url = api.actTakePicture()
        assertNotNull(url)
        assertTrue(url!!.contains("/postview/"))
        assertTrue(url.endsWith(".JPG"))
        // 实机返回的是嵌套数组 [["url"]]，必须正确剥开
        assertFalse("不该残留引号或方括号", url.contains("[") || url.contains("]") || url.contains("\""))
    }

    @Test
    fun eventCarriesReadOnlyParameters() {
        api.getApplicationInfo()
        val raw = api.getEvent(false)

        // 即使 setFNumber / getAvailableFNumber 全部不可用，
        // getEvent 里依然能读到 光圈 / 曝光补偿 —— 这就是状态栏的数据来源
        assertTrue(raw.contains("\"fNumber\""))
        assertTrue(raw.contains("\"5.0\""))
        assertTrue(raw.contains("currentExposureCompensation"))
        assertTrue(raw.contains("liveviewStatus"))
    }

    @Test
    fun fullSequenceHitsCameraInDocumentedOrder() {
        api.getApplicationInfo()
        api.refreshCapabilities()
        api.startRecMode()
        api.startLiveview()

        // startRecMode / startLiveview 结束后客户端都会自动刷新一次能力，
        // 所以 getAvailableApiList 会出现三次 —— 这正是"每次状态切换后重查"的纪律
        assertEquals(
            listOf(
                "getApplicationInfo",
                "getAvailableApiList",
                "startRecMode",
                "getAvailableApiList",
                "startLiveview",
                "getAvailableApiList",
            ),
            server.received.toList()
        )
    }
}
