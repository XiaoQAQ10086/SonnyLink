package com.sonnyapp.camera

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * SSDP 发现索尼相机。
 *
 * 发 M-SEARCH 到 239.255.255.250:1900，ST = urn:schemas-sony-com:service:ScalarWebAPI:1，
 * 从响应里取 LOCATION（DD.xml 地址）。
 *
 * 实机 ILCE-6300 返回：http://192.168.122.1:61000/scalarwebapi_dd.xml
 * 端口随机型/固件变化（公开案例有 64321 / 8080 / 10000），所以必须靠发现，不能写死。
 */
object SsdpDiscovery {

    private const val TAG = "SsdpDiscovery"
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ST = "urn:schemas-sony-com:service:ScalarWebAPI:1"

    private fun searchMessage(): ByteArray {
        val crlf = "\r\n"
        val msg = "M-SEARCH * HTTP/1.1" + crlf +
            "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + crlf +
            "MAN: \"ssdp:discover\"" + crlf +
            "MX: 2" + crlf +
            "ST: " + ST + crlf + crlf
        return msg.toByteArray(Charsets.US_ASCII)
    }

    suspend fun discover(
        network: Network,
        localAddress: InetAddress? = null,
        timeoutMs: Int = 6000,
    ): String? =
        withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.reuseAddress = true
                socket.broadcast = true
                socket.soTimeout = 1500

                // 关键：把 socket 绑定到相机的 Network 句柄，否则组播会从别的网卡出去
                val bound = try {
                    network.bindSocket(socket)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "network.bindSocket(DatagramSocket) failed: " + e.message)
                    false
                }
                if (!bound) {
                    val local = localAddress
                    if (local != null) {
                        socket.close()
                        socket = DatagramSocket(null)
                        socket.reuseAddress = true
                        socket.broadcast = true
                        socket.soTimeout = 1500
                        socket.bind(InetSocketAddress(local, 0))
                        Log.i(TAG, "fallback: bound to local address " + local)
                    }
                }

                val group = InetAddress.getByName(SSDP_ADDR)
                val payload = searchMessage()
                repeat(3) {
                    socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
                    Thread.sleep(300)
                }

                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (e: Exception) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                    val location = parseLocation(text)
                    if (!location.isNullOrEmpty()) {
                        Log.i(TAG, "LOCATION = " + location)
                        return@withContext location
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "discover failed: " + e.message)
                null
            } finally {
                try { socket?.close() } catch (e: Exception) { }
            }
        }

    fun parseLocation(response: String): String? {
        for (rawLine in response.split("\n")) {
            val line = rawLine.trim()
            if (line.length > 9 && line.regionMatches(0, "LOCATION:", 0, 9, ignoreCase = true)) {
                return line.substring(9).trim()
            }
        }
        return null
    }

}
