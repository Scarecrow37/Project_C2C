package com.c2c.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.c2c.app.calendar.CalendarRepository
import com.c2c.app.calendar.StoredEvent
import com.c2c.app.data.CreatedEvent
import com.c2c.app.data.CreatedEventStore
import com.c2c.app.data.FeedbackStore
import com.c2c.core.DeletionReason
import com.c2c.core.DeletionSource
import com.c2c.core.EventDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 목록에 보이는 일정 하나: 앱이 기억하는 출처 정보 + 캘린더의 현재 내용. */
data class EventItem(val meta: CreatedEvent, val stored: StoredEvent) {
    val id: Long get() = stored.eventId
    val draft: EventDraft get() = stored.draft
}

sealed interface EventsState {
    data object Loading : EventsState
    data object NoPermission : EventsState
    data class Loaded(val items: List<EventItem>) : EventsState
}

/** 방금 삭제한 일정의 이유를 물어보는 카드. */
data class ReasonRequest(val feedbackId: String, val title: String)

sealed interface EventsMessage {
    /** @param feedbackId 삭제 기록 ID. 되돌리면 기록도 지운다. */
    data class Deleted(val item: EventItem, val feedbackId: String) : EventsMessage
    data class Text(val text: String) : EventsMessage
}

/**
 * 자동 생성된 일정 목록. 캘린더가 원본이므로 캘린더 앱에서 바꾼 내용도 바로 반영하고,
 * 캘린더에서 지운 일정은 목록에서도 빠진다.
 */
class EventsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CreatedEventStore(app)
    private val feedback = FeedbackStore(app)

    private val _reasonRequest = MutableStateFlow<ReasonRequest?>(null)
    /** 목록 위에 보여줄 "삭제 이유" 질문. 고르거나 닫으면 null. */
    val reasonRequest: StateFlow<ReasonRequest?> = _reasonRequest
    private val repo = CalendarRepository(app)

    private val _state = MutableStateFlow<EventsState>(EventsState.Loading)

    /** 삭제 요청한 일정. 캘린더 삭제가 끝나기 전에 목록에서 먼저 뺀다 (스와이프가 자연스럽게 보이도록). */
    private val pendingDelete = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<EventsState> = combine(_state, pendingDelete) { s, pending ->
        if (s is EventsState.Loaded && pending.isNotEmpty()) EventsState.Loaded(s.items.filterNot { it.id in pending }) else s
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EventsState.Loading)

    private val _messages = Channel<EventsMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var refreshJob: Job? = null
    private var observerRegistered = false
    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        viewModelScope.launch { store.changes().collect { refresh() } }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!hasPermission()) {
                _state.value = EventsState.NoPermission
                return@launch
            }
            registerObserver()
            val items = withContext(Dispatchers.IO) {
                val metas = store.all()
                val found = metas.mapNotNull { meta -> repo.get(meta.eventId)?.let { EventItem(meta, it) } }
                // 캘린더 앱에서 삭제된 일정은 목록에서 정리하고 피드백으로 남긴다
                val foundIds = found.map { it.id }.toSet()
                val missing = metas.filter { it.eventId !in foundIds && it.eventId !in pendingDelete.value }
                missing.forEach { feedback.recordDeletion(it, null, DeletionSource.CALENDAR_APP) }
                store.removeAll(missing.map { it.eventId })
                found.sortedBy { it.draft.start }
            }
            _state.value = EventsState.Loaded(items)
        }
    }

    /** 일정 삭제. 스낵바의 "되돌리기"로 복구할 수 있다. 실패하면 목록에 다시 나타난다. */
    fun delete(item: EventItem) {
        pendingDelete.update { it + item.id }
        viewModelScope.launch {
            val feedbackId = withContext(Dispatchers.IO) {
                runCatching {
                    if (repo.delete(item.id)) {
                        feedback.recordDeletion(item.meta, item.draft, DeletionSource.APP).also { store.remove(item.id) }
                    } else {
                        null
                    }
                }.getOrNull()
            }
            if (feedbackId != null) {
                _reasonRequest.value = ReasonRequest(feedbackId, item.draft.title)
                _messages.send(EventsMessage.Deleted(item, feedbackId))
            } else {
                pendingDelete.update { it - item.id }
                _messages.send(EventsMessage.Text("삭제하지 못했습니다"))
            }
        }
    }

    fun undoDelete(item: EventItem, feedbackId: String) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val newId = repo.insert(item.stored.calendarId, item.draft)
                store.add(item.meta.copy(eventId = newId))
                feedback.remove(feedbackId) // 되돌렸으면 잘못 등록된 일정이 아니다
            }.isSuccess
        }
        if (ok && _reasonRequest.value?.feedbackId == feedbackId) _reasonRequest.value = null
        if (!ok) _messages.send(EventsMessage.Text("일정을 복구하지 못했습니다"))
    }

    /** 삭제 이유 선택. 다음 일정 추출 때 같은 실수를 피하는 데 쓰인다. */
    fun setReason(feedbackId: String, reason: DeletionReason) {
        _reasonRequest.value = null
        viewModelScope.launch(Dispatchers.IO) { feedback.setReason(feedbackId, reason) }
    }

    fun dismissReason() {
        _reasonRequest.value = null
    }

    /** 수정 내용을 캘린더에 저장한다. 성공하면 true. */
    suspend fun save(item: EventItem, draft: EventDraft): Boolean {
        val normalized = draft.normalized()
        normalized.validate()?.let {
            _messages.send(EventsMessage.Text(it))
            return false
        }
        val ok = withContext(Dispatchers.IO) { runCatching { repo.update(item.id, normalized) }.getOrDefault(false) }
        _messages.send(EventsMessage.Text(if (ok) "일정을 수정했습니다" else "일정을 찾지 못했습니다 (이미 삭제됨)"))
        refresh()
        return ok
    }

    private fun hasPermission() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
        ContextCompat.checkSelfPermission(getApplication(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerObserver() {
        if (observerRegistered) return
        observerRegistered = runCatching {
            getApplication<Application>().contentResolver
                .registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarObserver)
        }.isSuccess
    }

    override fun onCleared() {
        if (observerRegistered) getApplication<Application>().contentResolver.unregisterContentObserver(calendarObserver)
    }
}
