package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ExtractionParserTest {

    private fun success(json: String) = ExtractionParser.parse(json) as ExtractionResult.Success

    @Test
    fun `시작·종료 시간이 있는 일정`() {
        val r = success(
            """
            {"summary":"미팅 일정 확정","events":[{"title":"김민수 팀장 미팅","start_date":"2026-11-17",
             "start_time":"15:00","end_date":null,"end_time":"16:30","location":"강남역 스타벅스",
             "notes":"견적서 지참","confidence":0.95,"evidence":"다음 주 화요일 세 시에 강남역에서 뵙죠"}]}
            """,
        )
        val e = r.events.single()
        assertEquals("미팅 일정 확정", r.summary)
        assertEquals(LocalDateTime.of(2026, 11, 17, 15, 0), e.start)
        assertEquals(LocalDateTime.of(2026, 11, 17, 16, 30), e.end)
        assertFalse(e.allDay)
        assertEquals("강남역 스타벅스", e.location)
    }

    @Test
    fun `종료 시간이 없으면 1시간 일정`() {
        val e = success(
            """{"summary":"","events":[{"title":"치과","start_date":"2026-11-20","start_time":"9:30",
              "end_date":null,"end_time":null,"location":null,"notes":null,"confidence":0.8,"evidence":"x"}]}""",
        ).events.single()
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30), e.start)
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 30), e.end)
    }

    @Test
    fun `시간이 없으면 종일 일정`() {
        val e = success(
            """{"summary":"","events":[{"title":"서류 마감","start_date":"2026-11-30","start_time":null,
              "end_date":null,"end_time":null,"location":"","notes":null,"confidence":0.7,"evidence":"x"}]}""",
        ).events.single()
        assertTrue(e.allDay)
        assertEquals(LocalDateTime.of(2026, 11, 30, 0, 0), e.start)
        assertEquals(LocalDateTime.of(2026, 12, 1, 0, 0), e.end)
        assertEquals(null, e.location) // 빈 문자열은 null 처리
    }

    @Test
    fun `종료가 시작보다 앞서면 기본 길이로 보정`() {
        val e = success(
            """{"summary":"","events":[{"title":"a","start_date":"2026-11-20","start_time":"15:00",
              "end_date":null,"end_time":"14:00","location":null,"notes":null,"confidence":1,"evidence":""}]}""",
        ).events.single()
        assertEquals(LocalDateTime.of(2026, 11, 20, 16, 0), e.end)
    }

    @Test
    fun `날짜 형식이 잘못된 일정은 건너뜀`() {
        val r = success(
            """{"summary":"","events":[
              {"title":"ok","start_date":"2026-11-20","start_time":null,"end_date":null,"end_time":null,"location":null,"notes":null,"confidence":1,"evidence":""},
              {"title":"bad","start_date":"다음주","start_time":null,"end_date":null,"end_time":null,"location":null,"notes":null,"confidence":1,"evidence":""}]}""",
        )
        assertEquals(listOf("ok"), r.events.map { it.title })
    }

    @Test
    fun `깨진 JSON은 Failed`() {
        assertTrue(ExtractionParser.parse("{\"summary\": ") is ExtractionResult.Failed)
    }
}
