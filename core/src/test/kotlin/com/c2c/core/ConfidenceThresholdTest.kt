package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfidenceThresholdTest {

    @Test
    fun `0_05 단위로 맞추고 범위를 벗어나면 자른다`() {
        assertEquals(0.6, ConfidenceThreshold.snap(0.6))
        assertEquals(0.65, ConfidenceThreshold.snap(0.63))
        assertEquals(0.6, ConfidenceThreshold.snap(0.62))
        assertEquals(0.1, ConfidenceThreshold.snap(0.0))
        assertEquals(1.0, ConfidenceThreshold.snap(1.7))
        assertEquals(ConfidenceThreshold.DEFAULT, ConfidenceThreshold.snap(Double.NaN))
    }

    @Test
    fun `슬라이더에서 나온 float 값도 정확한 소수로 맞춘다`() {
        // Slider 는 Float 이라 0.35f.toDouble() = 0.3499999940395355 같은 값이 들어온다
        assertEquals(0.35, ConfidenceThreshold.snap(0.35f.toDouble()))
        assertEquals(0.85, ConfidenceThreshold.snap(0.85f.toDouble()))
    }

    @Test
    fun `퍼센트 변환`() {
        assertEquals(10, ConfidenceThreshold.toPercent(0.1))
        assertEquals(60, ConfidenceThreshold.toPercent(0.6))
        assertEquals(100, ConfidenceThreshold.toPercent(1.0))
        assertEquals(0.45, ConfidenceThreshold.fromPercent(45))
        assertEquals(0.1, ConfidenceThreshold.fromPercent(3))
    }

    @Test
    fun `슬라이더 눈금 - 10% 부터 100% 까지 19개 값`() {
        assertEquals(17, ConfidenceThreshold.SLIDER_STEPS)
        // 양 끝 2개 + 중간 눈금 17개 = 19개 값 (10, 15, ..., 100)
        val values = (0..ConfidenceThreshold.SLIDER_STEPS + 1).map {
            ConfidenceThreshold.toPercent(ConfidenceThreshold.MIN + it * ConfidenceThreshold.STEP)
        }
        assertEquals((10..100 step 5).toList(), values)
    }

    @Test
    fun `기준값과 같은 확신도는 등록된다`() {
        val ctx = CallContext(java.time.ZonedDateTime.of(2026, 11, 13, 14, 0, 0, 0, java.time.ZoneId.of("Asia/Seoul")))
        val start = java.time.LocalDateTime.of(2026, 11, 20, 10, 0)
        val ev = ScheduleEvent("a", start, start.plusHours(1), false, null, null, 0.35, "")
        assertEquals(1, EventFilter.select(listOf(ev), ctx, ConfidenceThreshold.snap(0.35f.toDouble())).size)
    }
}
