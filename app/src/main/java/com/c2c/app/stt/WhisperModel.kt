package com.c2c.app.stt

import android.content.Context
import java.io.File

/** Whisper ggml 모델 파일의 위치. 다운로드는 ModelDownloader 가 한다. */
object WhisperModel {

    /** 한국어 품질과 속도·용량(약 190MB) 사이의 절충. 정확도가 더 필요하면 medium 계열로 바꾼다. */
    const val FILE_NAME = "ggml-small-q5_1.bin"
    const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$FILE_NAME"

    fun file(context: Context) = File(context.filesDir, "models/$FILE_NAME")

    fun isReady(context: Context) = file(context).let { it.exists() && it.length() > 0 }
}
