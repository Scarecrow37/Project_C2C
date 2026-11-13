package com.c2c.app.model

import android.content.Context
import com.c2c.llm.LlamaEngine
import java.io.File

/** 일정 추출에 쓸 엔진. */
enum class ExtractionEngine(val label: String) {
    CLAUDE("Claude API"),
    LOCAL("기기 내 무료 모델"),
}

/** 기기에서 돌릴 GGUF 언어 모델 하나. */
data class LocalModelSpec(
    val id: String,
    val name: String,
    val description: String,
    /** 다운로드 주소. 직접 가져온 파일이면 null. */
    val url: String?,
    val fileName: String,
    val sizeLabel: String,
    /** 이 모델을 돌리기에 권장하는 기기 메모리 */
    val ramLabel: String,
)

/**
 * 기기 내 모델 목록. 기본 제공 목록 + 직접 입력한 주소 + 파일에서 가져오기.
 *
 * 크기는 Q4_K_M 양자화 기준이다. 모델마다 라이선스가 다르니 배포 전에 확인할 것
 * (Qwen2.5: Apache 2.0 / Qwen 라이선스, HyperCLOVA X SEED: HyperCLOVA X SEED 모델 라이선스).
 */
object LocalModels {

    const val CUSTOM_ID = "custom"
    const val IMPORTED_ID = "imported"

    val PRESETS = listOf(
        LocalModelSpec(
            id = "qwen2.5-3b",
            name = "Qwen2.5 3B Instruct",
            description = "한국어를 포함한 다국어 성능이 좋은 편입니다. 기본 추천.",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeLabel = "약 2.1GB",
            ramLabel = "메모리 6GB 이상",
        ),
        LocalModelSpec(
            id = "qwen2.5-1.5b",
            name = "Qwen2.5 1.5B Instruct",
            description = "가볍고 빠르지만 정확도가 낮습니다. 메모리가 적은 기기용.",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeLabel = "약 1.1GB",
            ramLabel = "메모리 4GB 이상",
        ),
        LocalModelSpec(
            id = "hyperclovax-seed-1.5b",
            name = "HyperCLOVA X SEED 1.5B",
            description = "네이버의 한국어 특화 모델. 공식 GGUF가 없어 커뮤니티 변환본 주소입니다. " +
                "받아지지 않으면 직접 변환한 파일을 '파일에서 가져오기'로 넣으세요.",
            url = "https://huggingface.co/cherryDavid/HyperCLOVAX-SEED-Text-Instruct-1.5B-Q4_K_M-GGUF/resolve/main/" +
                "hyperclovax-seed-text-instruct-1.5b-q4_k_m.gguf",
            fileName = "hyperclovax-seed-text-instruct-1.5b-q4_k_m.gguf",
            sizeLabel = "약 1.1GB",
            ramLabel = "메모리 4GB 이상",
        ),
    )

    const val DEFAULT_ID = "qwen2.5-3b"

    fun dir(context: Context) = File(context.filesDir, "models/llm")

    /** [id]에 해당하는 모델. 직접 입력·가져오기 모델은 [customUrl]로 만든다. */
    fun spec(id: String, customUrl: String = ""): LocalModelSpec = PRESETS.firstOrNull { it.id == id } ?: when (id) {
        IMPORTED_ID -> LocalModelSpec(IMPORTED_ID, "가져온 파일", "기기에서 가져온 GGUF 파일", null, "imported.gguf", "", "")
        else -> LocalModelSpec(CUSTOM_ID, "직접 입력한 주소", customUrl, customUrl.ifBlank { null }, "custom.gguf", "", "")
    }

    fun file(context: Context, spec: LocalModelSpec) = File(dir(context), spec.fileName)

    fun isReady(context: Context, spec: LocalModelSpec): Boolean = file(context, spec).let { it.exists() && LlamaEngine.isGguf(it) }

    fun delete(context: Context, spec: LocalModelSpec) {
        file(context, spec).delete()
    }

    /** 받아 둔 모델들이 차지하는 용량 (바이트). */
    fun usedBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
