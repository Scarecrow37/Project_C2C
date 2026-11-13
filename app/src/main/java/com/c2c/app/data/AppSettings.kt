package com.c2c.app.data

import android.content.Context
import android.content.SharedPreferences
import com.c2c.app.model.ExtractionEngine
import com.c2c.app.model.LocalModelSpec
import com.c2c.app.model.LocalModels
import com.c2c.core.ConfidenceThreshold
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

/** 처리 기록 한 건 (화면 표시용). */
data class HistoryEntry(
    val timeMillis: Long,
    val fileName: String,
    val status: String,
    val eventTitles: List<String>,
)

/**
 * 앱 설정과 처리 상태를 저장한다 (앱 전용 SharedPreferences).
 *
 * 주의: Claude API 키는 이 기기의 앱 전용 저장소에 평문으로 저장된다. 개인용·프로토타입 용도로만 쓰고,
 * 여러 사용자에게 배포할 때는 API 키를 서버에 두고 앱은 서버를 호출하도록 바꿔야 한다.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("c2c", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_API, v.trim()).apply()

    /** 일정 추출 엔진 (기본: Claude API). */
    var extractionEngine: ExtractionEngine
        get() = prefs.getString(KEY_ENGINE, null)
            ?.let { n -> ExtractionEngine.entries.firstOrNull { it.name == n } } ?: ExtractionEngine.CLAUDE
        set(v) = prefs.edit().putString(KEY_ENGINE, v.name).apply()

    /** 기기 내 모델 선택 ([LocalModels.PRESETS]의 id, 또는 custom / imported). */
    var localModelId: String
        get() = prefs.getString(KEY_LOCAL_MODEL, LocalModels.DEFAULT_ID)!!
        set(v) = prefs.edit().putString(KEY_LOCAL_MODEL, v).apply()

    /** 직접 입력한 GGUF 다운로드 주소. */
    var customModelUrl: String
        get() = prefs.getString(KEY_CUSTOM_URL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_CUSTOM_URL, v.trim()).apply()

    val localModel: LocalModelSpec get() = LocalModels.spec(localModelId, customModelUrl)

    /** 일정을 등록할 캘린더 ID. 미선택이면 -1. */
    var calendarId: Long
        get() = prefs.getLong(KEY_CALENDAR, -1L)
        set(v) = prefs.edit().putLong(KEY_CALENDAR, v).apply()

    var autoProcessEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO, v).apply()

    /** 자동 등록 기준 확신도 (0.1~1.0, 0.05 단위). 이 값 이상인 일정만 등록한다. */
    var minConfidence: Double
        get() = ConfidenceThreshold.snap(
            prefs.getFloat(KEY_MIN_CONF, ConfidenceThreshold.DEFAULT.toFloat()).toDouble(),
        )
        set(v) = prefs.edit().putFloat(KEY_MIN_CONF, ConfidenceThreshold.snap(v).toFloat()).apply()

    /** 이 시각(초) 이후에 추가된 녹음만 처리한다. 처음 켤 때 "지금"으로 정해 과거 녹음 전체를 처리하지 않게 한다. */
    var scanSinceSeconds: Long
        get() = prefs.getLong(KEY_SINCE, 0L)
        set(v) = prefs.edit().putLong(KEY_SINCE, v).apply()

    fun isProcessed(recordingKey: String) = recordingKey in prefs.getStringSet(KEY_PROCESSED, emptySet())!!

    fun markProcessed(recordingKey: String) {
        val current = prefs.getStringSet(KEY_PROCESSED, emptySet())!!.toMutableList()
        current += recordingKey
        // 오래된 항목은 정리 (scanSince 이후 것만 의미가 있으므로 개수만 제한)
        prefs.edit().putStringSet(KEY_PROCESSED, current.takeLast(MAX_PROCESSED).toSet()).apply()
    }

    fun history(): List<HistoryEntry> {
        val arr = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val titles = o.optJSONArray("events") ?: JSONArray()
            HistoryEntry(
                timeMillis = o.getLong("time"),
                fileName = o.getString("file"),
                status = o.getString("status"),
                eventTitles = (0 until titles.length()).map { titles.getString(it) },
            )
        }
    }

    @Synchronized
    fun addHistory(entry: HistoryEntry) {
        val list = listOf(entry) + history()
        val arr = JSONArray()
        list.take(MAX_HISTORY).forEach {
            arr.put(
                JSONObject()
                    .put("time", it.timeMillis)
                    .put("file", it.fileName)
                    .put("status", it.status)
                    .put("events", JSONArray(it.eventTitles)),
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** 설정이 바뀔 때마다 값을 내보낸다 (UI 갱신용). */
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_ENGINE = "extraction_engine"
        const val KEY_LOCAL_MODEL = "local_model"
        const val KEY_CUSTOM_URL = "custom_model_url"
        const val KEY_CALENDAR = "calendar_id"
        const val KEY_AUTO = "auto_process"
        const val KEY_MIN_CONF = "min_confidence"
        const val KEY_SINCE = "scan_since"
        const val KEY_PROCESSED = "processed"
        const val KEY_HISTORY = "history"
        const val MAX_PROCESSED = 500
        const val MAX_HISTORY = 30
    }
}
