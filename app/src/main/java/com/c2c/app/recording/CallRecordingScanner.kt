package com.c2c.app.recording

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** MediaStore 에 등록된 통화 녹음 파일 하나. */
data class CallRecording(
    val uri: Uri,
    val displayName: String,
    /** MediaStore DATE_ADDED (초) */
    val dateAddedSeconds: Long,
    val durationMs: Long,
) {
    /** 중복 처리 방지용 키. */
    val key: String get() = uri.toString()
}

/**
 * 기기의 통화 녹음 파일을 찾는다.
 *
 * 삼성 갤럭시 기본 통화 녹음은 `Recordings/Call/`(One UI 4 이상) 또는 `Call/`(구형)에 저장된다.
 * 다른 제조사 녹음 앱도 경로에 "Call"이 들어가면 대부분 잡힌다.
 */
class CallRecordingScanner(private val context: Context) {

    fun findNewRecordings(sinceSeconds: Long): List<CallRecording> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = buildString {
            append("${MediaStore.Audio.Media.DATE_ADDED} > ?")
            append(" AND ${MediaStore.Audio.Media.IS_PENDING} = 0") // 아직 녹음 중인 파일 제외
            append(" AND (")
            append(PATH_PATTERNS.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" })
            append(")")
        }
        val args = arrayOf(sinceSeconds.toString()) + PATH_PATTERNS

        val result = mutableListOf<CallRecording>()
        context.contentResolver.query(
            collection, projection, selection, args, "${MediaStore.Audio.Media.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val duration = c.getLong(durCol)
                if (duration in 1 until MIN_DURATION_MS) continue // 너무 짧은 통화는 건너뜀
                result += CallRecording(
                    uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    displayName = c.getString(nameCol),
                    dateAddedSeconds = c.getLong(dateCol),
                    durationMs = duration,
                )
            }
        }
        return result
    }

    private companion object {
        val PATH_PATTERNS = arrayOf("Recordings/Call/%", "Call/%", "%/Call/%", "%CallRecord%")
        const val MIN_DURATION_MS = 10_000L
    }
}
