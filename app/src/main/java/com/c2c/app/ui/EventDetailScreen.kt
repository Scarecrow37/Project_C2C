package com.c2c.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.c2c.core.EventFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 일정 상세: 캘린더의 현재 내용 + 어떤 통화에서 만들어졌는지. 하단에 수정·삭제 버튼. */
@Composable
fun EventDetailScreen(
    item: EventItem,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val created = remember(item.meta.createdAtMillis) {
        SimpleDateFormat("yyyy. M. d. HH:mm", Locale.KOREAN).format(Date(item.meta.createdAtMillis))
    }

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Text("← 목록") }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(item.draft.title, style = MaterialTheme.typography.headlineSmall)

            DetailRow("일시", EventFormat.range(item.draft))
            item.draft.location?.let { DetailRow("장소", it) }
            item.draft.description?.let { DetailRow("메모", it) }

            HorizontalDivider()
            Text("통화 정보", style = MaterialTheme.typography.titleSmall)
            DetailRow("통화 상대", item.meta.counterpart ?: "알 수 없음")
            if (item.meta.evidence.isNotBlank()) DetailRow("근거가 된 말", "\"${item.meta.evidence}\"")
            if (item.meta.recordingName.isNotBlank()) DetailRow("녹음 파일", item.meta.recordingName)
            DetailRow("자동 등록", created)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f),
            ) { Text("삭제") }
            Button(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("수정") }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
