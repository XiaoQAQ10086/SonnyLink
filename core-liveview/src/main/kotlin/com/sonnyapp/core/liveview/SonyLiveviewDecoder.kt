package com.sonnyapp.core.liveview

/**
 * Sony LiveView 流的增量解码器。
 *
 * 输入：来自 HTTP 响应的原始 socket 字节（顺序、无序、任意分片）。
 * 输出：解析出的 [LiveviewFrame] 列表。
 *
 * 两种传输形态：
 *  - chunked = true  : HTTP/1.1 Transfer-Encoding: chunked。ILCE-6300 实测为
 *                      "<hex 长度>\r\n<容器字节>\r\n"，每个 chunk 恰好装一个容器。
 *  - chunked = false : 裸流，容器首尾相接（部分机型/gstreamer 抓包形态）。
 *
 * 设计要点：
 *  1. **永不排队**：本类只负责切帧，不缓存历史帧；调用方拿到帧后自行决定丢弃策略。
 *  2. **可重同步**：任何一步校验失败都只前进 1 字节（或跳过坏 chunk），
 *     不会让整个连接报废 —— 这是实机长期运行的必要条件。
 *  3. 魔数 "$5hy" = 0x24 0x35 0x68 0x79 不可能出现在 chunk 头里
 *     （十六进制字符集 0-9a-f、CR、LF 与 0x24/0x35/0x68/0x79 无交集），
 *     所以用它做重同步锚点是安全的。
 */
class SonyLiveviewDecoder(
    private val chunked: Boolean = true,
    initialCapacity: Int = 128 * 1024,
) {
    private var buf = ByteArray(if (initialCapacity < LiveviewFrame.HEADER_LENGTH) 4096 else initialCapacity)
    private var len = 0

    /** 已成功解析的帧总数 */
    var frameCount: Int = 0
        private set

    /** 因数据异常而重同步的次数（应为 0） */
    var resyncCount: Int = 0
        private set

    /** chunk 内部长度与容器长度不一致的次数（应为 0） */
    var chunkAnomalyCount: Int = 0
        private set

    /** 当前缓冲区里尚未被消费的字节数 */
    val buffered: Int get() = len

    /**
     * 喂入一段新数据，返回本次能解析出的所有帧（可能为空）。
     */
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size): List<LiveviewFrame> {
        append(data, offset, length)
        val out = ArrayList<LiveviewFrame>(8)
        var pos = 0
        while (true) {
            val consumed = step(pos, out) ?: break
            pos += consumed
        }
        compact(pos)
        return out
    }

    /** 返回消费掉的字节数；返回 null 表示数据不足，需要继续喂。 */
    private fun step(pos: Int, out: MutableList<LiveviewFrame>): Int? {
        var p = pos
        var chunkBodyLen = -1
        var chunkTotal = 0

        if (chunked) {
            val nl = indexOfCrlf(p)
            if (nl < 0) {
                // chunk 头过长 = 数据已错位
                if (len - p > MAX_CHUNK_HEADER) {
                    resyncCount++
                    return 1
                }
                return null
            }
            val lineLen = nl - p
            if (lineLen == 0 || lineLen > MAX_CHUNK_HEADER) {
                resyncCount++
                return 1
            }
            val size = parseHex(buf, p, lineLen)
            if (size == null) {
                resyncCount++
                return 1
            }
            if (size == 0) {
                // 结束块（正常流不会出现），跳过整行
                return nl + 2 - p
            }
            val bodyStart = nl + 2
            if (len - bodyStart < size + 2) return null
            // 校验 chunk 末尾 CRLF
            if (buf[bodyStart + size] != CR || buf[bodyStart + size + 1] != LF) {
                resyncCount++
                return 1
            }
            chunkBodyLen = size
            chunkTotal = bodyStart + size + 2 - p
            p = bodyStart
        }

        val available = if (chunkBodyLen >= 0) chunkBodyLen else (len - p)

        if (available < LiveviewFrame.HEADER_LENGTH) {
            if (chunkBodyLen >= 0) {
                chunkAnomalyCount++
                return chunkTotal
            }
            return null
        }

        if ((buf[p].toInt() and 0xFF) != 0xFF) {
            resyncCount++
            return if (chunkBodyLen >= 0) chunkTotal else 1
        }

        val magic = ((buf[p + 8].toInt() and 0xFF) shl 24) or
            ((buf[p + 9].toInt() and 0xFF) shl 16) or
            ((buf[p + 10].toInt() and 0xFF) shl 8) or
            (buf[p + 11].toInt() and 0xFF)

        if (magic != LiveviewFrame.MAGIC) {
            resyncCount++
            return if (chunkBodyLen >= 0) chunkTotal else 1
        }

        val payloadSize = ((buf[p + 12].toInt() and 0xFF) shl 16) or
            ((buf[p + 13].toInt() and 0xFF) shl 8) or
            (buf[p + 14].toInt() and 0xFF)
        val paddingSize = buf[p + 15].toInt() and 0xFF
        val total = LiveviewFrame.HEADER_LENGTH + payloadSize + paddingSize

        if (available < total) {
            if (chunkBodyLen >= 0) {
                chunkAnomalyCount++
                return chunkTotal
            }
            return null
        }

        val payloadType = buf[p + 1].toInt() and 0xFF
        val sequenceNumber = ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
        val timestamp = ((buf[p + 4].toLong() and 0xFF) shl 24) or
            ((buf[p + 5].toLong() and 0xFF) shl 16) or
            ((buf[p + 6].toLong() and 0xFF) shl 8) or
            (buf[p + 7].toLong() and 0xFF)

        val payload = buf.copyOfRange(p + LiveviewFrame.HEADER_LENGTH, p + LiveviewFrame.HEADER_LENGTH + payloadSize)

        out.add(
            LiveviewFrame(
                payloadType = payloadType,
                sequenceNumber = sequenceNumber,
                timestampMs = timestamp,
                payloadSize = payloadSize,
                paddingSize = paddingSize,
                payload = payload,
            )
        )
        frameCount++

        return if (chunkBodyLen >= 0) chunkTotal else total
    }

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

    private fun compact(consumed: Int) {
        if (consumed <= 0) return
        val remain = len - consumed
        if (remain > 0) System.arraycopy(buf, consumed, buf, 0, remain)
        len = remain
    }

    private fun indexOfCrlf(from: Int): Int {
        var i = from
        while (i + 1 < len) {
            if (buf[i] == CR && buf[i + 1] == LF) return i
            i++
        }
        return -1
    }

    private fun parseHex(b: ByteArray, off: Int, n: Int): Int? {
        var v = 0
        for (i in 0 until n) {
            val c = b[off + i].toInt()
            val d = when (c) {
                in 0x30..0x39 -> c - 0x30
                in 0x61..0x66 -> c - 0x61 + 10
                in 0x41..0x46 -> c - 0x41 + 10
                else -> return null
            }
            v = (v shl 4) or d
            if (v > MAX_CHUNK_SIZE) return null
        }
        return v
    }

    companion object {
        private const val CR: Byte = 0x0D
        private const val LF: Byte = 0x0A
        private const val MAX_CHUNK_HEADER = 8
        private const val MAX_CHUNK_SIZE = 0x1000000
    }
}
