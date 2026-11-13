package com.c2c.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.c2c.core.DeletionReason
import com.c2c.core.EventFormat
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 통화에서 자동으로 만든 일정 목록.
 * - 항목을 누르면 상세 화면 (수정·삭제 버튼)
 * - 항목을 오른쪽에서 왼쪽으로 밀면 삭제 (스낵바에서 되돌리기)
 * @param openEventId 알림에서 들어왔을 때 바로 상세를 열 일정
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
    val reasonRequest by vm.reasonRequest.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }

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
                    if (r == SnackbarResult.ActionPerformed) vm.undoDelete(msg.item, msg.feedbackId)
                }
            }
        }
    }
    // 알림에서 연 일정이 있으면 상세 화면을 띄운다
    LaunchedEffect(openEventId) {
        if (openEventId != null) {
            selectedId = openEventId
            editing = false
            onOpenEventConsumed()
        }
    }

    val loaded = state as? EventsState.Loaded
    val selected = selectedId?.let { id -> loaded?.items?.firstOrNull { it.id == id } }
    // 선택한 일정이 사라지면 (삭제됨) 목록으로 돌아간다
    LaunchedEffect(loaded, selectedId) {
        if (loaded != null && selectedId != null && selected == null) {
            selectedId = null
            editing = false
        }
    }

    if (selected != null) {
        EventDetailScreen(
            item = selected,
            onBack = { selectedId = null },
            onEdit = { editing = true },
            onDelete = {
                selectedId = null
                vm.delete(selected)
            },
        )
        if (editing) {
            EditEventDialog(
                initial = selected.draft,
                onDismiss = { editing = false },
                onSave = { draft -> scope.launch { if (vm.save(selected, draft)) editing = false } },
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        reasonRequest?.let { req ->
            DeletionReasonCard(
                req,
                onReason = { vm.setReason(req.feedbackId, it) },
                onDismiss = vm::dismissReason,
            )
        }
        Box(Modifier.weight(1f)) { EventList(state, vm, onGoToSettings, onSelect = { selectedId = it }) }
    }
}

@Composable
private fun EventList(state: EventsState, vm: EventsViewModel, onGoToSettings: () -> Unit, onSelect: (Long) -> Unit) {
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
                item {
                    Text(
                        "항목을 누르면 상세 정보를, 왼쪽으로 밀면 삭제합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (upcoming.isNotEmpty()) {
                    item { SectionHeader("다가오는 일정 ${upcoming.size}") }
                    items(upcoming, key = { it.id }) { item ->
                        SwipeToDeleteCard(item, onClick = { onSelect(item.id) }, onDelete = { vm.delete(item) })
                    }
                }
                if (past.isNotEmpty()) {
                    item { SectionHeader("지난 일정 ${past.size}") }
                    items(past.reversed(), key = { it.id }) { item ->
                        SwipeToDeleteCard(item, onClick = { onSelect(item.id) }, onDelete = { vm.delete(item) })
                    }
                }
            }
        }
    }
}

/** 방금 삭제한 일정의 이유를 묻는 카드. 고르지 않아도 "잘못 등록됨"으로 기록된다. */
@Composable
private fun DeletionReasonCard(req: ReasonRequest, onReason: (DeletionReason) -> Unit, onDismiss: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "'${req.title}'을(를) 왜 삭제했나요?",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
            Text(
                "알려주시면 다음부터 비슷한 일정을 더 정확하게 등록합니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                REASON_CHOICES.forEach { reason ->
                    OutlinedButton(onClick = { onReason(reason) }) { Text(reason.label) }
                }
            }
        }
    }
}

private val REASON_CHOICES = listOf(
    DeletionReason.NOT_AN_EVENT,
    DeletionReason.WRONG_TIME,
    DeletionReason.WRONG_DETAILS,
    DeletionReason.CANCELLED,
    DeletionReason.DUPLICATE,
    DeletionReason.OTHER,
)

/** 오른쪽 → 왼쪽으로 밀면 삭제되는 카드. 왼쪽 → 오른쪽 스와이프는 막는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteCard(item: EventItem, onClick: () -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("삭제", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.titleSmall)
            }
        },
    ) {
        EventCard(item, onClick)
    }
}

@Composable
private fun EventCard(item: EventItem, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.draft.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(EventFormat.range(item.draft), style = MaterialTheme.typography.bodyMedium)
            item.draft.location?.let {
                Text("📍 $it", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "📞 ${item.meta.counterpart ?: "알 수 없음"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}
