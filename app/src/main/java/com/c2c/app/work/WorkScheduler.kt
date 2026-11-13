package com.c2c.app.work

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.c2c.app.data.AppSettings
import com.c2c.app.model.ExtractionEngine
import com.c2c.app.stt.WhisperModel
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 백그라운드 작업 등록/해제. */
object WorkScheduler {

    const val WORK_SCAN = "scan"
    const val WORK_MANUAL = "manual"
    const val WORK_TRIGGER = "media-trigger"
    const val WORK_PERIODIC = "periodic-scan"
    const val WORK_MODEL = "model-download"
    const val WORK_LLM_MODEL = "llm-model-download"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 통화 분석 작업의 조건. Claude 는 인터넷이 필요하고, 기기 내 모델은 오프라인에서도 돈다. */
    private fun scanConstraints(context: Context): Constraints =
        if (AppSettings(context).extractionEngine == ExtractionEngine.LOCAL) Constraints.NONE else networkConstraint

    /** 자동 처리 켜기: 새 녹음 감지 트리거 + 1시간 주기 백업 스캔. */
    fun enable(context: Context) {
        val settings = AppSettings(context)
        if (settings.scanSinceSeconds == 0L) {
            // 처음 켤 때는 지금 이후의 통화만 처리한다.
            settings.scanSinceSeconds = System.currentTimeMillis() / 1000
        }
        settings.autoProcessEnabled = true
        registerContentTrigger(context)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE, // 엔진을 바꾸면 네트워크 조건도 바뀌므로
            PeriodicWorkRequestBuilder<CallProcessingWorker>(1, TimeUnit.HOURS)
                .setConstraints(scanConstraints(context))
                .build(),
        )
    }

    fun disable(context: Context) {
        AppSettings(context).autoProcessEnabled = false
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WORK_TRIGGER)
            cancelUniqueWork(WORK_PERIODIC)
        }
    }

    /** 앱 시작 시 호출: 켜져 있으면 트리거가 살아 있도록 다시 등록한다. */
    fun ensureScheduled(context: Context) {
        if (AppSettings(context).autoProcessEnabled) enable(context)
    }

    internal fun registerContentTrigger(context: Context) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(10)) // 파일 저장이 끝날 때까지 잠깐 기다림
            .setTriggerContentMaxDelay(Duration.ofMinutes(2))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_TRIGGER,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RecordingTriggerWorker>().setConstraints(constraints).build(),
        )
    }

    /** 지금 새 녹음을 스캔한다. 진행 중인 스캔이 있으면 그 뒤에 한 번 더 실행된다. */
    fun enqueueScan(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_SCAN,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CallProcessingWorker>()
                .setConstraints(scanConstraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .build(),
        )
    }

    /** 사용자가 고른 녹음 파일 하나를 처리한다 (테스트용). */
    fun enqueueFile(context: Context, uri: Uri, displayName: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_MANUAL,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CallProcessingWorker>()
                .setInputData(
                    workDataOf(CallProcessingWorker.KEY_URI to uri.toString(), CallProcessingWorker.KEY_NAME to displayName),
                )
                .setConstraints(scanConstraints(context))
                .build(),
        )
    }

    /** Whisper 음성 인식 모델 다운로드. */
    fun downloadModel(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_MODEL,
            ExistingWorkPolicy.KEEP,
            download(WhisperModel.URL, WhisperModel.file(context), gguf = false),
        )
    }

    /** 기기 내 언어 모델(GGUF) 다운로드. 다른 모델을 받는 중이면 그것을 취소하고 새로 받는다. */
    fun downloadLocalModel(context: Context, url: String, target: File) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_LLM_MODEL,
            ExistingWorkPolicy.REPLACE,
            download(url, target, gguf = true, tag = llmTag(target)),
        )
    }

    fun cancelLocalModelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_LLM_MODEL)
    }

    /** 어떤 모델 파일을 받는 작업인지 표시하는 태그. */
    fun llmTag(target: File) = "llm:" + target.name

    private fun download(url: String, target: File, gguf: Boolean, tag: String? = null) =
        OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .apply { tag?.let { addTag(it) } }
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_URL to url,
                    ModelDownloadWorker.KEY_PATH to target.absolutePath,
                    ModelDownloadWorker.KEY_GGUF to gguf,
                ),
            )
            .setConstraints(networkConstraint)
            .build()
}
