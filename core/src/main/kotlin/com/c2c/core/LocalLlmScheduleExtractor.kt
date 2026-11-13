package com.c2c.core

/**
 * 기기 안에서 돌아가는 언어 모델 (예: llama.cpp + Qwen / HyperCLOVA X SEED GGUF).
 * 구현은 Android 쪽(:llm 모듈)에 있고, core 는 이 인터페이스만 안다.
 */
fun interface TextGenerator {
    /**
     * @param grammar GBNF 문법. 출력이 이 문법을 벗어나지 않도록 강제한다.
     * @return 모델이 생성한 텍스트
     */
    fun generate(system: String, user: String, grammar: String, maxTokens: Int): String
}

/**
 * 무료 온디바이스 모델로 일정을 추출한다. Claude 버전과 같은 프롬프트·JSON 형식을 쓰되,
 * 작은 모델에 맞게 다음을 조정한다.
 * - GBNF 문법으로 JSON 출력 형식을 강제한다 (작은 모델이 형식을 자주 깨뜨리므로).
 * - 녹취록이 길면 앞·뒤를 남기고 가운데를 줄인다 (컨텍스트 길이 제한).
 * - 피드백 예시 수를 줄인다.
 */
class LocalLlmScheduleExtractor(
    private val generator: TextGenerator,
    /** 모델 이름 (피드백 분석용). 예: "qwen2.5-3b" */
    modelName: String = "local",
    pastMistakes: List<DeletionFeedback> = emptyList(),
    pastCorrections: List<EditFeedback> = emptyList(),
    private val maxTranscriptChars: Int = DEFAULT_MAX_TRANSCRIPT_CHARS,
    private val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) : ScheduleExtractor {

    override val name: String = if (modelName.startsWith("local")) modelName else "local:$modelName"

    private val mistakes = pastMistakes.take(MAX_EXAMPLES)
    private val corrections = pastCorrections.take(MAX_EXAMPLES)

    override fun extract(transcript: String, context: CallContext): ExtractionResult {
        if (transcript.isBlank()) return ExtractionResult.Success(emptyList(), "")
        val user = ExtractionPrompt.userMessage(
            TranscriptTrimmer.trim(transcript, maxTranscriptChars),
            context,
            mistakes,
            corrections,
        )
        val output = try {
            generator.generate(SYSTEM, user, ExtractionGrammar.GBNF, maxOutputTokens)
        } catch (e: Exception) {
            return ExtractionResult.Failed("로컬 모델 실행 실패: ${e.message}")
        }
        val json = JsonText.firstObject(output) ?: return ExtractionResult.Failed("로컬 모델이 JSON을 만들지 못했습니다")
        return ExtractionParser.parse(json)
    }

    companion object {
        /** 한국어 약 4,000자 ≈ 3,000 토큰 내외. 프롬프트를 합쳐 컨텍스트 8K 안에 들어가게 한다. */
        const val DEFAULT_MAX_TRANSCRIPT_CHARS = 4_000
        const val DEFAULT_MAX_OUTPUT_TOKENS = 1_024
        const val MAX_EXAMPLES = 3

        /** 작은 모델용: 기본 지침 + 출력 형식을 글로도 설명한다 (문법만으로는 의미를 모르므로). */
        val SYSTEM: String = ExtractionPrompt.SYSTEM + "\n\n" + """
            반드시 아래 형식의 JSON 하나로만 답하세요. 설명이나 다른 글은 쓰지 마세요.
            {"summary": "통화 요약", "events": [{"title": "제목", "start_date": "YYYY-MM-DD", "start_time": "HH:MM 또는 null",
              "end_date": "YYYY-MM-DD 또는 null", "end_time": "HH:MM 또는 null", "location": "장소 또는 null",
              "notes": "메모 또는 null", "confidence": 0.0~1.0, "evidence": "근거 발화"}]}
            일정이 없으면 "events": [] 로 답하세요.
        """.trimIndent()
    }
}

/** 응답 JSON 형식을 강제하는 llama.cpp GBNF 문법. [ExtractionPrompt.SCHEMA] 와 같은 구조다. */
object ExtractionGrammar {
    val GBNF: String = """
        root ::= "{" ws "\"summary\"" ws ":" ws string ws "," ws "\"events\"" ws ":" ws events ws "}"
        events ::= "[" ws ( event ( ws "," ws event )* )? ws "]"
        event ::= "{" ws "\"title\"" ws ":" ws string ws "," ws "\"start_date\"" ws ":" ws date ws "," ws "\"start_time\"" ws ":" ws time-or-null ws "," ws "\"end_date\"" ws ":" ws date-or-null ws "," ws "\"end_time\"" ws ":" ws time-or-null ws "," ws "\"location\"" ws ":" ws string-or-null ws "," ws "\"notes\"" ws ":" ws string-or-null ws "," ws "\"confidence\"" ws ":" ws confidence ws "," ws "\"evidence\"" ws ":" ws string ws "}"
        date ::= "\"" [0-9] [0-9] [0-9] [0-9] "-" [0-1] [0-9] "-" [0-3] [0-9] "\""
        time ::= "\"" [0-2] [0-9] ":" [0-5] [0-9] "\""
        date-or-null ::= date | "null"
        time-or-null ::= time | "null"
        string-or-null ::= string | "null"
        confidence ::= "1" ( "." "0"+ )? | "0" ( "." [0-9] [0-9]? )?
        string ::= "\"" char* "\""
        char ::= [^"\\\x7F\x00-\x1F] | "\\" ( ["\\/bfnrt] | "u" hex hex hex hex )
        hex ::= [0-9a-fA-F]
        ws ::= [ \t\n]{0,20}
    """.trimIndent()
}

/** 모델 출력에서 첫 번째 완전한 JSON 객체를 꺼낸다 (코드 블록이나 앞뒤 설명이 섞여도). */
object JsonText {
    fun firstObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }
}

/** 긴 녹취록을 컨텍스트에 맞게 줄인다. 약속은 대개 통화 끝에 확정되므로 뒷부분을 더 많이 남긴다. */
object TranscriptTrimmer {
    const val GAP_MARKER = "\n…(중략)…\n"

    fun trim(transcript: String, maxChars: Int): String {
        val t = transcript.trim()
        if (t.length <= maxChars) return t
        val budget = (maxChars - GAP_MARKER.length).coerceAtLeast(0)
        val head = budget / 3
        val tail = budget - head
        return t.take(head) + GAP_MARKER + t.takeLast(tail)
    }
}
