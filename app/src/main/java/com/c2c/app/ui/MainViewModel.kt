package com.c2c.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.c2c.app.calendar.CalendarInfo
import com.c2c.app.calendar.CalendarRepository
import com.c2c.app.data.AppSettings
import com.c2c.app.data.HistoryEntry
import com.c2c.app.stt.WhisperModel
import com.c2c.app.work.ModelDownloadWorker
import com.c2c.app.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val apiKeySaved: Boolean = false,
    val calendarId: Long = -1,
    val autoEnabled: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
)

/** 모델 상태: 준비됨 / 다운로드 중(진행률, 모르면 null) / 없음 */
sealed interface ModelState {
    data object Ready : ModelState
    data class Downloading(val progress: Float?) : ModelState
    data class Missing(val failed: Boolean) : ModelState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val calendarRepo = CalendarRepository(app)

    val state: StateFlow<UiState> = settings.changes().map {
        UiState(
            apiKeySaved = settings.apiKey.isNotBlank(),
            calendarId = settings.calendarId,
            autoEnabled = settings.autoProcessEnabled,
            history = settings.history(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    val modelState: StateFlow<ModelState> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(WorkScheduler.WORK_MODEL).map { infos ->
            val info = infos.lastOrNull()
            when {
                info != null && (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED) -> {
                    val p = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, -1f)
                    ModelState.Downloading(p.takeIf { it >= 0f })
                }
                WhisperModel.isReady(app) -> ModelState.Ready
                else -> ModelState.Missing(failed = info?.state == WorkInfo.State.FAILED)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            if (WhisperModel.isReady(app)) ModelState.Ready else ModelState.Missing(false),
        )

    private val _calendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val calendars: StateFlow<List<CalendarInfo>> = _calendars.asStateFlow()

    fun loadCalendars() = viewModelScope.launch {
        val list = withContext(Dispatchers.IO) { runCatching { calendarRepo.writableCalendars() }.getOrDefault(emptyList()) }
        _calendars.value = list
        // 아직 고르지 않았으면 기본 캘린더를 자동 선택
        if (settings.calendarId < 0) list.firstOrNull()?.let { settings.calendarId = it.id }
    }

    fun saveApiKey(key: String) {
        settings.apiKey = key
    }

    fun selectCalendar(id: Long) {
        settings.calendarId = id
    }

    fun setAutoEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        if (enabled) WorkScheduler.enable(ctx) else WorkScheduler.disable(ctx)
    }

    fun downloadModel() = WorkScheduler.downloadModel(getApplication())

    fun scanNow() = WorkScheduler.enqueueScan(getApplication())

    /** 사용자가 고른 오디오 파일로 전체 흐름을 테스트한다. */
    fun processFile(uri: Uri) {
        val ctx = getApplication<Application>()
        // 작업이 나중에 실행되어도 파일을 읽을 수 있도록 권한을 유지한다.
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "선택한 파일"
        WorkScheduler.enqueueFile(ctx, uri, name)
    }
}
