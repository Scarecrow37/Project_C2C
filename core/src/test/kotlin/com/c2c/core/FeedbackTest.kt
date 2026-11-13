package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FeedbackTest {

    private val nov20 = LocalDate.of(2026, 11, 20)
    private val extracted = EventDraft("김민수 미팅", nov20.atTime(15, 0), nov20.atTime(16, 0), false, "강남역", null)

    private fun origin(evidence: String = "다음 주 금요일 세 시쯤 한번 봐요") = EventOrigin(
        extracted = extracted,
        confidence = 0.7,
        evidence = evidence,
        summary = "견적 관련 통화",
        counterpart = "김민수",
        callTime = LocalDateTime.of(2026, 11, 13, 14, 0),
        recordingName = "통화 녹음 김민수_261113_140000.m4a",
        transcript = "여보세요 ...",
    )

    private fun fb(
        id: String,
        reason: DeletionReason,
        source: DeletionSource = DeletionSource.APP,
        at: Long = 0,
        evidence: String = "다음 주 금요일 세 시쯤 한번 봐요",
        lastDraft: EventDraft? = null,
    ) = DeletionFeedback(id, at, source, reason, origin(evidence), lastDraft)

    @Test
    fun `추출 실수로 볼 수 있는 기록만 고른다`() {
        assertTrue(fb("a", DeletionReason.NOT_AN_EVENT).isExtractionMistake)
        assertTrue(fb("b", DeletionReason.WRONG_TIME).isExtractionMistake)
        assertTrue(fb("c", DeletionReason.UNSPECIFIED, DeletionSource.APP).isExtractionMistake)
        assertTrue(fb("d", DeletionReason.UNSPECIFIED, DeletionSource.NOTIFICATION).isExtractionMistake)
        // 캘린더 앱에서 이유 없이 지운 건 단순 정리일 수 있음
        assertFalse(fb("e", DeletionReason.UNSPECIFIED, DeletionSource.CALENDAR_APP).isExtractionMistake)
        // 취소·중복은 추출 실수가 아님
        assertFalse(fb("f", DeletionReason.CANCELLED).isExtractionMistake)
        assertFalse(fb("g", DeletionReason.DUPLICATE).isExtractionMistake)
    }

    @Test
    fun `예시는 최근 순으로 최대 개수만, 근거 없는 기록은 제외`() {
        val list = listOf(
            fb("old", DeletionReason.NOT_AN_EVENT, at = 1),
            fb("new", DeletionReason.WRONG_TIME, at = 3),
            fb("mid", DeletionReason.NOT_AN_EVENT, at = 2),
            fb("noEvidence", DeletionReason.NOT_AN_EVENT, at = 4, evidence = ""),
            fb("cancelled", DeletionReason.CANCELLED, at = 5),
        )
        assertEquals(listOf("new", "mid"), FeedbackExamples.select(list, max = 2).map { it.id })
    }

    @Test
    fun `프롬프트 섹션에 근거 발화, 삭제 이유, 사용자가 고친 시간이 들어간다`() {
        val fixed = extracted.copy(start = nov20.atTime(17, 0), end = nov20.atTime(18, 0))
        val text = FeedbackExamples.promptSection(listOf(fb("a", DeletionReason.WRONG_TIME, lastDraft = fixed)))!!
        assertTrue(text.startsWith("<past_mistakes>"))
        assertTrue(text.contains("\"김민수 미팅\" 2026-11-20 15:00 (금)"), text)
        assertTrue(text.contains("근거로 삼은 발화: \"다음 주 금요일 세 시쯤 한번 봐요\""))
        assertTrue(text.contains("삭제 이유: 날짜나 시간을 잘못 해석했음"))
        assertTrue(text.contains("사용자가 고친 일시: 2026-11-20 17:00 (금)"))
        assertNull(FeedbackExamples.promptSection(emptyList()))
    }

    @Test
    fun `사용자 메시지에 과거 실수 섹션이 녹취록 앞에 붙는다`() {
        val ctx = CallContext(LocalDateTime.of(2026, 11, 13, 14, 0).atZone(ZoneId.of("Asia/Seoul")), "김민수")
        val msg = ExtractionPrompt.userMessage("내일 봐요", ctx, listOf(fb("a", DeletionReason.NOT_AN_EVENT)))
        assertTrue(msg.indexOf("<past_mistakes>") in 0 until msg.indexOf("<transcript>"), msg)
        assertFalse(ExtractionPrompt.userMessage("내일 봐요", ctx).contains("past_mistakes"))
    }

    @Test
    fun `JSON 저장 후 다시 읽으면 같다`() {
        val list = listOf(
            fb("a", DeletionReason.WRONG_TIME, at = 123, lastDraft = extracted.copy(title = "고친 제목")),
            fb("b", DeletionReason.UNSPECIFIED, DeletionSource.CALENDAR_APP, at = 456),
        )
        assertEquals(list, FeedbackCodec.feedbackListFromJson(FeedbackCodec.feedbackListToJson(list)))
    }

    @Test
    fun `깨진 JSON이나 모르는 enum 값은 안전하게 읽는다`() {
        assertEquals(emptyList<DeletionFeedback>(), FeedbackCodec.feedbackListFromJson("{oops"))
        val read = FeedbackCodec.feedbackListFromJson("""[{"id":"x","reason":"SOMETHING_NEW","source":"???"}]""").single()
        assertEquals(DeletionReason.UNSPECIFIED, read.reason)
        assertEquals(DeletionSource.APP, read.source)
        assertNull(read.origin.extracted)
    }

    @Test
    fun `통계와 내보내기`() {
        val list = listOf(fb("a", DeletionReason.NOT_AN_EVENT), fb("b", DeletionReason.CANCELLED))
        val stats = FeedbackStats.of(list, totalCreated = 10)
        assertEquals(2, stats.deleted)
        assertEquals(1, stats.mistakes)
        assertEquals(0.1, stats.mistakeRate!!, 1e-9)

        val json = FeedbackCodec.exportJson(list, emptyList(), stats, LocalDateTime.of(2026, 11, 13, 15, 0))
        assertTrue(json.contains("\"format\" : \"c2c-feedback/2\""), json)
        assertTrue(json.contains("\"NOT_AN_EVENT\" : 1"))
        assertTrue(json.contains("\"transcript\" : \"여보세요 ...\""))
    }

    @Test
    fun `EventOrigin 문자열 왕복`() {
        val o = origin()
        assertEquals(o, FeedbackCodec.originFromString(FeedbackCodec.originToString(o)))
        assertNull(FeedbackCodec.originFromString("not json"))
    }
}
