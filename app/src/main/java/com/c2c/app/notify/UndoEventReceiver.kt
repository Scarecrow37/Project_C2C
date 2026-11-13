package com.c2c.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.c2c.app.calendar.CalendarRepository
import com.c2c.app.data.CreatedEventStore
import com.c2c.app.data.FeedbackStore
import com.c2c.core.DeletionSource

/** 알림의 "취소" 버튼: 자동 등록된 일정을 삭제하고, 잘못 등록된 일정으로 피드백을 남긴다. */
class UndoEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (eventId < 0) return

        val calendar = CalendarRepository(context)
        val store = CreatedEventStore(context)
        val lastDraft = runCatching { calendar.get(eventId)?.draft }.getOrNull()
        val deleted = runCatching { calendar.delete(eventId) }.getOrDefault(false)
        if (deleted) {
            store.find(eventId)?.let { FeedbackStore(context).recordDeletion(it, lastDraft, DeletionSource.NOTIFICATION) }
        }
        store.remove(eventId)
        Notifier(context).cancel(notificationId)
        Toast.makeText(context, if (deleted) "일정을 삭제했습니다" else "일정을 찾지 못했습니다", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
