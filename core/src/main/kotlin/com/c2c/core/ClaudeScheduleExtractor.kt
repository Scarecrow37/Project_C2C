package com.c2c.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlin.jvm.optionals.getOrNull

/**
 * Claude API로 통화 녹취록에서 일정을 추출한다.
 *
 * - 구조화 출력(JSON 스키마)으로 응답 형식을 강제한다.
 * - 안전 분류기가 요청을 거절하면 서버 측 fallback이 다른 모델로 다시 시도한다.
 *
 * 네트워크 호출을 하므로 메인 스레드에서 부르면 안 된다.
 * API 오류(인증 실패, 429, 5xx 등)는 SDK 예외(com.anthropic.errors.*)로 그대로 던진다.
 */
class ClaudeScheduleExtractor(
    private val client: AnthropicClient,
    private val model: String = DEFAULT_MODEL,
    /** 프롬프트에 함께 보낼 과거 삭제 기록 (개선 피드백). [FeedbackExamples.select] 로 고른 것. */
    private val pastMistakes: List<DeletionFeedback> = emptyList(),
    /** 프롬프트에 함께 보낼 과거 수정 기록 (개선 피드백). [CorrectionExamples.select] 로 고른 것. */
    private val pastCorrections: List<EditFeedback> = emptyList(),
) : ScheduleExtractor {

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        fun create(
            apiKey: String,
            pastMistakes: List<DeletionFeedback> = emptyList(),
            pastCorrections: List<EditFeedback> = emptyList(),
            model: String = DEFAULT_MODEL,
        ) = ClaudeScheduleExtractor(
            AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
            model,
            pastMistakes,
            pastCorrections,
        )
    }

    override fun extract(transcript: String, context: CallContext): ExtractionResult {
        if (transcript.isBlank()) return ExtractionResult.Success(emptyList(), "")

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .addBeta(FALLBACK_BETA)
            .fallbacks(BetaFallbacksParam.ofDefault())
            .system(ExtractionPrompt.SYSTEM)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.MEDIUM)
                    .format(
                        BetaJsonOutputFormat.builder()
                            .schema(
                                BetaJsonOutputFormat.Schema.builder()
                                    .putAllAdditionalProperties(ExtractionPrompt.SCHEMA)
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .addUserMessage(ExtractionPrompt.userMessage(transcript, context, pastMistakes, pastCorrections))
            .build()

        val message = client.beta().messages().create(params)

        when (message.stopReason().getOrNull()) {
            BetaStopReason.REFUSAL ->
                return ExtractionResult.Refused(message.stopDetails().getOrNull()?.category()?.toString())
            BetaStopReason.MAX_TOKENS ->
                return ExtractionResult.Failed("응답이 최대 토큰 수에서 잘렸습니다")
            else -> Unit
        }

        val json = message.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("")
        return ExtractionParser.parse(json)
    }
}
