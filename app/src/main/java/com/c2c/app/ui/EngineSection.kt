package com.c2c.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.c2c.app.model.ExtractionEngine
import com.c2c.app.model.LocalModels

/** 설정 → 일정 추출 엔진: Claude API 또는 기기 내 무료 모델(Qwen, HyperCLOVA X SEED 등). */
@Composable
fun EngineSection(vm: EngineViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    ChoiceRow(
        selected = state.engine == ExtractionEngine.CLAUDE,
        title = "Claude API",
        subtitle = "정확도가 가장 높습니다. 유료(API 사용료)이며 인터넷이 필요하고, 녹취록 텍스트가 Claude로 전송됩니다.",
        onClick = { vm.setEngine(ExtractionEngine.CLAUDE) },
    )
    ChoiceRow(
        selected = state.engine == ExtractionEngine.LOCAL,
        title = "기기 내 무료 모델",
        subtitle = "무료이고 인터넷 없이 동작하며 녹취록이 기기 밖으로 나가지 않습니다. " +
            "대신 정확도가 낮을 수 있고 분석에 시간이 걸립니다(통화 1건당 수십 초~몇 분).",
        onClick = { vm.setEngine(ExtractionEngine.LOCAL) },
    )
    HorizontalDivider(Modifier.padding(vertical = 4.dp))

    when (state.engine) {
        ExtractionEngine.CLAUDE -> ApiKeyEditor(state.apiKeySaved, vm::saveApiKey)
        ExtractionEngine.LOCAL -> LocalModelPicker(state, vm)
    }
}

@Composable
private fun ApiKeyEditor(saved: Boolean, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Text(if (saved) "✅ API 키 저장됨" else "console.anthropic.com 에서 발급한 키를 입력하세요")
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("sk-ant-...") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSave(key); key = "" }, enabled = key.isNotBlank()) { Text("저장") }
    Text(
        "키는 이 기기에만 저장됩니다. 음성 파일은 기기 밖으로 나가지 않습니다.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LocalModelPicker(state: EngineUiState, vm: EngineViewModel) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importModel)
    }
    Text("사용할 모델", style = MaterialTheme.typography.titleSmall)
    LocalModels.PRESETS.forEach { spec ->
        ChoiceRow(
            selected = state.selected.id == spec.id,
            title = "${spec.name} · ${spec.sizeLabel}",
            subtitle = "${spec.description} (${spec.ramLabel})",
            onClick = { vm.selectLocalModel(spec.id) },
        )
    }
    ChoiceRow(
        selected = state.selected.id == LocalModels.CUSTOM_ID,
        title = "다른 GGUF 모델 주소 입력",
        subtitle = "Hugging Face 등에서 받을 수 있는 .gguf 파일 주소 (Q4_K_M 권장)",
        onClick = { vm.selectLocalModel(LocalModels.CUSTOM_ID) },
    )
    if (state.selected.id == LocalModels.CUSTOM_ID) {
        var url by remember(state.customUrl) { mutableStateOf(state.customUrl) }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("https://…/model.gguf") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        val valid = url.trim().startsWith("https://")
        OutlinedButton(onClick = { vm.setCustomUrl(url) }, enabled = valid && url.trim() != state.customUrl) {
            Text("주소 저장")
        }
    }
    ChoiceRow(
        selected = state.selected.id == LocalModels.IMPORTED_ID,
        title = "파일에서 가져오기",
        subtitle = "직접 변환하거나 미리 받아 둔 .gguf 파일을 기기에서 고릅니다",
        onClick = { vm.selectLocalModel(LocalModels.IMPORTED_ID) },
    )

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    ModelStatus(state, vm, onImport = { importer.launch(arrayOf("*/*")) })
}

@Composable
private fun ModelStatus(state: EngineUiState, vm: EngineViewModel, onImport: () -> Unit) {
    val isImported = state.selected.id == LocalModels.IMPORTED_ID
    when (val m = state.model) {
        is LocalModelState.Ready -> {
            Text("✅ ${state.selected.name} 준비됨 (${formatSize(m.sizeBytes)})")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.deleteModel() }) { Text("모델 삭제") }
                if (isImported) OutlinedButton(onClick = onImport) { Text("다른 파일 가져오기") }
            }
        }
        is LocalModelState.Downloading -> {
            Text("다운로드 중…" + (m.progress?.let { " ${(it * 100).toInt()}%" } ?: ""))
            if (m.progress != null) {
                LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = vm::cancelDownload) { Text("취소") }
        }
        LocalModelState.Importing -> {
            Text("파일을 가져오는 중…")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is LocalModelState.Missing -> {
            m.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }
            when {
                isImported -> Button(onClick = onImport) { Text("GGUF 파일 고르기") }
                state.selected.url != null -> {
                    Text("모델 파일이 필요합니다. Wi-Fi 연결을 권장합니다.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::download) {
                        Text("다운로드" + state.selected.sizeLabel.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty())
                    }
                }
                else -> Text("먼저 모델 주소를 입력하고 저장하세요.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (state.usedBytes > 0) {
        Text(
            "받아 둔 기기 내 모델 전체: ${formatSize(state.usedBytes)}. 쓰지 않는 모델은 선택한 뒤 삭제하면 공간이 늘어납니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1fGB".format(bytes / (1L shl 30).toDouble())
    else -> "%dMB".format(bytes / (1L shl 20))
}
