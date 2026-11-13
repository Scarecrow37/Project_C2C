package com.c2c.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.c2c.core.DeletionFeedback
import com.c2c.core.DeletionReason
import com.c2c.core.EditFeedback
import com.c2c.core.EventDraft
import com.c2c.core.EventFormat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 개선 피드백 기록: 삭제 기록(이유 변경·기록 삭제)과 수정 기록, JSON 내보내기. */
@Composable
fun FeedbackScreen(snackbar: SnackbarHostState, onBack: () -> Unit, vm: FeedbackViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                snackbar.showSnackbar(if (vm.export(uri)) "삭제 기록을 내보냈습니다" else "내보내지 못했습니다")
            }
        }
    }

    val hasAny = state.records.isNotEmpty() || state.edits.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← 설정") }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("개선 피드백 기록", style = MaterialTheme.typography.headlineSmall)
                    StatsText(state)
                    Text(
                        "내보낸 파일에는 통화 녹취록 일부가 들어 있습니다. 공유할 때 주의하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { exporter.launch("c2c-feedback-${System.currentTimeMillis() / 1000}.json") },
                            enabled = hasAny,
                        ) { Text("JSON으로 내보내기") }
                        OutlinedButton(onClick = { confirmClear = true }, enabled = hasAny) {
                            Text("모두 지우기")
                        }
                    }
                }
            }
            item { SectionTitle("삭제 기록 ${state.records.size}") }
            if (state.records.isEmpty()) item { EmptyText("아직 삭제한 자동 등록 일정이 없습니다") }
            items(state.records, key = { "d-" + it.id }) { record ->
                FeedbackCard(record, onReason = { vm.setReason(record.id, it) }, onRemove = { vm.remove(record.id) })
            }

            item { SectionTitle("수정 기록 ${state.edits.size}") }
            if (state.edits.isEmpty()) item { EmptyText("아직 고친 자동 등록 일정이 없습니다") }
            items(state.edits, key = { "e-" + it.eventId }) { edit ->
                EditCard(edit, onRemove = { vm.removeEdit(edit.eventId) })
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("삭제·수정 기록을 모두 지울까요?") },
            text = { Text("지우면 일정 추출에 더 이상 반영되지 않습니다.") },
            confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text("지우기") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } },
        )
    }
}

@Composable
fun StatsText(state: FeedbackUiState) {
    val s = state.stats
    val rate = s.mistakeRate?.let { " (${"%.0f".format(it * 100)}%)" }.orEmpty()
    val editRate = s.editRate?.let { " (${"%.0f".format(it * 100)}%)" }.orEmpty()
    Text(
        "자동 등록 ${s.totalCreated}건 · 잘못 등록 ${s.mistakes}건$rate · 수정 ${s.edited}건$editRate",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (s.byField.isNotEmpty()) {
        Text(
            "자주 고친 항목: " + s.byField.entries.sortedByDescending { it.value }.joinToString { "${it.key.label} ${it.value}" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        if (state.useInPrompt) {
            "다음 일정 추출에 삭제 기록 ${state.examplesInUse}건, 수정 기록 ${state.correctionsInUse}건을 참고 예시로 보냅니다."
        } else {
            "일정 추출에 반영하지 않습니다."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeedbackCard(record: DeletionFeedback, onReason: (DeletionReason) -> Unit, onRemove: () -> Unit) {
    val deletedAt = remember(record.deletedAtMillis) {
        SimpleDateFormat("M/d HH:mm", Locale.KOREAN).format(Date(record.deletedAtMillis))
    }
    var menu by remember { mutableStateOf(false) }
    val o = record.origin
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(o.extracted?.title ?: "(제목 없음)", style = MaterialTheme.typography.titleMedium)
            o.extracted?.let { Text(EventFormat.range(it), style = MaterialTheme.typography.bodyMedium) }
            if (o.evidence.isNotBlank()) {
                Text(
                    "\"${o.evidence}\"",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "📞 ${o.counterpart ?: "알 수 없음"} · $deletedAt ${record.source.label}" +
                    (o.confidence?.let { " · 확신도 ${"%.2f".format(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { menu = true }) { Text("이유: ${record.reason.label} ▾") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DeletionReason.entries.forEach { reason ->
                            DropdownMenuItem(text = { Text(reason.label) }, onClick = { onReason(reason); menu = false })
                        }
                    }
                }
                TextButton(onClick = onRemove) { Text("기록 삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun EditCard(edit: EditFeedback, onRemove: () -> Unit) {
    val editedAt = remember(edit.editedAtMillis) {
        SimpleDateFormat("M/d HH:mm", Locale.KOREAN).format(Date(edit.editedAtMillis))
    }
    val o = edit.origin
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(edit.corrected.title, style = MaterialTheme.typography.titleMedium)
            Text("바뀐 항목: ${edit.changes.joinToString { it.label }}", style = MaterialTheme.typography.bodySmall)
            o.extracted?.let { before ->
                Text("등록: ${describe(before)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("수정: ${describe(edit.corrected)}", style = MaterialTheme.typography.bodyMedium)
            if (o.evidence.isNotBlank()) {
                Text("\"${o.evidence}\"", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📞 ${o.counterpart ?: "알 수 없음"} · $editedAt ${edit.source.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) { Text("기록 삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun describe(d: EventDraft): String = buildString {
    append(d.title).append(" · ").append(EventFormat.range(d))
    d.location?.let { append(" · ").append(it) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun EmptyText(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
