package com.sonnyapp.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 相机热点连接。
 *
 * 核心问题：**相机热点没有互联网**。Android 会把默认路由留在蜂窝网络上，
 * 发往 192.168.122.1 的请求会走错出口并超时。
 *
 * 解决办法（Android 官方推荐）：
 *   用 WifiNetworkSpecifier 主动请求这个 Wi-Fi，拿到 Network 句柄，
 *   之后**所有** socket/HTTP 都从这个句柄创建 —— 也就是所谓的"句柄级绑定"。
 *
 * 刻意**不用** bindProcessToNetwork()：它会把宿主 App 的全部 socket 都改道。
 */
/**
 * 当前 Wi-Fi 的状态快照，用于连接页显示与自动判断。
 *
 * @param connected        是否连在某个 Wi-Fi 上
 * @param ssid             SSID；没有权限时为 null（Android 10+ 会抹掉）
 * @param looksLikeCamera  看起来是不是相机热点
 */
data class WifiState(
    val connected: Boolean = false,
    val ssid: String? = null,
    val looksLikeCamera: Boolean = false,
)

class CameraNetwork(private val context: Context) {

    private val cm: ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val wifi: WifiManager =
        context.applicationContext.getSystemService(WifiManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    var network: Network? = null
        private set

    /**
     * 当前连着的 Wi-Fi 的 SSID。读不到返回 null。
     *
     * Android 10 起 SSID 会被抹掉，需要 NEARBY_WIFI_DEVICES（13+）或定位权限。
     * 读不到不影响使用 —— 下面还有「有没有互联网」这条不需要权限的判据。
     */
    fun currentSsid(): String? {
        // 1) 走 ConnectivityManager —— 不依赖被限制的 WifiManager.connectionInfo
        try {
            val n = findSystemWifi() ?: return null
            val caps = cm.getNetworkCapabilities(n) ?: return null
            val info = caps.transportInfo as? WifiInfo
            val s = info?.ssid
            if (!s.isNullOrBlank() && s != UNKNOWN_SSID && s != "0x") return s.trim('"')
        } catch (e: Exception) {
            // 继续尝试下一条路
        }
        // 2) 退回 WifiManager
        try {
            val s = wifi.connectionInfo?.ssid
            if (!s.isNullOrBlank() && s != UNKNOWN_SSID && s != "0x") return s.trim('"')
        } catch (e: Exception) {
            // 读不到就算了
        }
        return null
    }

    /**
     * 当前是否连在某个 Wi-Fi 上。
     *
     * **不要用 WifiManager.connectionInfo 判断** —— Android 10+ 缺定位权限时
     * 其返回的 WifiInfo.networkId 为 -1，会把已连接误判为未连接。
     * 而且这个错误会让 looksLikeCameraWifi 里的 if (!hasWifi()) return false 直接短路，
     * 后面那条真正有用的判据根本没机会执行。
     */
    fun hasWifi(): Boolean = findSystemWifi() != null

    /** 当前 Wi-Fi 是否被系统验证过能真正上网。相机热点永远是 false。 */
    fun wifiHasInternet(): Boolean = try {
        val n = findSystemWifi() ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        false
    }

    /**
     * 当前 Wi-Fi 看起来是不是相机热点。
     *
     * 两条判据，任一成立即可：
     *  1. SSID 以 DIRECT- 开头（索尼相机热点的命名约定）
     *  2. **这个 Wi-Fi 没有互联网** —— 相机热点绝不提供上网，
     *     这是不需要任何权限就能拿到的强信号
     */
    fun looksLikeCameraWifi(): Boolean {
        // 判据 1：SSID 命名（最可靠）
        val ssid = currentSsid()
        if (ssid != null) {
            val up = ssid.uppercase()
            if (up.startsWith("DIRECT-") || up.contains("ILCE") || up.contains("SONY")) return true
        }
        // 判据 2：连着一个"没被验证能上网"的 Wi-Fi —— 相机热点必然如此。
        // 这里不要先判断 hasWifi，否则一旦判断本身出错就永远不会走到这。
        val n = findSystemWifi() ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 一次性取当前 Wi-Fi 状态。 */
    fun wifiState(): WifiState = WifiState(
        connected = hasWifi(),
        ssid = currentSsid(),
        looksLikeCamera = looksLikeCameraWifi(),
    )

    /**
     * 找系统当前已连上的 Wi-Fi 网络。
     *
     * 优先返回「**没有互联网**的 Wi-Fi」—— 相机热点正是这个特征
     * （连上后系统会提示"此网络无法访问互联网"，但连接是有效的）。
     */
    fun findSystemWifi(): Network? {
        return try {
            var fallback: Network? = null
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (fallback == null) fallback = n
                // 判据必须是 VALIDATED 而不是 INTERNET。
                // 实测相机热点的能力位是 NOT_METERED&INTERNET&NOT_RESTRICTED&TRUSTED…，
                // **带着 INTERNET 但没有 VALIDATED** —— 用 INTERNET 判断永远匹配不到相机。
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return n
                }
            }
            fallback
        } catch (e: Exception) {
            Log.w(TAG, "枚举网络失败: " + e.message)
            null
        }
    }

    /**
     * 直接采用系统已连的 Wi-Fi，**不弹系统对话框**。
     *
     * 用于「已在系统设置中连好相机热点」这条路径。
     * 拿到的 Network 句柄同样用于所有 socket —— 保持句柄级绑定，
     * 不去动进程的默认网络。
     */
    fun adoptSystemWifi(): Network? {
        val n = findSystemWifi() ?: return null
        network = n
        acquireMulticastLock()
        Log.i(TAG, "adopted system wifi: " + n)
        return n
    }

    /** 连接相机热点。会弹出系统对话框让用户确认（Android 的既定行为）。 */
    suspend fun connect(ssid: String, passphrase: String?, timeoutMs: Int = 60000): Network =
        suspendCancellableCoroutine { cont ->
            releaseNetwork()
            val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (!passphrase.isNullOrEmpty()) {
                specBuilder.setWpa2Passphrase(passphrase)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specBuilder.build())
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) {
                    Log.i(TAG, "camera network available: " + n)
                    network = n
                    if (cont.isActive) cont.resume(n)
                }

                override fun onUnavailable() {
                    Log.w(TAG, "camera network unavailable (user cancelled?)")
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("未能连接相机热点（被取消或超时）"))
                    }
                }

                override fun onLost(n: Network) {
                    Log.w(TAG, "camera network lost: " + n)
                    if (network == n) network = null
                }
            }
            callback = cb
            cm.requestNetwork(request, cb, timeoutMs)
        }

    /** SSDP 组播接收需要拿 MulticastLock，否则 Wi-Fi 固件会丢弃组播包。 */
    fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val lock = wifi.createMulticastLock(MULTICAST_TAG)
            lock.setReferenceCounted(true)
            lock.acquire()
            multicastLock = lock
            Log.i(TAG, "multicast lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "multicast lock failed: " + e.message)
        }
    }

    fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }

    /** 相机 IP = 这个网络的网关（实测 ILCE-6300 = 192.168.122.1）。 */
    fun gatewayAddress(): InetAddress? {
        val n = network ?: return null
        return try {
            val lp = cm.getLinkProperties(n) ?: return null
            // 相机热点的默认网关就是相机本身（实测 192.168.122.1）
            lp.routes.firstOrNull { it.isDefaultRoute }?.gateway
                ?: lp.routes.firstOrNull { it.gateway != null }?.gateway
        } catch (e: Exception) {
            null
        }
    }

    /** 本机在这个网络上的 IPv4 地址。 */
    fun localAddress(): InetAddress? {
        val n = network ?: return null
        return try {
            val lp = cm.getLinkProperties(n) ?: return null
            lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }
        } catch (e: Exception) {
            null
        }
    }

    fun release() {
        releaseMulticastLock()
        releaseNetwork()
    }

    private fun releaseNetwork() {
        val cb = callback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // ignore
        }
        callback = null
    }

    private fun intToInet(value: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
    )

    companion object {
        private const val TAG = "CameraNetwork"
        private const val MULTICAST_TAG = "sonnyapp-ssdp"
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
