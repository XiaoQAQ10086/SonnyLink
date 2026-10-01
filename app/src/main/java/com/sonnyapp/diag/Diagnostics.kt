package com.sonnyapp.diag

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断报告生成器。
 *
 * ## 为什么需要它
 *
 * 用户的机型、Android 版本、相机型号与固件各不相同，只收到一句「连不上」无法排查。
 * 这个报告把**定位问题所需的全部环境信息**一次收集好，用户点一下复制就能发出来。
 *
 * 内容分四段：应用信息 / 设备信息 / 运行状态 / 连接日志。
 * 后两段由调用方传入，因为遥控与相册两条路径的状态项不同。
 */
object Diagnostics {

    private const val NL = "\n"

    /**
     * @param cameraLog 连接过程的逐行日志（由各 Repository 记录）
     * @param extras    本次运行的补充状态，按顺序输出
     */
    fun build(
        context: Context,
        cameraLog: String,
        extras: List<Pair<String, String>> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.append("===== SonnyLink 诊断报告 =====\n")
        sb.append("生成时间 : ").append(timestamp()).append(NL)
        sb.append(NL)

        sb.append("--- 应用 ---").append(NL)
        sb.append(row("版本", appVersion(context)))
        sb.append(row("包名", context.packageName))
        sb.append(NL)

        sb.append("--- 设备 ---").append(NL)
        sb.append(row("厂商", Build.MANUFACTURER))
        sb.append(row("型号", Build.MODEL))
        sb.append(row("代号", Build.DEVICE))
        sb.append(row("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"))
        sb.append(row("ABI", Build.SUPPORTED_ABIS.joinToString(", ")))
        for ((k, v) in screenInfo(context)) sb.append(row(k, v))
        sb.append(NL)

        if (extras.isNotEmpty()) {
            sb.append("--- 运行状态 ---").append(NL)
            for ((k, v) in extras) sb.append(row(k, v))
            sb.append(NL)
        }

        sb.append("--- 连接日志 ---").append(NL)
        sb.append(if (cameraLog.isBlank()) "(空)" else cameraLog.trimEnd())
        sb.append(NL)
        return sb.toString()
    }

    private fun row(key: String, value: String): String =
        key.padEnd(9) + ": " + value + NL

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun appVersion(context: Context): String = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        pi.versionName + " (" + code + ")"
    } catch (e: Exception) {
        "?"
    }

    private fun screenInfo(context: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (d != null) {
                val m = d.mode
                out.add("分辨率" to m.physicalWidth.toString() + "x" + m.physicalHeight)
                out.add("当前刷新率" to fmt(m.refreshRate) + " Hz")
                val rates = d.supportedModes.map { it.refreshRate }.distinct().sorted()
                out.add("支持刷新率" to rates.joinToString(" / ") { fmt(it) })
            }
        } catch (e: Exception) {
            out.add("屏幕" to ("读取失败: " + (e.message ?: "")))
        }
        try {
            val dm2 = context.resources.displayMetrics
            out.add(
                "密度" to (dm2.densityDpi.toString() + " dpi  (" +
                    (dm2.widthPixels / dm2.density).toInt() + "x" +
                    (dm2.heightPixels / dm2.density).toInt() + " dp)")
            )
        } catch (e: Exception) {
            // 忽略
        }
        return out
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.0f", v)
}
