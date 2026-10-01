package com.sonnyapp.camera

import android.net.Network
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * 绑定到相机 Network 的极简 HTTP 客户端。
 *
 * 为什么不用 HttpURLConnection：
 *  - 取景流是**无限长**的 chunked 响应，需要完全控制缓冲与读取节奏；
 *  - 我们要拿到的就是**线上原始字节**（含 chunked 分帧），
 *    这样 core-liveview 解码器可以跑在已验证过的 chunked=true 模式上。
 */
object NetHttp {

    private const val TAG = "NetHttp"

    /** 最近一次失败的详细原因，供上层拼出可读的错误信息。 */
    @Volatile
    var lastError: String? = null
        private set

    class Stream(val socket: Socket, val input: InputStream, val responseHeaders: String) {
        fun close() {
            try { input.close() } catch (e: Exception) { }
            try { socket.close() } catch (e: Exception) { }
        }
    }

    /** GET 一个短响应，返回完整 body（用于 DD.xml）。 */
    fun getString(network: Network, url: String, connectTimeoutMs: Int = 8000, readTimeoutMs: Int = 10000): String? {
        val s = open(network, url, null, connectTimeoutMs, readTimeoutMs) ?: return null
        return try {
            val out = ByteArrayOutputStream(8192)
            val buf = ByteArray(8192)
            while (true) {
                val n = s.input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            lastError = (e.javaClass.simpleName + ": " + (e.message ?: ""))
            Log.w(TAG, "getString failed: " + e.message)
            null
        } finally {
            s.close()
        }
    }

    /**
     * 打开一条 HTTP GET 流并**读掉响应头**，返回可以直接读 body 的输入流。
     * body 是原始的（未解 chunked）字节。
     */
    fun open(
        network: Network,
        url: String,
        accept: String?,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 0,
    ): Stream? {
        return try {
            val u = URL(url)
            val port = if (u.port > 0) u.port else 80
            val path = if (u.file.isNullOrEmpty()) "/" else u.file

            val socket = network.socketFactory.createSocket()
            socket.tcpNoDelay = true
            socket.soTimeout = if (readTimeoutMs > 0) readTimeoutMs else 0
            socket.connect(InetSocketAddress(u.host, port), connectTimeoutMs)

            val req = StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(u.host).append(':').append(port).append("\r\n")
                .append("User-Agent: SonnyApp/1.0\r\n")
            if (accept != null) req.append("Accept: ").append(accept).append("\r\n")
            req.append("\r\n")

            socket.getOutputStream().apply {
                write(req.toString().toByteArray(Charsets.US_ASCII))
                flush()
            }

            val input = socket.getInputStream()
            val header = readHeaders(input)
            if (header == null) {
                socket.close()
                return null
            }
            lastError = null
            Stream(socket, input, header)
        } catch (e: Exception) {
            lastError = (e.javaClass.simpleName + ": " + (e.message ?: ""))
            Log.w(TAG, "open failed " + url + " : " + e.message)
            null
        }
    }

    private fun readHeaders(input: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
            if (last4 == 0x0D0A0D0A) break
            if (sb.length > 8192) break
        }
        return sb.toString()
    }
}
