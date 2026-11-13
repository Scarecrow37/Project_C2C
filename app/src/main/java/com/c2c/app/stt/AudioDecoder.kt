package com.c2c.app.stt

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.max

/** 녹음 파일(m4a, mp3, amr 등)을 Whisper 입력 형식인 16kHz mono float PCM 으로 디코딩한다. */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000
    private const val TIMEOUT_US = 10_000L
    /** 메모리 보호: 최대 90분까지만 처리한다. */
    private const val MAX_SECONDS = 90 * 60

    fun decode(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            requireNotNull(pfd) { "파일을 열 수 없습니다: $uri" }
            extractor.setDataSource(pfd.fileDescriptor)
        }
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("오디오 트랙이 없습니다")
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                return drain(extractor, codec, inputFormat)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat): FloatArray {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false

        val mono = FloatBuffer()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    if (floatPcm) {
                        val fb = out.asFloatBuffer()
                        while (fb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += fb.get() }
                            mono.add(sum / channels)
                        }
                    } else {
                        val sb = out.asShortBuffer()
                        while (sb.remaining() >= channels) {
                            var sum = 0f
                            repeat(channels) { sum += sb.get() / 32768f }
                            mono.add(sum / channels)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (mono.size > sampleRate.toLong() * MAX_SECONDS) outputDone = true
                }
            }
        }
        return resample(mono.toArray(), sampleRate, TARGET_SAMPLE_RATE)
    }

    /** 구간 평균(다운샘플 시 간단한 저역 통과 효과) + 선형 보간(업샘플) 리샘플러. 음성 인식 용도로 충분하다. */
    internal fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = from.toDouble() / to
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        if (ratio > 1.0) {
            for (i in 0 until outLen) {
                val start = (i * ratio).toInt()
                val end = max(start + 1, ((i + 1) * ratio).toInt()).coerceAtMost(input.size)
                var sum = 0f
                for (j in start until end) sum += input[j]
                out[i] = sum / (end - start)
            }
        } else {
            for (i in 0 until outLen) {
                val pos = i * ratio
                val j = pos.toInt()
                val frac = (pos - j).toFloat()
                val a = input[j]
                val b = if (j + 1 < input.size) input[j + 1] else a
                out[i] = a + (b - a) * frac
            }
        }
        return out
    }

    /** 박싱 없이 늘어나는 float 배열. */
    private class FloatBuffer {
        private var data = FloatArray(16_000 * 60)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
