package com.c2c.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.c2c.core.EventDraft
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("yyyy. M. d. (E)", Locale.KOREAN)
private val TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)

/** 일정 수정 대화상자. 시작 시각을 바꾸면 일정 길이를 유지하도록 종료 시각도 함께 옮긴다. */
@Composable
fun EditEventDialog(
    initial: EventDraft,
    onDismiss: () -> Unit,
    onSave: (EventDraft) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var startDate by remember { mutableStateOf(initial.start.toLocalDate()) }
    var startTime by remember { mutableStateOf(initial.start.toLocalTime()) }
    var endDate by remember { mutableStateOf(if (initial.allDay) initial.lastDay else initial.end.toLocalDate()) }
    var endTime by remember { mutableStateOf(if (initial.allDay) LocalTime.of(10, 0) else initial.end.toLocalTime()) }
    var location by remember { mutableStateOf(initial.location.orEmpty()) }
    var description by remember { mutableStateOf(initial.description.orEmpty()) }

    fun draft(): EventDraft = if (allDay) {
        EventDraft.allDay(title, startDate, endDate, location, description)
    } else {
        EventDraft(title, startDate.atTime(startTime), endDate.atTime(endTime), false, location, description)
    }

    /** 시작을 옮길 때 기존 길이만큼 종료도 옮긴다. */
    fun moveStart(newDate: LocalDate, newTime: LocalTime) {
        val oldStart = startDate.atTime(startTime)
        val oldEnd = endDate.atTime(endTime)
        val newStart = newDate.atTime(newTime)
        val newEnd = newStart.plus(Duration.between(oldStart, oldEnd).coerceAtLeast(Duration.ZERO))
        startDate = newDate
        startTime = newTime
        endDate = newEnd.toLocalDate()
        endTime = newEnd.toLocalTime()
    }

    val context = LocalContext.current
    fun pickDate(current: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(context, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) },
            current.year, current.monthValue - 1, current.dayOfMonth).show()
    }
    fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(context, { _, h, min -> onPicked(LocalTime.of(h, min)) }, current.hour, current.minute, false).show()
    }

    val error = draft().normalized().validate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일정 수정") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("제목") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("종일", Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }

                Text("시작", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(startDate) { moveStart(it, startTime) } }) { Text(startDate.format(DATE)) }
                    if (!allDay) {
                        OutlinedButton(onClick = { pickTime(startTime) { moveStart(startDate, it) } }) { Text(startTime.format(TIME)) }
                    }
                }

                Text(if (allDay) "마지막 날" else "종료", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate(endDate) { endDate = it } }) { Text(endDate.format(DATE)) }
                    if (!allDay) {
                        OutlinedButton(onClick = { pickTime(endTime) { endTime = it } }) { Text(endTime.format(TIME)) }
                    }
                }

                OutlinedTextField(location, { location = it }, label = { Text("장소") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("메모") }, minLines = 3, modifier = Modifier.fillMaxWidth())

                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft()) }, enabled = error == null) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
