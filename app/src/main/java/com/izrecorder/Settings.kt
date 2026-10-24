package com.izrecorder

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Модель распознавания whisper.cpp. */
enum class WhisperModel(val fileName: String, val title: String, val sizeMb: Int) {
    BASE("ggml-base-q5_1.bin", "Base — быстро, попроще", 60),
    SMALL("ggml-small-q5_1.bin", "Small — баланс (рекомендуется)", 190),
    TURBO("ggml-large-v3-turbo-q5_0.bin", "Large-v3 Turbo — точнее всего, медленно", 550);

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

data class AppSettings(
    val volumeTriggerEnabled: Boolean = true,
    val pressCount: Int = 5,
    val pressGapMs: Int = 600,
    /** 0 — без ограничения. */
    val maxDurationMin: Int = 30,
    val startVibrationMs: Int = 3000,
    val stopVibrationMs: Int = 600,
    val inboxRetentionDays: Int = 30,
    val model: WhisperModel = WhisperModel.SMALL,
    /** "auto", "en" или "ru". */
    val language: String = "auto",
)

object Settings {
    private const val PREFS = "settings"
    private lateinit var prefs: SharedPreferences
    private val _flow = MutableStateFlow(AppSettings())
    val flow: StateFlow<AppSettings> get() = _flow
    val current: AppSettings get() = _flow.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _flow.value = load()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            volumeTriggerEnabled = prefs.getBoolean("volumeTriggerEnabled", d.volumeTriggerEnabled),
            pressCount = prefs.getInt("pressCount", d.pressCount),
            pressGapMs = prefs.getInt("pressGapMs", d.pressGapMs),
            maxDurationMin = prefs.getInt("maxDurationMin", d.maxDurationMin),
            startVibrationMs = prefs.getInt("startVibrationMs", d.startVibrationMs),
            stopVibrationMs = prefs.getInt("stopVibrationMs", d.stopVibrationMs),
            inboxRetentionDays = prefs.getInt("inboxRetentionDays", d.inboxRetentionDays),
            model = runCatching { WhisperModel.valueOf(prefs.getString("model", d.model.name)!!) }
                .getOrDefault(d.model),
            language = prefs.getString("language", d.language) ?: d.language,
        )
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_flow.value)
        prefs.edit()
            .putBoolean("volumeTriggerEnabled", s.volumeTriggerEnabled)
            .putInt("pressCount", s.pressCount)
            .putInt("pressGapMs", s.pressGapMs)
            .putInt("maxDurationMin", s.maxDurationMin)
            .putInt("startVibrationMs", s.startVibrationMs)
            .putInt("stopVibrationMs", s.stopVibrationMs)
            .putInt("inboxRetentionDays", s.inboxRetentionDays)
            .putString("model", s.model.name)
            .putString("language", s.language)
            .apply()
        _flow.value = s
    }
}
