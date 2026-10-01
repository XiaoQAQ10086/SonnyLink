package com.sonnyapp.core.protocol

/**
 * 一台相机**此刻**的能力集合。
 *
 * 设计依据（实测 ILCE-6300）：getAvailableApiList 在同一台机器上会随状态变化
 * （未进遥控模式 7 个方法 → startRecMode 后 13 个 → startLiveview 后 23 个），
 * 而 getMethodTypes 列的是固件支持的全集（67 个）。
 *
 * **两者必须分开**：
 *   - [available] 决定 UI 显示什么控件
 *   - [supported] 只用于调试面板与"这个机型到底行不行"的判断
 *
 * 绝不按机型名分支。所有功能开关都必须走这里。
 */
class CameraCapabilities(
    /** 当前状态下可调用的方法 */
    val available: Set<String> = emptySet(),
    /** 固件声明支持的全部方法（与当前状态无关） */
    val supported: Set<String> = emptySet(),
    /** 方法签名，来自 getMethodTypes */
    val signatures: Map<String, SonyMethodSignature> = emptyMap(),
) {
    fun can(method: String): Boolean = available.contains(method)

    fun supportsEver(method: String): Boolean = supported.contains(method)

    /** 只有同时"固件支持"且"此刻可用"才算真能用 */
    fun canReally(method: String): Boolean = available.contains(method)

    // ---- 便于 UI 层直接问的语义化查询 ----

    val canLiveview: Boolean get() = can("startLiveview")
    val canTakePicture: Boolean get() = can("actTakePicture")
    val canAwaitPicture: Boolean get() = can("awaitTakePicture")
    val canZoom: Boolean get() = can("actZoom")
    val canSetExposureCompensation: Boolean get() = can("setExposureCompensation")
    val canSetIso: Boolean get() = can("setIsoSpeedRate")
    val canSetShutterSpeed: Boolean get() = can("setShutterSpeed")
    val canSetAperture: Boolean get() = can("setFNumber")
    val canSetWhiteBalance: Boolean get() = can("setWhiteBalance")
    val canSetSelfTimer: Boolean get() = can("setSelfTimer")
    val canSetShootMode: Boolean get() = can("setShootMode")
    val canTouchAf: Boolean get() = can("setTouchAFPosition")

    /** 当前状态下能写的方法（用于调试面板展示） */
    val writableMethods: List<String> get() = available.filter { it.startsWith("set") || it.startsWith("act") }.sorted()

    fun withAvailable(list: List<String>): CameraCapabilities =
        CameraCapabilities(list.toSet(), supported, signatures)

    fun withMethodTypes(list: List<SonyMethodSignature>): CameraCapabilities =
        CameraCapabilities(available, list.map { it.name }.toSet(), list.associateBy { it.name })

    override fun toString(): String =
        "CameraCapabilities(available=" + available.size + ", supported=" + supported.size + ")"
}
