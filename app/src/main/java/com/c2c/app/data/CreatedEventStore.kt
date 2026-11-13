package com.c2c.app.data

import android.content.Context
import android.content.SharedPreferences
import com.c2c.core.EventOrigin
import com.c2c.core.FeedbackCodec
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱이 통화에서 자동으로 만든 일정의 목록.
 * 일정 내용(제목, 시간 등)은 캘린더가 원본이고, 여기에는 이벤트 ID와 "어떤 통화에서 어떻게 만들어졌는지"만 저장한다.
 * 삭제되면 [origin]이 개선 피드백의 근거가 된다.
 */
data class CreatedEvent(
    val eventId: Long,
    val createdAtMillis: Long,
    val origin: EventOrigin,
) {
    val counterpart: String? get() = origin.counterpart
    val recordingName: String get() = origin.recordingName
    val evidence: String get() = origin.evidence
}

class CreatedEventStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("c2c_events", Context.MODE_PRIVATE)

    /** 최근에 만든 일정이 앞에 온다. */
    fun all(): List<CreatedEvent> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val origin = o.optString("origin").takeIf { it.isNotEmpty() }?.let(FeedbackCodec::originFromString)
                // 예전 형식: 통화 정보만 있던 기록
                ?: EventOrigin(
                    extracted = null,
                    confidence = null,
                    evidence = o.optString("evidence"),
                    summary = "",
                    counterpart = o.optString("counterpart").ifEmpty { null },
                    callTime = null,
                    recordingName = o.optString("recording"),
                    transcript = null,
                )
            CreatedEvent(o.getLong("id"), o.getLong("created"), origin)
        }
    }

    fun find(eventId: Long): CreatedEvent? = all().firstOrNull { it.eventId == eventId }

    @Synchronized
    fun add(event: CreatedEvent) = save(listOf(event) + all().filterNot { it.eventId == event.eventId })

    @Synchronized
    fun remove(eventId: Long) = save(all().filterNot { it.eventId == eventId })

    @Synchronized
    fun removeAll(eventIds: Collection<Long>) {
        if (eventIds.isEmpty()) return
        val ids = eventIds.toSet()
        save(all().filterNot { it.eventId in ids })
    }

    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun save(list: List<CreatedEvent>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(
                JSONObject()
                    .put("id", it.eventId)
                    .put("created", it.createdAtMillis)
                    .put("origin", FeedbackCodec.originToString(it.origin)),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "created_events"
        const val MAX = 300
    }
}
