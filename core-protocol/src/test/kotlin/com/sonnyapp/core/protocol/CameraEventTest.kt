package com.sonnyapp.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 a6300 实机抓取的 getEvent 原文做样本（probe2.txt）。
 * 这些断言锁定的是"相机实际怎么发"，不是我们以为它怎么发。
 */
class CameraEventTest {

    private val realEvent = """
{"id":9,"result":[{"type":"availableApiList","names":["getVersions","actTakePicture"]},
{"type":"cameraStatus","cameraStatus":"IDLE"},
{"type":"liveviewStatus","liveviewStatus":true},null,[],[],null,null,null,[],
{"type":"exposureMode","exposureModeCandidates":[],"currentExposureMode":"Manual"},
{"type":"postviewImageSize","postviewImageSizeCandidates":["Original","2M"],"currentPostviewImageSize":"2M"},
{"type":"selfTimer","currentSelfTimer":0,"selfTimerCandidates":[0,2]},
{"type":"shootMode","shootModeCandidates":["still"],"currentShootMode":"still"},null,null,null,
{"type":"exposureCompensation","minExposureCompensation":-15,"stepIndexOfExposureCompensation":1,"maxExposureCompensation":15,"currentExposureCompensation":3},
{"type":"flashMode","flashModeCandidates":[],"currentFlashMode":"on"},
{"type":"fNumber","fNumberCandidates":["3.5","4.0","5.0"],"currentFNumber":"3.5"},
{"type":"focusMode","focusModeCandidates":[],"currentFocusMode":"AF-S"},
{"type":"isoSpeedRate","isoSpeedRateCandidates":[],"currentIsoSpeedRate":"400"},null,
{"type":"programShift","isShifted":false},
{"type":"shutterSpeed","shutterSpeedCandidates":["1/60","1/80"],"currentShutterSpeed":"1/60"}]}
"""

    @Test
    fun parsesRealSonyEvent() {
        val e = CameraEvent.parse(realEvent)
        assertEquals("3.5", e.fNumber)
        assertEquals("400", e.isoSpeedRate)
        assertEquals("AF-S", e.focusMode)
        assertEquals("Manual", e.exposureMode)
        assertEquals("1/60", e.shutterSpeed)
        assertEquals("on", e.flashMode)
        assertEquals("still", e.shootMode)
        assertEquals("IDLE", e.cameraStatus)
    }

    @Test
    fun parsesExposureCompensationRange() {
        val e = CameraEvent.parse(realEvent)
        assertEquals(3, e.exposureCompensation)
        assertEquals(-15, e.exposureCompensationMin)
        assertEquals(15, e.exposureCompensationMax)
        assertEquals(1, e.exposureCompensationStep)
    }

    @Test
    fun parsesSelfTimerCandidates() {
        val e = CameraEvent.parse(realEvent)
        assertEquals(0, e.selfTimer)
        assertEquals(listOf(0, 2), e.selfTimerCandidates)
    }

    @Test
    fun parsesBooleanFields() {
        val e = CameraEvent.parse(realEvent)
        assertTrue(e.liveviewStatus)
        assertFalse(e.programShift)
    }

    /** 关键：isoSpeedRateCandidates 是空的，但 currentIsoSpeedRate 有值。
     *  "候选为空" 不等于 "读不到值" —— 早期实现差点因此不显示 ISO。 */
    @Test
    fun emptyCandidatesDoesNotMeanValueMissing() {
        val e = CameraEvent.parse(realEvent)
        assertEquals("400", e.isoSpeedRate)
        assertTrue(realEvent.contains("\"isoSpeedRateCandidates\":[]"))
    }

    @Test
    fun missingFieldsFallBackToDefaultsWithoutCrash() {
        val e = CameraEvent.parse("{\"id\":1,\"result\":[]}")
        assertEquals("", e.fNumber)
        assertEquals("", e.isoSpeedRate)
        assertEquals("", e.focusMode)
        assertEquals(0, e.exposureCompensation)
        assertEquals(0, e.exposureCompensationMin)
        assertEquals(1, e.exposureCompensationStep)
        assertEquals(0, e.selfTimer)
        assertEquals(emptyList<Int>(), e.selfTimerCandidates)
        assertFalse(e.liveviewStatus)
    }

    /** 空数组返回空列表，不能返回 [0]。 */
    @Test
    fun emptyIntArrayParsesToEmptyList() {
        val e = CameraEvent.parse("{\"type\":\"selfTimer\",\"currentSelfTimer\":0,\"selfTimerCandidates\":[]}")
        assertEquals(0, e.selfTimer)
        assertEquals(emptyList<Int>(), e.selfTimerCandidates)
    }

    /** 负数也要能解析（曝光补偿范围是 -15..+15）。 */
    @Test
    fun parsesNegativeNumbers() {
        val e = CameraEvent.parse("{\"type\":\"exposureCompensation\",\"minExposureCompensation\":-15,\"currentExposureCompensation\":-7,\"maxExposureCompensation\":15}")
        assertEquals(-15, e.exposureCompensationMin)
        assertEquals(-7, e.exposureCompensation)
        assertEquals(15, e.exposureCompensationMax)
    }

    @Test
    fun hasExposureInfoReflectsReadability() {
        assertTrue(CameraEvent.parse(realEvent).hasExposureInfo)
        assertFalse(CameraEvent.parse("{}").hasExposureInfo)
    }
}
