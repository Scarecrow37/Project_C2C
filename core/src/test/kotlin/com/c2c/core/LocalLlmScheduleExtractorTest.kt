package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LocalLlmScheduleExtractorTest {

    private val ctx = CallContext(LocalDateTime.of(2026, 11, 13, 14, 0).atZone(ZoneId.of("Asia/Seoul")), "김민수")
    private val validJson = """{"summary":"미팅 약속","events":[{"title":"김민수 미팅","start_date":"2026-11-17",
        "start_time":"15:00","end_date":null,"end_time":null,"location":"강남역","notes":null,
        "confidence":0.9,"evidence":"화요일 세 시에 봬요"}]}"""

    @Test
    fun `생성기 출력을 일정으로 바꾼다 - 앞뒤 설명이나 코드 블록이 섞여도`() {
        var seen: Triple<String, String, String>? = null
        val extractor = LocalLlmScheduleExtractor({ system, user, grammar, _ ->
            seen = Triple(system, user, grammar)
            "네, 결과입니다.\n```json\n$validJson\n```"
        })
        val r = extractor.extract("화요일 세 시에 봬요", ctx) as ExtractionResult.Success
        assertEquals(LocalDate.of(2026, 11, 17), r.events.single().start.toLocalDate())
        assertTrue(seen!!.first.contains("JSON 하나로만 답하세요"))
        assertTrue(seen!!.second.contains("<transcript>"))
        assertTrue(seen!!.third.startsWith("root ::="))
    }

    @Test
    fun `JSON 이 없거나 생성기가 실패하면 Failed`() {
        assertTrue(LocalLlmScheduleExtractor({ _, _, _, _ -> "모르겠어요" }).extract("x", ctx) is ExtractionResult.Failed)
        assertTrue(
            LocalLlmScheduleExtractor({ _, _, _, _ -> error("메모리 부족") }).extract("x", ctx) is ExtractionResult.Failed,
        )
    }

    @Test
    fun `빈 녹취록은 모델을 부르지 않는다`() {
        val r = LocalLlmScheduleExtractor({ _, _, _, _ -> error("호출되면 안 됨") }).extract("  ", ctx)
        assertEquals(ExtractionResult.Success(emptyList(), ""), r)
    }

    @Test
    fun `피드백 예시는 최대 3개만 보낸다`() {
        val origin = EventOrigin(null, null, "근거", "", null, null, "", null)
        val mistakes = (1..5).map { DeletionFeedback("$it", it.toLong(), DeletionSource.APP, DeletionReason.NOT_AN_EVENT, origin, null) }
        var user = ""
        LocalLlmScheduleExtractor({ _, u, _, _ -> user = u; validJson }, pastMistakes = mistakes).extract("x", ctx)
        assertEquals(3, Regex("""삭제 이유:""").findAll(user).count(), user)
    }

    @Test
    fun `첫 번째 완전한 JSON 객체 - 문자열 안의 괄호와 이스케이프는 무시`() {
        assertEquals("""{"a":"} { \" }","b":{"c":1}}""", JsonText.firstObject("""앞 {"a":"} { \" }","b":{"c":1}} 뒤 {"x":2}"""))
        assertNull(JsonText.firstObject("""{"잘린": "json"""))
        assertNull(JsonText.firstObject("없음"))
    }

    @Test
    fun `긴 녹취록은 앞 3분의 1, 뒤 3분의 2를 남기고 줄인다`() {
        val t = "가".repeat(500) + "나".repeat(500) + "다".repeat(500)
        val trimmed = TranscriptTrimmer.trim(t, 300)
        assertEquals(300, trimmed.length)
        assertTrue(trimmed.startsWith("가"))
        assertTrue(trimmed.endsWith("다"))
        assertTrue(trimmed.contains(TranscriptTrimmer.GAP_MARKER))
        assertEquals("짧음", TranscriptTrimmer.trim(" 짧음 ", 300))
    }

    @Test
    fun `엔진 이름이 피드백 기록에 남는다`() {
        assertEquals("local:qwen2.5-3b", LocalLlmScheduleExtractor({ _, _, _, _ -> "" }, "qwen2.5-3b").name)
        val origin = EventOrigin(null, null, "", "", null, null, "", null, extractor = "local:qwen2.5-3b")
        assertEquals(origin, FeedbackCodec.originFromString(FeedbackCodec.originToString(origin)))
    }
}
