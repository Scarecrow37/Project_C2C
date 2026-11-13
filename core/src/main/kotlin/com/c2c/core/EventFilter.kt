package com.c2c.core

/** 캘린더에 자동 등록할 일정만 고른다. */
object EventFilter {

    const val DEFAULT_MIN_CONFIDENCE = 0.6

    fun select(
        events: List<ScheduleEvent>,
        context: CallContext,
        minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    ): List<ScheduleEvent> {
        val callTime = context.callTime.toLocalDateTime()
        return events
            .filter { it.confidence >= minConfidence }
            .filter { it.end.isAfter(callTime) } // 통화 시점에 이미 끝난 일정 제외
            .distinctBy { it.title to it.start }
            .sortedBy { it.start }
    }
}
