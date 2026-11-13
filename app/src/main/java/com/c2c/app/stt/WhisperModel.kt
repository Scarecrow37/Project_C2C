package com.c2c.app.stt

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Whisper ggml 모델 파일의 위치와 다운로드. */
object WhisperModel {

    /** 한국어 품질과 속도·용량(약 190MB) 사이의 절충. 정확도가 더 필요하면 medium 계열로 바꾼다. */
    const val FILE_NAME = "ggml-small-q5_1.bin"
    private const val URL_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun file(context: Context) = File(context.filesDir, "models/$FILE_NAME")

    fun isReady(context: Context) = file(context).let { it.exists() && it.length() > 0 }

    /** 모델을 내려받는다. 블로킹 호출이다. [onProgress]는 0.0~1.0 (전체 크기를 모르면 -1). */
    fun download(context: Context, onProgress: (Float) -> Unit) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")

        val conn = (URL(URL_BASE + FILE_NAME).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("모델 다운로드 실패: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var read = 0L
            var lastReported = -1
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        } else {
                            onProgress(-1f)
                        }
                    }
                }
            }
            if (total > 0 && read != total) throw IOException("다운로드가 중간에 끊겼습니다 ($read/$total)")
            if (!part.renameTo(target)) throw IOException("모델 파일을 저장하지 못했습니다")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }
}
