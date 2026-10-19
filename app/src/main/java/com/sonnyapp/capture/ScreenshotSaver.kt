package com.sonnyapp.capture

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把取景帧存成图片。
 *
 * 存的是**相机推过来的原始 JPEG 字节**，不是截图后重新压缩的画面：
 * 640x424 的源再压一次只会更糊，而且相机的取景流本身就是 JPEG。
 *
 * 走 MediaStore + IS_PENDING：写入期间其他 App 看不到半张图，
 * 写完才置 0。minSdk 29 起写自己的图片不需要任何权限。
 */
object ScreenshotSaver {

    class Result(val ok: Boolean, val message: String)

    fun saveLiveviewJpeg(context: Context, bytes: ByteArray, width: Int, height: Int): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "SonnyLink_" + stamp + ".jpg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SonnyLink")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = try {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            null
        } ?: return Result(false, "截图失败：无法创建相册条目")

        return try {
            val out = resolver.openOutputStream(uri)
                ?: return Result(false, "截图失败：无法打开写入流")
            out.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result(true, "已截图 " + width + "x" + height + " · " + (bytes.size / 1024) + " KB")
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (e2: Exception) { }
            Result(false, "截图失败：" + (e.message ?: e.toString()))
        }
    }
}
