package com.c2c.app.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.c2c.core.CalendarTime
import com.c2c.core.EventDraft
import java.time.ZoneId

data class CalendarInfo(val id: Long, val name: String, val account: String, val isPrimary: Boolean)

/** 캘린더에 저장된 일정 하나 (캘린더 앱에서 수정한 내용까지 반영된 현재 값). */
data class StoredEvent(val eventId: Long, val calendarId: Long, val draft: EventDraft)

/** 기기 캘린더(CalendarContract)에 일정을 읽고 쓴다. READ/WRITE_CALENDAR 권한이 필요하다. */
class CalendarRepository(
    private val context: Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

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

    /** 일정을 등록하고 알림(reminder)을 붙인 뒤 이벤트 ID를 돌려준다. */
    fun insert(calendarId: Long, draft: EventDraft): Long {
        val values = toValues(draft).apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
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
                put(CalendarContract.Reminders.MINUTES, reminderMinutes(draft.allDay))
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
        return eventId
    }

    /** 일정 내용을 수정한다. 일정이 이미 삭제되었으면 false. */
    fun update(eventId: Long, draft: EventDraft): Boolean =
        resolver.update(eventUri(eventId), toValues(draft), null, null) > 0

    /** 일정의 현재 값을 읽는다. 삭제되었거나 없으면 null. */
    fun get(eventId: Long): StoredEvent? {
        val projection = arrayOf(
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DELETED,
        )
        resolver.query(eventUri(eventId), projection, null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(7) == 1) return null
            val allDay = c.getInt(4) == 1
            val start = CalendarTime.fromMillis(c.getLong(2), allDay, zone)
            // DTEND가 비어 있는 일정(반복 일정 등)은 1시간짜리로 본다
            val end = if (c.isNull(3)) start.plusHours(1) else CalendarTime.fromMillis(c.getLong(3), allDay, zone)
            return StoredEvent(
                eventId = eventId,
                calendarId = c.getLong(0),
                draft = EventDraft(
                    title = c.getString(1).orEmpty(),
                    start = start,
                    end = end,
                    allDay = allDay,
                    location = c.getString(5),
                    description = c.getString(6),
                ),
            )
        }
        return null
    }

    fun delete(eventId: Long): Boolean = resolver.delete(eventUri(eventId), null, null) > 0

    private fun eventUri(eventId: Long) = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)

    private fun toValues(draft: EventDraft) = ContentValues().apply {
        put(CalendarContract.Events.TITLE, draft.title)
        put(CalendarContract.Events.DESCRIPTION, draft.description)
        put(CalendarContract.Events.EVENT_LOCATION, draft.location)
        put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, CalendarTime.timezoneId(draft.allDay, zone))
        put(CalendarContract.Events.DTSTART, CalendarTime.toMillis(draft.start, draft.allDay, zone))
        put(CalendarContract.Events.DTEND, CalendarTime.toMillis(draft.end, draft.allDay, zone))
    }

    companion object {
        const val TIMED_REMINDER_MINUTES = 30
        /** 종일 일정: 전날 오전 9시 (자정 기준 15시간 전) */
        const val ALL_DAY_REMINDER_MINUTES = 15 * 60

        fun reminderMinutes(allDay: Boolean) = if (allDay) ALL_DAY_REMINDER_MINUTES else TIMED_REMINDER_MINUTES
    }
}
