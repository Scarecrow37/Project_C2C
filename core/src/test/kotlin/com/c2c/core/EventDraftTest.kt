package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class EventDraftTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val nov20 = LocalDate.of(2026, 11, 20)

    private fun timed(start: LocalDateTime, end: LocalDateTime, title: String = "미팅") =
        EventDraft(title, start, end, false, null, null)

    @Test
    fun `제목이 비었거나 종료가 시작보다 앞이면 오류`() {
        val s = nov20.atTime(15, 0)
        assertNotNull(timed(s, s.plusHours(1), title = "  ").validate())
        assertNotNull(timed(s, s).validate())
        assertNotNull(timed(s, s.minusMinutes(1)).validate())
        assertNull(timed(s, s.plusMinutes(30)).validate())
    }

    @Test
    fun `종일 일정은 마지막 날 다음 날 0시까지`() {
        val d = EventDraft.allDay("출장", nov20, nov20.plusDays(2), null, null)
        assertEquals(nov20.atStartOfDay(), d.start)
        assertEquals(nov20.plusDays(3).atStartOfDay(), d.end)
        assertEquals(nov20.plusDays(2), d.lastDay)
    }

    @Test
    fun `종일 일정 하루짜리 lastDay`() {
        assertEquals(nov20, EventDraft.allDay("마감", nov20, nov20, null, null).lastDay)
    }

    @Test
    fun `시간 일정을 종일로 바꾸면 날짜 경계로 맞춘다`() {
        val d = timed(nov20.atTime(15, 0), nov20.atTime(16, 0)).copy(allDay = true).normalized()
        assertEquals(nov20.atStartOfDay(), d.start)
        assertEquals(nov20.plusDays(1).atStartOfDay(), d.end)
        assertNull(d.validate())
    }

    @Test
    fun `normalized는 공백을 정리하고 빈 값은 null`() {
        val d = EventDraft(" 미팅 ", nov20.atTime(9, 0), nov20.atTime(10, 0), false, "  ", " 견적서 ").normalized()
        assertEquals("미팅", d.title)
        assertNull(d.location)
        assertEquals("견적서", d.description)
    }

    @Test
    fun `millis 변환은 왕복해도 같다`() {
        val t = nov20.atTime(15, 30)
        assertEquals(t, CalendarTime.fromMillis(CalendarTime.toMillis(t, false, seoul), false, seoul))
        val day = nov20.atStartOfDay()
        val ms = CalendarTime.toMillis(day, true, seoul)
        assertEquals(day, CalendarTime.fromMillis(ms, true, seoul))
        // 종일 일정은 기기 시간대와 상관없이 UTC 자정
        assertEquals(0L, ms % 86_400_000L)
    }
}
