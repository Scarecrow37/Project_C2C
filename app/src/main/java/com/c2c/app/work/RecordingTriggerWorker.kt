package com.c2c.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * MediaStore 오디오가 바뀌면(새 통화 녹음 저장) 깨어나는 가벼운 작업.
 * 콘텐츠 URI 트리거는 1회성이라 매번 자신을 다시 등록하고, 실제 처리는 [CallProcessingWorker]에 넘긴다.
 */
class RecordingTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WorkScheduler.enqueueScan(applicationContext)
        WorkScheduler.registerContentTrigger(applicationContext)
        return Result.success()
    }
}
