package com.c2c.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.c2c.app.data.FeedbackStore
import com.c2c.core.DeletionFeedback
import com.c2c.core.CorrectionExamples
import com.c2c.core.DeletionReason
import com.c2c.core.EditFeedback
import com.c2c.core.FeedbackExamples
import com.c2c.core.FeedbackCodec
import com.c2c.core.FeedbackStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

data class FeedbackUiState(
    val records: List<DeletionFeedback> = emptyList(),
    /** 실제로 바뀐 항목이 있는 수정 기록 */
    val edits: List<EditFeedback> = emptyList(),
    val stats: FeedbackStats = FeedbackStats(0, 0, 0, emptyMap()),
    val useInPrompt: Boolean = true,
    /** 다음 추출 때 실제로 프롬프트에 들어갈 삭제 기록 수 */
    val examplesInUse: Int = 0,
    /** 다음 추출 때 실제로 프롬프트에 들어갈 수정 기록 수 */
    val correctionsInUse: Int = 0,
)

/** 개선 피드백(삭제·수정 기록) 화면과 설정 섹션. */
class FeedbackViewModel(app: Application) : AndroidViewModel(app) {

    private val store = FeedbackStore(app)

    val state: StateFlow<FeedbackUiState> = store.changes().map {
        val records = store.all()
        val edits = store.edits().filter { it.changes.isNotEmpty() }
        val use = store.useInPrompt
        FeedbackUiState(
            records = records,
            edits = edits,
            stats = FeedbackStats.of(records, store.totalCreated, edits),
            useInPrompt = use,
            examplesInUse = if (use) FeedbackExamples.select(records).size else 0,
            correctionsInUse = if (use) CorrectionExamples.select(edits).size else 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedbackUiState())

    fun setUseInPrompt(enabled: Boolean) {
        store.useInPrompt = enabled
    }

    fun setReason(id: String, reason: DeletionReason) = viewModelScope.launch(Dispatchers.IO) { store.setReason(id, reason) }

    fun remove(id: String) = viewModelScope.launch(Dispatchers.IO) { store.remove(id) }

    fun removeEdit(eventId: Long) = viewModelScope.launch(Dispatchers.IO) { store.removeEdit(eventId) }

    fun clear() = viewModelScope.launch(Dispatchers.IO) { store.clear() }

    /** 분석용 JSON 파일로 내보낸다. 성공하면 true. */
    suspend fun export(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = FeedbackCodec.exportJson(store.all(), store.edits(), store.stats(), LocalDateTime.now())
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
        }.isSuccess
    }
}
