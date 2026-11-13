package com.c2c.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RecordingFileNameTest {

    private val at = LocalDateTime.of(2024, 11, 13, 15, 30, 12)

    @Test
    fun `원UI 한국어 파일명`() {
        assertEquals(RecordingFileName("홍길동", at), RecordingFileName.parse("통화 녹음 홍길동_241113_153012.m4a"))
    }

    @Test
    fun `영문 파일명과 전화번호`() {
        assertEquals(
            RecordingFileName("010-1234-5678", at),
            RecordingFileName.parse("Call recording 010-1234-5678_241113_153012.m4a"),
        )
    }

    @Test
    fun `이름에 밑줄이 있는 경우`() {
        assertEquals(RecordingFileName("김_대리", at), RecordingFileName.parse("통화 녹음 김_대리_241113_153012.m4a"))
    }

    @Test
    fun `구형 기기 파일명`() {
        assertEquals(RecordingFileName("홍길동", at), RecordingFileName.parse("홍길동_20241113153012.m4a"))
    }

    @Test
    fun `알 수 없는 형식`() {
        assertEquals(RecordingFileName("memo", null), RecordingFileName.parse("memo.m4a"))
    }
}
