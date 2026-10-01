package com.sonnyapp.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * 打开 HTTP 连接的抽象。
 *
 * 存在的唯一理由：Android 上相机热点没有互联网，必须把连接绑定到相机的 Network 句柄。
 * 而 Android 官方推荐的做法是句柄级的 network.openConnection(url)，
 * **不是** bindProcessToNetwork()（后者会把宿主 App 的全部 socket 都改道）。
 *
 * 所以这里注入工厂：Android 层传 network::openConnection，JVM 测试用默认实现。
 */
fun interface HttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

/**
 * Sony Camera Remote API 的 JSON-RPC 客户端。
 *
 * 协议：HTTP POST，Content-Type: application/json
 *       body = {"method":"<name>","params":[...],"id":1,"version":"1.0"}
 *       响应 = {"id":1,"result":[...]} 或 {"id":1,"error":[code,"message"]}
 *
 * **调用必须串行**：相机固件是单线程处理，并发请求会超时。由 [SonyCameraApi] 保证。
 */
class SonyJsonRpcClient(
    private val connectionFactory: HttpConnectionFactory = HttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    },
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 30000,
) {
    private val ids = AtomicInteger(0)

    /** 发送一次 JSON-RPC 调用，返回原始响应字符串（不解析、不抛 API 错误）。 */
    fun call(
        endpoint: String,
        method: String,
        params: List<Any?> = emptyList(),
        version: String = "1.0",
        readTimeoutOverrideMs: Int = 0,
    ): String {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("method", method)
            put("params", buildJsonArray { params.forEach { add(anyToJson(it)) } })
            put("id", id)
            put("version", version)
        }.toString()

        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = connectionFactory.open(URL(endpoint))
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = if (readTimeoutOverrideMs > 0) readTimeoutOverrideMs else readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Length", bytes.size.toString())

            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = if (stream == null) "" else readAll(stream)
            if (code !in 200..299 && text.isEmpty()) {
                throw SonyHttpException(code, method, endpoint)
            }
            return text
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    private fun readAll(input: InputStream): String {
        val out = ByteArrayOutputStream(4096)
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        input.close()
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun anyToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is List<*> -> buildJsonArray { value.forEach { add(anyToJson(it)) } }
        else -> JsonPrimitive(value.toString())
    }
}

/** HTTP 层面的失败（连接不上、超时、非 2xx 且无响应体）。 */
class SonyHttpException(
    val statusCode: Int,
    val method: String,
    val endpoint: String,
) : Exception("HTTP " + statusCode + " calling " + method + " @ " + endpoint)
