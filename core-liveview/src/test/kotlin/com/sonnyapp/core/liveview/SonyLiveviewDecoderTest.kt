package com.sonnyapp.core.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 用**真实抓包数据**验证 LiveView 容器解析。
 *
 * 夹具：probe/liveview-raw-20260930-151210.bin
 *   —— ILCE-6300 + Smart Remote Control SR/3.31 的原始 HTTP 响应体，524288 字节，
 *      含 81 个完整容器包（type 全为 0x01），末尾有 1 个被截断的包。
 */
class SonyLiveviewDecoderTest {

    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/liveview-raw-20260930-151210.bin")!!.use { it.readBytes() }

    private fun sig(f: LiveviewFrame): String =
        f.payloadType.toString() + "/" + f.sequenceNumber + "/" + f.timestampMs + "/" +
            f.payloadSize + "/" + f.paddingSize + "/" + f.payload.size

    /** 独立实现一遍 HTTP chunked 拆包，用于交叉验证解码器。 */
    private fun dechunk(raw: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < raw.size) {
            var j = i
            while (j + 1 < raw.size && !(raw[j] == 0x0D.toByte() && raw[j + 1] == 0x0A.toByte())) j++
            if (j + 1 >= raw.size) break
            val sizeHex = String(raw, i, j - i, Charsets.US_ASCII).trim()
            val size = try { sizeHex.toInt(16) } catch (e: NumberFormatException) { break }
            if (size <= 0) { i = j + 2; continue }
            val bodyStart = j + 2
            if (bodyStart + size > raw.size) break
            out.add(raw.copyOfRange(bodyStart, bodyStart + size))
            i = bodyStart + size + 2
        }
        return out
    }

    // ---------------------------------------------------------------- 基础

    @Test
    fun decodesEveryFrameFromRealCapture() {
        val dec = SonyLiveviewDecoder(chunked = true)
        val frames = dec.feed(fixture())

        assertEquals("应解析出 81 帧", 81, frames.size)
        assertEquals("不应发生重同步", 0, dec.resyncCount)
        assertEquals("不应出现 chunk 长度异常", 0, dec.chunkAnomalyCount)
        assertEquals("frameCount 应与返回帧数一致", 81, dec.frameCount)
    }

    @Test
    fun firstFrameMatchesDocumentedByteLayout() {
        val frames = SonyLiveviewDecoder(chunked = true).feed(fixture())
        val f = frames[0]

        assertEquals("payloadType", LiveviewFrame.PAYLOAD_TYPE_JPEG, f.payloadType)
        assertEquals("sequenceNumber", 1, f.sequenceNumber)
        assertEquals("timestamp", 319L, f.timestampMs)
        assertEquals("payloadSize", 6016, f.payloadSize)
        assertEquals("paddingSize", 0, f.paddingSize)
        assertEquals("payload 长度应等于 payloadSize", 6016, f.payload.size)
        assertEquals(0xFF, f.payload[0].toInt() and 0xFF)
        assertEquals(0xD8, f.payload[1].toInt() and 0xFF)

        // 关键：payloadSize 是相机缓冲区大小，不是 JPEG 长度
        assertTrue("应是完整 JPEG", f.isCompleteJpeg)
        assertEquals("真实 JPEG 长度（SOI..EOI）", 5967, f.jpegLength)
        assertEquals("尾部残留脏数据字节数", 49, f.trailingBytes)
        assertNotNull("应能取出干净 JPEG", f.jpegBytes())
        assertEquals(5967, f.jpegBytes()!!.size)
    }

    @Test
    fun sequenceIsStrictlyIncreasingWithoutGaps() {
        val frames = SonyLiveviewDecoder(chunked = true).feed(fixture())
        frames.forEachIndexed { i, f ->
            assertEquals("第 " + i + " 帧的序号", i + 1, f.sequenceNumber)
        }
    }

    @Test
    fun timestampsAreMonotonic() {
        val frames = SonyLiveviewDecoder(chunked = true).feed(fixture())
        for (i in 1 until frames.size) {
            assertTrue(
                "时间戳必须单调递增（第 " + i + " 帧）",
                frames[i].timestampMs > frames[i - 1].timestampMs
            )
        }
    }

    @Test
    fun everyFrameCarriesAFull640x424Jpeg() {
        val frames = SonyLiveviewDecoder(chunked = true).feed(fixture())
        val sizes = HashSet<String>()
        frames.forEach { f ->
            assertTrue("每帧都应是完整 JPEG，seq=" + f.sequenceNumber, f.isCompleteJpeg)
            assertEquals("payload 长度应等于 payloadSize", f.payloadSize, f.payload.size)

            // 必须用裁剪后的 JPEG，而不是整段 payload（后者含上一帧残留脏数据）
            val clean = f.jpegBytes()
            assertNotNull("应能取出干净 JPEG，seq=" + f.sequenceNumber, clean)
            val s = JpegInfo.size(clean!!)
            assertNotNull("应能读出 JPEG 尺寸，seq=" + f.sequenceNumber, s)
            sizes.add(s!!.toString())
        }
        assertEquals("所有帧分辨率应一致", setOf("640x424"), sizes)
    }

    @Test
    fun payloadSizeIsBufferSizeNotJpegLength() {
        val frames = SonyLiveviewDecoder(chunked = true).feed(fixture())

        // 实测本机型整段抓包只有两种 payloadSize —— 说明它是相机缓冲区大小
        val payloadSizes = frames.map { it.payloadSize }.toSet()
        assertEquals(setOf(6016, 6272), payloadSizes)

        frames.forEach { f ->
            assertTrue(
                "JPEG 必须短于 payload，seq=" + f.sequenceNumber +
                    " jpeg=" + f.jpegLength + " payload=" + f.payloadSize,
                f.jpegLength < f.payloadSize
            )
            assertTrue(
                "残留数据不应超过 payload 的 10%，seq=" + f.sequenceNumber,
                f.jpegLength > f.payloadSize * 9 / 10
            )
        }

        // JPEG 长度随场景变化 —— 证明残留数据不是图像的一部分
        val distinct = frames.map { it.jpegLength }.toSet().size
        assertTrue("JPEG 长度应当各异，实际只有 " + distinct + " 种", distinct > 5)
    }

    // ------------------------------------------------- 与独立实现交叉验证

    @Test
    fun containerLengthEqualsChunkLengthForEveryPacket() {
        val raw = fixture()
        val chunks = dechunk(raw)
        assertEquals("独立拆包应得到 81 个 chunk", 81, chunks.size)

        var fileOffset = 0
        for ((idx, body) in chunks.withIndex()) {
            assertTrue("chunk " + idx + " 至少要有 136 字节头", body.size >= LiveviewFrame.HEADER_LENGTH)
            val payloadSize = ((body[12].toInt() and 0xFF) shl 16) or
                ((body[13].toInt() and 0xFF) shl 8) or (body[14].toInt() and 0xFF)
            val paddingSize = body[15].toInt() and 0xFF
            assertEquals(
                "chunk " + idx + " 长度必须等于 136 + payload + padding",
                body.size,
                LiveviewFrame.HEADER_LENGTH + payloadSize + paddingSize
            )
            assertEquals("chunk " + idx + " 的魔数", LiveviewFrame.MAGIC,
                ((body[8].toInt() and 0xFF) shl 24) or ((body[9].toInt() and 0xFF) shl 16) or
                    ((body[10].toInt() and 0xFF) shl 8) or (body[11].toInt() and 0xFF))
            fileOffset += 0
        }
    }

    // ------------------------------------------------------- 流式增量一致性

    @Test
    fun incrementalFeedingProducesIdenticalResult() {
        val raw = fixture()
        val expected = SonyLiveviewDecoder(chunked = true).feed(raw).map { sig(it) }

        val dec = SonyLiveviewDecoder(chunked = true)
        val got = ArrayList<String>()
        val step = 997
        var off = 0
        while (off < raw.size) {
            val n = minOf(step, raw.size - off)
            dec.feed(raw, off, n).forEach { got.add(sig(it)) }
            off += n
        }
        assertEquals(expected, got)
        assertEquals("分片喂入也不应重同步", 0, dec.resyncCount)
    }

    @Test
    fun randomSmallChunksProduceIdenticalResult() {
        val raw = fixture()
        val expected = SonyLiveviewDecoder(chunked = true).feed(raw).map { sig(it) }

        val dec = SonyLiveviewDecoder(chunked = true)
        val got = ArrayList<String>()
        val rnd = Random(42L)
        var off = 0
        while (off < raw.size) {
            val n = minOf(rnd.nextInt(128) + 1, raw.size - off)
            dec.feed(raw, off, n).forEach { got.add(sig(it)) }
            off += n
        }
        assertEquals(expected, got)
    }

    @Test
    fun byteByByteFeedingUpToFirstChunkBoundary() {
        val raw = fixture()
        val dec = SonyLiveviewDecoder(chunked = true)
        val got = ArrayList<LiveviewFrame>()
        val limit = 20000
        for (i in 0 until limit) {
            dec.feed(raw, i, 1).forEach { got.add(it) }
        }
        assertTrue("逐字节喂入头 20KB 应至少解出 3 帧，实际 " + got.size, got.size >= 3)
        assertEquals("逐字节喂入不应重同步", 0, dec.resyncCount)
        got.forEachIndexed { i, f -> assertEquals(i + 1, f.sequenceNumber) }
    }

    // --------------------------------------------------------------- 裸流模式

    @Test
    fun rawModeDecodesDechunkedStream() {
        val raw = fixture()
        val chunks = dechunk(raw)
        val concat = ByteArray(chunks.sumOf { it.size })
        var p = 0
        for (c in chunks) {
            System.arraycopy(c, 0, concat, p, c.size)
            p += c.size
        }

        val dec = SonyLiveviewDecoder(chunked = false)
        val frames = dec.feed(concat)
        assertEquals("裸流模式也应解出 81 帧", 81, frames.size)
        assertEquals("裸流模式不应重同步", 0, dec.resyncCount)

        val chunkedFrames = SonyLiveviewDecoder(chunked = true).feed(raw)
        assertEquals(chunkedFrames.map { sig(it) }, frames.map { sig(it) })
    }

    @Test
    fun resyncsAfterGarbageInjected() {
        val raw = fixture()
        val chunks = dechunk(raw)
        val concat = ByteArray(chunks.sumOf { it.size })
        var p = 0
        for (c in chunks) {
            System.arraycopy(c, 0, concat, p, c.size)
            p += c.size
        }

        // 在第 10 个包之后塞入 137 字节垃圾（长度故意不是任何合法头）
        val cut = concat.size / 2
        val garbage = ByteArray(137) { 0x7A }
        val mixed = ByteArray(concat.size + garbage.size)
        System.arraycopy(concat, 0, mixed, 0, cut)
        System.arraycopy(garbage, 0, mixed, cut, garbage.size)
        System.arraycopy(concat, cut, mixed, cut + garbage.size, concat.size - cut)

        val dec = SonyLiveviewDecoder(chunked = false)
        val frames = dec.feed(mixed)
        assertTrue("注入垃圾后应发生重同步", dec.resyncCount > 0)
        assertTrue("重同步后仍应解出绝大多数帧，实际 " + frames.size, frames.size >= 79)
    }
}
