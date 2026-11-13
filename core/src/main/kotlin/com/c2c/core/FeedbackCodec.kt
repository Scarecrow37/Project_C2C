package com.c2c.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.LocalDateTime

/** [EventOrigin], [DeletionFeedback] 를 JSON 으로 저장·내보내기 위한 변환. 알 수 없는 값은 안전하게 기본값으로 읽는다. */
object FeedbackCodec {

    private val mapper = ObjectMapper()

    // ---- EventOrigin ----

    fun originToJson(o: EventOrigin): ObjectNode = mapper.createObjectNode().apply {
        o.extracted?.let { set<JsonNode>("extracted", draftToJson(it)) }
        o.confidence?.let { put("confidence", it) }
        put("evidence", o.evidence)
        put("summary", o.summary)
        o.counterpart?.let { put("counterpart", it) }
        o.callTime?.let { put("callTime", it.toString()) }
        put("recording", o.recordingName)
        o.transcript?.let { put("transcript", it) }
    }

    fun originFromJson(n: JsonNode): EventOrigin = EventOrigin(
        extracted = n.get("extracted")?.let(::draftFromJson),
        confidence = n.get("confidence")?.takeIf { it.isNumber }?.asDouble(),
        evidence = n.text("evidence").orEmpty(),
        summary = n.text("summary").orEmpty(),
        counterpart = n.text("counterpart")?.ifEmpty { null },
        callTime = n.text("callTime")?.let(::parseTime),
        recordingName = n.text("recording").orEmpty(),
        transcript = n.text("transcript"),
    )

    fun originToString(o: EventOrigin): String = mapper.writeValueAsString(originToJson(o))

    fun originFromString(json: String): EventOrigin? =
        runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject }?.let(::originFromJson)

    // ---- DeletionFeedback ----

    fun feedbackToJson(f: DeletionFeedback): ObjectNode = mapper.createObjectNode().apply {
        put("id", f.id)
        put("deletedAt", f.deletedAtMillis)
        put("source", f.source.name)
        put("reason", f.reason.name)
        set<JsonNode>("origin", originToJson(f.origin))
        f.lastDraft?.let { set<JsonNode>("lastDraft", draftToJson(it)) }
    }

    fun feedbackFromJson(n: JsonNode): DeletionFeedback? {
        val id = n.text("id") ?: return null
        return DeletionFeedback(
            id = id,
            deletedAtMillis = n.path("deletedAt").asLong(0),
            source = enumOr(n.text("source"), DeletionSource.APP),
            reason = enumOr(n.text("reason"), DeletionReason.UNSPECIFIED),
            origin = n.get("origin")?.let(::originFromJson) ?: EMPTY_ORIGIN,
            lastDraft = n.get("lastDraft")?.let(::draftFromJson),
        )
    }

    fun feedbackListToJson(list: List<DeletionFeedback>): String =
        mapper.writeValueAsString(mapper.createArrayNode().apply { list.forEach { add(feedbackToJson(it)) } })

    fun feedbackListFromJson(json: String?): List<DeletionFeedback> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        return if (root.isArray) root.mapNotNull(::feedbackFromJson) else emptyList()
    }

    /** 분석용 내보내기 파일 (사람이 읽기 좋게 들여쓰기). */
    fun exportJson(list: List<DeletionFeedback>, stats: FeedbackStats, exportedAt: LocalDateTime): String {
        val root = mapper.createObjectNode().apply {
            put("format", "c2c-deletion-feedback/1")
            put("exportedAt", exportedAt.toString())
            set<JsonNode>(
                "stats",
                mapper.createObjectNode().apply {
                    put("totalCreated", stats.totalCreated)
                    put("deleted", stats.deleted)
                    put("extractionMistakes", stats.mistakes)
                    set<JsonNode>(
                        "byReason",
                        mapper.createObjectNode().apply { stats.byReason.forEach { (r, c) -> put(r.name, c) } },
                    )
                },
            )
            set<JsonNode>("feedback", mapper.createArrayNode().apply { list.forEach { add(feedbackToJson(it)) } })
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
    }

    // ---- EventDraft ----

    private fun draftToJson(d: EventDraft): ObjectNode = mapper.createObjectNode().apply {
        put("title", d.title)
        put("start", d.start.toString())
        put("end", d.end.toString())
        put("allDay", d.allDay)
        d.location?.let { put("location", it) }
        d.description?.let { put("description", it) }
    }

    private fun draftFromJson(n: JsonNode): EventDraft? {
        val start = n.text("start")?.let(::parseTime) ?: return null
        return EventDraft(
            title = n.text("title").orEmpty(),
            start = start,
            end = n.text("end")?.let(::parseTime) ?: start.plusHours(1),
            allDay = n.path("allDay").asBoolean(false),
            location = n.text("location"),
            description = n.text("description"),
        )
    }

    private val EMPTY_ORIGIN = EventOrigin(null, null, "", "", null, null, "", null)

    private fun JsonNode.text(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

    private fun parseTime(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s) }.getOrNull()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
