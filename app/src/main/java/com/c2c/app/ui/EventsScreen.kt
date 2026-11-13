package com.c2c.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.c2c.core.EventFormat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * 통화에서 자동으로 만든 일정 목록. 수정·원클릭 삭제(되돌리기 가능)를 지원한다.
 * @param openEventId 알림에서 들어왔을 때 바로 수정 창을 열 일정
 */
@Composable
fun EventsScreen(
    snackbar: SnackbarHostState,
    openEventId: Long?,
    onOpenEventConsumed: () -> Unit,
    onGoToSettings: () -> Unit,
    vm: EventsViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EventItem?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            when (msg) {
                is EventsMessage.Text -> snackbar.showSnackbar(msg.text)
                is EventsMessage.Deleted -> {
                    val r = snackbar.showSnackbar(
                        "'${msg.item.draft.title}' 삭제됨",
                        actionLabel = "되돌리기",
                        duration = SnackbarDuration.Long,
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.undoDelete(msg.item)
                }
            }
        }
    }
    // 알림에서 연 일정이 있으면 수정 창을 띄운다
    LaunchedEffect(openEventId, state) {
        val loaded = state as? EventsState.Loaded ?: return@LaunchedEffect
        if (openEventId != null) {
            editing = loaded.items.firstOrNull { it.id == openEventId }
            onOpenEventConsumed()
        }
    }

    when (val s = state) {
        EventsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        EventsState.NoPermission -> EmptyMessage(
            "캘린더 권한이 필요합니다",
            "설정 탭에서 권한을 허용해 주세요.",
            action = "설정으로 이동" to onGoToSettings,
        )
        is EventsState.Loaded -> if (s.items.isEmpty()) {
            EmptyMessage("아직 생성된 일정이 없습니다", "통화에서 약속이 확인되면 여기에 자동으로 추가됩니다.")
        } else {
            val now = LocalDateTime.now()
            val (past, upcoming) = s.items.partition { it.draft.end.isBefore(now) }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (upcoming.isNotEmpty()) {
                    item { SectionHeader("다가오는 일정 ${upcoming.size}") }
                    items(upcoming, key = { it.id }) { EventCard(it, onEdit = { editing = it }, onDelete = { vm.delete(it) }) }
                }
                if (past.isNotEmpty()) {
                    item { SectionHeader("지난 일정 ${past.size}") }
                    items(past.reversed(), key = { it.id }) { EventCard(it, onEdit = { editing = it }, onDelete = { vm.delete(it) }) }
                }
            }
        }
    }

    editing?.let { item ->
        EditEventDialog(
            initial = item.draft,
            onDismiss = { editing = null },
            onSave = { draft -> scope.launch { if (vm.save(item, draft)) editing = null } },
        )
    }
}

@Composable
private fun EventCard(item: EventItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    val created = remember(item.meta.createdAtMillis) {
        SimpleDateFormat("M/d HH:mm", Locale.KOREAN).format(Date(item.meta.createdAtMillis))
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(item.draft.title, style = MaterialTheme.typography.titleMedium)
            Text(EventFormat.range(item.draft), style = MaterialTheme.typography.bodyMedium)
            item.draft.location?.let { Text("📍 $it", style = MaterialTheme.typography.bodyMedium) }
            Text(
                "📞 ${item.meta.counterpart ?: "알 수 없음"} · $created 통화에서 추가",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.meta.evidence.isNotBlank()) {
                Text(
                    "\"${item.meta.evidence}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("수정") }
                TextButton(onClick = onDelete) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun EmptyMessage(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}
