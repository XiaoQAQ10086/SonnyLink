package com.sonnyapp.core.liveview

/**
 * 裸 JPEG 流扫描器 —— 无容器机型的兜底解析。
 *
 * [SonyLiveviewDecoder] 假定 body 里是 Sony 的 "$5hy" 容器包。部分机型的取景
 * body **不含容器**，直接在字节流里连续输出裸 JPEG；此时容器解析器永远找不到
 * 魔数，表现为「字节持续到达但一帧都切不出来」。
 *
 * 本类不假设任何容器结构：定位 SOI(FF D8 FF)，再用 [JpegInfo.endOfImage] 走到 EOI
 * 切出完整 JPEG。与容器解析器互斥使用，且**只作为回退** —— 容器模式优先。
 *
 * 每帧返回独立数组。容器模式的 payload 同样是每帧一份拷贝（相机缓冲区不能复用），
 * 因此这里的分配行为与主路径一致。
 *
 * 内存自限：缓冲区不会无限增长。既找不到 SOI 又超过 [maxBuffer] 时，从头部丢弃
 * 垃圾字节（保留末尾 2 字节，避免把跨读取边界的 SOI 切开）。
 */
class BareJpegScanner(private val maxBuffer: Int = 4 * 1024 * 1024) {

    private var buf = ByteArray(256 * 1024)
    private var len = 0

    /** 已从缓冲区头部移除的字节总数，用于换算流内绝对偏移 */
    private var consumed = 0L

    /** 已切出的完整 JPEG 数量 */
    var frameCount: Int = 0
        private set

    /** 为重新同步而丢弃的字节数 */
    var droppedBytes: Long = 0L
        private set

    /** 首个 SOI 在流中的绝对偏移；-1 表示尚未出现 */
    var firstSoiOffset: Long = -1L
        private set

    /** 缓冲区曾达到的最大长度（诊断用） */
    var highWater: Int = 0
        private set

    /** 当前缓冲区里尚未被消费的字节数 */
    val buffered: Int get() = len

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size): List<ByteArray> {
        append(data, offset, length)
        if (len > highWater) highWater = len

        val out = ArrayList<ByteArray>(4)
        var pos = 0
        while (true) {
            val soi = indexOfSoi(pos)
            if (soi < 0) {
                pos = trimLeadingGarbage(pos)
                break
            }
            if (soi > pos) {
                droppedBytes += (soi - pos).toLong()
                pos = soi
            }
            val end = JpegInfo.endOfImage(buf, pos, len)
            if (end < 0) {
                // SOI 已定位但结构不完整：可能还差后续字节。
                // 若这一段已经远超合理帧长，说明不是真 JPEG，丢弃后重新同步。
                if (len - pos > maxBuffer) {
                    droppedBytes += (len - pos).toLong()
                    pos = len
                }
                break
            }
            if (firstSoiOffset < 0) firstSoiOffset = consumed + pos
            out.add(buf.copyOfRange(pos, end))
            frameCount++
            pos = end
        }
        compact(pos)
        return out
    }

    /** 找不到 SOI 时按内存上限丢弃头部垃圾，保留末尾 2 字节以防 SOI 被读取边界切开。 */
    private fun trimLeadingGarbage(pos: Int): Int {
        if (len <= maxBuffer) return pos
        val drop = len - 2
        if (drop <= pos) return pos
        droppedBytes += (drop - pos).toLong()
        return drop
    }

    private fun indexOfSoi(from: Int): Int {
        var i = from
        while (i + 2 < len) {
            if (u8(i) == 0xFF && u8(i + 1) == 0xD8 && u8(i + 2) == 0xFF) return i
            i++
        }
        return -1
    }

    private fun u8(i: Int): Int = buf[i].toInt() and 0xFF

    private fun append(src: ByteArray, off: Int, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) {
            var cap = buf.size shl 1
            while (cap < len + n) cap = cap shl 1
            buf = buf.copyOf(cap)
        }
        System.arraycopy(src, off, buf, len, n)
        len += n
    }

    private fun compact(count: Int) {
        if (count <= 0) return
        val remain = len - count
        if (remain > 0) System.arraycopy(buf, count, buf, 0, remain)
        len = remain
        consumed += count
    }
}
