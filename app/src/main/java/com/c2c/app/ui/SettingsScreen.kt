package com.c2c.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.c2c.app.work.CallProcessingWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun requiredPermissions(): Array<String> = buildList {
    add(CallProcessingWorker.audioPermission())
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun SettingsScreen(
    onOpenFeedback: () -> Unit,
    vm: MainViewModel = viewModel(),
    feedbackVm: FeedbackViewModel = viewModel(),
) {
    val feedback by feedbackVm.state.collectAsState()
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val model by vm.modelState.collectAsState()
    val calendars by vm.calendars.collectAsState()

    fun granted() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var permissionsGranted by remember { mutableStateOf(granted()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsGranted = granted()
        vm.loadCalendars()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::processFile)
    }
    LaunchedEffect(permissionsGranted) { if (permissionsGranted) vm.loadCalendars() }

    run {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("통화 일정 자동 등록", style = MaterialTheme.typography.headlineSmall)
            Text(
                "통화 녹음을 기기에서 텍스트로 바꾸고, Claude가 약속을 찾아 캘린더에 등록합니다. " +
                    "갤럭시의 '통화 자동 녹음'을 켜 두세요.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Section("1. 권한") {
                Text(if (permissionsGranted) "✅ 모든 권한이 허용되었습니다" else "녹음 파일, 캘린더, 알림 권한이 필요합니다")
                if (!permissionsGranted) {
                    Button(onClick = { permissionLauncher.launch(requiredPermissions()) }) { Text("권한 허용") }
                }
            }

            Section("2. Claude API 키") {
                var key by remember { mutableStateOf("") }
                Text(if (state.apiKeySaved) "✅ 저장됨" else "console.anthropic.com 에서 발급한 키를 입력하세요")
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("sk-ant-...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.saveApiKey(key); key = "" }, enabled = key.isNotBlank()) { Text("저장") }
                Text(
                    "키는 이 기기에만 저장되며, 통화 녹취록 텍스트가 Claude API로 전송됩니다. 음성 파일은 기기 밖으로 나가지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("3. 등록할 캘린더") {
                if (calendars.isEmpty()) {
                    Text("캘린더 권한을 허용하면 목록이 나타납니다")
                } else {
                    var expanded by remember { mutableStateOf(false) }
                    val selected = calendars.firstOrNull { it.id == state.calendarId }
                    OutlinedButton(onClick = { expanded = true }) {
                        Text(selected?.let { "${it.name} (${it.account})" } ?: "캘린더 선택")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        calendars.forEach { cal ->
                            DropdownMenuItem(
                                text = { Text("${cal.name} (${cal.account})") },
                                onClick = { vm.selectCalendar(cal.id); expanded = false },
                            )
                        }
                    }
                }
            }

            Section("4. 음성 인식 모델 (Whisper)") {
                when (val m = model) {
                    ModelState.Ready -> Text("✅ 준비됨")
                    is ModelState.Downloading -> {
                        Text("다운로드 중…")
                        if (m.progress != null) {
                            LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is ModelState.Missing -> {
                        Text(if (m.failed) "⚠️ 다운로드에 실패했습니다. 다시 시도해 주세요." else "약 190MB, Wi-Fi 연결을 권장합니다")
                        Button(onClick = vm::downloadModel) { Text("모델 다운로드") }
                    }
                }
            }

            Section("5. 자동 처리") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("통화가 끝나면 자동으로 분석", Modifier.weight(1f))
                    Switch(checked = state.autoEnabled, onCheckedChange = vm::setAutoEnabled)
                }
                Row {
                    OutlinedButton(onClick = vm::scanNow) { Text("지금 스캔") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) { Text("녹음 파일로 테스트") }
                }
            }

            Section("6. 개선 피드백") {
                Text(
                    "자동 등록된 일정을 삭제하거나 고치면 기록됩니다. 이 기록을 참고해 다음 통화부터 같은 실수를 줄이고, 고친 방식을 따릅니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                StatsText(feedback)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("삭제·수정 기록을 일정 추출에 반영", Modifier.weight(1f))
                    Switch(checked = feedback.useInPrompt, onCheckedChange = feedbackVm::setUseInPrompt)
                }
                OutlinedButton(onClick = onOpenFeedback) { Text("피드백 기록 보기") }
            }

            Section("최근 처리 기록") {
                if (state.history.isEmpty()) Text("아직 없습니다")
                val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREAN) }
                state.history.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider()
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(h.fileName, style = MaterialTheme.typography.bodyMedium)
                        Text("${fmt.format(Date(h.timeMillis))} · ${h.status}", style = MaterialTheme.typography.bodySmall)
                        h.eventTitles.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
