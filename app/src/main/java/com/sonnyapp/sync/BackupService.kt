package com.sonnyapp.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 一条待归档记录。uri 指向已经下载到本机的文件（MediaStore 或 SAF）。 */
data class BackupItem(
    val id: String,
    val name: String,
    val uri: String,
    val addedAt: Long,
    val tries: Int = 0,
)

data class BackupConfig(
    val url: String = "",
    val user: String = "",
    val pass: String = "",
    val dir: String = "SonnyLink",
    val auto: Boolean = false,
) {
    val ready: Boolean get() = url.isNotBlank()
}

/**
 * 照片自动归档到 NAS。
 *
 * ## 为什么是「先落地、后上传」而不是「下载完立刻推」
 *
 * 手机连相机热点时**没有外网**：热点是相机自己开的 AP，只跑 DLNA/HTTP，
 * 没有任何上游出口。这时候把照片直接推到 NAS 是物理上做不到的。
 *
 * 所以流程是：
 *   1. 相机连着的当下，照片先落到本机相册；
 *   2. 扔进持久化队列（进程被杀也不会丢）；
 *   3. 一旦手机回到有外网的网络（家里的 Wi-Fi / 移动数据），队列自动上传；
 *      App 在前台时会监听网络变化，不必用户手动点。
 *
 * 队列写 filesDir/backup_queue.json，纯 JSON，不引库。
 */
object BackupService {

    private const val TAG = "BackupService"
    private const val QUEUE_FILE = "backup_queue.json"
    private const val MAX_TRIES = 5
    private const val MAX_BYTES = 200L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null

    private val _items = MutableStateFlow<List<BackupItem>>(emptyList())
    val items: StateFlow<List<BackupItem>> = _items.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var listenerRegistered = false

    // ------------------------------------------------------------- 初始化

    fun init(context: Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        _items.value = loadQueue(c)
        registerNetworkListener(c)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("sonnyapp", 0)

    fun config(): BackupConfig {
        val c = app ?: return BackupConfig()
        val p = prefs(c)
        return BackupConfig(
            url = p.getString(KEY_URL, "") ?: "",
            user = p.getString(KEY_USER, "") ?: "",
            pass = p.getString(KEY_PASS, "") ?: "",
            dir = p.getString(KEY_DIR, "SonnyLink") ?: "SonnyLink",
            auto = p.getBoolean(KEY_AUTO, false),
        )
    }

    fun saveConfig(cfg: BackupConfig) {
        val c = app ?: return
        prefs(c).edit()
            .putString(KEY_URL, cfg.url.trim())
            .putString(KEY_USER, cfg.user)
            .putString(KEY_PASS, cfg.pass)
            .putString(KEY_DIR, cfg.dir.trim())
            .putBoolean(KEY_AUTO, cfg.auto)
            .apply()
    }

    /** 用当前配置试连一次，返回 null 表示通。 */
    fun probe(cfg: BackupConfig): String? {
        if (!cfg.ready) return "请先填写服务器地址"
        return WebDavClient(cfg.url, cfg.user, cfg.pass).probe() ?: run {
            // PROPFIND 可能被禁用，退一步用 MKCOL 探目录
            val cl = WebDavClient(cfg.url, cfg.user, cfg.pass)
            if (cl.ensureDir(cfg.dir.ifBlank { "SonnyLink" })) null
            else cl.lastError ?: "服务器无响应"
        }
    }

    // --------------------------------------------------------------- 入队

    /**
     * 把一张已下载的照片加入归档队列，并在有外网时立刻尝试上传。
     *
     * 文件名从 ContentResolver 查，查不到就用 URI 最后一段。
     */
    fun enqueue(context: Context, uri: String, fallbackName: String) {
        init(context)
        val c = app ?: return
        val name = queryName(c, uri) ?: fallbackName
        val item = BackupItem(
            id = UUID.randomUUID().toString(),
            name = name,
            uri = uri,
            addedAt = System.currentTimeMillis(),
        )
        _items.value = _items.value + item
        saveQueue(c, _items.value)
        Log.i(TAG, "enqueue " + name + " (队列 " + _items.value.size + ")")
        maybeAutoUpload()
    }

    private fun queryName(c: Context, uri: String): String? = try {
        c.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    // --------------------------------------------------------------- 上传

    /** 只有存在「已验证可用」的网络时才自动上传（相机热点不算）。 */
    fun maybeAutoUpload() {
        val c = app ?: return
        if (!config().auto) return
        if (_items.value.isEmpty()) return
        if (_running.value) return
        if (!hasInternet(c)) {
            _status.value = "已排队 " + _items.value.size + " 张，等回到有网络的 Wi-Fi 再自动归档"
            return
        }
        uploadNow()
    }

    fun uploadNow() {
        val c = app ?: return
        if (_running.value) return
        val cfg = config()
        if (!cfg.ready) {
            _status.value = "未配置 NAS 地址"
            return
        }
        val queue = _items.value
        if (queue.isEmpty()) {
            _status.value = "队列为空"
            return
        }

        _running.value = true
        scope.launch {
            val client = WebDavClient(cfg.url, cfg.user, cfg.pass)
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val remoteDir = listOf(cfg.dir.trim('/'), day)
                .filter { it.isNotEmpty() }
                .joinToString("/")

            if (!client.ensureDir(remoteDir)) {
                _status.value = client.lastError ?: "无法创建远程目录"
                _running.value = false
                return@launch
            }

            var ok = 0
            var failed = 0
            for (item in queue) {
                _status.value = "上传中 " + item.name
                val bytes = readBytes(c, item.uri)
                if (bytes == null) {
                    // 本地文件没了（用户删了照片）：直接从队列移除，不算失败
                    removeItem(c, item.id)
                    continue
                }
                val st = client.put(remoteDir + "/" + item.name, bytes, mimeOf(item.name))
                if (st in 200..299) {
                    ok++
                    removeItem(c, item.id)
                } else {
                    failed++
                    val tries = item.tries + 1
                    if (tries >= MAX_TRIES) {
                        removeItem(c, item.id)
                    } else {
                        _items.value = _items.value.map { if (it.id == item.id) it.copy(tries = tries) else it }
                        saveQueue(c, _items.value)
                    }
                }
            }
            _status.value = "归档完成：成功 " + ok + "，失败 " + failed
            _running.value = false
        }
    }

    fun removeItem(context: Context, id: String) {
        val c = app ?: context.applicationContext
        _items.value = _items.value.filterNot { it.id == id }
        saveQueue(c, _items.value)
    }

    fun clearQueue(context: Context) {
        init(context)
        _items.value = emptyList()
        saveQueue(context.applicationContext, emptyList())
        _status.value = "队列已清空"
    }

    private fun readBytes(c: Context, uri: String): ByteArray? = try {
        c.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        Log.w(TAG, "读取本地文件失败: " + e.message)
        null
    }

    private fun mimeOf(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }

    // --------------------------------------------------------- 网络监听

    private fun hasInternet(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 回到有外网的网络时自动补传。
     *
     * 用 registerDefaultNetworkCallback：相机热点也是「默认网络」，
     * 所以必须再用 VALIDATED 过滤 —— 热点永远拿不到 VALIDATED。
     */
    private fun registerNetworkListener(c: Context) {
        if (listenerRegistered) return
        listenerRegistered = true
        try {
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    maybeAutoUpload()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        maybeAutoUpload()
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "注册网络回调失败: " + e.message)
        }
    }

    // ---------------------------------------------------------- 持久化

    private fun queueFile(c: Context) = File(c.filesDir, QUEUE_FILE)

    private fun loadQueue(c: Context): List<BackupItem> {
        val f = queueFile(c)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<BackupItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    BackupItem(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        uri = o.optString("uri"),
                        addedAt = o.optLong("addedAt"),
                        tries = o.optInt("tries"),
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "队列文件损坏，已忽略: " + e.message)
            emptyList()
        }
    }

    private fun saveQueue(c: Context, list: List<BackupItem>) {
        try {
            val arr = JSONArray()
            for (it in list) {
                arr.put(
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                        put("uri", it.uri)
                        put("addedAt", it.addedAt)
                        put("tries", it.tries)
                    }
                )
            }
            queueFile(c).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "写队列失败: " + e.message)
        }
    }

    private const val KEY_URL = "davUrl"
    private const val KEY_USER = "davUser"
    private const val KEY_PASS = "davPass"
    private const val KEY_DIR = "davDir"
    private const val KEY_AUTO = "davAuto"
}
