package com.c2c.app.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.c2c.core.ScheduleEvent
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(val id: Long, val name: String, val account: String, val isPrimary: Boolean)

/** 기기 캘린더(CalendarContract)에 일정을 읽고 쓴다. READ/WRITE_CALENDAR 권한이 필요하다. */
class CalendarRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** 일정을 추가할 수 있는 캘린더 목록. */
    fun writableCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND " +
            "${CalendarContract.Calendars.VISIBLE} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val list = mutableListOf<CalendarInfo>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                list += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "(이름 없음)",
                    account = c.getString(2) ?: "",
                    isPrimary = c.getInt(3) == 1,
                )
            }
        }
        return list.sortedByDescending { it.isPrimary }
    }

    /** 일정을 등록하고 이벤트 ID를 돌려준다. */
    fun insert(
        calendarId: Long,
        event: ScheduleEvent,
        zone: ZoneId,
        description: String,
        reminderMinutes: Int = if (event.allDay) ALL_DAY_REMINDER_MINUTES else TIMED_REMINDER_MINUTES,
    ): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, description)
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (event.allDay) {
                // 종일 일정은 UTC 자정 기준으로 저장해야 한다 (CalendarContract 규칙).
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, event.start.toInstant(ZoneOffset.UTC).toEpochMilli())
                put(CalendarContract.Events.DTEND, event.end.toInstant(ZoneOffset.UTC).toEpochMilli())
            } else {
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                put(CalendarContract.Events.DTSTART, event.start.atZone(zone).toInstant().toEpochMilli())
                put(CalendarContract.Events.DTEND, event.end.atZone(zone).toInstant().toEpochMilli())
            }
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("캘린더에 일정을 추가하지 못했습니다")
        val eventId = ContentUris.parseId(uri)

        // 일정 알림: 캘린더 앱이 지정한 시간에 알림을 띄운다.
        resolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
        return eventId
    }

    fun delete(eventId: Long): Boolean =
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) > 0

    companion object {
        const val TIMED_REMINDER_MINUTES = 30
        /** 종일 일정: 전날 오전 9시 (자정 기준 15시간 전) */
        const val ALL_DAY_REMINDER_MINUTES = 15 * 60
    }
}
