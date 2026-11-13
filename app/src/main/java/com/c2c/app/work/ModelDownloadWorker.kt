package com.c2c.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.c2c.app.model.ModelDownloader
import com.c2c.llm.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 모델 파일 다운로드 (Whisper, GGUF 언어 모델 공용).
 * 입력: [KEY_URL], [KEY_PATH], [KEY_GGUF]. 진행률은 [KEY_PROGRESS] (0.0~1.0) 로 보고한다.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val target = File(inputData.getString(KEY_PATH) ?: return@withContext Result.failure())
        try {
            ModelDownloader.download(url, target) { p ->
                runBlocking { setProgress(workDataOf(KEY_PROGRESS to p)) }
            }
            if (inputData.getBoolean(KEY_GGUF, false) && !LlamaEngine.isGguf(target)) {
                target.delete()
                Log.e(TAG, "GGUF 파일이 아닙니다: $url")
                return@withContext Result.failure(workDataOf(KEY_ERROR to "GGUF 모델 파일이 아닙니다"))
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "다운로드 실패", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "다운로드 실패")))
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_PATH = "path"
        const val KEY_GGUF = "gguf"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val TAG = "ModelDownload"
    }
}
