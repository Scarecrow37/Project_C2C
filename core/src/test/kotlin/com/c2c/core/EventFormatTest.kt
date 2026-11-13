package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EventFormatTest {
    private val nov20 = LocalDate.of(2026, 11, 20) // 금요일

    @Test
    fun `같은 날 시간 일정`() {
        val d = EventDraft("a", nov20.atTime(15, 0), nov20.atTime(16, 30), false, null, null)
        assertEquals("11월 20일 (금) 오후 3:00 ~ 오후 4:30", EventFormat.range(d))
    }

    @Test
    fun `날짜를 넘기는 시간 일정`() {
        val d = EventDraft("a", nov20.atTime(22, 0), nov20.plusDays(1).atTime(1, 0), false, null, null)
        assertEquals("11월 20일 (금) 오후 10:00 ~ 11월 21일 (토) 오전 1:00", EventFormat.range(d))
    }

    @Test
    fun `종일 일정`() {
        assertEquals("11월 20일 (금) 종일", EventFormat.range(EventDraft.allDay("a", nov20, nov20, null, null)))
        assertEquals(
            "11월 20일 (금) ~ 11월 22일 (일)",
            EventFormat.range(EventDraft.allDay("a", nov20, nov20.plusDays(2), null, null)),
        )
    }
}
