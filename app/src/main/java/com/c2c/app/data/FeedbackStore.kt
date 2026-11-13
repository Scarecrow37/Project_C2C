package com.c2c.app.data

import android.content.Context
import android.content.SharedPreferences
import com.c2c.core.DeletionFeedback
import com.c2c.core.DeletionReason
import com.c2c.core.DeletionSource
import com.c2c.core.EditDiff
import com.c2c.core.EditFeedback
import com.c2c.core.EditSource
import com.c2c.core.EventDraft
import com.c2c.core.FeedbackCodec
import com.c2c.core.FeedbackStats
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * 개선 피드백 기록: 삭제된 자동 등록 일정과 사용자가 고친 자동 등록 일정.
 * - 다음 일정 추출 때 Claude 프롬프트에 "과거 실수" 예시로 들어간다 ([useInPrompt]).
 * - 분석용 JSON 으로 내보낼 수 있다.
 * 기록에는 통화 녹취록 일부가 들어 있으므로 앱 전용 저장소에만 둔다.
 */
class FeedbackStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("c2c_feedback", Context.MODE_PRIVATE)

    /** 최근 삭제가 앞에 온다. */
    fun all(): List<DeletionFeedback> = FeedbackCodec.feedbackListFromJson(prefs.getString(KEY_LIST, null))

    /** 삭제 기록을 남기고 기록 ID를 돌려준다. 같은 일정의 수정 기록은 삭제 기록에 합쳐지므로 지운다. */
    @Synchronized
    fun recordDeletion(event: CreatedEvent, lastDraft: EventDraft?, source: DeletionSource): String {
        removeEdit(event.eventId)
        val feedback = DeletionFeedback(
            id = UUID.randomUUID().toString(),
            deletedAtMillis = System.currentTimeMillis(),
            source = source,
            reason = DeletionReason.UNSPECIFIED,
            origin = event.origin,
            lastDraft = lastDraft,
        )
        save(listOf(feedback) + all())
        return feedback.id
    }

    @Synchronized
    fun setReason(id: String, reason: DeletionReason) =
        save(all().map { if (it.id == id) it.copy(reason = reason) else it })

    /** 삭제를 되돌렸거나 사용자가 기록을 지울 때. */
    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

    @Synchronized
    fun clear() = prefs.edit().remove(KEY_LIST).remove(KEY_EDITS).apply()

    // ---- 수정 기록 ----

    /** 최근 수정이 앞에 온다. */
    fun edits(): List<EditFeedback> = FeedbackCodec.editListFromJson(prefs.getString(KEY_EDITS, null))

    /**
     * 사용자가 고친 일정을 기록한다 (일정당 하나, 최신 값으로 덮어씀).
     * 처음 추출한 값과 같아졌으면(되돌렸으면) 기록을 지운다.
     * @return 기록이 새로 생기거나 바뀌었으면 true
     */
    @Synchronized
    fun recordEdit(event: CreatedEvent, corrected: EventDraft, source: EditSource): Boolean {
        val extracted = event.origin.extracted ?: return false
        val others = edits().filterNot { it.eventId == event.eventId }
        val existing = edits().firstOrNull { it.eventId == event.eventId }
        if (EditDiff.changes(extracted, corrected).isEmpty()) {
            if (existing != null) saveEdits(others)
            return existing != null
        }
        if (existing != null && EditDiff.changes(existing.corrected, corrected).isEmpty()) {
            // 같은 값이 이미 기록됨. 앱에서 저장하는 사이 캘린더 변경 감지가 먼저 기록했다면 출처만 바로잡는다.
            if (source == EditSource.APP && existing.source != EditSource.APP) {
                saveEdits(listOf(existing.copy(source = EditSource.APP)) + others)
            }
            return false
        }
        saveEdits(listOf(EditFeedback(event.eventId, System.currentTimeMillis(), source, event.origin, corrected)) + others)
        return true
    }

    @Synchronized
    fun removeEdit(eventId: Long) {
        val list = edits()
        if (list.any { it.eventId == eventId }) saveEdits(list.filterNot { it.eventId == eventId })
    }

    /** 지금까지 자동 등록한 일정 수 (삭제 비율 계산용). */
    val totalCreated: Int get() = prefs.getInt(KEY_TOTAL, 0)

    fun incrementCreated(count: Int = 1) {
        synchronized(this) { prefs.edit().putInt(KEY_TOTAL, totalCreated + count).apply() }
    }

    fun stats(): FeedbackStats = FeedbackStats.of(all(), totalCreated, edits())

    /** 삭제·수정 기록을 일정 추출 프롬프트에 반영할지 (기본: 켜짐). */
    var useInPrompt: Boolean
        get() = prefs.getBoolean(KEY_USE, true)
        set(v) = prefs.edit().putBoolean(KEY_USE, v).apply()

    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun saveEdits(list: List<EditFeedback>) {
        prefs.edit().putString(KEY_EDITS, FeedbackCodec.editListToJson(list.take(MAX))).apply()
    }

    private fun save(list: List<DeletionFeedback>) {
        prefs.edit().putString(KEY_LIST, FeedbackCodec.feedbackListToJson(list.take(MAX))).apply()
    }

    private companion object {
        const val KEY_LIST = "feedback"
        const val KEY_EDITS = "edits"
        const val KEY_TOTAL = "total_created"
        const val KEY_USE = "use_in_prompt"
        const val MAX = 200
    }
}
