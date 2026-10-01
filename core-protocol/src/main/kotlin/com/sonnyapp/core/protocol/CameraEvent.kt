package com.sonnyapp.core.protocol

/**
 * getEvent 返回的相机状态快照。
 *
 * 字段在相机不支持时留空 —— 上层按 "空 = 不显示" 处理，不要显示占位符。
 *
 * 注意几个字段的语义差异（容易被误读）：
 *  - [fNumber] 等是**当前值**，可读
 *  - 对应的 xxxCandidates 是**可选范围**，但为空**不代表不能读**（a6300 实测：
 *    isoSpeedRateCandidates 为空，但 currentIsoSpeedRate 有值 "400"）
 */
data class CameraEvent(
    val fNumber: String = "",
    val isoSpeedRate: String = "",
    val focusMode: String = "",
    val exposureMode: String = "",
    val shutterSpeed: String = "",
    val flashMode: String = "",
    val shootMode: String = "",
    val cameraStatus: String = "",
    val exposureCompensation: Int = 0,
    val exposureCompensationMin: Int = 0,
    val exposureCompensationMax: Int = 0,
    val exposureCompensationStep: Int = 1,
    val selfTimer: Int = 0,
    val selfTimerCandidates: List<Int> = emptyList(),
    val liveviewStatus: Boolean = false,
    val programShift: Boolean = false,
) {
    val hasExposureInfo: Boolean get() = fNumber.isNotEmpty() || isoSpeedRate.isNotEmpty()

    companion object {
        private const val DQ = "\""

        /** 抠出 "type":"<name>" 对应的那个 JSON 对象（到第一个 } 为止）。 */
        private fun block(raw: String, type: String): String? {
            val marker = DQ + "type" + DQ + ":" + DQ + type + DQ
            val i = raw.indexOf(marker)
            if (i < 0) return null
            val j = raw.indexOf('}', i)
            return if (j > i) raw.substring(i, j + 1) else raw.substring(i)
        }

        private fun str(b: String?, key: String): String {
            if (b == null) return ""
            val marker = DQ + key + DQ + ":" + DQ
            val i = b.indexOf(marker)
            if (i < 0) return ""
            val s = i + marker.length
            val e = b.indexOf(DQ, s)
            return if (e > s) b.substring(s, e) else ""
        }

        private fun num(b: String?, key: String): Int? {
            if (b == null) return null
            val marker = DQ + key + DQ + ":"
            val i = b.indexOf(marker)
            if (i < 0) return null
            var s = i + marker.length
            var e = s
            if (e < b.length && (b[e] == '-' || b[e] == '+')) e++
            val digitsStart = e
            while (e < b.length && b[e].isDigit()) e++
            if (e == digitsStart) return null
            return b.substring(s, e).toIntOrNull()
        }

        private fun bool(b: String?, key: String): Boolean {
            if (b == null) return false
            val marker = DQ + key + DQ + ":"
            val i = b.indexOf(marker)
            if (i < 0) return false
            return b.startsWith("true", i + marker.length)
        }

        private fun intArray(b: String?, key: String): List<Int> {
            if (b == null) return emptyList()
            val marker = DQ + key + DQ + ":["
            val i = b.indexOf(marker)
            if (i < 0) return emptyList()
            val j = b.indexOf(']', i)
            if (j < 0) return emptyList()
            val body = b.substring(i + marker.length, j)
            if (body.isBlank()) return emptyList()
            return body.split(',').mapNotNull { it.trim().toIntOrNull() }
        }

        fun parse(raw: String): CameraEvent {
            val fNum = block(raw, "fNumber")
            val iso = block(raw, "isoSpeedRate")
            val focus = block(raw, "focusMode")
            val expoMode = block(raw, "exposureMode")
            val shutter = block(raw, "shutterSpeed")
            val flash = block(raw, "flashMode")
            val shoot = block(raw, "shootMode")
            val status = block(raw, "cameraStatus")
            val ev = block(raw, "exposureCompensation")
            val selfT = block(raw, "selfTimer")
            val lv = block(raw, "liveviewStatus")
            val prog = block(raw, "programShift")

            return CameraEvent(
                fNumber = str(fNum, "currentFNumber"),
                isoSpeedRate = str(iso, "currentIsoSpeedRate"),
                focusMode = str(focus, "currentFocusMode"),
                exposureMode = str(expoMode, "currentExposureMode"),
                shutterSpeed = str(shutter, "currentShutterSpeed"),
                flashMode = str(flash, "currentFlashMode"),
                shootMode = str(shoot, "currentShootMode"),
                cameraStatus = str(status, "cameraStatus"),
                exposureCompensation = num(ev, "currentExposureCompensation") ?: 0,
                exposureCompensationMin = num(ev, "minExposureCompensation") ?: 0,
                exposureCompensationMax = num(ev, "maxExposureCompensation") ?: 0,
                exposureCompensationStep = num(ev, "stepIndexOfExposureCompensation") ?: 1,
                selfTimer = num(selfT, "currentSelfTimer") ?: 0,
                selfTimerCandidates = intArray(selfT, "selfTimerCandidates"),
                liveviewStatus = bool(lv, "liveviewStatus"),
                programShift = bool(prog, "isShifted"),
            )
        }
    }
}
