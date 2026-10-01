package com.sonnyapp.core.liveview

/**
 * JPEG 结构工具。
 *
 * 存在的意义有二：
 *
 * 1. **永远不要假设取景分辨率**。实测 QX100 是 640x480、AS 系列 640x360、
 *    ILCE-6300 是 640x424 —— 客户端只能读实际值。
 *
 * 2. **永远不要用 payloadSize 当作 JPEG 长度**。ILCE-6300 实测：
 *    container 的 payloadSize 是相机内部缓冲区大小（6016 / 6272），
 *    JPEG 结束后还跟着几十字节**上一帧残留的脏数据**（缓冲区未清零）。
 *    因此必须按 SOI..EOI 精确定位图像结束位置。
 */
object JpegInfo {

    class Size(val width: Int, val height: Int) {
        override fun toString(): String = "" + width + "x" + height
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    /**
     * 从 [offset] 处的 SOI 开始，按 JPEG 标记结构走到 EOI。
     *
     * @return 图像结束后的下一个字节下标（即 SOI..EOI 的长度 = 返回值 - offset）；
     *         返回 -1 表示结构不完整或不是 JPEG。
     */
    fun endOfImage(b: ByteArray, offset: Int = 0, limit: Int = b.size): Int {
        if (offset + 2 > limit) return -1
        if (u8(b, offset) != 0xFF || u8(b, offset + 1) != 0xD8) return -1

        var i = offset + 2
        var inScan = false

        while (i + 1 < limit) {
            val c = u8(b, i)

            if (inScan) {
                if (c != 0xFF) { i++; continue }
                val n = u8(b, i + 1)
                when {
                    n == 0x00 -> i += 2              // 字节填充
                    n == 0xFF -> i += 1              // 填充字节
                    n in 0xD0..0xD7 -> i += 2        // 重启标记
                    n == 0xD9 -> return i + 2        // 图像结束
                    else -> { inScan = false }       // 遇到新段，回到段解析
                }
                continue
            }

            if (c != 0xFF) { i++; continue }
            val m = u8(b, i + 1)
            when {
                m == 0xD9 -> return i + 2
                m == 0xFF -> i += 1                                  // 填充字节
                m == 0x01 || m in 0xD0..0xD7 || m == 0x08 -> i += 2  // 独立标记
                else -> {
                    if (i + 4 > limit) return -1
                    val segLen = (u8(b, i + 2) shl 8) or u8(b, i + 3)
                    if (segLen < 2) return -1
                    if (m == 0xDA) inScan = true                      // 进入熵编码数据
                    i += 2 + segLen
                }
            }
        }
        return -1
    }

    /** 拿实际显示的宽高（扫描 SOF0..SOF15，排除 DHT/JPG/DAC）。 */
    fun size(jpeg: ByteArray, offset: Int = 0, limit: Int = jpeg.size): Size? {
        if (offset + 4 > limit) return null
        if (u8(jpeg, offset) != 0xFF || u8(jpeg, offset + 1) != 0xD8) return null

        var i = offset + 2
        while (i + 9 < limit) {
            if (u8(jpeg, i) != 0xFF) { i++; continue }
            val marker = u8(jpeg, i + 1)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = (u8(jpeg, i + 5) shl 8) or u8(jpeg, i + 6)
                val w = (u8(jpeg, i + 7) shl 8) or u8(jpeg, i + 8)
                if (w <= 0 || h <= 0) return null
                return Size(w, h)
            }
            if (marker == 0xD9) return null
            if (i + 4 > limit) return null
            val segLen = (u8(jpeg, i + 2) shl 8) or u8(jpeg, i + 3)
            if (segLen < 2) return null
            i += 2 + segLen
        }
        return null
    }
}
