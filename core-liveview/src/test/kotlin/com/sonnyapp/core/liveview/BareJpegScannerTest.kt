package com.sonnyapp.core.liveview

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裸 JPEG 扫描器的行为锁定。
 *
 * 夹具是合成的最小 JPEG：SOI + APP0 + SOS + 熵编码占位 + EOI。
 * 不依赖真实抓包，因为这条路径服务于「body 里没有 Sony 容器」的机型。
 */
class BareJpegScannerTest {

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

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/liveview-raw-20260930-151210.bin")!!.use { it.readBytes() }

    /**
     * 真实抓包回放：把 chunked + "$5hy" 容器的原始字节直接喂给裸 JPEG 扫描器。
     *
     * chunk 头与容器头都是需要跳过的垃圾，但 JPEG 本身在容器内是连续的，
     * 因此应完整切出 81 帧，且每帧与容器解析器得出的 JPEG 完全一致。
     *
     * 这条路径是容器解析彻底失败时的兜底，必须对真实相机 JPEG 成立。
     */
    @Test
    fun recoversEveryFrameFromRealCapture() {
        val s = BareJpegScanner()
        val frames = s.feed(fixture())

        assertEquals("应切出 81 帧", 81, frames.size)
        assertTrue("应跳过 chunk 头与容器头", s.droppedBytes > 0)
        assertTrue("首个 SOI 必须落在容器头之后", s.firstSoiOffset > 136)

        // 与容器解析器交叉验证：首帧真实 JPEG 长度（SOI..EOI）实测 5967 字节
        assertEquals("首帧长度应与容器解析结果一致", 5967, frames[0].size)
        for (f in frames) {
            assertEquals("每帧以 SOI 开头", 0xFF, f[0].toInt() and 0xFF)
            assertEquals(0xD8, f[1].toInt() and 0xFF)
            assertEquals("每帧以 EOI 结尾", 0xD9, f[f.size - 1].toInt() and 0xFF)
            assertEquals(0xFF, f[f.size - 2].toInt() and 0xFF)
        }
    }

    @Test
    fun splitsBackToBackFrames() {
        val a = jpeg(0x55, 400)
        val b = jpeg(0x66, 700)
        val s = BareJpegScanner()

        val frames = s.feed(a + b)

        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
        assertEquals(0L, s.droppedBytes)
        assertEquals(0, s.buffered)
        assertEquals(0L, s.firstSoiOffset)
    }

    @Test
    fun resyncsPastLeadingGarbage() {
        val noise = ByteArray(37) { 0x11 }
        val a = jpeg(0x55, 64)
        val s = BareJpegScanner()

        val frames = s.feed(noise + a)

        assertEquals(1, frames.size)
        assertArrayEquals(a, frames[0])
        assertEquals("噪声应计入丢弃", 37L, s.droppedBytes)
        assertEquals(37L, s.firstSoiOffset)
    }

    @Test
    fun survivesSingleByteFeeds() {
        val a = jpeg(0x55, 32)
        val b = jpeg(0x66, 33)
        val all = a + b
        val s = BareJpegScanner()

        var count = 0
        for (byte in all) {
            count += s.feed(byteArrayOf(byte)).size
        }

        assertEquals("逐字节喂入也应切出 2 帧", 2, count)
        assertEquals(0, s.buffered)
    }

    @Test
    fun holdsIncompleteTrailingFrame() {
        val a = jpeg(0x55, 100)
        val partial = jpeg(0x66, 200).copyOfRange(0, 50)
        val s = BareJpegScanner()

        val frames = s.feed(a + partial)

        assertEquals("截断的帧不应输出", 1, frames.size)
        assertArrayEquals(a, frames[0])
        assertEquals("截断帧应留在缓冲区等后续字节", 50, s.buffered)
    }

    @Test
    fun boundsBufferWhenNoImageEverAppears() {
        val s = BareJpegScanner(maxBuffer = 8 * 1024)

        var fed = 0
        repeat(64) {
            val junk = ByteArray(1024) { 0x11 }
            s.feed(junk)
            fed += junk.size
        }

        assertTrue("必须有丢弃发生", s.droppedBytes > 0)
        assertTrue("缓冲区不得超过上限", s.buffered <= s.highWater)
        assertTrue("缓冲区必须被限制", s.buffered <= 8 * 1024)
        assertEquals("丢弃量应接近喂入量", 0, (s.droppedBytes + s.buffered).compareTo(fed.toLong()))
    }
}
