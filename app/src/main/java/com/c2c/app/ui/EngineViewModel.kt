package com.c2c.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.c2c.app.data.AppSettings
import com.c2c.app.model.ExtractionEngine
import com.c2c.app.model.LocalModelSpec
import com.c2c.app.model.LocalModels
import com.c2c.app.work.ModelDownloadWorker
import com.c2c.app.work.WorkScheduler
import com.c2c.llm.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 선택한 기기 내 모델 파일의 상태. */
sealed interface LocalModelState {
    data class Ready(val sizeBytes: Long) : LocalModelState
    data class Downloading(val progress: Float?) : LocalModelState
    data object Importing : LocalModelState
    data class Missing(val error: String?) : LocalModelState
}

data class EngineUiState(
    val engine: ExtractionEngine = ExtractionEngine.CLAUDE,
    val apiKeySaved: Boolean = false,
    val selected: LocalModelSpec = LocalModels.spec(LocalModels.DEFAULT_ID),
    val customUrl: String = "",
    val model: LocalModelState = LocalModelState.Missing(null),
    /** 받아 둔 기기 내 모델 전체 용량 */
    val usedBytes: Long = 0,
)

/** 설정 → 일정 추출 엔진 (Claude API / 기기 내 무료 모델). */
class EngineViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = AppSettings(app)
    private val workManager = WorkManager.getInstance(app)

    /** 파일 가져오기 진행 중 / 오류 (WorkManager 밖에서 하는 작업이라 따로 둔다). */
    private val importing = MutableStateFlow(false)
    private val importError = MutableStateFlow<String?>(null)
    /** 파일을 지우거나 가져온 뒤 상태를 다시 계산하기 위한 신호 */
    private val fileChanged = MutableStateFlow(0)

    val state: StateFlow<EngineUiState> = combine(
        settings.changes(),
        workManager.getWorkInfosForUniqueWorkFlow(WorkScheduler.WORK_LLM_MODEL),
        importing,
        importError,
        fileChanged,
    ) { _, infos, isImporting, importErr, _ ->
        val spec = settings.localModel
        val file = LocalModels.file(app, spec)
        val info = infos.lastOrNull { WorkScheduler.llmTag(file) in it.tags }
        val modelState = when {
            spec.id == LocalModels.IMPORTED_ID && isImporting -> LocalModelState.Importing
            info != null && (info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED) ->
                LocalModelState.Downloading(info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, -1f).takeIf { it >= 0f })
            LocalModels.isReady(app, spec) -> LocalModelState.Ready(file.length())
            info?.state == WorkInfo.State.FAILED ->
                LocalModelState.Missing(info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "다운로드에 실패했습니다")
            spec.id == LocalModels.IMPORTED_ID -> LocalModelState.Missing(importErr)
            else -> LocalModelState.Missing(null)
        }
        EngineUiState(
            engine = settings.extractionEngine,
            apiKeySaved = settings.apiKey.isNotBlank(),
            selected = spec,
            customUrl = settings.customModelUrl,
            model = modelState,
            usedBytes = LocalModels.usedBytes(app),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineUiState())

    fun setEngine(engine: ExtractionEngine) {
        settings.extractionEngine = engine
        // Claude 는 인터넷이 필요하고 기기 내 모델은 아니므로 자동 분석 작업의 조건을 다시 건다
        WorkScheduler.ensureScheduled(getApplication())
    }

    fun saveApiKey(key: String) {
        settings.apiKey = key
    }

    fun selectLocalModel(id: String) {
        settings.localModelId = id
    }

    fun setCustomUrl(url: String) {
        settings.customModelUrl = url
        settings.localModelId = LocalModels.CUSTOM_ID
    }

    fun download() {
        val spec = settings.localModel
        val url = spec.url ?: return
        WorkScheduler.downloadLocalModel(getApplication(), url, LocalModels.file(getApplication(), spec))
    }

    fun cancelDownload() = WorkScheduler.cancelLocalModelDownload(getApplication())

    fun deleteModel() = viewModelScope.launch(Dispatchers.IO) {
        LocalModels.delete(getApplication(), settings.localModel)
        fileChanged.value++
    }

    /** 기기에 있는 GGUF 파일을 앱 저장소로 복사한다 (직접 변환한 HyperCLOVA X SEED 등). */
    fun importModel(uri: Uri) = viewModelScope.launch {
        settings.localModelId = LocalModels.IMPORTED_ID
        importing.value = true
        importError.value = null
        val app = getApplication<Application>()
        val target = LocalModels.file(app, LocalModels.spec(LocalModels.IMPORTED_ID))
        val error = withContext(Dispatchers.IO) {
            val part = File(target.path + ".part")
            try {
                target.parentFile?.mkdirs()
                app.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "파일을 열 수 없습니다" }
                    part.outputStream().use { input.copyTo(it, 1024 * 1024) }
                }
                if (!LlamaEngine.isGguf(part)) return@withContext "GGUF 모델 파일이 아닙니다"
                target.delete()
                if (!part.renameTo(target)) return@withContext "파일을 저장하지 못했습니다"
                null
            } catch (e: Exception) {
                e.message ?: "가져오지 못했습니다"
            } finally {
                part.delete()
            }
        }
        importError.value = error
        importing.value = false
        fileChanged.value++
    }
}
