package com.sonnyapp.core.protocol

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.Collections

/**
 * 假相机服务器。
 *
 * **所有响应体都是从 ILCE-6300 实机抓下来的原文**（见 probe/batch*-*.log），
 * 不是编造的。这样能在没有相机的情况下验证客户端的整条状态机。
 */
class MockCameraServer(private val ddXml: String) {

    private lateinit var server: HttpServer
    var port: Int = 0
        private set

    /** 服务器实际收到的方法调用顺序（用于验证"不该调的没调"） */
    val received: MutableList<String> = Collections.synchronizedList(ArrayList())

    var recModeEntered: Boolean = false
        private set

    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/scalarwebapi_dd.xml") { ex ->
            respond(ex, 200, ddXml.toByteArray(Charsets.UTF_8), "text/xml; charset=utf-8")
        }
        server.createContext("/sony/camera") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val method = methodOf(body)
            received.add(method)
            respond(ex, 200, dispatch(method).toByteArray(Charsets.UTF_8), "application/json")
        }
        server.executor = null
        server.start()
        port = server.address.port
    }

    fun stop() {
        try { server.stop(0) } catch (e: Exception) { }
    }

    private fun methodOf(body: String): String = try {
        Json.parseToJsonElement(body).jsonObject["method"]?.jsonPrimitive?.content ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun dispatch(method: String): String = when (method) {
        "getApplicationInfo" -> APP_INFO
        "getAvailableApiList" -> if (recModeEntered) API_LIST_AFTER_REC else API_LIST_BEFORE_REC
        "getMethodTypes" -> methodTypes
        "startRecMode" -> { recModeEntered = true; OK_ZERO }
        "startLiveview" -> START_LIVEVIEW
        "stopLiveview" -> OK_ZERO
        "actTakePicture" -> TAKE_PICTURE
        "getEvent" -> EVENT
        "setExposureCompensation" -> OK_ZERO
        "setShootMode" -> OK_ZERO
        "actZoom" -> OK_ZERO
        "getAvailableLiveviewSize" -> ERR_NOT_AVAILABLE
        "setSelfTimer" -> ERR_ILLEGAL_ARGUMENT
        else -> "{\"id\":1,\"error\":[12,\"" + method + "\"]}"
    }

    private fun respond(ex: HttpExchange, code: Int, body: ByteArray, contentType: String) {
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    var methodTypes: String = "{\"id\":1,\"results\":[]}"

    companion object {
        // ---- 以下全部是实机原文 ----

        const val APP_INFO = "{\"id\":1,\"result\":[\"Smart Remote Control SR/3.31 __SAK__\",\"2.0.1\"]}"

        const val API_LIST_BEFORE_REC =
            "{\"id\":1,\"result\":[[\"getVersions\",\"getMethodTypes\",\"getApplicationInfo\"," +
                "\"getAvailableApiList\",\"getEvent\",\"startRecMode\",\"stopRecMode\"]]}"

        const val API_LIST_AFTER_REC =
            "{\"id\":1,\"result\":[[\"getVersions\",\"getMethodTypes\",\"getApplicationInfo\"," +
                "\"getAvailableApiList\",\"getEvent\",\"actTakePicture\",\"stopRecMode\"," +
                "\"startLiveview\",\"stopLiveview\",\"actZoom\",\"awaitTakePicture\",\"setSelfTimer\"," +
                "\"getSelfTimer\",\"getAvailableSelfTimer\",\"getSupportedSelfTimer\"," +
                "\"setExposureCompensation\",\"getExposureCompensation\"," +
                "\"getAvailableExposureCompensation\",\"getSupportedExposureCompensation\"," +
                "\"setShootMode\",\"getShootMode\",\"getAvailableShootMode\"," +
                "\"getSupportedShootMode\",\"getSupportedFlashMode\"]]}"

        const val OK_ZERO = "{\"id\":1,\"result\":[0]}"

        const val START_LIVEVIEW =
            "{\"id\":1,\"result\":[\"http://192.168.122.1:8080/liveview/liveviewstream\"]}"

        const val TAKE_PICTURE =
            "{\"id\":1,\"result\":[[\"http://192.168.122.1:8080/postview/pict20260930_151936_0.JPG\"]]}"

        const val EVENT =
            "{\"id\":1,\"result\":[" +
                "{\"type\":\"availableApiList\",\"names\":[\"getEvent\",\"startLiveview\",\"actTakePicture\"]}," +
                "{\"type\":\"cameraStatus\",\"cameraStatus\":\"IDLE\"}," +
                "{\"type\":\"liveviewStatus\",\"liveviewStatus\":true}," +
                "{\"type\":\"postviewImageSize\",\"postviewImageSizeCandidates\":[\"Original\",\"2M\"]," +
                "\"currentPostviewImageSize\":\"2M\"}," +
                "{\"type\":\"exposureCompensation\",\"minExposureCompensation\":-15," +
                "\"stepIndexOfExposureCompensation\":1,\"maxExposureCompensation\":15," +
                "\"currentExposureCompensation\":0}," +
                "{\"type\":\"fNumber\",\"fNumberCandidates\":[\"3.5\",\"5.0\"],\"currentFNumber\":\"5.0\"}" +
                "]}"

        const val ERR_NOT_AVAILABLE = "{\"id\":1,\"error\":[1,\"Not Available Now\"]}"
        const val ERR_ILLEGAL_ARGUMENT = "{\"id\":1,\"error\":[3,\"illegal argument\"]}"
    }
}
