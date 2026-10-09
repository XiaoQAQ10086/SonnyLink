package com.sonnyapp.core.liveview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP chunk 边界与容器边界**不重合**的流形态（ILCE-6000 实测）。
 *
 * 该机型把 136 字节容器头单独放进一个 chunk（chunk 头恰好是 "88"），
 * 载荷放进紧随其后的另一个 chunk。于是同一段字节在三种分帧模式下表现完全不同：
 *
 *  - chunked = true：假定「一个 chunk 装一个容器」，chunk 长度恒为 136、
 *    与容器总长不符 -> 一帧都切不出来，只留下 chunk 长度异常计数；
 *  - chunked = false：会按错误边界切出「帧」，但载荷起点是 chunk 头，
 *    按 SOI..EOI 定位必然失败（jpegLength = -1）—— 这类帧不可解码；
 *  - [BareJpegScanner]：不受 chunk 与容器边界影响，能切出完整 JPEG。
 *
 * 本测试锁定这三条，防止分帧判定再次选中「有帧但没有完整 JPEG」的模式。
 */
class SplitChunkFramingTest {

    private fun jpeg(fill: Int, payload: Int): ByteArray {
        val head = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),                                     // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,                         // APP0, len 16
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00,
            0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08,                         // SOS, len 8
            0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
        )
        val body = ByteArray(payload) { fill.toByte() }
        val tail = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return head + body + tail
    }

    /**
     * 136 字节容器头：
     * 0xFF | payloadType | seq(u16 BE) | timestamp(u32 BE) | dollar-5-h-y 魔数 |
     * payloadSize(u24 BE) | paddingSize | 120 字节保留区
     */
    private fun containerHeader(seq: Int, payloadSize: Int): ByteArray {
        val h = ByteArray(LiveviewFrame.HEADER_LENGTH)
        h[0] = 0xFF.toByte()
        h[1] = LiveviewFrame.PAYLOAD_TYPE_JPEG.toByte()
        h[2] = ((seq shr 8) and 0xFF).toByte()
        h[3] = (seq and 0xFF).toByte()
        h[8] = 0x24
        h[9] = 0x35
        h[10] = 0x68
        h[11] = 0x79
        h[12] = ((payloadSize shr 16) and 0xFF).toByte()
        h[13] = ((payloadSize shr 8) and 0xFF).toByte()
        h[14] = (payloadSize and 0xFF).toByte()
        return h
    }

    private fun chunk(body: ByteArray): ByteArray {
        val head = Integer.toHexString(body.size).toByteArray(Charsets.US_ASCII)
        val crlf = byteArrayOf(0x0D, 0x0A)
        return head + crlf + body + crlf
    }

    /** ILCE-6000 形态：容器头独占一个 chunk，载荷（含尾部残留脏数据）占下一个 chunk。 */
    private fun splitChunkStream(vararg jpegs: ByteArray): ByteArray {
        var out = ByteArray(0)
        var seq = 1
        for (j in jpegs) {
            val payload = j + ByteArray(13) { 0x7E }
            out += chunk(containerHeader(seq, payload.size))
            out += chunk(payload)
            seq++
        }
        return out
    }

    private fun twoFrames(): List<ByteArray> =
        listOf(jpeg(0x55, 400), jpeg(0x66, 900))

    @Test
    fun chunkedContainerModeCannotFollowSplitChunks() {
        val jpegs = twoFrames()
        val dec = SonyLiveviewDecoder(chunked = true)

        val frames = dec.feed(splitChunkStream(*jpegs.toTypedArray()))

        assertEquals("chunk 与容器边界不重合时应切不出帧", 0, frames.size)
        assertTrue("应记录 chunk 长度异常", dec.chunkAnomalyCount > 0)
    }

    @Test
    fun rawContainerModeProducesNoDecodableFrame() {
        val jpegs = twoFrames()
        val dec = SonyLiveviewDecoder(chunked = false)

        val frames = dec.feed(splitChunkStream(*jpegs.toTypedArray()))

        assertTrue("按错误边界仍会切出帧", frames.isNotEmpty())
        assertTrue(
            "这些帧的载荷起点是 chunk 头，不可能是完整 JPEG",
            frames.none { it.isCompleteJpeg },
        )
    }

    @Test
    fun bareScannerRecoversEveryFrame() {
        val jpegs = twoFrames()
        val s = BareJpegScanner()

        val frames = s.feed(splitChunkStream(*jpegs.toTypedArray()))

        assertEquals(2, frames.size)
        assertArrayEquals(jpegs[0], frames[0])
        assertArrayEquals(jpegs[1], frames[1])
        assertTrue("容器头、chunk 头与残留脏数据都应被跳过", s.droppedBytes > 0)
    }
}
