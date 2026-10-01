package com.sonnyapp.liveview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.sonnyapp.camera.NetHttp
import com.sonnyapp.core.liveview.SonyLiveviewDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException

/**
 * 取景流水线。
 *
 * ## 单线程设计（读 → 解析 → 解码 → 绘制，全在同一条线程上）
 *
 * 为什么单线程：
 *  - **没有缓冲区竞争**：复用同一个 Bitmap（inBitmap）时，如果解码和绘制在不同线程，
 *    绘制读到一半就被下一帧改写 → 撕裂。单线程天然没有这个问题。
 *  - **天然不排队**：相机只推 15.8 fps，单帧处理约 5 ms（解码 2 ms + 绘制 3 ms），
 *    一帧预算 63 ms，余量 10 倍以上。处理不过来时数据留在内核缓冲，
 *    下一轮会一次性读走并只画最后一帧 —— 就是"丢帧不积压"。
 *
 * ## 内存优化
 *  - **inBitmap 复用**：稳态零 Bitmap 分配（优化前为每帧 543 KB、约 8.6 MB/s）
 *  - **offset 解码**：BitmapFactory.decodeByteArray(payload, jpegOffset, jpegLength)
 *    直接用偏移量，省掉每帧一次裁剪拷贝（约 31 KB/帧）
 *
 * ## 统计
 * 所有指标写在 @Volatile 字段里，由 ViewModel **每 500 ms 采样一次** —— 
 * 这样 HUD 更新不会以 15.8 Hz 触发 Compose 重组。
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

    fun start(scope: CoroutineScope, url: String) {
        job = scope.launch(Dispatchers.IO) { pipeline(url) }
    }

    fun stop() {
        job?.cancel()
        job = null
        running = false
        fps = 0f
    }

    private suspend fun pipeline(url: String) {
        Log.i(TAG, "liveview pipeline start: " + url)
        val stream = NetHttp.open(network, url, "image/jpeg", 8000, 4000)
        if (stream == null) {
            lastError = "无法打开取景流"
            return
        }
        val decoder = SonyLiveviewDecoder(chunked = true)
        val buf = ByteArray(64 * 1024)

        // 复用的解码目标：稳态下不再分配 Bitmap
        var reusable: Bitmap? = null
        // ARGB_8888 而不是 RGB_565：
        // RGB_565 每通道只有 5/6/5 位，渐变（天空、肤色）会出现色带和抖动。
        // 内存从 543KB 涨到 1.08MB，但 inBitmap 复用后稳态仍是零分配。
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }

        var produced = 0L
        val t0 = SystemClock.elapsedRealtime()
        running = true
        try {
            while (currentCoroutineContext().isActive) {
                val n = try {
                    stream.input.read(buf)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    lastError = "读取中断：" + e.message
                    break
                }
                if (n <= 0) break

                for (f in decoder.feed(buf, 0, n)) {
                    if (!f.isJpeg || !f.isCompleteJpeg) continue

                    val tDecode = SystemClock.elapsedRealtime()
                    val bmp = decodeInto(f.payload, f.jpegOffset, f.jpegLength, reusable, opts)
                    decodeMs = SystemClock.elapsedRealtime() - tDecode
                    if (bmp == null) continue

                    if (bmp !== reusable) {
                        reusable = bmp
                        width = bmp.width
                        height = bmp.height
                    }
                    jpegBytes = f.jpegLength
                    produced++
                    framesTotal = produced
                    val elapsedSec = (SystemClock.elapsedRealtime() - t0) / 1000f
                    if (elapsedSec > 0.5f) fps = produced / elapsedSec

                    renderer.draw(bmp)
                    drawMs = renderer.lastDrawMs
                    skipped = renderer.framesSkipped
                }
            }
        } finally {
            running = false
            stream.close()
            Log.i(TAG, "liveview pipeline stop (frames=" + produced + ")")
        }
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
            // 复用的 Bitmap 不满足要求（尺寸变了/不可变）→ 重新分配
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
    }
}
