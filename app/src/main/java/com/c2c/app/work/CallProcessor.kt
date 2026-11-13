package com.c2c.app.work

import android.content.Context
import android.net.Uri
import com.c2c.app.calendar.CalendarRepository
import com.c2c.app.data.AppSettings
import com.c2c.app.data.HistoryEntry
import com.c2c.app.notify.Notifier
import com.c2c.app.stt.AudioDecoder
import com.c2c.app.stt.WhisperTranscriber
import com.c2c.core.CallContext
import com.c2c.core.EventFilter
import com.c2c.core.ExtractionResult
import com.c2c.core.RecordingFileName
import com.c2c.core.ScheduleExtractor
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** 녹음 한 건의 처리 결과. */
sealed interface ProcessOutcome {
    data class Added(val titles: List<String>) : ProcessOutcome
    data object NoEvents : ProcessOutcome
    data class Skipped(val reason: String) : ProcessOutcome
}

/**
 * 녹음 파일 → 음성 인식 → Claude 일정 추출 → 캘린더 등록 → 알림.
 * 네트워크·네이티브 연산을 하므로 백그라운드 스레드에서 호출해야 한다.
 */
class CallProcessor(
    private val context: Context,
    private val transcriber: WhisperTranscriber,
    private val extractor: ScheduleExtractor,
    private val settings: AppSettings = AppSettings(context),
    private val calendar: CalendarRepository = CalendarRepository(context),
    private val notifier: Notifier = Notifier(context),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * @param fallbackTimeSeconds 파일명에서 통화 시각을 못 읽었을 때 쓸 시각 (MediaStore DATE_ADDED 등)
     * @throws Exception Claude API 오류 등. 호출 측에서 재시도 여부를 판단한다.
     */
    fun process(uri: Uri, displayName: String, fallbackTimeSeconds: Long): ProcessOutcome {
        val parsedName = RecordingFileName.parse(displayName)
        val callTime = parsedName.recordedAt?.atZone(zone)
            ?: ZonedDateTime.ofInstant(Instant.ofEpochSecond(fallbackTimeSeconds), zone)
        val callContext = CallContext(callTime, parsedName.counterpart)

        val samples = AudioDecoder.decode(context, uri)
        if (samples.size < AudioDecoder.TARGET_SAMPLE_RATE * 3) return ProcessOutcome.Skipped("녹음이 너무 짧음")

        val transcript = transcriber.transcribe(samples)
        if (transcript.isBlank()) return ProcessOutcome.Skipped("인식된 음성 없음")

        return when (val result = extractor.extract(transcript, callContext)) {
            is ExtractionResult.Refused -> ProcessOutcome.Skipped("모델이 처리를 거절함 (${result.category})")
            is ExtractionResult.Failed -> ProcessOutcome.Skipped(result.reason)
            is ExtractionResult.Success -> {
                val events = EventFilter.select(result.events, callContext, settings.minConfidence)
                if (events.isEmpty()) return ProcessOutcome.NoEvents

                val description = buildString {
                    append("📞 통화에서 자동 등록된 일정\n")
                    parsedName.counterpart?.let { append("통화 상대: ").append(it).append('\n') }
                    if (result.summary.isNotBlank()) append("요약: ").append(result.summary).append('\n')
                }
                val titles = events.map { event ->
                    val desc = buildString {
                        append(description)
                        event.notes?.let { append("메모: ").append(it).append('\n') }
                        if (event.evidence.isNotBlank()) append("근거: \"").append(event.evidence).append("\"\n")
                    }
                    val id = calendar.insert(settings.calendarId, event, zone, desc)
                    notifier.eventAdded(id, event, parsedName.counterpart)
                    event.title
                }
                ProcessOutcome.Added(titles)
            }
        }
    }

    fun record(displayName: String, outcome: ProcessOutcome) {
        val (status, titles) = when (outcome) {
            is ProcessOutcome.Added -> "일정 ${outcome.titles.size}건 등록" to outcome.titles
            ProcessOutcome.NoEvents -> "일정 없음" to emptyList()
            is ProcessOutcome.Skipped -> "건너뜀: ${outcome.reason}" to emptyList()
        }
        settings.addHistory(HistoryEntry(System.currentTimeMillis(), displayName, status, titles))
    }
}
