package id.twsanalyzer.ui

import android.content.Context
import id.twsanalyzer.audio.AnalyzerConfig
import id.twsanalyzer.audio.AudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppSettings(
    val fftSize: Int = 4096,
    val smoothing: Float = 0.6f,
    val peakHold: Boolean = true,
    val tiltDbPerOct: Float = 0f,
    val minDb: Float = -100f,
    val autoFallback: Boolean = true,
    val keepScreenOn: Boolean = true,
    val preferredSource: AudioEngine.Source = AudioEngine.Source.PLAYBACK,
) {
    fun toAnalyzerConfig() = AnalyzerConfig(fftSize, smoothing, peakHold, tiltDbPerOct)
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow
    val value get() = _flow.value

    init {
        AudioEngine.config = value.toAnalyzerConfig()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            fftSize = prefs.getInt("fft", d.fftSize),
            smoothing = prefs.getFloat("smooth", d.smoothing),
            peakHold = prefs.getBoolean("peak", d.peakHold),
            tiltDbPerOct = prefs.getFloat("tilt", d.tiltDbPerOct),
            minDb = prefs.getFloat("minDb", d.minDb),
            autoFallback = prefs.getBoolean("fallback", d.autoFallback),
            keepScreenOn = prefs.getBoolean("screenOn", d.keepScreenOn),
            preferredSource = runCatching {
                AudioEngine.Source.valueOf(prefs.getString("source", d.preferredSource.name)!!)
            }.getOrDefault(d.preferredSource),
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_flow.value)
        _flow.value = n
        AudioEngine.config = n.toAnalyzerConfig()
        prefs.edit()
            .putInt("fft", n.fftSize)
            .putFloat("smooth", n.smoothing)
            .putBoolean("peak", n.peakHold)
            .putFloat("tilt", n.tiltDbPerOct)
            .putFloat("minDb", n.minDb)
            .putBoolean("fallback", n.autoFallback)
            .putBoolean("screenOn", n.keepScreenOn)
            .putString("source", n.preferredSource.name)
            .apply()
    }
}
