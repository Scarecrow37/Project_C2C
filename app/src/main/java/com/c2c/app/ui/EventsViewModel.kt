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
import com.c2c.core.EventDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

sealed interface EventsMessage {
    data class Deleted(val item: EventItem) : EventsMessage
    data class Text(val text: String) : EventsMessage
}

/**
 * 자동 생성된 일정 목록. 캘린더가 원본이므로 캘린더 앱에서 바꾼 내용도 바로 반영하고,
 * 캘린더에서 지운 일정은 목록에서도 빠진다.
 */
class EventsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = CreatedEventStore(app)
    private val repo = CalendarRepository(app)

    private val _state = MutableStateFlow<EventsState>(EventsState.Loading)
    val state: StateFlow<EventsState> = _state.asStateFlow()

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
                // 캘린더 앱에서 삭제된 일정은 목록에서도 정리
                store.removeAll(metas.map { it.eventId } - found.map { it.id }.toSet())
                found.sortedBy { it.draft.start }
            }
            _state.value = EventsState.Loaded(items)
        }
    }

    /** 원클릭 삭제. 스낵바의 "되돌리기"로 복구할 수 있다. */
    fun delete(item: EventItem) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { repo.delete(item.id).also { store.remove(item.id) } }.getOrDefault(false)
        }
        _messages.send(if (ok) EventsMessage.Deleted(item) else EventsMessage.Text("삭제하지 못했습니다"))
    }

    fun undoDelete(item: EventItem) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val newId = repo.insert(item.stored.calendarId, item.draft)
                store.add(item.meta.copy(eventId = newId))
            }.isSuccess
        }
        if (!ok) _messages.send(EventsMessage.Text("일정을 복구하지 못했습니다"))
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
