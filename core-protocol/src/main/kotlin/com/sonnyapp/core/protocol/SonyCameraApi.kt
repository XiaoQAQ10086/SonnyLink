package com.sonnyapp.core.protocol

/**
 * Sony 相机的高层 API。
 *
 * 把实机验证过的调用纪律固化在这里：
 *
 *  1. **版本闸门**：getApplicationInfo 的服务端版本必须 >= 2.0.0。
 *  2. **startRecMode 后必须等一下再重新查能力**：实机从 7 个方法扩到 23 个。
 *  3. **每次状态切换后都要刷新能力**。
 *  4. 取景中直接拍照可行（实机 2485 ms，流不中断），不需要 stopLiveview。
 *  5. 任何调用失败都按能力问题降级，不弹错误。
 */
class SonyCameraApi(
    private val client: SonyJsonRpcClient,
    val description: DeviceDescription,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    val endpoint: String = description.cameraEndpoint
        ?: throw IllegalArgumentException("DD.xml 里没有 camera 服务: " + description)

    var serverName: String = ""
        private set
    var serverVersion: String = ""
        private set
    var capabilities: CameraCapabilities = CameraCapabilities()
        private set

    /** 最近一次 getEvent 的原始内容，供调试面板使用。 */
    var lastEventRaw: String? = null
        private set

    val isServerVersionSupported: Boolean
        get() = compareVersions(serverVersion, MIN_SERVER_VERSION) >= 0

    // ---------------------------------------------------------------- 基础

    fun getApplicationInfo(): Pair<String, String> {
        val raw = client.call(endpoint, "getApplicationInfo")
        val info = SonyJson.parseApplicationInfo(raw)
            ?: throw IllegalStateException("getApplicationInfo 返回异常: " + raw.take(200))
        serverName = info.first
        serverVersion = info.second
        return info
    }

    fun getAvailableApiList(): List<String> {
        val raw = client.call(endpoint, "getAvailableApiList")
        return SonyJson.parseApiList(raw)
    }

    fun getMethodTypes(version: String = "1.0"): List<SonyMethodSignature> {
        val raw = client.call(endpoint, "getMethodTypes", listOf(version))
        return SonyJson.parseMethodTypes(raw)
    }

    /** 拉取"固件支持的全部方法"，与当前状态无关。 */
    fun refreshMethodTypes(): CameraCapabilities {
        capabilities = capabilities.withMethodTypes(getMethodTypes())
        return capabilities
    }

    /** 拉取"此刻可用的方法"。每次状态变化后都要调。 */
    fun refreshCapabilities(): CameraCapabilities {
        capabilities = capabilities.withAvailable(getAvailableApiList())
        return capabilities
    }

    // ------------------------------------------------------------ 状态机

    /** 进入遥控拍摄模式。返回后能力集已刷新。 */
    fun startRecMode(): Boolean {
        if (!capabilities.can("startRecMode")) {
            refreshCapabilities()
            return false
        }
        client.call(endpoint, "startRecMode", emptyList(), "1.0", 30000)
        sleeper(REC_MODE_SETTLE_MS)
        refreshCapabilities()
        return true
    }

    /**
     * 开始取景，返回 liveview 流 URL。
     * 只用 startLiveview —— 实机 a6300 的 startLiveviewWithSize 返回 Not Available Now。
     */
    fun startLiveview(): String {
        val raw = client.call(endpoint, "startLiveview", emptyList(), "1.0", 30000)
        SonyJson.throwIfError(raw, "startLiveview")
        val arr = SonyJson.extractResultArrayOrNull(raw)
        val url = arr?.firstOrNull()?.toString()?.trim('"')
        if (url.isNullOrEmpty()) throw IllegalStateException("startLiveview 没有返回 URL: " + raw.take(200))
        refreshCapabilities()
        return url
    }

    fun stopLiveview() {
        client.call(endpoint, "stopLiveview", emptyList(), "1.0", 60000)
        refreshCapabilities()
    }

    fun stopRecMode() {
        client.call(endpoint, "stopRecMode")
        refreshCapabilities()
    }

    // -------------------------------------------------------------- 拍摄

    /**
     * 拍照。**不停止取景** —— 实机验证可行且更快。
     * @return postview 图片 URL（2M；a6300 实测 1616x1080 / 407 KB）。
     */
    fun actTakePicture(): String? {
        val raw = client.call(endpoint, "actTakePicture", emptyList(), "1.0", 60000)
        SonyJson.throwIfError(raw, "actTakePicture")
        val arr = SonyJson.extractResultArrayOrNull(raw) ?: return null
        val inner = arr.firstOrNull() ?: return null
        val url = inner.toString().trim('[', ']', '"')
        return url.ifEmpty { null }
    }

    // -------------------------------------------------------------- 事件

    /**
     * 取事件。longPoll=true 时相机会 hold 住连接直到状态变化。
     * 取景进行中建议用 false（实测往返 384 ms），避免和其他命令抢相机。
     */
    fun getEvent(longPoll: Boolean = false, readTimeoutMs: Int = 0): String {
        val raw = client.call(endpoint, "getEvent", listOf(longPoll), "1.0", readTimeoutMs)
        lastEventRaw = raw
        return raw
    }

    fun isLiveviewReady(): Boolean {
        val raw = try { getEvent(false) } catch (e: Exception) { return false }
        return raw.contains("\"liveviewStatus\":true")
    }

    /** 等待 liveviewStatus 变 true；超时返回 false。 */
    fun awaitLiveviewReady(timeoutMs: Long = 6000, intervalMs: Long = 500): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (isLiveviewReady()) return true
            sleeper(intervalMs)
            waited += intervalMs
        }
        return false
    }

    // ------------------------------------------------------------ 参数写入

    /**
     * 绕过能力门禁直接调用，返回原始响应（不抛异常）。
     * **仅用于探测/调试** —— 正常功能必须走 [tryCall]。
     */
    fun callForce(method: String, params: List<Any?> = emptyList()): String =
        client.call(endpoint, method, params, "1.0", 20000)

    /**
     * 直接调用，**不查能力缓存**，成功与否以相机的回应为准。
     *
     * 为什么不用 [tryCall]：能力缓存 [capabilities] 只在我们主动刷新时更新，很容易过期。
     * 缓存停在 12 个方法时，相机仍可接受 setExposureCompensation，
     * 却被本地门禁挡掉，UI 表现为"调了没反应，而且自己弹回去"。
     *
     * **相机自己是权威**：不可用时它会回 error[1]。
     * UI 的显隐判断交给上层用实时的可用列表（getAvailableApiList）去做。
     */
    private fun callAndCheck(method: String, params: List<Any?>): Boolean {
        return try {
            val raw = client.call(endpoint, method, params)
            !raw.contains("\"error\"")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 曝光补偿。范围来自 getEvent 的 min/max。
     * a6300 报 -15..+15 是**1/3 EV 步进**，所以实际范围是 ±5 EV。
     * **只在取景运行期间可用** —— 停止取景后该方法会从可用列表消失。
     */
    fun setExposureCompensation(value: Int): Boolean =
        callAndCheck("setExposureCompensation", listOf(value))

    /**
     * 定时自拍秒数。
     * 参数必须是 **int**，且必须是**扁平写法 [v]**：
     *   传字符串 "2"  -> ERR 3 illegal argument（实测）
     *   传嵌套 [[2]]  -> ERR 3 illegal argument（实测）
     */
    fun setSelfTimer(seconds: Int): Boolean =
        callAndCheck("setSelfTimer", listOf(seconds))

    /**
     * 变焦。
     * @param direction "in" / "out"
     * @param movement  "1step" / "start" / "stop"
     * 仅在镜头支持电动变焦时可用（a6300 + 非 PZ 镜头实测不可用）。
     */
    fun actZoom(direction: String, movement: String): Boolean =
        callAndCheck("actZoom", listOf(direction, movement))

    /** 拉取并解析相机状态。 */
    fun readEvent(): CameraEvent = CameraEvent.parse(getEvent(false))

    /** 只有出现在 availableApiList 里才调用；返回原始响应，不可用时返回 null。 */
    fun tryCall(method: String, params: List<Any?>): String? {
        if (!capabilities.can(method)) return null
        val raw = client.call(endpoint, method, params)
        SonyJson.throwIfError(raw, method)
        return raw
    }

    companion object {
        const val MIN_SERVER_VERSION = "2.0.0"
        const val REC_MODE_SETTLE_MS = 500L

        /** 简单点分版本比较，用于 >= 2.0.0 闸门。 */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.trim().split(".")
            val pb = b.trim().split(".")
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val va = if (i < pa.size) numOrZero(pa[i]) else 0
                val vb = if (i < pb.size) numOrZero(pb[i]) else 0
                if (va != vb) return if (va > vb) 1 else -1
            }
            return 0
        }

        private fun numOrZero(s: String): Int {
            val digits = StringBuilder()
            for (c in s) {
                if (c.isDigit()) digits.append(c) else break
            }
            return if (digits.isEmpty()) 0 else digits.toString().toInt()
        }
    }
}
