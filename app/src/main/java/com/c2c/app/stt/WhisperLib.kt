package com.c2c.app.stt

/** whisper_jni.cpp 의 네이티브 함수. */
internal object WhisperLib {
    init {
        System.loadLibrary("c2c_whisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun transcribe(
        contextPtr: Long,
        samples: FloatArray,
        language: String,
        initialPrompt: String,
        threads: Int,
    ): String?
}
