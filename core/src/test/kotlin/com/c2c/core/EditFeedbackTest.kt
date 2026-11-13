package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class EditFeedbackTest {

    private val nov20 = LocalDate.of(2026, 11, 20)
    private val extracted = EventDraft("김민수 미팅", nov20.atTime(15, 0), nov20.atTime(16, 0), false, "강남역", "견적서")

    private fun origin(evidence: String = "금요일 세 시에 강남역에서 봬요") = EventOrigin(
        extracted, 0.8, evidence, "요약", "김민수", LocalDateTime.of(2026, 11, 13, 14, 0), "rec.m4a", "...",
    )

    private fun edit(corrected: EventDraft, at: Long = 0, evidence: String = "금요일 세 시에 강남역에서 봬요") =
        EditFeedback(1, at, EditSource.APP, origin(evidence), corrected)

    @Test
    fun `바뀐 항목을 찾는다 - 메모와 공백 차이는 무시`() {
        assertEquals(emptySet<CorrectedField>(), EditDiff.changes(extracted, extracted.copy(description = "다른 메모")))
        assertEquals(emptySet<CorrectedField>(), EditDiff.changes(extracted, extracted.copy(title = " 김민수 미팅 ")))
        assertEquals(
            setOf(CorrectedField.DATE_TIME),
            EditDiff.changes(extracted, extracted.copy(start = nov20.atTime(16, 0), end = nov20.atTime(17, 0))),
        )
        assertEquals(
            setOf(CorrectedField.TITLE, CorrectedField.LOCATION),
            EditDiff.changes(extracted, extracted.copy(title = "견적 미팅", location = "역삼역")),
        )
        val allDay = EventDraft.allDay("김민수 미팅", nov20, nov20, "강남역", null)
        assertEquals(setOf(CorrectedField.ALL_DAY, CorrectedField.DATE_TIME), EditDiff.changes(extracted, allDay))
    }

    @Test
    fun `예시는 실제로 바뀐 최근 기록만`() {
        val list = listOf(
            edit(extracted.copy(location = "역삼역"), at = 1),
            edit(extracted.copy(description = "메모만 바꿈"), at = 5), // 바뀐 항목 없음
            edit(extracted.copy(title = "견적 미팅"), at = 3),
            edit(extracted.copy(title = "x"), at = 9, evidence = ""), // 근거 없음
        )
        assertEquals(listOf(3L, 1L), CorrectionExamples.select(list).map { it.editedAtMillis })
    }

    @Test
    fun `프롬프트 섹션에 등록했던 값과 고친 값이 들어간다`() {
        val fixed = extracted.copy(start = nov20.atTime(16, 0), end = nov20.atTime(17, 0))
        val text = CorrectionExamples.promptSection(listOf(edit(fixed)))!!
        assertTrue(text.startsWith("<past_corrections>"))
        assertTrue(text.contains("등록했던 값: \"김민수 미팅\" 2026-11-20 15:00 (금) ~ 2026-11-20 16:00 (금) @ 강남역"), text)
        assertTrue(text.contains("사용자가 고친 값: \"김민수 미팅\" 2026-11-20 16:00 (금) ~ 2026-11-20 17:00 (금) @ 강남역"))
        assertTrue(text.contains("바뀐 항목: 날짜·시간"))
        assertNull(CorrectionExamples.promptSection(emptyList()))
    }

    @Test
    fun `사용자 메시지에 수정 기록 섹션이 녹취록 앞에 붙는다`() {
        val ctx = CallContext(LocalDateTime.of(2026, 11, 13, 14, 0).atZone(ZoneId.of("Asia/Seoul")))
        val msg = ExtractionPrompt.userMessage("내일 봐요", ctx, pastCorrections = listOf(edit(extracted.copy(title = "견적 미팅"))))
        assertTrue(msg.indexOf("<past_corrections>") in 0 until msg.indexOf("<transcript>"), msg)
        assertFalse(msg.contains("<past_mistakes>"))
    }

    @Test
    fun `JSON 왕복과 통계`() {
        val list = listOf(edit(extracted.copy(title = "견적 미팅"), at = 7), edit(extracted.copy(description = "메모")))
        assertEquals(list, FeedbackCodec.editListFromJson(FeedbackCodec.editListToJson(list)))
        assertEquals(emptyList<EditFeedback>(), FeedbackCodec.editListFromJson("[{\"eventId\":1}]")) // corrected 없음

        val stats = FeedbackStats.of(emptyList(), totalCreated = 4, edits = list)
        assertEquals(1, stats.edited) // 메모만 바꾼 건 수정으로 치지 않음
        assertEquals(mapOf(CorrectedField.TITLE to 1), stats.byField)
        assertEquals(0.25, stats.editRate!!, 1e-9)

        val json = FeedbackCodec.exportJson(emptyList(), list, stats, LocalDateTime.of(2026, 11, 13, 15, 0))
        assertTrue(json.contains("\"edits\" : ["), json)
        assertTrue(json.contains("\"TITLE\" : 1"))
    }
}
