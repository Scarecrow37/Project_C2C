package com.c2c.llm

import java.io.Closeable
import java.io.File

/**
 * 기기 안에서 GGUF 언어 모델(Qwen, HyperCLOVA X SEED 등)을 실행한다.
 * 모델 로딩에 수 초가 걸리고 수백 MB~수 GB 메모리를 쓰므로, 쓸 때 열고 다 쓰면 바로 닫는다.
 * 한 번에 하나의 생성만 실행한다.
 */
class LlamaEngine private constructor(private var handle: Long) : Closeable {

    /**
     * 시스템/사용자 메시지에 모델의 대화 템플릿을 적용해 답을 생성한다.
     * @param grammar GBNF 문법 (빈 문자열이면 제한 없음)
     */
    @Synchronized
    fun generate(system: String, user: String, grammar: String, maxTokens: Int): String {
        check(handle != 0L) { "이미 닫힌 모델" }
        return String(LlamaNative.generate(handle, system, user, grammar, maxTokens), Charsets.UTF_8)
    }

    /** 진행 중인 생성을 멈춘다 (다른 스레드에서 호출). 그때까지 만든 텍스트가 반환된다. */
    fun cancel() {
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0L
        }
    }

    companion object {
        const val DEFAULT_CONTEXT = 8192

        fun load(
            modelFile: File,
            contextLength: Int = DEFAULT_CONTEXT,
            threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
        ): LlamaEngine {
            require(modelFile.exists()) { "모델 파일이 없습니다: ${modelFile.name}" }
            return LlamaEngine(LlamaNative.load(modelFile.absolutePath, contextLength, threads))
        }

        /** GGUF 파일인지 앞 4바이트("GGUF")로 확인한다. */
        fun isGguf(file: File): Boolean = runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            }
        }.getOrDefault(false)
    }
}
