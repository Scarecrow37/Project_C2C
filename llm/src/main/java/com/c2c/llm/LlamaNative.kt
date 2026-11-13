package com.c2c.llm

/** llama_jni.cpp 의 네이티브 함수. */
internal object LlamaNative {
    init {
        System.loadLibrary("c2c_llama")
    }

    /** @throws IllegalStateException 모델을 불러오지 못했을 때 */
    external fun load(modelPath: String, contextLength: Int, threads: Int): Long
    external fun free(handle: Long)
    external fun cancel(handle: Long)

    /** @return UTF-8 바이트. @throws IllegalStateException 프롬프트가 너무 길거나 문법 오류 등 */
    external fun generate(handle: Long, system: String, user: String, grammar: String, maxTokens: Int): ByteArray
}
