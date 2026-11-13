package com.c2c.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.c2c.app.stt.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Whisper 모델 다운로드. 진행률은 [KEY_PROGRESS] (0.0~1.0) 로 보고한다. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            WhisperModel.download(applicationContext) { p ->
                runBlocking { setProgress(workDataOf(KEY_PROGRESS to p)) }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("ModelDownload", "다운로드 실패", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_PROGRESS = "progress"
    }
}
