package com.c2c.app.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.c2c.app.data.AppSettings
import com.c2c.app.data.HistoryEntry
import com.c2c.app.notify.Notifier
import com.c2c.app.recording.CallRecording
import com.c2c.app.recording.CallRecordingScanner
import com.c2c.app.stt.WhisperModel
import com.c2c.app.stt.WhisperTranscriber
import com.c2c.core.ClaudeScheduleExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 새 통화 녹음을 찾아 처리한다.
 * - 입력 데이터에 [KEY_URI]가 있으면 그 파일 하나만 처리한다 ("파일로 테스트" 기능).
 * - 없으면 MediaStore 에서 아직 처리하지 않은 통화 녹음을 모두 처리한다.
 */
class CallProcessingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val settings = AppSettings(context)
    private val notifier = Notifier(context)

    override suspend fun doWork(): Result = lock.withLock {
        withContext(Dispatchers.Default) { processAll() }
    }

    private suspend fun processAll(): Result {
        val manualUri = inputData.getString(KEY_URI)?.let(Uri::parse)
        if (!checkSetup()) return Result.success()

        val targets: List<CallRecording> = if (manualUri != null) {
            listOf(
                CallRecording(
                    manualUri,
                    inputData.getString(KEY_NAME) ?: "선택한 파일",
                    System.currentTimeMillis() / 1000,
                    0,
                ),
            )
        } else {
            if (!hasPermission(audioPermission())) return Result.success()
            CallRecordingScanner(applicationContext)
                .findNewRecordings(settings.scanSinceSeconds)
                .filterNot { settings.isProcessed(it.key) }
        }
        if (targets.isEmpty()) return Result.success()

        tryStartForeground("${targets.size}건의 통화를 분석하고 있습니다")

        val transcriber = try {
            WhisperTranscriber(WhisperModel.file(applicationContext))
        } catch (e: Throwable) {
            Log.e(TAG, "Whisper 초기화 실패", e)
            notifier.alert(Notifier.ID_ERROR, "음성 인식 모델 오류", "모델을 다시 다운로드해 주세요. (${e.message})")
            return Result.failure()
        }

        transcriber.use {
            val processor = CallProcessor(
                applicationContext,
                transcriber,
                ClaudeScheduleExtractor.create(settings.apiKey),
                settings,
            )
            for (rec in targets) {
                try {
                    val outcome = processor.process(rec.uri, rec.displayName, rec.dateAddedSeconds)
                    processor.record(rec.displayName, outcome)
                    if (manualUri == null) settings.markProcessed(rec.key)
                } catch (e: UnauthorizedException) {
                    authError(); return Result.failure()
                } catch (e: PermissionDeniedException) {
                    authError(); return Result.failure()
                } catch (e: RateLimitException) {
                    return retryOrGiveUp(rec, e)
                } catch (e: InternalServerException) {
                    return retryOrGiveUp(rec, e)
                } catch (e: AnthropicIoException) {
                    return retryOrGiveUp(rec, e) // 네트워크 끊김
                } catch (e: Exception) {
                    // 디코딩 실패 등 재시도해도 소용없는 오류: 기록하고 다음 파일로
                    Log.e(TAG, "처리 실패: ${rec.displayName}", e)
                    processor.record(rec.displayName, ProcessOutcome.Skipped(e.message ?: e.javaClass.simpleName))
                    if (manualUri == null) settings.markProcessed(rec.key)
                }
            }
        }
        if (manualUri == null) {
            // 모두 처리했으므로 다음 스캔 기준 시각을 앞당긴다.
            settings.scanSinceSeconds = maxOf(settings.scanSinceSeconds, targets.maxOf { it.dateAddedSeconds })
        }
        return Result.success()
    }

    private fun retryOrGiveUp(rec: CallRecording, e: Exception): Result {
        Log.w(TAG, "일시적 오류 (시도 ${runAttemptCount + 1}회): ${e.message}")
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        settings.addHistory(
            HistoryEntry(System.currentTimeMillis(), rec.displayName, "실패: ${e.message}", emptyList()),
        )
        if (inputData.getString(KEY_URI) == null) settings.markProcessed(rec.key)
        return Result.failure()
    }

    private fun authError() {
        notifier.alert(Notifier.ID_ERROR, "Claude API 키 오류", "API 키가 올바르지 않거나 권한이 없습니다. 앱에서 키를 확인해 주세요.")
    }

    /** 필수 설정이 빠졌으면 안내 알림을 띄우고 false. */
    private fun checkSetup(): Boolean {
        val missing = buildList {
            if (settings.apiKey.isBlank()) add("Claude API 키")
            if (!WhisperModel.isReady(applicationContext)) add("음성 인식 모델 다운로드")
            if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) add("캘린더 권한")
            if (settings.calendarId < 0) add("등록할 캘린더 선택")
        }
        if (missing.isEmpty()) return true
        notifier.alert(Notifier.ID_SETUP, "설정이 필요합니다", "통화 일정을 자동 등록하려면 다음을 설정해 주세요: ${missing.joinToString()}")
        return false
    }

    private suspend fun tryStartForeground(text: String) {
        try {
            setForeground(foregroundInfo(text))
        } catch (e: Exception) {
            // Android 12+ 에서 백그라운드 시작 제한에 걸리면 일반 작업으로 계속 진행한다.
            Log.w(TAG, "포그라운드 전환 실패: ${e.message}")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("통화를 분석하고 있습니다")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val n = notifier.processingNotification(text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_PROCESSING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.ID_PROCESSING, n)
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(applicationContext, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        private const val TAG = "CallProcessingWorker"
        private const val MAX_ATTEMPTS = 4

        /** 자동 스캔(콘텐츠 트리거, 주기 작업, 수동)이 겹쳐도 한 번에 하나만 처리한다. */
        private val lock = Mutex()

        fun audioPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
}
