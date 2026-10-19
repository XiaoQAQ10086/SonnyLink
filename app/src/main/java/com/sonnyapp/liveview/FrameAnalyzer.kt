package com.sonnyapp.liveview

import android.graphics.Bitmap

/**
 * 取景画面的直方图统计。
 *
 * ## 为什么放在取景线程上算，而不是在 UI 层
 *
 * 取景帧的 Bitmap 是**复用**的（引擎用 inBitmap 反复解码到同一块内存），
 * UI 线程拿到的引用随时可能被下一帧改写。要么每帧复制一份（17fps × 1MB = 每秒 17MB 垃圾），
 * 要么就在持有它的线程上算完再发结果。这里选后者：只发 256 个 int，代价可以忽略。
 *
 * ## 降采样
 *
 * 640x424 = 27 万像素，全量统计每帧约 3~5 ms，会直接吃掉取景线程的空闲。
 * 隔点采样（每 2 像素取 1）后样本约 6.8 万，统计耗时降到 1 ms 以内，
 * 而直方图的形态**完全不受影响** —— 直方图只看分布，不看总像素数。
 */
object FrameAnalyzer {

    /** 复用采样缓冲，避免每次分配。只在取景线程访问。 */
    private var pixels = IntArray(0)

    /**
     * 统计亮度直方图，写进 [out]（长度必须为 256，会被先清零）。
     *
     * 亮度用 BT.601 系数：人眼对绿最敏感，这与相机测光的口径一致。
     */
    fun lumaHistogram(bmp: Bitmap, out: IntArray) {
        if (out.size != 256 || bmp.isRecycled) return
        java.util.Arrays.fill(out, 0)

        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return
        val need = w * h
        if (pixels.size != need) pixels = IntArray(need)
        val px = pixels
        try {
            bmp.getPixels(px, 0, w, 0, 0, w, h)
        } catch (e: Exception) {
            return
        }

        val step = if (need > 120_000) 2 else 1
        var y = 0
        while (y < h) {
            val row = y * w
            var x = 0
            while (x < w) {
                val c = px[row + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // (0.299R + 0.587G + 0.114B) * 256，四舍五入到 0..255
                val luma = (r * 77 + g * 150 + b * 29) shr 8
                out[luma]++
                x += step
            }
            y += step
        }
    }

    /** 直方图的峰值（用于纵向归一化）。 */
    fun peak(bins: IntArray): Int {
        var m = 0
        for (v in bins) if (v > m) m = v
        return m
    }
}
