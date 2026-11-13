package com.c2c.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 통화 한 건에 대한 메타데이터. 상대적 날짜("내일", "다음 주 화요일")를 해석하는 기준이 된다. */
data class CallContext(
    /** 통화가 시작된 시각 (기기 시간대 기준). */
    val callTime: ZonedDateTime,
    /** 통화 상대 이름 또는 전화번호. 모르면 null. */
    val counterpart: String? = null,
) {
    val zone: ZoneId get() = callTime.zone
}

/** 통화 내용에서 추출한 일정 하나. 시각은 [CallContext.zone] 기준 로컬 시각이다. */
data class ScheduleEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** 시간이 언급되지 않은 일정이면 true (종일 일정으로 등록). */
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
    /** 0.0~1.0. 실제로 합의된 약속인지에 대한 모델의 확신도. */
    val confidence: Double,
    /** 일정의 근거가 된 통화 속 발화 (짧은 인용). */
    val evidence: String,
)

sealed interface ExtractionResult {
    /** 추출 성공. 일정이 없으면 [events]가 빈 리스트다. */
    data class Success(val events: List<ScheduleEvent>, val summary: String) : ExtractionResult

    /** 모델이 요청을 거절함 (안전 분류기). */
    data class Refused(val category: String?) : ExtractionResult

    /** 응답을 해석할 수 없음 (출력 잘림, JSON 오류 등). */
    data class Failed(val reason: String) : ExtractionResult
}

fun interface ScheduleExtractor {
    fun extract(transcript: String, context: CallContext): ExtractionResult
}
