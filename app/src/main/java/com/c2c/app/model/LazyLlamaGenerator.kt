package com.c2c.app.model

import com.c2c.core.TextGenerator
import com.c2c.llm.LlamaEngine
import java.io.File

/**
 * 생성할 때만 모델을 메모리에 올리고 끝나면 바로 내린다.
 * Whisper 와 언어 모델이 동시에 메모리를 오래 차지하지 않게 하기 위함이다 (모델 로딩은 수 초).
 */
class LazyLlamaGenerator(private val modelFile: File) : TextGenerator {
    override fun generate(system: String, user: String, grammar: String, maxTokens: Int): String =
        LlamaEngine.load(modelFile).use { it.generate(system, user, grammar, maxTokens) }
}
