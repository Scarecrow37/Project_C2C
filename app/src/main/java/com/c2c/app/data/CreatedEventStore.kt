package com.c2c.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 앱이 통화에서 자동으로 만든 일정의 목록.
 * 일정 내용(제목, 시간 등)은 캘린더가 원본이고, 여기에는 이벤트 ID와 "어떤 통화에서 나왔는지"만 저장한다.
 */
data class CreatedEvent(
    val eventId: Long,
    val createdAtMillis: Long,
    /** 통화 상대 (이름 또는 번호). 모르면 null. */
    val counterpart: String?,
    /** 녹음 파일 이름 */
    val recordingName: String,
    /** 일정의 근거가 된 통화 속 발화 */
    val evidence: String,
)

class CreatedEventStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("c2c_events", Context.MODE_PRIVATE)

    /** 최근에 만든 일정이 앞에 온다. */
    fun all(): List<CreatedEvent> {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CreatedEvent(
                eventId = o.getLong("id"),
                createdAtMillis = o.getLong("created"),
                counterpart = o.optString("counterpart").ifEmpty { null },
                recordingName = o.optString("recording"),
                evidence = o.optString("evidence"),
            )
        }
    }

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

    /** 일정을 다시 만들어 ID가 바뀌었을 때 (삭제 되돌리기). */
    @Synchronized
    fun replaceId(oldId: Long, newId: Long) =
        save(all().map { if (it.eventId == oldId) it.copy(eventId = newId) else it })

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
                    .put("counterpart", it.counterpart ?: "")
                    .put("recording", it.recordingName)
                    .put("evidence", it.evidence),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "created_events"
        const val MAX = 300
    }
}
