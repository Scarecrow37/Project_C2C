package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EventFilterTest {

    private val ctx = CallContext(LocalDateTime.of(2026, 11, 13, 14, 0).atZone(ZoneId.of("Asia/Seoul")))

    private fun ev(title: String, start: LocalDateTime, confidence: Double = 0.9) =
        ScheduleEvent(title, start, start.plusHours(1), false, null, null, confidence, "")

    @Test
    fun `확신도 낮은 일정과 이미 지난 일정은 제외하고 시간순 정렬`() {
        val events = listOf(
            ev("나중", LocalDateTime.of(2026, 11, 20, 10, 0)),
            ev("애매", LocalDateTime.of(2026, 11, 15, 10, 0), confidence = 0.3),
            ev("지난", LocalDateTime.of(2026, 11, 12, 10, 0)),
            ev("먼저", LocalDateTime.of(2026, 11, 14, 10, 0)),
            ev("먼저", LocalDateTime.of(2026, 11, 14, 10, 0)), // 중복
        )
        assertEquals(listOf("먼저", "나중"), EventFilter.select(events, ctx).map { it.title })
    }
}
