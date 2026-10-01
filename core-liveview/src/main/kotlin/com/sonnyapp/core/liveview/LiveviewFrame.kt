package com.sonnyapp.core.liveview

/**
 * Sony LiveView 容器中的一个包。
 *
 * 线格式（ILCE-6300 实机验证，81/81 包完全吻合，头总长恒为 136 字节）：
 *
 *   [0..7]    8 字节 common header: 0xFF | payloadType u8 | seq u16be | timestamp u32be
 *   [8..11]   魔数 0x24 0x35 0x68 0x79 ("$5hy")
 *   [12..14]  payloadSize   u24 大端（**3 字节**）
 *   [15]      paddingSize   u8
 *   [16..135] 保留区 120 字节
 *   [136..]   payload（payloadSize 字节）
 *
 * **payloadSize 不是 JPEG 长度**。相机把内部缓冲区整个发出来，
 * JPEG 结束后还跟着几十字节**上一帧残留的脏数据**。
 * 取图请用 [jpegOffset] + [jpegLength] 直接喂给 BitmapFactory（**避免多一次拷贝**）。
 *
 * [jpegLength] 在构造时计算一次，不要每帧重复扫描 JPEG 标记。
 */
class LiveviewFrame(
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestampMs: Long,
    val payloadSize: Int,
    val paddingSize: Int,
    val payload: ByteArray,
    /** payload 内 JPEG 起点偏移（实测恒为 0；保留以兼容未来带前导数据的机型） */
    val jpegOffset: Int = 0,
) {
    val isJpeg: Boolean get() = payloadType == PAYLOAD_TYPE_JPEG

    /** SOI..EOI 长度；-1 表示不是完整 JPEG。构造时算一次。 */
    val jpegLength: Int = if (payloadType != PAYLOAD_TYPE_JPEG) {
        -1
    } else {
        val end = JpegInfo.endOfImage(payload, jpegOffset, payload.size)
        if (end < 0) -1 else end - jpegOffset
    }

    /** JPEG 之后的残留脏数据字节数（诊断用） */
    val trailingBytes: Int
        get() = if (jpegLength < 0) payload.size - jpegOffset
        else payload.size - jpegOffset - jpegLength

    val isCompleteJpeg: Boolean get() = jpegLength > 0

    /** 去掉尾部脏数据后的纯 JPEG。会分配新数组；热路径请用 [jpegOffset] + [jpegLength]。 */
    fun jpegBytes(): ByteArray? {
        val n = jpegLength
        if (n <= 0) return null
        return payload.copyOfRange(jpegOffset, jpegOffset + n)
    }

    override fun toString(): String =
        "LiveviewFrame(type=0x" + Integer.toHexString(payloadType) +
            ", seq=" + sequenceNumber +
            ", ts=" + timestampMs +
            ", payload=" + payloadSize +
            ", jpeg=" + jpegLength +
            ", pad=" + paddingSize + ")"

    companion object {
        const val HEADER_LENGTH = 136
        const val MAGIC = 0x24356879
        const val PAYLOAD_TYPE_JPEG = 0x01
        const val PAYLOAD_TYPE_FRAME_INFO = 0x02
        const val PAYLOAD_TYPE_STREAMING_IMAGE = 0x11
        const val PAYLOAD_TYPE_PLAYBACK_INFO = 0x12
    }
}
