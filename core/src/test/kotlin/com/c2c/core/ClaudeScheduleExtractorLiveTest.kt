package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 실제 Claude API를 호출한다. ANTHROPIC_API_KEY 가 설정된 경우에만 실행된다. */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ClaudeScheduleExtractorLiveTest {

    @Test
    fun `통화 녹취록에서 상대적 날짜를 해석해 일정을 추출한다`() {
        // 2026-11-13(금) 통화 → "다음 주 화요일" = 2026-11-17
        val ctx = CallContext(LocalDateTime.of(2026, 11, 13, 14, 0).atZone(ZoneId.of("Asia/Seoul")), "김민수")
        val transcript = """
            여보세요 네 김민수입니다 아 네 팀장님 안녕하세요 지난번에 말씀드린 견적 건으로 한번 뵙고 싶은데요
            네 좋습니다 다음 주 화요일 어떠세요 화요일 오후 세 시 괜찮으세요 네 세 시 좋습니다
            장소는 강남역 근처 스타벅스로 할까요 네 거기서 뵙겠습니다 견적서 출력해서 가져가겠습니다
            언제 한번 밥도 같이 드시죠 네 좋죠 그럼 화요일에 뵙겠습니다
        """.trimIndent()

        val result = ClaudeScheduleExtractor.create(System.getenv("ANTHROPIC_API_KEY"))
            .extract(transcript, ctx)

        assertTrue(result is ExtractionResult.Success, result.toString())
        val events = EventFilter.select((result as ExtractionResult.Success).events, ctx)
        println(result)
        assertEquals(1, events.size, "막연한 식사 제안은 제외되어야 함: $events")
        assertEquals(LocalDate.of(2026, 11, 17), events[0].start.toLocalDate())
        assertEquals(LocalTime.of(15, 0), events[0].start.toLocalTime())
    }
}
