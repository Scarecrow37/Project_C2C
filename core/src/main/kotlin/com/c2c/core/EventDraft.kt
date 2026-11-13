package com.c2c.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 캘린더에 저장하거나 수정할 일정 내용. 시각은 기기 시간대 기준 로컬 시각이다.
 * 종일 일정이면 [start]는 시작일 0시, [end]는 마지막 날 다음 날 0시다 (CalendarContract 규칙과 같다).
 */
data class EventDraft(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
) {
    /** 종일 일정의 마지막 날 (포함). 시간 일정이면 종료 날짜. */
    val lastDay: LocalDate
        get() = if (allDay) end.toLocalDate().minusDays(1).coerceAtLeast(start.toLocalDate()) else end.toLocalDate()

    /** 저장할 수 없는 내용이면 이유를, 괜찮으면 null을 돌려준다. */
    fun validate(): String? = when {
        title.isBlank() -> "제목을 입력하세요"
        !end.isAfter(start) -> "끝나는 시간이 시작 시간보다 뒤여야 합니다"
        else -> null
    }

    /** 앞뒤 공백 정리, 빈 문자열은 null, 종일 일정은 날짜 경계로 맞춘다. */
    fun normalized(): EventDraft {
        val s = if (allDay) start.toLocalDate().atStartOfDay() else start
        val e = if (allDay) {
            maxOf(end.toLocalDate().let { if (end.toLocalTime() == java.time.LocalTime.MIDNIGHT) it else it.plusDays(1) }, s.toLocalDate().plusDays(1))
                .atStartOfDay()
        } else {
            end
        }
        return copy(
            title = title.trim(),
            start = s,
            end = e,
            location = location?.trim()?.ifEmpty { null },
            description = description?.trim()?.ifEmpty { null },
        )
    }

    companion object {
        fun from(event: ScheduleEvent, description: String) = EventDraft(
            title = event.title,
            start = event.start,
            end = event.end,
            allDay = event.allDay,
            location = event.location,
            description = description,
        )

        /**
         * 종일 일정을 날짜 범위로 만든다.
         * @param lastDay 마지막 날 (포함)
         */
        fun allDay(title: String, firstDay: LocalDate, lastDay: LocalDate, location: String?, description: String?) =
            EventDraft(
                title = title,
                start = firstDay.atStartOfDay(),
                end = maxOf(lastDay, firstDay).plusDays(1).atStartOfDay(),
                allDay = true,
                location = location,
                description = description,
            )
    }
}

/**
 * CalendarContract의 epoch millis와 로컬 시각 사이의 변환.
 * 종일 일정은 UTC 자정 기준으로 저장해야 한다 (CalendarContract 규칙).
 */
object CalendarTime {
    fun toMillis(time: LocalDateTime, allDay: Boolean, zone: ZoneId): Long =
        if (allDay) time.toInstant(ZoneOffset.UTC).toEpochMilli() else time.atZone(zone).toInstant().toEpochMilli()

    fun fromMillis(millis: Long, allDay: Boolean, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), if (allDay) ZoneOffset.UTC else zone)

    /** 저장할 때 쓰는 시간대 이름. */
    fun timezoneId(allDay: Boolean, zone: ZoneId): String = if (allDay) "UTC" else zone.id
}
