package com.c2c.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.c2c.app.calendar.CalendarRepository

/** 알림의 "취소" 버튼: 자동 등록된 일정을 삭제한다. */
class UndoEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (eventId < 0) return
        val deleted = runCatching { CalendarRepository(context).delete(eventId) }.getOrDefault(false)
        Notifier(context).cancel(notificationId)
        Toast.makeText(context, if (deleted) "일정을 삭제했습니다" else "일정을 찾지 못했습니다", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
