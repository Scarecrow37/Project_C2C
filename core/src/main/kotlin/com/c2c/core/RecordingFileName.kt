package com.c2c.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 삼성 갤럭시 기본 통화 녹음 파일명에서 통화 상대와 통화 시각을 추출한다.
 *
 * 지원 예시:
 * - `통화 녹음 홍길동_241113_153012.m4a`
 * - `Call recording 010-1234-5678_241113_153012.m4a`
 * - `홍길동_20241113153012.m4a` (구형 기기)
 */
data class RecordingFileName(val counterpart: String?, val recordedAt: LocalDateTime?) {

    companion object {
        private val PREFIX = Regex("""^(통화\s*녹음|Call\s*recording)\s*""", RegexOption.IGNORE_CASE)
        private val EXT = Regex("""\.(m4a|mp3|amr|3gp|aac|wav|ogg)$""", RegexOption.IGNORE_CASE)

        // <이름>_<yyMMdd>_<HHmmss>  또는  <이름>_<yyyyMMdd>_<HHmmss>  또는  <이름>_<yyyyMMddHHmmss>
        private val PATTERNS = listOf(
            Regex("""^(.*)_(\d{6})_(\d{6})$""") to "yyMMddHHmmss",
            Regex("""^(.*)_(\d{8})_(\d{6})$""") to "yyyyMMddHHmmss",
            Regex("""^(.*)_(\d{8})(\d{6})$""") to "yyyyMMddHHmmss",
        )

        fun parse(fileName: String): RecordingFileName {
            val base = fileName.replace(EXT, "").replace(PREFIX, "").trim()
            for ((regex, pattern) in PATTERNS) {
                val m = regex.matchEntire(base) ?: continue
                val (name, date, time) = m.destructured
                val at = try {
                    LocalDateTime.parse(date + time, DateTimeFormatter.ofPattern(pattern))
                } catch (_: DateTimeParseException) {
                    null
                }
                return RecordingFileName(name.trim().ifEmpty { null }, at)
            }
            return RecordingFileName(base.ifEmpty { null }, null)
        }
    }
}
