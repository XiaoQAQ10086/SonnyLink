package com.sonnyapp

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Window

/**
 * 刷新率控制。
 *
 *  平常     -> 屏幕支持的**最高**刷新率（相册滑动才顺）
 *  取景时   -> 降到 **60Hz**
 *
 * 为什么取景要降：取景流是 17 fps 的持续画面，而且走 GPU 渲染 + SurfaceView。
 * 屏幕再跑 120Hz 只是把同一帧多刷 53 次，纯耗电，看不出任何差别。
 *
 * 用 WindowManager.LayoutParams.preferredRefreshRate（API 21+）而不是
 * Window.setFrameRate —— 后者是更高版本才有的 API，低版本编译不过。
 */
object RefreshRate {

    private var win: Window? = null
    private var maxRate = 0f
    private var lowPower = false

    fun attach(w: Window) {
        win = w
        maxRate = queryMax(w.context)
        apply()
    }

    /** 取景开始/结束时调用。 */
    fun setLowPower(on: Boolean) {
        if (lowPower == on) return
        lowPower = on
        apply()
    }

    private fun apply() {
        val w = win ?: return
        val rate = if (lowPower) 60f else maxRate
        if (rate <= 0f) return
        try {
            val a = w.attributes
            a.preferredRefreshRate = rate
            w.attributes = a
        } catch (e: Exception) {
            // 设备不支持就忽略
        }
    }

    private fun queryMax(ctx: Context): Float {
        return try {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0f
            // 取所有模式下最高的刷新率（120 / 144 之类）
            d.supportedModes.maxOfOrNull { it.refreshRate } ?: 0f
        } catch (e: Exception) {
            0f
        }
    }
}
