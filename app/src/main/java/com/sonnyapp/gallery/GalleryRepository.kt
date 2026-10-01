package com.sonnyapp.gallery

import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.sonnyapp.camera.CameraNetwork
import com.sonnyapp.camera.DlnaClient
import com.sonnyapp.core.dlna.DidlResult
import com.sonnyapp.core.dlna.DlnaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 相册仓库：连相机热点 -> DLNA 发现 -> 浏览 -> 下载。
 *
 * ## 与遥控模式的关系
 *
 * 相机一次只能跑一个模式（遥控 或 传照片），所以这里用**独立的 CameraNetwork 实例**，
 * 进入相册时重新连一次热点，退出时释放。
 */
class GalleryRepository(private val context: Context) {

    private val net = CameraNetwork(context)
    private var client: DlnaClient? = null

    val network: Network? get() = net.network
    val isReady: Boolean get() = client?.isReady == true

    /** 当前 Wi-Fi 状态（判断用户连的是不是相机）。 */
    fun wifiState(): com.sonnyapp.camera.WifiState = net.wifiState()
    val friendlyName: String get() = client?.friendlyName ?: ""

    /**
     * 用**系统已经连上**的 Wi-Fi（用户在系统设置里连的相机热点）。
     * 不弹系统对话框，也不需要 SSID/密码。
     */
    suspend fun connectViaSystemWifi(): String? = withContext(Dispatchers.IO) {
        val n = net.adoptSystemWifi()
            ?: return@withContext "没有找到已连接的 Wi-Fi。\n请先在系统设置里连上相机的热点，再回来。"
        return@withContext afterNetwork(n)
    }

    /** 连热点 + DLNA 发现。返回错误信息，成功返回 null。 */
    suspend fun connect(ssid: String, passphrase: String?): String? = withContext(Dispatchers.IO) {
        try {
            net.connect(ssid, passphrase)
            net.acquireMulticastLock()
        } catch (e: Exception) {
            return@withContext "连接相机热点失败：" + (e.message ?: e.toString())
        }
        val n = net.network ?: return@withContext "相机网络句柄为空"
        return@withContext afterNetwork(n)
    }

    /** 网络就绪后做 DLNA 发现。 */
    private fun afterNetwork(n: Network): String? {
        val c = DlnaClient(n)
        val ok = try {
            c.discover()
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            return "没有发现 DLNA 媒体服务。\n" +
                "请确认相机停留在「发送到智能手机」界面。"
        }
        client = c
        return null
    }

    suspend fun browse(objectId: String = "0", startIndex: Int = 0, count: Int = 100): DidlResult =
        withContext(Dispatchers.IO) {
            try {
                client?.browse(objectId, startIndex, count) ?: DidlResult()
            } catch (e: Exception) {
                Log.w(TAG, "browse 失败: " + e.message)
                DidlResult()
            }
        }

    /** 服务器自报支持的格式。用来在 UI 上说明「为什么没有 RAW」。 */
    suspend fun readProtocolInfo(): List<String> = withContext(Dispatchers.IO) {
        try {
            client?.readProtocolInfo() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 打开缩略图流（负责方需关闭）。 */
    suspend fun openStream(url: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            client?.openStream(url)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 下载到指定位置。
     *
     * @param treeUri SAF 目录（用户选的保存位置）。为 null 时存到系统相册的 Pictures/SonnyApp。
     */
    suspend fun downloadTo(
        item: DlnaItem,
        url: String,
        treeUri: String?,
        onProgress: (Long, Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext -1L
        if (treeUri != null) {
            return@withContext downloadToTree(c, item, url, Uri.parse(treeUri), onProgress)
        }
        return@withContext downloadToMediaStore(c, item, url, onProgress)
    }

    /**
     * 写到用户用 SAF 选的目录。
     *
     * 注意：Android 10+ 的分区存储不允许随便写绝对路径，SAF 是**唯一正规做法**。
     * 文件名重复时自动加 (1)(2) 后缀，不覆盖已有文件。
     */
    private fun downloadToTree(
        c: DlnaClient,
        item: DlnaItem,
        url: String,
        tree: Uri,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val resolver = context.contentResolver
        val mime = if (item.isVideo) "video/mp4" else "image/jpeg"
        return try {
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val name = uniqueName(tree, item.title)
            val fileUri = DocumentsContract.createDocument(resolver, parentDoc, mime, name)
                ?: return -1L
            var written = -1L
            resolver.openOutputStream(fileUri, "wt")?.use { out ->
                written = c.download(url, out, onProgress)
            }
            if (written <= 0) {
                try { resolver.delete(fileUri, null, null) } catch (e: Exception) { }
                -1L
            } else {
                written
            }
        } catch (e: Exception) {
            Log.w(TAG, "写入 SAF 目录失败: " + e.message)
            -1L
        }
    }

    /** 目录里已有同名文件时，生成一个不冲突的名字。 */
    private fun uniqueName(tree: Uri, name: String): String {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val existing = HashSet<String>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { cur ->
                while (cur.moveToNext()) existing.add(cur.getString(0) ?: "")
            }
        } catch (e: Exception) {
            // 查不到就当没有重名
        }
        if (!existing.contains(name)) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (i < 10000) {
            val cand = base + " (" + i + ")" + (if (ext.isEmpty()) "" else "." + ext)
            if (!existing.contains(cand)) return cand
            i++
        }
        return name
    }

    /**
     * 下载到系统相册（MediaStore）。
     *
     * 用 IS_PENDING 标记：下载完成前对其他 App 不可见，避免半张图被人打开。
     * 下载失败会把刚建的空条目删掉。
     */
    private fun downloadToMediaStore(
        c: DlnaClient,
        item: DlnaItem,
        url: String,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        val resolver = context.contentResolver
        val isVideo = item.isVideo
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val mime = if (isVideo) "video/mp4" else "image/jpeg"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.title)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/SonnyApp" else "Pictures/SonnyApp")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return -1L
        var written = -1L
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                written = c.download(url, out, onProgress)
            }
            if (written <= 0) {
                resolver.delete(uri, null, null)
                return -1L
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            written
        } catch (e: Exception) {
            Log.w(TAG, "保存失败: " + e.message)
            try { resolver.delete(uri, null, null) } catch (e2: Exception) { }
            -1L
        }
    }

    fun disconnect() {
        client = null
        try { net.release() } catch (e: Exception) { }
    }

    companion object {
        private const val TAG = "GalleryRepository"
    }
}
