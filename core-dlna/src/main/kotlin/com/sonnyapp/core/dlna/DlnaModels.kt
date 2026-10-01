package com.sonnyapp.core.dlna

/**
 * DLNA 资源（一个 <res> 条目）。
 *
 * 索尼的实现规律（ILCE-6300 实测）：
 *   ORG_xxx  = 卡上的**原文件**（带 size / resolution，JPEG 才有）
 *   LRG_xxx  = 大预览（转码）
 *   SM_xxx   = 小预览（转码）
 *   TN_xxx   = 缩略图（转码）
 *
 * RAW(.ARW) 只给 LRG/SM/TN 三个转码预览，**没有 ORG_** ——
 *    直接请求 ORG_*.ARW 会得到 HTTP 406。相机固件就是这么设计的。
 */
data class DlnaResource(
    val url: String,
    val protocolInfo: String = "",
    val sizeBytes: Long = -1L,
    val resolution: String = "",
    val duration: String = "",
) {
    /** DLNA profile 名，如 JPEG_TN / JPEG_SM / JPEG_LRG。没有则为空。 */
    val profile: String
        get() = Regex("DLNA\\.ORG_PN=([A-Za-z0-9_]+)").find(protocolInfo)?.groupValues?.get(1) ?: ""

    /** 是否为转码内容（DLNA.ORG_CI=1 表示"转换过"）。 */
    val isConverted: Boolean get() = protocolInfo.contains("DLNA.ORG_CI=1")

    val isThumbnail: Boolean get() = profile == "JPEG_TN"
    val isPreviewLarge: Boolean get() = profile == "JPEG_LRG"

    /** 是否卡上原文件 —— 以 URL 里的 ORG_ 标记为准（索尼的实际做法）。 */
    val isOriginal: Boolean get() = url.contains("/ORG_")
}

/**
 * DLNA 内容项（<container> 或 <item>）。
 */
data class DlnaItem(
    val id: String,
    val parentId: String = "",
    val title: String = "",
    val isContainer: Boolean = false,
    val childCount: Int = -1,
    val upnpClass: String = "",
    val date: String = "",
    /** 索尼扩展：av:mediaClass，实测 "P,V" 表示 Photo + Video */
    val mediaClass: String = "",
    val resources: List<DlnaResource> = emptyList(),
) {
    val thumbnailUrl: String? get() = resources.firstOrNull { it.isThumbnail }?.url
    val previewUrl: String?
        get() = resources.firstOrNull { it.isPreviewLarge }?.url
            ?: resources.firstOrNull { !it.isThumbnail }?.url

    /** 卡上原文件地址；RAW / 视频没有，返回 null。 */
    val originalUrl: String? get() = resources.firstOrNull { it.isOriginal }?.url

    /** 原文件大小（字节）；未知返回 -1。 */
    val originalSize: Long get() = resources.firstOrNull { it.isOriginal }?.sizeBytes ?: -1L

    val extension: String get() = title.substringAfterLast('.', "").uppercase()

    val isRaw: Boolean get() = extension == "ARW" || extension == "RAW" || extension == "SRF"

    val isVideo: Boolean
        get() = upnpClass.contains("videoItem") ||
            extension in setOf("MP4", "MTS", "M2TS", "MOV", "AVI", "MPG")

    /** 能否下载到卡上的原文件。RAW / 视频为 false。 */
    val canDownloadOriginal: Boolean get() = originalUrl != null

    /**
     * 实际可下载的最佳资源。
     *
     * 优先卡上原文件（ORG_）；RAW 没有原文件时退而求其次给**大预览**（LRG_）。
     * 这样 RAW 至少能下到一张看得清的图，而不是完全没得下。
     */
    val bestDownloadUrl: String? get() = originalUrl ?: previewUrl

    /** 上面那个 URL 是不是卡上原文件。 */
    val downloadIsOriginal: Boolean get() = originalUrl != null
}

/** 一次 Browse 的结果。 */
data class DidlResult(
    val items: List<DlnaItem> = emptyList(),
    val numberReturned: Int = 0,
    val totalMatches: Int = 0,
    val updateId: String = "",
) {
    val containers: List<DlnaItem> get() = items.filter { it.isContainer }
    val files: List<DlnaItem> get() = items.filter { !it.isContainer }
    val hasMore: Boolean get() = numberReturned > 0 && items.size < totalMatches
}
