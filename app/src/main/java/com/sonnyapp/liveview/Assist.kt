package com.sonnyapp.liveview

/**
 * 构图网格。
 *
 * 只用**画面矩形内**的线条，不画到黑边上 —— 否则「适应」模式下
 * 网格会跑到黑区里，看起来像画错了。
 */
enum class GridMode(val label: String) {
    OFF("网格 关"),
    THIRDS("网格 九宫"),
    GOLDEN("网格 黄金"),
    CROSS("网格 十字"),
}

/**
 * 峰值对焦强度。数值是着色器里的梯度阈值：
 * 阈值越低，被抓出来的边缘越多（也越吵）。
 */
enum class PeakingLevel(val label: String, val threshold: Float) {
    OFF("峰值 关", 0f),
    LOW("峰值 低", 0.26f),
    MID("峰值 中", 0.16f),
    HIGH("峰值 高", 0.10f),
}

/** 峰值描边颜色。默认红色 —— 画面里最不容易和真实内容混淆的颜色。 */
enum class PeakingColor(val label: String, val r: Float, val g: Float, val b: Float) {
    RED("红", 1f, 0.20f, 0.16f),
    YELLOW("黄", 1f, 0.86f, 0.15f),
    WHITE("白", 1f, 1f, 1f),
    CYAN("青", 0.15f, 0.95f, 1f),
}

/**
 * 监看辅助的全部开关 + 当前变焦。
 *
 * 单独抽一个不可变对象，是因为它要同时喂给三个消费者：
 *  - Compose（画网格/直方图/面板）
 *  - [LiveviewRenderer]（着色器里的峰值、变焦矩形）
 *  - [LiveviewEngine]（是否需要算直方图）
 * 分散成多个 Boolean 会出现「UI 关了但引擎还在算」这种半状态。
 */
data class AssistState(
    val grid: GridMode = GridMode.OFF,
    val peaking: PeakingLevel = PeakingLevel.OFF,
    val peakColor: PeakingColor = PeakingColor.RED,
    val histogram: Boolean = false,
    val zoom: Float = 1f,
    /** 变焦中心（图像归一化坐标）。1x 时无意义。 */
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
) {
    val isZoomed: Boolean get() = zoom > 1.001f

    /** 有任何一项辅助开着 —— 用于胶囊的高亮态。 */
    val anyActive: Boolean
        get() = grid != GridMode.OFF || peaking != PeakingLevel.OFF || histogram || isZoomed

    fun cycleGrid(): AssistState {
        val vals = GridMode.entries
        return copy(grid = vals[(grid.ordinal + 1) % vals.size])
    }

    fun cyclePeaking(): AssistState {
        val vals = PeakingLevel.entries
        return copy(peaking = vals[(peaking.ordinal + 1) % vals.size])
    }
}
