package com.c2c.core

import java.time.format.DateTimeFormatter
import java.util.Locale

/** 일정 시간을 화면에 보여줄 한국어 문자열로 만든다. */
object EventFormat {
    private val DATE = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    private val TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

    /**
     * 예: `11월 20일 (금) 오후 3:00 ~ 오후 4:30`, `11월 20일 (금) 종일`,
     * `11월 20일 (금) ~ 11월 22일 (일)`
     */
    fun range(draft: EventDraft): String {
        val startDay = draft.start.toLocalDate()
        if (draft.allDay) {
            val last = draft.lastDay
            return if (last == startDay) "${startDay.format(DATE)} 종일" else "${startDay.format(DATE)} ~ ${last.format(DATE)}"
        }
        val start = "${startDay.format(DATE)} ${draft.start.format(TIME)}"
        val end = if (draft.end.toLocalDate() == startDay) {
            draft.end.format(TIME)
        } else {
            "${draft.end.toLocalDate().format(DATE)} ${draft.end.format(TIME)}"
        }
        return "$start ~ $end"
    }

    fun date(draft: EventDraft): String = draft.start.toLocalDate().format(DATE)
}
