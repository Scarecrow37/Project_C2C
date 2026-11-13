package com.c2c.core

import java.time.format.DateTimeFormatter
import java.util.Locale

/** 사용자가 고친 항목. 메모(설명)는 앱이 자동으로 채운 내용이 섞여 있어 비교하지 않는다. */
enum class CorrectedField(val label: String) {
    TITLE("제목"),
    DATE_TIME("날짜·시간"),
    ALL_DAY("종일 여부"),
    LOCATION("장소"),
}

/** 어디서 고쳤는지. */
enum class EditSource(val label: String) {
    APP("앱에서 수정"),
    /** 다른 캘린더 앱에서 바뀐 것을 발견 */
    CALENDAR_APP("캘린더 앱에서 수정"),
}

/**
 * 자동 등록 후 사용자가 고친 일정 = 개선 근거. 일정 하나당 하나만 두고, 다시 고치면 최신 값으로 바꾼다.
 * 추출했던 값([EventOrigin.extracted])과 고친 값([corrected])의 차이가 "정답"을 알려준다.
 */
data class EditFeedback(
    val eventId: Long,
    val editedAtMillis: Long,
    val source: EditSource,
    val origin: EventOrigin,
    val corrected: EventDraft,
) {
    val changes: Set<CorrectedField>
        get() = origin.extracted?.let { EditDiff.changes(it, corrected) }.orEmpty()
}

object EditDiff {
    /** 두 일정 사이에 사용자가 바꾼 항목. 공백·빈 값 차이는 무시한다. */
    fun changes(before: EventDraft, after: EventDraft): Set<CorrectedField> {
        val a = before.normalized()
        val b = after.normalized()
        return buildSet {
            if (a.title != b.title) add(CorrectedField.TITLE)
            if (a.allDay != b.allDay) add(CorrectedField.ALL_DAY)
            if (a.start != b.start || a.end != b.end) add(CorrectedField.DATE_TIME)
            if (a.location != b.location) add(CorrectedField.LOCATION)
        }
    }
}

/** 수정 기록을 다음 일정 추출 프롬프트의 예시로 만든다. */
object CorrectionExamples {

    const val DEFAULT_MAX = 10
    private const val MAX_EVIDENCE_CHARS = 200
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (E)", Locale.KOREAN)
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd (E)", Locale.KOREAN)

    /** 실제로 바뀐 항목이 있고 근거 발화가 있는 최근 기록 [max]개. */
    fun select(edits: List<EditFeedback>, max: Int = DEFAULT_MAX): List<EditFeedback> =
        edits
            .filter { it.changes.isNotEmpty() && it.origin.evidence.isNotBlank() }
            .sortedByDescending { it.editedAtMillis }
            .take(max)

    fun promptSection(examples: List<EditFeedback>): String? {
        if (examples.isEmpty()) return null
        return buildString {
            appendLine("<past_corrections>")
            appendLine("아래는 이전 통화에서 자동 등록한 뒤 이 사용자가 직접 고친 일정입니다. 고친 값이 정답입니다.")
            appendLine("비슷한 표현을 해석하거나 제목·장소를 정할 때 이 사용자의 방식을 따르세요.")
            examples.forEachIndexed { i, e ->
                val o = e.origin
                appendLine("${i + 1}. 근거로 삼은 발화: \"${o.evidence.take(MAX_EVIDENCE_CHARS)}\"")
                o.callTime?.let { appendLine("   통화 일시: ${it.format(TIME)}") }
                o.extracted?.let { appendLine("   등록했던 값: ${describe(it)}") }
                appendLine("   사용자가 고친 값: ${describe(e.corrected)}")
                appendLine("   바뀐 항목: ${e.changes.joinToString { it.label }}")
            }
            appendLine("</past_corrections>")
        }
    }

    private fun describe(d: EventDraft): String = buildString {
        append('"').append(d.title).append("\" ")
        if (d.allDay) {
            append(d.start.toLocalDate().format(DATE))
            if (d.lastDay != d.start.toLocalDate()) append(" ~ ").append(d.lastDay.format(DATE))
            append(" 종일")
        } else {
            append(d.start.format(TIME)).append(" ~ ").append(d.end.format(TIME))
        }
        d.location?.let { append(" @ ").append(it) }
    }
}
