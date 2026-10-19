package com.sonnyapp.sync

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 极简 WebDAV 客户端：只做「建目录 + PUT」这两件归档必需的事。
 *
 * 为什么是 WebDAV 而不是 SMB：
 *  - SMB 要额外拉 jcifs-ng（约 1 MB），还要自己处理 NTLM 握手；
 *  - 飞牛 NAS / 群晖 / Nextcloud 全都自带 WebDAV，一个 PUT 就能落盘；
 *  - 纯 HTTP，走的是**默认网络**（不是相机那条），可以在连相机热点时
 *    切到移动数据上传 —— 这正好绕开「相机热点没有外网」的死结。
 *
 * 注意：这里**不做断点续传**。单张照片几 MB，弱网重传一次比实现 Range
 * 协商更划算；失败次数记在队列里，超过阈值才标记失败。
 */
class WebDavClient(
    baseUrl: String,
    user: String,
    pass: String,
) {
    private val base = baseUrl.trimEnd('/')

    private val auth: String? =
        if (user.isEmpty() && pass.isEmpty()) null
        else "Basic " + Base64.encodeToString(
            (user + ":" + pass).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 确保远程目录存在（逐级 MKCOL）。
     *
     * 405 = 已存在，201 = 创建成功，两者都算成功；
     * 409 表示父目录不存在 —— 递归到这里通常意味着账号没有写权限。
     */
    fun ensureDir(remoteDir: String): Boolean {
        val segments = remoteDir.trim('/').split('/').filter { it.isNotEmpty() }
        var path = base
        for (seg in segments) {
            path = path + "/" + encode(seg)
            val status = try {
                val c = open(path, "MKCOL")
                val s = c.responseCode
                c.disconnect()
                s
            } catch (e: Exception) {
                lastError = "MKCOL 失败：" + (e.message ?: e.toString())
                return false
            }
            if (status !in 200..299 && status != 405) {
                lastError = "建目录失败 HTTP $status（$remoteDir）"
                return false
            }
        }
        return true
    }

    /** 上传一个文件。返回 HTTP 状态码，异常时返回 -1。 */
    fun put(remotePath: String, bytes: ByteArray, mime: String): Int {
        val url = base + "/" + remotePath.trim('/').split('/').filter { it.isNotEmpty() }
            .joinToString("/") { encode(it) }
        return try {
            val c = open(url, "PUT")
            c.doOutput = true
            c.setRequestProperty("Content-Type", mime)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val status = c.responseCode
            if (status !in 200..299) {
                lastError = "上传失败 HTTP " + status + " " + readError(c)
            }
            c.disconnect()
            status
        } catch (e: Exception) {
            lastError = "上传异常：" + (e.message ?: e.toString())
            Log.w(TAG, "put failed " + url + " : " + e.message)
            -1
        }
    }

    /** 用一次 MKCOL 探测服务器是否可达且凭据有效（不写真实文件）。 */
    fun probe(): String? {
        val status = try {
            val c = open(base + "/", "PROPFIND")
            c.setRequestProperty("Depth", "0")
            val s = c.responseCode
            c.disconnect()
            s
        } catch (e: Exception) {
            return "连不上：" + (e.message ?: e.toString())
        }
        return when {
            status in 200..299 -> null
            status == 401 || status == 403 -> "认证失败 HTTP $status（检查用户名/密码）"
            status == 404 -> "路径不存在 HTTP 404（检查地址里的目录）"
            else -> "服务器返回 HTTP $status"
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        auth?.let { c.setRequestProperty("Authorization", it) }
        return c
    }

    private fun readError(c: HttpURLConnection): String = try {
        val out = ByteArrayOutputStream()
        c.errorStream?.use { s ->
            val buf = ByteArray(512)
            var n: Int
            while (true) {
                n = s.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > 400) break
            }
        }
        out.toString("UTF-8").replace("\n", " ").take(120)
    } catch (e: Exception) {
        ""
    }

    /** 路径片段编码：文件名里可能有中文或空格。 */
    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "WebDavClient"
    }
}
