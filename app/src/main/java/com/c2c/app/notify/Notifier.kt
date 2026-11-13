package com.c2c.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.c2c.app.R
import com.c2c.app.ui.MainActivity
import com.c2c.core.ScheduleEvent
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 앱 알림 (일정 등록 완료, 처리 중, 오류). */
class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_EVENTS, "일정 자동 등록", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "통화에서 찾은 일정을 캘린더에 등록하면 알려줍니다" },
                NotificationChannel(CH_PROCESSING, "통화 분석 중", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_ALERTS, "설정·오류 안내", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun eventAdded(eventId: Long, event: ScheduleEvent, counterpart: String?) {
        // 고정 ID(1~3)와 겹치지 않도록 1000 이상으로
        val notificationId = (eventId % 1_000_000).toInt() + 1000
        // 알림을 누르면 앱의 "생성된 일정" 목록에서 해당 일정을 바로 수정할 수 있게 연다.
        val viewIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val undoIntent = Intent(context, UndoEventReceiver::class.java)
            .putExtra(UndoEventReceiver.EXTRA_EVENT_ID, eventId)
            .putExtra(UndoEventReceiver.EXTRA_NOTIFICATION_ID, notificationId)

        val text = buildString {
            append(event.start.format(if (event.allDay) DATE else DATE_TIME))
            event.location?.let { append(" · ").append(it) }
            counterpart?.let { append("\n통화: ").append(it) }
            if (event.evidence.isNotBlank()) append("\n\"").append(event.evidence).append('"')
        }
        notify(
            notificationId,
            NotificationCompat.Builder(context, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("일정 추가됨: ${event.title}")
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending(notificationId, viewIntent, activity = true))
                .addAction(0, "취소", pending(notificationId, undoIntent, activity = false))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun alert(id: Int, title: String, text: String) {
        val open = Intent(context, MainActivity::class.java)
        notify(
            id,
            NotificationCompat.Builder(context, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending(id, open, activity = true))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun processingNotification(text: String) =
        NotificationCompat.Builder(context, CH_PROCESSING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("통화 내용 분석 중")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun notify(id: Int, notification: android.app.Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun pending(requestCode: Int, intent: Intent, activity: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (activity) {
            PendingIntent.getActivity(context, requestCode, intent, flags)
        } else {
            PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }

    companion object {
        const val CH_EVENTS = "events"
        const val CH_PROCESSING = "processing"
        const val CH_ALERTS = "alerts"

        const val ID_PROCESSING = 1
        const val ID_SETUP = 2
        const val ID_ERROR = 3

        private val DATE = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
        private val DATE_TIME = DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREAN)
    }
}
