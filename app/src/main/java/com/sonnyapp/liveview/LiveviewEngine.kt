package com.sonnyapp.liveview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.sonnyapp.camera.NetHttp
import com.sonnyapp.core.liveview.BareJpegScanner
import com.sonnyapp.core.liveview.LiveviewFrame
import com.sonnyapp.core.liveview.SonyLiveviewDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 取景 body 的分帧形态。
 *
 * 三种形态的字节解释互不兼容，必须由 HTTP 响应头判定后再选解析器：
 *
 *  - [CHUNKED_CONTAINER]：HTTP/1.1 chunked 分帧，每个 chunk 装一个 "$5hy" 容器包
 *    （ILCE-6300 实测形态，见 docs/camera-profiles/ILCE-6300.md）
 *  - [RAW_CONTAINER]：容器包首尾相接，body 内没有 chunk 头
 *  - [BARE_JPEG]：body 内是连续裸 JPEG，没有容器
 */
enum class LiveviewFraming {
    CHUNKED_CONTAINER,
    RAW_CONTAINER,
    BARE_JPEG,
}

/**
 * 一种分帧模式在同一段字节上的解析结果。
 *
 * 存在的意义：无法导出原始字节时，报告文本必须能区分「字节没到」「分帧选错」
 * 「帧类型不是 JPEG」这三种完全不同的故障，否则远程只能反复猜。
 */
class FramingAttempt(val framing: LiveviewFraming) {
    var frames: Int = 0

    /** 其中按 SOI..EOI 定位成功的完整 JPEG —— 只有这种帧可以解码 */
    var completeJpegs: Int = 0
    var resync: Int = 0
    var chunkAnomaly: Int = 0
    var buffered: Int = 0
    var dropped: Long = 0L
    var payloadTypes: Map<Int, Int> = emptyMap()

    /** 该模式下的字节级观察（首个 CRLF / 魔数 / SOI 的落点） */
    var note: String = ""
}

/**
 * 取景流水线。
 *
 * ## 单线程设计（读 → 解析 → 解码 → 绘制，全在同一条线程上）
 *
 * 为什么单线程：
 *  - **没有缓冲区竞争**：复用同一个 Bitmap（inBitmap）时，如果解码和绘制在不同线程，
 *    绘制读到一半就被下一帧改写 → 撕裂。单线程天然没有这个问题。
 *  - **天然不排队**：相机只推 15~20 fps，单帧处理约 5 ms，
 *    处理不过来时数据留在内核缓冲，下一轮会一次性读走并只画最后一帧。
 *
 * ## 分帧自动判定
 *
 * 打开流后先按响应头给出的形态解析；若在一段字节内切不出**完整 JPEG**，
 * 就对**同一段已缓存字节**换另外两种形态重解析，取切出完整 JPEG 最多的那种继续。
 *
 * 判定依据必须是「完整 JPEG 数量」而不是「帧数量」：部分机型（实测 ILCE-6000）
 * 的 HTTP chunk 边界与容器边界不重合 —— 136 字节容器头独占一个 chunk，
 * 载荷在下一个 chunk。此时容器解析器会在错误边界上切出大量帧，
 * 但帧载荷的起点是 chunk 头，按 SOI..EOI 定位必然失败，全部不可解码。
 *
 * 判定过程全部记录进 [diagnostics]，因此即使三种都不成立，
 * 报告里也有每种模式的字节级观察。
 *
 * ## 内存
 *  - **inBitmap 复用**：稳态零 Bitmap 分配
 *  - **offset 解码**：直接用偏移量喂 BitmapFactory，省掉每帧一次裁剪拷贝
 *  - 判定用的字节缓存上限 [RETENTION_MAX]，判定完成后立即释放
 */
class LiveviewEngine(
    private val network: Network,
    private val renderer: LiveviewRenderer,
) {
    private var job: Job? = null

    @Volatile var running: Boolean = false
        private set
    @Volatile var fps: Float = 0f
        private set
    @Volatile var jpegBytes: Int = 0
        private set
    @Volatile var decodeMs: Long = 0L
        private set
    @Volatile var drawMs: Long = 0L
        private set
    @Volatile var skipped: Long = 0L
        private set
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set
    @Volatile var framesTotal: Long = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    // ---------------------------------------------------------------- 传输层诊断

    @Volatile var httpStatusLine: String = "(未打开)"
        private set
    @Volatile var httpHeaders: String = ""
        private set
    @Volatile var adoptedFraming: LiveviewFraming? = null
        private set
    @Volatile var bytesRead: Long = 0L
        private set
    @Volatile var readCalls: Long = 0L
        private set
    @Volatile var readTimeouts: Long = 0L
        private set
    @Volatile var firstBytesHex: String = ""
        private set
    @Volatile var nonJpegFrames: Long = 0L
        private set
    @Volatile var incompleteJpegFrames: Long = 0L
        private set
    @Volatile var payloadTypes: Map<Int, Int> = emptyMap()
        private set

    /** 无法出帧时的简短原因，供状态栏与连接日志使用 */
    @Volatile var failureReason: String? = null
        private set

    private val trials = CopyOnWriteArrayList<FramingAttempt>()

    fun start(scope: CoroutineScope, url: String) {
        job = scope.launch(Dispatchers.IO) { pipeline(url) }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        fps = 0f
    }

    /** 一行式失败摘要（状态栏/日志用）。 */
    fun failureSummary(): String {
        val fr = failureReason ?: return "未收到取景帧"
        return fr + "（字节 " + bytesRead + "，分帧 " + (adoptedFraming?.name ?: "?") + "）"
    }

    /** 多行诊断，进入诊断报告文本。 */
    fun diagnostics(): String {
        val sb = StringBuilder()
        sb.append("取景流 HTTP : ").append(httpStatusLine).append('\n')
        if (httpHeaders.isNotEmpty()) sb.append("响应头      : ").append(httpHeaders).append('\n')
        sb.append("采用分帧    : ").append(adoptedFraming?.name ?: "(未开始)").append('\n')
        sb.append("收到字节    : ").append(bytesRead).append(" B（read ").append(readCalls)
            .append(" 次 / 超时 ").append(readTimeouts).append(" 次）").append('\n')
        sb.append("首字节      : ").append(if (firstBytesHex.isEmpty()) "(无)" else firstBytesHex).append('\n')
        sb.append("帧过滤      : 非 JPEG ").append(nonJpegFrames)
            .append(" 帧 / JPEG 不完整 ").append(incompleteJpegFrames).append(" 帧").append('\n')
        sb.append("帧类型分布  : ").append(typeHistogram()).append('\n')
        if (trials.isNotEmpty()) {
            sb.append("分帧试解析  :").append('\n')
            for (t in trials) {
                sb.append("    ").append(t.framing.name)
                    .append(" -> 帧 ").append(t.frames)
                    .append(" / 完整 JPEG ").append(t.completeJpegs)
                    .append(" / 重同步 ").append(t.resync)
                    .append(" / chunk 异常 ").append(t.chunkAnomaly)
                    .append(" / 丢弃 ").append(t.dropped)
                    .append(" / 缓冲 ").append(t.buffered)
                if (t.note.isNotEmpty()) sb.append(" / ").append(t.note)
                if (t.payloadTypes.isNotEmpty()) sb.append(" / 类型 ").append(formatTypes(t.payloadTypes))
                sb.append('\n')
            }
        }
        failureReason?.let { sb.append("失败原因    : ").append(it).append('\n') }
        sb.append("流水线      : 运行中=").append(running).append("  总帧=").append(framesTotal)
        lastError?.let { sb.append("  错误=").append(it) }
        return sb.toString()
    }

    private fun typeHistogram(): String =
        if (payloadTypes.isEmpty()) "{}" else formatTypes(payloadTypes)

    private fun formatTypes(m: Map<Int, Int>): String =
        m.entries.sortedBy { it.key }.joinToString(", ") { "0x" + Integer.toHexString(it.key) + "=" + it.value }

    // =================================================================
    //  流水线
    // =================================================================

    /** 与分帧形态无关的帧视图：容器帧与裸 JPEG 帧都归一到 (数组, 偏移, 长度)。 */
    private class RawFrame(
        val bytes: ByteArray,
        val offset: Int,
        val length: Int,
        val payloadType: Int,
    )

    /** 一种分帧形态的解析器。 */
    private class Parser(val framing: LiveviewFraming) {
        val container: SonyLiveviewDecoder? =
            if (framing == LiveviewFraming.BARE_JPEG) null
            else SonyLiveviewDecoder(chunked = framing == LiveviewFraming.CHUNKED_CONTAINER)

        val bare: BareJpegScanner? =
            if (framing == LiveviewFraming.BARE_JPEG) BareJpegScanner() else null

        val attempt = FramingAttempt(framing)

        fun feed(data: ByteArray, off: Int, len: Int): List<RawFrame> {
            val c = container
            if (c != null) {
                val frames = c.feed(data, off, len)
                attempt.frames = c.frameCount
                attempt.resync = c.resyncCount
                attempt.chunkAnomaly = c.chunkAnomalyCount
                attempt.buffered = c.buffered
                val out = ArrayList<RawFrame>(frames.size)
                for (f in frames) {
                    out.add(RawFrame(f.payload, f.jpegOffset, f.jpegLength, f.payloadType))
                }
                attempt.completeJpegs += completeJpegCount(out)
                return out
            }
            val b = bare!!
            val jpegs = b.feed(data, off, len)
            attempt.frames = b.frameCount
            attempt.dropped = b.droppedBytes
            attempt.buffered = b.buffered
            val out = ArrayList<RawFrame>(jpegs.size)
            for (j in jpegs) {
                out.add(RawFrame(j, 0, j.size, LiveviewFrame.PAYLOAD_TYPE_JPEG))
            }
            attempt.completeJpegs += completeJpegCount(out)
            return out
        }

        /** 只有「类型为 JPEG 且按 SOI..EOI 定位成功」的帧才可解码。 */
        private fun completeJpegCount(frames: List<RawFrame>): Int {
            var n = 0
            for (f in frames) {
                if (f.payloadType == LiveviewFrame.PAYLOAD_TYPE_JPEG && f.length > 0) n++
            }
            return n
        }
    }

    private suspend fun pipeline(url: String) {
        Log.i(TAG, "liveview pipeline start: " + url)

        val stream = NetHttp.open(network, url, "image/jpeg", CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        if (stream == null) {
            failureReason = "无法打开取景流"
            lastError = failureReason
            return
        }

        val headers = stream.responseHeaders
        httpStatusLine = NetHttp.statusLine(headers)
        httpHeaders = headers.replace("\r\n", " | ").trim()
        val headerChunked = NetHttp.isChunked(headers)

        val order = ArrayList<LiveviewFraming>(3)
        if (headerChunked) {
            order.add(LiveviewFraming.CHUNKED_CONTAINER)
            order.add(LiveviewFraming.RAW_CONTAINER)
        } else {
            order.add(LiveviewFraming.RAW_CONTAINER)
            order.add(LiveviewFraming.CHUNKED_CONTAINER)
        }
        order.add(LiveviewFraming.BARE_JPEG)

        var active = Parser(order[0])
        adoptedFraming = order[0]

        val retention = ByteArrayOutputStream(RETENTION_MAX)
        var needTrial = true
        var resolved = false

        val buf = ByteArray(64 * 1024)
        var reusable: Bitmap? = null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val types = HashMap<Int, Int>()
        var produced = 0L
        val t0 = SystemClock.elapsedRealtime()
        running = true

        fun process(frames: List<RawFrame>) {
            for (fr in frames) {
                types[fr.payloadType] = (types[fr.payloadType] ?: 0) + 1
                payloadTypes = HashMap(types)

                if (fr.payloadType != LiveviewFrame.PAYLOAD_TYPE_JPEG) {
                    nonJpegFrames++
                    continue
                }
                if (fr.length <= 0) {
                    incompleteJpegFrames++
                    continue
                }

                val tDecode = SystemClock.elapsedRealtime()
                val bmp = decodeInto(fr.bytes, fr.offset, fr.length, reusable, opts)
                decodeMs = SystemClock.elapsedRealtime() - tDecode
                if (bmp == null) continue

                if (bmp !== reusable) {
                    reusable = bmp
                    width = bmp.width
                    height = bmp.height
                }
                jpegBytes = fr.length
                produced++
                framesTotal = produced
                val elapsedSec = (SystemClock.elapsedRealtime() - t0) / 1000f
                if (elapsedSec > 0.5f) fps = produced / elapsedSec

                renderer.draw(bmp)
                drawMs = renderer.lastDrawMs
                skipped = renderer.framesSkipped
            }
        }

        try {
            while (currentCoroutineContext().isActive) {
                readCalls++
                val n = try {
                    stream.input.read(buf)
                } catch (e: SocketTimeoutException) {
                    readTimeouts++
                    if (bytesRead == 0L &&
                        SystemClock.elapsedRealtime() - t0 >= NO_DATA_TIMEOUT_MS
                    ) {
                        failureReason = "取景流已打开但相机未推送数据（" +
                            (NO_DATA_TIMEOUT_MS / 1000) + " 秒 0 字节）"
                        break
                    }
                    continue
                } catch (e: Exception) {
                    failureReason = "读取中断：" + e.message
                    break
                }
                if (n <= 0) {
                    failureReason = "取景流结束"
                    break
                }

                bytesRead += n
                if (firstBytesHex.isEmpty()) firstBytesHex = toHex(buf, minOf(n, HEX_PREFIX_BYTES))
                if (needTrial && retention.size() < RETENTION_MAX) {
                    retention.write(buf, 0, minOf(n, RETENTION_MAX - retention.size()))
                }

                process(active.feed(buf, 0, n))

                if (!resolved && active.attempt.completeJpegs > 0) {
                    // 当前形态已经切出完整 JPEG，无需再判定
                    val a = snapshot(active.attempt)
                    a.note = "解析正常"
                    trials.add(a)
                    resolved = true
                    needTrial = false
                } else if (!resolved && needTrial &&
                    (bytesRead >= PROBE_BYTES || SystemClock.elapsedRealtime() - t0 >= PROBE_MS)
                ) {
                    val prefix = retention.toByteArray()
                    val best = resolveFraming(prefix, order)
                    resolved = true
                    needTrial = false
                    if (best != null) {
                        active = Parser(best)
                        adoptedFraming = best
                        Log.i(TAG, "framing switched to " + best)
                        // 已缓存的字节交给新解析器重放，避免丢掉开头的帧
                        process(active.feed(prefix, 0, prefix.size))
                    } else {
                        failureReason = "取景流分帧无法识别（" + order.size + " 种模式均未切出完整 JPEG）"
                    }
                }
            }
        } finally {
            running = false
            stream.close()
            Log.i(TAG, "liveview pipeline stop (frames=" + produced + ", bytes=" + bytesRead + ")")
        }
    }

    /** 对同一段缓存字节依次试解析全部形态，返回第一个能出帧的。 */
    private fun resolveFraming(prefix: ByteArray, order: List<LiveviewFraming>): LiveviewFraming? {
        var best: LiveviewFraming? = null
        var bestComplete = 0
        for (mode in order) {
            val p = Parser(mode)
            val frames = p.feed(prefix, 0, prefix.size)
            val a = p.attempt
            a.note = inspectPrefix(prefix, mode)
            val hist = LinkedHashMap<Int, Int>()
            for (f in frames) hist[f.payloadType] = (hist[f.payloadType] ?: 0) + 1
            a.payloadTypes = hist
            trials.add(a)
            // 判定依据是「完整 JPEG 数量」而不是「帧数量」：chunk 边界与容器边界不重合时，
            // 容器解析器仍会按错误边界切出大量帧，但载荷起点是 chunk 头，一帧都解不出来。
            if (a.completeJpegs > bestComplete) {
                bestComplete = a.completeJpegs
                best = mode
            }
        }
        return best
    }

    private fun snapshot(a: FramingAttempt): FramingAttempt {
        val c = FramingAttempt(a.framing)
        c.frames = a.frames
        c.completeJpegs = a.completeJpegs
        c.resync = a.resync
        c.chunkAnomaly = a.chunkAnomaly
        c.buffered = a.buffered
        c.dropped = a.dropped
        c.payloadTypes = a.payloadTypes
        c.note = a.note
        return c
    }

    /** 与分帧形态对应的字节级观察：这些数字能直接指出为什么切不出帧。 */
    private fun inspectPrefix(b: ByteArray, mode: LiveviewFraming): String {
        return when (mode) {
            LiveviewFraming.CHUNKED_CONTAINER -> {
                var crlf = -1
                var i = 0
                while (i + 1 < b.size) {
                    if (b[i] == 0x0D.toByte() && b[i + 1] == 0x0A.toByte()) {
                        crlf = i
                        break
                    }
                    i++
                }
                if (crlf < 0) "前 " + b.size + " 字节内无 CRLF"
                else "首个 CRLF 偏移 " + crlf + "，该行=\"" + printable(b, crlf) + "\""
            }

            LiveviewFraming.RAW_CONTAINER -> {
                val magic = indexOf(b, byteArrayOf(0x24, 0x35, 0x68, 0x79))
                val b0 = if (b.isEmpty()) -1 else (b[0].toInt() and 0xFF)
                "魔数 \"$5hy\" " + (if (magic < 0) "未命中" else "偏移 " + magic) +
                    "，首字节 0x" + Integer.toHexString(b0)
            }

            LiveviewFraming.BARE_JPEG -> {
                val soi = indexOf(b, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
                "SOI(FF D8 FF) " + (if (soi < 0) "未命中" else "偏移 " + soi)
            }
        }
    }

    private fun printable(b: ByteArray, rowLen: Int): String {
        val n = minOf(rowLen, 32)
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xFF
            sb.append(if (v in 32..126) v.toChar() else '.')
        }
        return sb.toString()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun toHex(b: ByteArray, n: Int): String {
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) {
            if (i > 0) sb.append(' ')
            val v = b[i].toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /**
     * 用 inBitmap 复用解码目标。
     * 尺寸/配置不匹配时 BitmapFactory 会抛 IllegalArgumentException —— 那就丢掉复用池重来。
     */
    private fun decodeInto(
        data: ByteArray,
        offset: Int,
        length: Int,
        reuse: Bitmap?,
        opts: BitmapFactory.Options,
    ): Bitmap? {
        opts.inBitmap = reuse?.takeIf { !it.isRecycled && it.isMutable }
        return try {
            BitmapFactory.decodeByteArray(data, offset, length, opts)
        } catch (e: IllegalArgumentException) {
            opts.inBitmap = null
            try {
                BitmapFactory.decodeByteArray(data, offset, length, opts)
            } catch (e2: Exception) {
                Log.w(TAG, "decode failed: " + e2.message)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed: " + e.message)
            null
        }
    }

    companion object {
        private const val TAG = "LiveviewEngine"

        private const val CONNECT_TIMEOUT_MS = 8000
        private const val READ_TIMEOUT_MS = 4000

        /** 判定分帧形态所需的字节数 */
        private const val PROBE_BYTES = 256 * 1024

        /** 判定分帧形态的最长等待 */
        private const val PROBE_MS = 5000L

        /** 完全没有字节时放弃等待的时长 */
        private const val NO_DATA_TIMEOUT_MS = 6000L

        /** 判定期间缓存字节的上限 */
        private const val RETENTION_MAX = 512 * 1024

        /** 报告里记录的首字节数量 */
        private const val HEX_PREFIX_BYTES = 96
    }
}
