package com.sonnyapp.liveview

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 取景画面在屏幕上的填充方式。 */
enum class LiveviewScaleMode {
    /** 完整画面 + 黑边（不变形、不丢内容）—— 默认 */
    FIT,

    /** 填满屏幕、居中裁切（不变形，但看不到画面上下各约 1/6） */
    CROP,
}

/** 锐化强度。 */
enum class SharpenLevel { OFF, WEAK, STRONG }

/**
 * 取景渲染器：GLES 单遍渲染，完全绕开 Compose。
 *
 * 为什么不用 Canvas.drawBitmap：
 *  Android 的 Canvas 放大只有**双线性**插值，把 640x424 拉到 2400x1080（3.75 倍）
 *  会明显发糊。GLES 用**双三次（Catmull-Rom）** 4 次采样近似 + 非锐化掩模锐化。
 *
 * 线程模型：
 *  取景流水线线程调用 [draw]，把"上传纹理"post 到 GL 线程，
 *  **并用 CountDownLatch 等上传完成**才返回。
 *  这一等很关键 —— 解码目标 Bitmap 被复用（inBitmap），
 *  不等就可能在 GL 线程读它时被下一帧改写，产生撕裂。
 */
class LiveviewRenderer {

    @Volatile
    var scaleMode: LiveviewScaleMode = LiveviewScaleMode.FIT

    @Volatile
    var sharpen: SharpenLevel = SharpenLevel.WEAK

    @Volatile
    var lastDrawMs: Long = 0L
        private set

    @Volatile
    var framesDrawn: Long = 0L
        private set

    @Volatile
    var framesSkipped: Long = 0L
        private set

    /**
     * 取景画面在 View 里占的矩形，**归一化到 0..1**（左上原点）。
     *
     * 为什么要暴露它：UI 上的曝光滑杆要**贴住画面的右缘**而不是屏幕右缘。
     * 「适应」模式下左右有黑边，画面右缘和屏幕右缘差很远。
     */
    @Volatile
    var imageLeft: Float = 0f
        private set
    @Volatile
    var imageTop: Float = 0f
        private set
    @Volatile
    var imageRight: Float = 1f
        private set
    @Volatile
    var imageBottom: Float = 1f
        private set

    /** GL 自检信息 —— logcat 被 MIUI 屏蔽，只能靠它回传。 */
    @Volatile
    var diag: String = "渲染器未附加"
        private set

    private var glView: GLSurfaceView? = null
    private val gl = GlCore()

    fun diagnostics(): String {
        val sb = StringBuilder()
        sb.append("附加 SurfaceView: ").append(glView != null).append('\n')
        sb.append("scaleMode=").append(scaleMode).append("  sharpen=").append(sharpen).append('\n')
        sb.append("已绘制 ").append(framesDrawn).append(" 帧，跳过 ")
            .append(framesSkipped).append(" 帧，上次绘制 ").append(lastDrawMs).append(" ms\n")
        sb.append(gl.selfCheck())
        return sb.toString()
    }

    fun attach(view: GLSurfaceView) {
        if (glView === view) return
        detach()
        view.setEGLContextClientVersion(2)
        // 显式要求 8/8/8 帧缓冲 —— 默认 RGB_565 会把 ARGB_8888 换来的色深丢回去
        view.setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        view.setRenderer(gl)
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        view.setZOrderOnTop(false)
        glView = view
        view.onResume()
        Log.i(TAG, "GLSurfaceView attached")
    }

    fun detach() {
        val v = glView ?: return
        try { v.onPause() } catch (e: Exception) { }
        glView = null
        gl.release()
        Log.i(TAG, "GLSurfaceView detached")
    }

    fun isReady(): Boolean = gl.hasSurface

    /** 由取景流水线线程调用。 */
    fun draw(bitmap: Bitmap): Boolean {
        val v = glView
        if (v == null || bitmap.isRecycled) {
            framesSkipped++
            return false
        }
        val t0 = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        var ok = false
        v.queueEvent {
            try {
                ok = gl.upload(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "upload failed: " + e.message)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(600, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!ok) {
            framesSkipped++
            return false
        }
        v.requestRender()
        lastDrawMs = System.currentTimeMillis() - t0
        framesDrawn++
        return true
    }

    // =================================================================
    //  GL 核心
    // =================================================================

    private inner class GlCore : GLSurfaceView.Renderer {

        @Volatile var hasSurface = false

        @Volatile var glErrorCount = 0L
            private set
        @Volatile var lastGlError: String = "无"
            private set

        fun selfCheck(): String {
            val sb = StringBuilder()
            sb.append("GL: surface=").append(hasSurface)
                .append(" 主程序=").append(mainProg != null).append('\n')
            sb.append("纹理 ").append(texW).append('x').append(texH).append('\n')
            sb.append("GL 错误 ").append(glErrorCount).append(" 次，最后一次: ")
                .append(lastGlError).append('\n')
            sb.append("自检: ").append(diag)
            return sb.toString()
        }

        private var mainProg: QuadProgram? = null
        private var texId = 0
        private var texW = 0
        private var texH = 0
        private var texDirty = false

        private var uTexSize = 0
        private var uSharpen = 0

        private var viewW = 0
        private var viewH = 0

        private val quad: FloatBuffer = ByteBuffer
            .allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
                position(0)
            }

        private val dstMin = FloatArray(2)
        private val dstMax = FloatArray(2)
        private val srcMin = FloatArray(2)
        private val srcMax = FloatArray(2)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            mainProg = QuadProgram.build(VERTEX_SHADER, FRAGMENT_MAIN)
            val mp = mainProg
            if (mp == null) {
                diag = "着色器编译/链接失败"
                Log.e(TAG, diag)
                hasSurface = false
                return
            }
            uTexSize = GLES20.glGetUniformLocation(mp.id, "uTexSize")
            uSharpen = GLES20.glGetUniformLocation(mp.id, "uSharpen")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texId = ids[0]
            bindTexDefaults(texId)

            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            hasSurface = true
            diag = "着色器编译成功"
            Log.i(TAG, "GL surface created")
        }

        private fun bindTexDefaults(id: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width
            viewH = height
            GLES20.glViewport(0, 0, width, height)
            Log.i(TAG, "GL surface changed " + width + "x" + height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!hasSurface || texW == 0 || texH == 0 || !texDirty) return

            computeRects(texW, texH, viewW, viewH, scaleMode)
            val mp = mainProg ?: return
            // 必须先 useProgram 再设 uniform —— uniform 只作用于当前程序
            mp.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(mp.uTex, 0)
            GLES20.glUniform2f(uTexSize, texW.toFloat(), texH.toFloat())
            GLES20.glUniform1f(uSharpen, sharpenAmount())
            mp.draw(dstMin, dstMax, srcMin, srcMax, quad)

            // 把 NDC 目标矩形换算成"相对 View 的归一化矩形"给 UI 用
            imageLeft = (dstMin[0] + 1f) / 2f
            imageRight = (dstMax[0] + 1f) / 2f
            imageTop = (1f - dstMin[1]) / 2f
            imageBottom = (1f - dstMax[1]) / 2f

            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) {
                glErrorCount++
                lastGlError = "0x" + Integer.toHexString(err) + " (第 " + glErrorCount + " 次)"
                Log.e(TAG, "GL error: " + lastGlError)
            }
        }

        private fun sharpenAmount(): Float = when (sharpen) {
            SharpenLevel.OFF -> 0f
            SharpenLevel.WEAK -> 0.45f
            SharpenLevel.STRONG -> 0.95f
        }

        fun upload(bitmap: Bitmap): Boolean {
            if (!hasSurface || texId == 0) return false
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            if (bitmap.width != texW || bitmap.height != texH) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                bindTexDefaults(texId)
                texW = bitmap.width
                texH = bitmap.height
            } else {
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            }
            texDirty = true
            return true
        }

        fun release() {
            hasSurface = false
            mainProg?.delete()
            mainProg = null
            if (texId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                texId = 0
            }
            texW = 0
            texH = 0
            texDirty = false
        }

        private fun computeRects(bw: Int, bh: Int, cw: Int, ch: Int, mode: LiveviewScaleMode) {
            if (cw <= 0 || ch <= 0) return
            when (mode) {
                LiveviewScaleMode.FIT -> {
                    val scale = minOf(cw.toFloat() / bw, ch.toFloat() / bh)
                    val w = bw * scale
                    val h = bh * scale
                    val left = (cw - w) / 2f
                    val top = (ch - h) / 2f
                    dstMin[0] = left / cw * 2f - 1f
                    dstMin[1] = 1f - top / ch * 2f
                    dstMax[0] = (left + w) / cw * 2f - 1f
                    dstMax[1] = 1f - (top + h) / ch * 2f
                    srcMin[0] = 0f
                    srcMin[1] = 0f
                    srcMax[0] = 1f
                    srcMax[1] = 1f
                }

                LiveviewScaleMode.CROP -> {
                    val scale = maxOf(cw.toFloat() / bw, ch.toFloat() / bh)
                    val sw = minOf(cw / scale, bw.toFloat())
                    val sh = minOf(ch / scale, bh.toFloat())
                    val sx = (bw - sw) / 2f
                    val sy = (bh - sh) / 2f
                    dstMin[0] = -1f
                    dstMin[1] = 1f
                    dstMax[0] = 1f
                    dstMax[1] = -1f
                    srcMin[0] = sx / bw
                    srcMin[1] = sy / bh
                    srcMax[0] = (sx + sw) / bw
                    srcMax[1] = (sy + sh) / bh
                }
            }
        }
    }

    private class QuadProgram private constructor(val id: Int) {
        val uTex: Int = GLES20.glGetUniformLocation(id, "uTex")
        private val uDstMin = GLES20.glGetUniformLocation(id, "uDstMin")
        private val uDstMax = GLES20.glGetUniformLocation(id, "uDstMax")
        private val uSrcMin = GLES20.glGetUniformLocation(id, "uSrcMin")
        private val uSrcMax = GLES20.glGetUniformLocation(id, "uSrcMax")
        private val aPos = GLES20.glGetAttribLocation(id, "aPos")

        fun use() {
            GLES20.glUseProgram(id)
        }

        fun draw(
            dstMin: FloatArray,
            dstMax: FloatArray,
            srcMin: FloatArray,
            srcMax: FloatArray,
            quad: FloatBuffer,
        ) {
            GLES20.glUseProgram(id)
            GLES20.glUniform2f(uDstMin, dstMin[0], dstMin[1])
            GLES20.glUniform2f(uDstMax, dstMax[0], dstMax[1])
            GLES20.glUniform2f(uSrcMin, srcMin[0], srcMin[1])
            GLES20.glUniform2f(uSrcMax, srcMax[0], srcMax[1])
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
        }

        fun delete() {
            GLES20.glDeleteProgram(id)
        }

        companion object {
            fun build(vs: String, fs: String): QuadProgram? {
                val v = compile(GLES20.GL_VERTEX_SHADER, vs)
                val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
                if (v == 0 || f == 0) return null
                val p = GLES20.glCreateProgram()
                GLES20.glAttachShader(p, v)
                GLES20.glAttachShader(p, f)
                GLES20.glLinkProgram(p)
                val status = IntArray(1)
                GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
                GLES20.glDeleteShader(v)
                GLES20.glDeleteShader(f)
                if (status[0] != GLES20.GL_TRUE) {
                    Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(p))
                    GLES20.glDeleteProgram(p)
                    return null
                }
                return QuadProgram(p)
            }

            private fun compile(type: Int, src: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, src)
                GLES20.glCompileShader(s)
                val status = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] != GLES20.GL_TRUE) {
                    Log.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(s))
                    GLES20.glDeleteShader(s)
                    return 0
                }
                return s
            }
        }
    }

    companion object {
        private const val TAG = "LiveviewRenderer"

        private const val VERTEX_SHADER = """
attribute vec2 aPos;
uniform vec2 uDstMin;
uniform vec2 uDstMax;
uniform vec2 uSrcMin;
uniform vec2 uSrcMax;
varying vec2 vTex;
void main() {
    vec2 ndc = mix(uDstMin, uDstMax, aPos);
    vTex = mix(uSrcMin, uSrcMax, aPos);
    gl_Position = vec4(ndc, 0.0, 1.0);
}
"""

        private const val FRAGMENT_MAIN = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uSharpen;
varying vec2 vTex;

vec4 cubicWeights(float x) {
    float x2 = x * x;
    float x3 = x2 * x;
    return vec4(
        -0.5 * x3 + 1.0 * x2 - 0.5 * x,
         1.5 * x3 - 2.5 * x2 + 1.0,
        -1.5 * x3 + 2.0 * x2 + 0.5 * x,
         0.5 * x3 - 0.5 * x2
    );
}

vec4 bicubic(vec2 uv, vec2 texSize) {
    vec2 invTex = 1.0 / texSize;
    vec2 t = uv * texSize - 0.5;
    vec2 f = fract(t);
    t -= f;
    vec4 cx = cubicWeights(f.x);
    vec4 cy = cubicWeights(f.y);
    vec4 c = t.xxyy + vec2(-0.5, 1.5).xyxy;
    vec4 s = vec4(cx.xz + cx.yw, cy.xz + cy.yw);
    vec4 off = c + vec4(cx.yw, cy.yw) / s;
    off *= invTex.xxyy;
    vec4 s0 = texture2D(uTex, off.xz);
    vec4 s1 = texture2D(uTex, off.yz);
    vec4 s2 = texture2D(uTex, off.xw);
    vec4 s3 = texture2D(uTex, off.yw);
    float sx = s.x / (s.x + s.y);
    float sy = s.z / (s.z + s.w);
    return mix(mix(s3, s2, sx), mix(s1, s0, sx), sy);
}

void main() {
    vec4 c = bicubic(vTex, uTexSize);
    if (uSharpen > 0.001) {
        vec2 tx = 1.0 / uTexSize;
        vec4 n = texture2D(uTex, vTex + vec2(0.0, -tx.y));
        vec4 s = texture2D(uTex, vTex + vec2(0.0,  tx.y));
        vec4 e = texture2D(uTex, vTex + vec2( tx.x, 0.0));
        vec4 w = texture2D(uTex, vTex + vec2(-tx.x, 0.0));
        vec3 blur = (n.rgb + s.rgb + e.rgb + w.rgb) * 0.25;
        c.rgb = clamp(c.rgb + (c.rgb - blur) * uSharpen, 0.0, 1.0);
    }
    gl_FragColor = vec4(c.rgb, 1.0);
}
"""
    }
}
