package com.c2c.app.stt

import java.io.Closeable
import java.io.File

/**
 * 온디바이스 Whisper 음성 인식기. 모델 로딩이 무거우므로 여러 녹음을 처리할 때 인스턴스를 재사용한다.
 * 스레드 안전하지 않다. 한 번에 하나씩 호출할 것.
 */
class WhisperTranscriber(modelFile: File) : Closeable {

    private var ctx: Long = WhisperLib.initContext(modelFile.absolutePath)

    init {
        check(ctx != 0L) { "Whisper 모델을 불러오지 못했습니다: ${modelFile.name}" }
    }

    /** @param samples 16kHz mono float PCM ([-1, 1]) */
    fun transcribe(samples: FloatArray): String {
        check(ctx != 0L) { "이미 닫힌 Transcriber" }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        return WhisperLib.transcribe(ctx, samples, LANGUAGE, INITIAL_PROMPT, threads)
            ?: error("음성 인식에 실패했습니다")
    }

    override fun close() {
        if (ctx != 0L) {
            WhisperLib.freeContext(ctx)
            ctx = 0L
        }
    }

    private companion object {
        const val LANGUAGE = "ko"
        // 한국어 전화 통화라는 힌트를 주면 인식 품질이 좋아진다.
        const val INITIAL_PROMPT = "다음은 한국어 전화 통화 녹음입니다. 약속 날짜와 시간, 장소가 나올 수 있습니다."
    }
}
