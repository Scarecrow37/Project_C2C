package com.c2c.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ExtractionPromptTest {
    @Test
    fun `통화 일시와 요일, 녹취록이 포함된다`() {
        val ctx = CallContext(LocalDateTime.of(2026, 11, 13, 14, 5).atZone(ZoneId.of("Asia/Seoul")), "홍길동")
        val msg = ExtractionPrompt.userMessage("  내일 보자  ", ctx)
        assertTrue(msg.contains("2026-11-13 14:05 (금요일)"), msg)
        assertTrue(msg.contains("통화 상대: 홍길동"))
        assertTrue(msg.contains("<transcript>\n내일 보자\n</transcript>"))
    }
}
