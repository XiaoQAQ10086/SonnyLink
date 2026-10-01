package com.sonnyapp.gallery

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sonnyapp.core.dlna.DlnaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class DlState { RUNNING, DONE, FAILED }

/** 列表筛选模式。默认仅显示原图；RAW 预览图数量过多会影响滚动性能。 */
enum class FilterMode { ORIGINALS_ONLY, ALL }

data class DlTask(
    val id: String,
    val title: String,
    val done: Long = 0L,
    val total: Long = -1L,
    val state: DlState = DlState.RUNNING,
    val note: String = "",
    /** 测速用：上次采样时刻与字节数 */
    val lastAt: Long = 0L,
    val lastDone: Long = 0L,
    /** 最近一次采样的瞬时速度（字节/秒） */
    val rate: Long = 0L,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
}

/** 按拍摄日期分的一组。 */
data class DayGroup(val date: String, val label: String, val items: List<DlnaItem>)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = GalleryRepository(app)
    private val prefs = app.getSharedPreferences("sonnyapp", 0)

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _protocols = MutableStateFlow<List<String>>(emptyList())
    val protocols: StateFlow<List<String>> = _protocols.asStateFlow()

    /** 全部条目（未筛选，供统计用）。 */
    private val _allFiles = MutableStateFlow<List<DlnaItem>>(emptyList())
    val allFiles: StateFlow<List<DlnaItem>> = _allFiles.asStateFlow()

    /** 按日期分组后的列表。 */
    private val _groups = MutableStateFlow<List<DayGroup>>(emptyList())
    val groups: StateFlow<List<DayGroup>> = _groups.asStateFlow()

    private val _filter = MutableStateFlow(FilterMode.ORIGINALS_ONLY)
    val filter: StateFlow<FilterMode> = _filter.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks.asStateFlow()

    /** 保存位置（SAF 目录 URI 字符串）。null = 存到系统相册的 Pictures/SonnyApp。 */
    private val _wifi = MutableStateFlow(com.sonnyapp.camera.WifiState())
    val wifiState: StateFlow<com.sonnyapp.camera.WifiState> = _wifi.asStateFlow()

    /**
     * 进入连接页时自动判断：当前 Wi-Fi 为相机热点则直接连接，无需手动操作。
     * 否则仅刷新状态，由界面引导前往系统设置。
     */
    fun autoConnect() {
        if (_connected.value || _busy.value) return
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { repo.wifiState() }
            _wifi.value = st
            if (st.looksLikeCamera) connectViaSystemWifi()
        }
    }

    /** 只刷新 Wi-Fi 状态，不连接。 */
    fun refreshWifiState() {
        viewModelScope.launch {
            _wifi.value = withContext(Dispatchers.IO) { repo.wifiState() }
        }
    }

    private val _thumbStats = MutableStateFlow("")
    val thumbStats: StateFlow<String> = _thumbStats.asStateFlow()
    private var statsJob: Job? = null

    private val _saveTreeUri = MutableStateFlow(prefs.getString(KEY_TREE, null))
    val saveTreeUri: StateFlow<String?> = _saveTreeUri.asStateFlow()

    /**
     * 缩略图并发闸门。
     *
     * 不加限制时，网格里每个可见项都会同时发请求，
     * 相机的 Wi-Fi 只有 1~3 Mbps，几百个并发请求直接把链路打满 —— 表现为「打开就卡死」。
     * 限制成 3 个并发后，缩略图按顺序补齐，滚动反而更顺。
     */
    // 实测相机返回单张缩略图约需 500~800ms（相机端处理耗时，非带宽限制）。
    // 单张延迟没法降，只能靠并发把总吞吐拉起来：10 路约等于 1.4 屏/秒。
    private val thumbGate = Semaphore(10)

    /**
     * 下载并发数。相机端是它自己的小 HTTP 服务（端口 60151），
     * 提高并发在弱链路上通常有用，但超过相机处理能力就只是互相抢带宽。
     * 因此做成可调，可按实测速度选择。
     */
    private val _concurrency = MutableStateFlow(prefs.getInt(KEY_CONC, 3))
    val concurrency: StateFlow<Int> = _concurrency.asStateFlow()

    fun setConcurrency(n: Int) {
        _concurrency.value = n.coerceIn(1, 8)
        prefs.edit().putInt(KEY_CONC, _concurrency.value).apply()
    }

    /**
     * 缩略图内存缓存，**按字节数限制**（24 MB）。
     *
     * 必须重写 sizeOf —— LruCache 默认对每个条目返回 1，
     * 不重写的话传入的 24*1024*1024 会被当成「2400 万个条目」的上限，
     * 等于**永不淘汰**：每张看过的缩略图都永久占着内存，越滚越大。
     * 不重写此方法会使缓存永不淘汰，内存持续增长。
     *
     * 256px RGB_565 约 131 KB/张，24 MB 能缓存约 190 张。
     */
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 磁盘缩略图目录（放在 cacheDir，系统空间紧张时会自动清理） */
    private val thumbDir: java.io.File = java.io.File(app.cacheDir, "thumbs")

    @Volatile private var hitsMem = 0L
    @Volatile private var hitsDisk = 0L
    @Volatile private var hitsNet = 0L
    @Volatile private var lastNetMs = 0L

    private var downloadJob: Job? = null

    val savedSsid: String get() = prefs.getString("ssid", "DIRECT-p2E0:ILCE-6300") ?: ""
    val savedPass: String get() = prefs.getString("pass", "") ?: ""

    // ------------------------------------------------------------ 连接

    /**
     * 默认路径：相机热点已在**系统设置**中连接，App 直接接管该 Wi-Fi。
     * 不需要 SSID/密码，也不弹系统对话框。
     */
    fun connectViaSystemWifi() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在检查已连接的 Wi-Fi …"
            val err = repo.connectViaSystemWifi()
            if (err != null) {
                _status.value = err
                _connected.value = false
                _busy.value = false
                return@launch
            }
            _connected.value = true
            _status.value = "已连接 " + repo.friendlyName
            _protocols.value = repo.readProtocolInfo()
            _busy.value = false
            loadAll()
            return@launch
        }
    }

    /** 手动路径：在 App 里输入 SSID/密码。 */
    fun connect(ssid: String, pass: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "正在连接 " + ssid + " …"
            prefs.edit().putString("ssid", ssid).putString("pass", pass).apply()
            val err = repo.connect(ssid, pass.ifEmpty { null })
            if (err != null) {
                _status.value = err
                _connected.value = false
                _busy.value = false
                return@launch
            }
            _connected.value = true
            _status.value = "已连接 " + repo.friendlyName
            _protocols.value = repo.readProtocolInfo()
            // 先清忙再调 loadAll（loadAll 开头是"忙就返回"，顺序反了会静默什么都不做）
            _busy.value = false
            loadAll()
            return@launch
        }
    }

    fun disconnect() {
        downloadJob?.cancel()
        viewModelScope.launch {
            repo.disconnect()
            _connected.value = false
            _allFiles.value = emptyList()
            _groups.value = emptyList()
            _selected.value = emptySet()
            _tasks.value = emptyList()
            _protocols.value = emptyList()
            thumbCache.evictAll()
            _status.value = "已断开"
        }
    }

    // ------------------------------------------------------------ 读取

    fun loadAll() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val all = ArrayList<DlnaItem>()
                var containers: List<DlnaItem> = emptyList()
                var id = "0"
                var depth = 0
                while (depth < 4) {
                    val r = repo.browse(id, 0, 200)
                    all.addAll(r.files)
                    containers = r.containers
                    if (containers.size != 1 || r.files.isNotEmpty()) break
                    id = containers[0].id
                    depth++
                }
                // 并行读取日期文件夹。相机每次 Browse 要 200~500ms，
                // 14 个串行就是 3~7 秒；并行 6 路只要 1 秒左右。
                val total = containers.size
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                val gate = Semaphore(6)
                val lists = coroutineScope {
                    containers.map { c ->
                        async(Dispatchers.IO) {
                            val files = gate.withPermit { repo.browse(c.id, 0, 500).files }
                            _status.value = "读取目录 " + done.incrementAndGet() + " / " + total + " …"
                            files
                        }
                    }.awaitAll()
                }
                for (l in lists) all.addAll(l)
                val sorted = all.sortedWith(compareByDescending { it.date })
                _allFiles.value = sorted
                regroup()
            } catch (e: Exception) {
                _status.value = "读取失败：" + (e.message ?: "")
            } finally {
                _busy.value = false
            }
        }
    }

    fun setFilter(mode: FilterMode) {
        _filter.value = mode
        regroup()
    }

    /** 按筛选条件重建「按日期分组」的列表。 */
    private fun regroup() {
        val src = when (_filter.value) {
            FilterMode.ORIGINALS_ONLY -> _allFiles.value.filter { it.canDownloadOriginal }
            FilterMode.ALL -> _allFiles.value
        }
        val byDay = LinkedHashMap<String, MutableList<DlnaItem>>()
        for (item in src) {
            val day = item.date.substringBefore('T').ifEmpty { "0000-00-00" }
            byDay.getOrPut(day) { ArrayList() }.add(item)
        }
        _groups.value = byDay.entries
            .sortedByDescending { it.key }
            .map { DayGroup(it.key, prettyDate(it.key), it.value) }
        val orig = _allFiles.value.count { it.canDownloadOriginal }
        _status.value = "已显示 " + src.size + " 张 · 全部 " + _allFiles.value.size +
            " 张（可下原图 " + orig + "）"
    }

    // ------------------------------------------------------------ 选择

    fun toggle(id: String) {
        val cur = _selected.value
        _selected.value = if (cur.contains(id)) cur - id else cur + id
    }

    fun selectAll() {
        _selected.value = _groups.value.flatMap { it.items }.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    // ------------------------------------------------------------ 下载

    fun downloadSelected() {
        val ids = _selected.value
        if (ids.isEmpty() || downloadJob?.isActive == true) return
        val targets = _groups.value.flatMap { it.items }.filter { ids.contains(it.id) }
        val treeUri = _saveTreeUri.value

        val gate = Semaphore(_concurrency.value)
        val now0 = System.currentTimeMillis()

        downloadJob = viewModelScope.launch {
            _tasks.value = targets.map { DlTask(it.id, it.title, lastAt = now0) }
            var okCount = 0
            var failCount = 0

            // 并发下载：相机端是小 HTTP 服务，多路并发能明显提高总吞吐
            coroutineScope {
                targets.mapIndexed { idx, item ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val url = item.bestDownloadUrl
                            if (url == null) {
                                updateTask(idx) { it.copy(state = DlState.FAILED, note = "没有可用资源") }
                                return@withPermit false
                            }
                            val written = repo.downloadTo(
                                item = item,
                                url = url,
                                treeUri = treeUri,
                                onProgress = { done, total ->
                                    val now = System.currentTimeMillis()
                                    updateTask(idx) { t ->
                                        // 每 500ms 采一次瞬时速度，太快会抖动得看不出来
                                        if (now - t.lastAt >= 500) {
                                            val dt = now - t.lastAt
                                            val rate = if (dt > 0) (done - t.lastDone) * 1000L / dt else t.rate
                                            t.copy(
                                                done = done, total = total,
                                                lastAt = now, lastDone = done, rate = rate,
                                            )
                                        } else {
                                            t.copy(done = done, total = total)
                                        }
                                    }
                                },
                            )
                            if (written > 0) {
                                val tag = if (item.downloadIsOriginal) "原图 " else "预览图 "
                                updateTask(idx) {
                                    it.copy(state = DlState.DONE, done = written, note = tag + humanSize(written))
                                }
                                true
                            } else {
                                updateTask(idx) { it.copy(state = DlState.FAILED, note = "下载失败") }
                                false
                            }
                        }
                    }
                }.awaitAll().forEach { if (it) okCount++ else failCount++ }
            }
            _status.value = "下载完成：成功 " + okCount + "，失败 " + failCount
            _selected.value = emptySet()
        }
    }

    private fun updateTask(index: Int, f: (DlTask) -> DlTask) {
        val list = _tasks.value.toMutableList()
        if (index < 0 || index >= list.size) return
        list[index] = f(list[index])
        _tasks.value = list
    }

    fun clearTasks() {
        _tasks.value = emptyList()
    }

    // ---------------------------------------------------------- 保存位置

    fun setSaveTreeUri(uri: String?) {
        _saveTreeUri.value = uri
        prefs.edit().putString(KEY_TREE, uri).apply()
    }

    // ---------------------------------------------------------- 缩略图

    /**
     * 取缩略图。**并发受限**，避免几百个请求同时打满相机 Wi-Fi。
     * 已经从缓存拿到的直接返回，不进闸门。
     */
    /**
     * **同步**查内存缓存。UI 第一帧就用它拿图，避免"先闪灰块再出图"。
     *
     * 这正是"滚回去看起来像重新加载"的原因：格子总是从 null 起步，
     * 等 90ms 防抖 + 缓存查询之后才出图 —— 120Hz 下就是 11 帧灰块。
     */
    fun peekThumb(id: String): Bitmap? = thumbCache.get(id)

    /**
     * 取缩略图。三级缓存：内存 -> 磁盘 -> 网络。
     *
     * 磁盘缓存是关键 —— 相机 Wi-Fi 只有 1~3 Mbps，同一个位置重拉要几秒；
     * 存到磁盘后**再次滚回来是瞬时的**。
     */
    suspend fun loadThumb(item: DlnaItem): Bitmap? {
        thumbCache.get(item.id)?.let {
            hitsMem++
            return it
        }
        return thumbGate.withPermit {
            thumbCache.get(item.id)?.let { return@withPermit it }

            val f = thumbFile(item.id)
            if (f.exists() && f.length() > 0) {
                val cached = withContext(Dispatchers.IO) {
                    try {
                        // RGB_565：缩略图看不出色差，占用只有 ARGB_8888 的一半
                        val o = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeFile(f.absolutePath, o)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (cached != null) {
                    thumbCache.put(item.id, cached)
                    hitsDisk++
                    return@withPermit cached
                }
            }

            val url = item.thumbnailUrl ?: item.previewUrl ?: return@withPermit null
            val t0 = System.currentTimeMillis()
            val bmp = withContext(Dispatchers.IO) { decodeThumb(url) }
            lastNetMs = System.currentTimeMillis() - t0
            if (bmp != null) {
                thumbCache.put(item.id, bmp)
                hitsNet++
                withContext(Dispatchers.IO) {
                    try {
                        java.io.FileOutputStream(f).use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 82, it)
                        }
                    } catch (e: Exception) {
                        // 写缓存失败不影响显示
                    }
                }
            }
            bmp
        }
    }

    /** 缩略图缓存文件：用 id 的 hash 当文件名，避免非法字符。 */
    private fun thumbFile(id: String): java.io.File {
        if (!thumbDir.exists()) thumbDir.mkdirs()
        return java.io.File(thumbDir, Integer.toHexString(id.hashCode()) + ".jpg")
    }

    /** 缩略图统计，用于诊断「到图慢」还是「渲染慢」。 */
    fun thumbStats(): String {
        val disk = try { thumbDir.list()?.size ?: 0 } catch (e: Exception) { 0 }
        return "缩略图 内存 " + hitsMem + " · 磁盘 " + hitsDisk + " · 网络 " + hitsNet +
            " · 上次网络 " + lastNetMs + "ms · 磁盘已有 " + disk + " 张"
    }

    /**
     * 解码缩略图。
     *
     * 必须先探明尺寸、再按 inSampleSize 降采样 —— 这是滑动卡顿的真正原因。
     *
     * 直接使用 decodeStream 会**按原始尺寸**解成 ARGB_8888：
     *   TN 缺失时会退到大预览 LRG（1616x1080），单张就是 **7 MB**；
     *   网格里十几个格子同时解，内存带宽瞬间被打满，表现就是"滑动卡顿、像没有高刷"。
     *
     * 现在：先读成字节数组 -> inJustDecodeBounds 只探尺寸 -> 按目标边长降采样
     *      -> 用 RGB_565（2 字节/像素，缩略图够用）。同样的 LRG 只占 128 KB。
     */
    private suspend fun decodeThumb(url: String): Bitmap? {
        return try {
            val bytes = repo.openStream(url)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, THUMB_TARGET_PX)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    /** 取能让两边都不小于 target 的最大 2 的幂。 */
    private fun sampleFor(w: Int, h: Int, target: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        var ww = w
        var hh = h
        while (ww / 2 >= target && hh / 2 >= target) {
            ww /= 2
            hh /= 2
            s *= 2
        }
        return s
    }

    override fun onCleared() {
        downloadJob?.cancel()
        repo.disconnect()
        super.onCleared()
    }

    companion object {
        private const val KEY_TREE = "saveTreeUri"
        private const val KEY_CONC = "downloadConcurrency"

        /** 缩略图解码目标边长（像素）。网格格子约 104dp ≈ 286px，256 足够。 */
        private const val THUMB_TARGET_PX = 256

        fun humanSize(bytes: Long): String = when {
            bytes <= 0 -> "?"
            bytes < 1024 -> bytes.toString() + " B"
            bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
            else -> String.format("%.1f MB", bytes / 1048576.0)
        }

        /** "2026-03-27" -> "2026年3月27日" */
        fun prettyDate(day: String): String {
            val p = day.split('-')
            if (p.size != 3) return if (day == "0000-00-00") "未知日期" else day
            val m = p[1].toIntOrNull() ?: return day
            val d = p[2].toIntOrNull() ?: return day
            return p[0] + "年" + m + "月" + d + "日"
        }
    }
}
