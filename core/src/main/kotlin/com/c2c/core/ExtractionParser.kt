package com.c2c.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Claude가 돌려준 JSON 문자열을 [ScheduleEvent] 목록으로 바꾼다. 형식이 잘못된 일정은 건너뛴다. */
object ExtractionParser {

    /** 종료 시간이 없을 때 적용하는 기본 길이. */
    const val DEFAULT_DURATION_MINUTES = 60L

    private val mapper = ObjectMapper()

    fun parse(json: String): ExtractionResult {
        val root = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            return ExtractionResult.Failed("JSON 파싱 실패: ${e.message}")
        }
        if (root == null || !root.isObject) return ExtractionResult.Failed("JSON 객체가 아님")

        val summary = root.text("summary").orEmpty()
        val events = root.path("events").takeIf { it.isArray }?.mapNotNull(::toEvent).orEmpty()
        return ExtractionResult.Success(events, summary)
    }

    private fun toEvent(node: JsonNode): ScheduleEvent? {
        val title = node.text("title")?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val startDate = node.text("start_date")?.let(::parseDate) ?: return null
        val startTime = node.text("start_time")?.let(::parseTime)
        val endDate = node.text("end_date")?.let(::parseDate) ?: startDate
        val endTime = node.text("end_time")?.let(::parseTime)

        val allDay = startTime == null
        val start = startDate.atTime(startTime ?: LocalTime.MIDNIGHT)
        var end = when {
            allDay -> endDate.plusDays(1).atStartOfDay() // 종일 일정은 다음 날 0시까지
            endTime != null -> endDate.atTime(endTime)
            else -> start.plusMinutes(DEFAULT_DURATION_MINUTES)
        }
        if (!end.isAfter(start)) end = start.plusMinutes(DEFAULT_DURATION_MINUTES)

        return ScheduleEvent(
            title = title,
            start = start,
            end = end,
            allDay = allDay,
            location = node.text("location")?.trim()?.ifEmpty { null },
            notes = node.text("notes")?.trim()?.ifEmpty { null },
            confidence = node.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0),
            evidence = node.text("evidence").orEmpty(),
        )
    }

    private fun JsonNode.text(field: String): String? =
        get(field)?.takeUnless { it.isNull }?.asText()

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseTime(s: String): LocalTime? = try {
        LocalTime.parse(s.trim().let { if (it.length == 4) "0$it" else it }) // "9:30" → "09:30"
    } catch (_: DateTimeParseException) {
        null
    }
}
