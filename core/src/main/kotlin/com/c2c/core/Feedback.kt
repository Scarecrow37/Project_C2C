package com.c2c.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 자동 등록된 일정이 "어떻게 만들어졌는지"에 대한 기록.
 * 나중에 삭제되면 이 정보가 개선 근거(피드백)가 된다.
 */
data class EventOrigin(
    /** Claude가 처음 추출한 그대로의 일정 (사용자가 수정하기 전). 예전 기록에는 없을 수 있다. */
    val extracted: EventDraft?,
    val confidence: Double?,
    /** 근거가 된 통화 속 발화 */
    val evidence: String,
    /** 통화 요약 */
    val summary: String,
    val counterpart: String?,
    val callTime: LocalDateTime?,
    val recordingName: String,
    /** 음성 인식 결과 (분석용, 길이 제한). 예전 기록에는 없을 수 있다. */
    val transcript: String?,
) {
    companion object {
        const val MAX_TRANSCRIPT_CHARS = 6_000

        fun of(
            event: ScheduleEvent,
            summary: String,
            context: CallContext,
            recordingName: String,
            transcript: String,
        ) = EventOrigin(
            extracted = EventDraft.from(event, event.notes.orEmpty()).copy(description = event.notes),
            confidence = event.confidence,
            evidence = event.evidence,
            summary = summary,
            counterpart = context.counterpart,
            callTime = context.callTime.toLocalDateTime(),
            recordingName = recordingName,
            transcript = transcript.trim().take(MAX_TRANSCRIPT_CHARS),
        )
    }
}

/** 사용자가 고르는 삭제 이유. */
enum class DeletionReason(val label: String, val promptText: String) {
    NOT_AN_EVENT("일정이 아님", "실제로 합의된 약속이 아니었음"),
    WRONG_TIME("날짜·시간이 틀림", "날짜나 시간을 잘못 해석했음"),
    WRONG_DETAILS("내용이 틀림", "제목·장소 등 내용을 잘못 추출했음"),
    CANCELLED("나중에 취소됨", "약속이 나중에 취소되었음"),
    DUPLICATE("중복 일정", "이미 있는 일정과 중복이었음"),
    OTHER("기타", "기타 이유"),
    UNSPECIFIED("이유 미선택", "사용자가 잘못 등록된 일정이라며 삭제함"),
}

/** 어디서 삭제했는지. */
enum class DeletionSource(val label: String) {
    /** 앱의 목록·상세 화면에서 삭제 */
    APP("앱에서 삭제"),
    /** "일정 추가됨" 알림의 취소 버튼 */
    NOTIFICATION("알림에서 취소"),
    /** 다른 캘린더 앱에서 삭제된 것을 발견 (단순 정리일 수도 있음) */
    CALENDAR_APP("캘린더 앱에서 삭제"),
}

/** 삭제된 자동 등록 일정 = 개선 근거 하나. */
data class DeletionFeedback(
    val id: String,
    val deletedAtMillis: Long,
    val source: DeletionSource,
    val reason: DeletionReason,
    val origin: EventOrigin,
    /** 삭제 직전 캘린더의 내용 (사용자가 고친 뒤 지웠을 수도 있음). 모르면 null. */
    val lastDraft: EventDraft?,
) {
    /**
     * 추출이 잘못되었다는 신호로 볼 수 있는지.
     * 취소·중복은 추출 실수가 아니고, 캘린더 앱에서 이유 없이 지운 것은 단순 정리일 수 있어 제외한다.
     */
    val isExtractionMistake: Boolean
        get() = when (reason) {
            DeletionReason.NOT_AN_EVENT, DeletionReason.WRONG_TIME, DeletionReason.WRONG_DETAILS -> true
            DeletionReason.UNSPECIFIED -> source != DeletionSource.CALENDAR_APP
            DeletionReason.CANCELLED, DeletionReason.DUPLICATE, DeletionReason.OTHER -> false
        }
}

/** 삭제 기록을 다음 일정 추출 프롬프트에 넣을 예시로 만든다. */
object FeedbackExamples {

    const val DEFAULT_MAX = 10
    private const val MAX_EVIDENCE_CHARS = 200
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)", Locale.KOREAN)

    /** 추출 실수로 볼 수 있는 최근 기록 [max]개. 근거 발화가 없는 기록은 예시로 쓸 수 없어 뺀다. */
    fun select(feedback: List<DeletionFeedback>, max: Int = DEFAULT_MAX): List<DeletionFeedback> =
        feedback
            .filter { it.isExtractionMistake && it.origin.evidence.isNotBlank() }
            .sortedByDescending { it.deletedAtMillis }
            .take(max)

    /** 프롬프트에 붙일 섹션. 예시가 없으면 null. */
    fun promptSection(examples: List<DeletionFeedback>): String? {
        if (examples.isEmpty()) return null
        return buildString {
            appendLine("<past_mistakes>")
            appendLine("아래는 이전 통화에서 자동 등록했지만 이 사용자가 잘못 등록되었다며 삭제한 일정입니다.")
            appendLine("비슷한 발화나 상황에서 같은 실수를 반복하지 마세요. 단, 이번 통화에서 분명히 합의된 약속은 그대로 추출하세요.")
            examples.forEachIndexed { i, f ->
                val o = f.origin
                append("${i + 1}. 등록했던 일정: ")
                append(o.extracted?.let { "\"${it.title}\" ${it.start.format(TIME)}${if (it.allDay) " 종일" else ""}" } ?: "(내용 없음)")
                appendLine()
                o.callTime?.let { appendLine("   통화 일시: ${it.format(TIME)}") }
                appendLine("   근거로 삼은 발화: \"${o.evidence.take(MAX_EVIDENCE_CHARS)}\"")
                appendLine("   삭제 이유: ${f.reason.promptText}")
                // 시간을 틀렸고 사용자가 고친 뒤 지웠다면 고친 값이 정답에 가깝다
                val fixed = f.lastDraft
                if (fixed != null && o.extracted != null && fixed.start != o.extracted.start) {
                    appendLine("   사용자가 고친 일시: ${fixed.start.format(TIME)}")
                }
            }
            appendLine("</past_mistakes>")
        }
    }
}

/** 개선 피드백 통계 (설정 화면 표시용). */
data class FeedbackStats(
    val totalCreated: Int,
    val deleted: Int,
    val mistakes: Int,
    val byReason: Map<DeletionReason, Int>,
    /** 사용자가 고친 일정 수 */
    val edited: Int = 0,
    val byField: Map<CorrectedField, Int> = emptyMap(),
) {
    /** 자동 등록 대비 추출 실수(잘못 등록) 비율 (0.0~1.0). 등록한 일정이 없으면 null. */
    val mistakeRate: Double? get() = if (totalCreated > 0) mistakes.toDouble() / totalCreated else null

    /** 자동 등록 대비 수정 비율 (0.0~1.0). */
    val editRate: Double? get() = if (totalCreated > 0) edited.toDouble() / totalCreated else null

    companion object {
        fun of(feedback: List<DeletionFeedback>, totalCreated: Int, edits: List<EditFeedback> = emptyList()): FeedbackStats {
            val realEdits = edits.filter { it.changes.isNotEmpty() }
            return FeedbackStats(
                totalCreated = maxOf(totalCreated, feedback.size + realEdits.size),
                deleted = feedback.size,
                mistakes = feedback.count { it.isExtractionMistake },
                byReason = feedback.groupingBy { it.reason }.eachCount(),
                edited = realEdits.size,
                byField = realEdits.flatMap { it.changes }.groupingBy { it }.eachCount(),
            )
        }
    }
}
