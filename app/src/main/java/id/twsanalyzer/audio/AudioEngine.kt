package id.twsanalyzer.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pusat pengambilan audio. Dua sumber, keduanya mengambil sinyal DIGITAL yang diputar HP
 * (bukan mikrofon):
 *  - PLAYBACK  : AudioPlaybackCapture (PCM 16-bit stereo 48 kHz, kualitas penuh)
 *  - VISUALIZER: android.media.audiofx.Visualizer pada output mix (mono, 8-bit, 1024 sampel)
 */
object AudioEngine {
    private const val TAG = "AudioEngine"
    const val PCM_RATE = 48000

    enum class Source { NONE, PLAYBACK, VISUALIZER }

    data class Status(
        val source: Source = Source.NONE,
        val sampleRate: Int = 0,
        /** Audio internal sunyi total padahal ada musik diputar → app pemutar memblokir capture. */
        val captureBlocked: Boolean = false,
        /** Visualizer tidak menerima data padahal ada musik. */
        val noSignal: Boolean = false,
        val error: String? = null,
        val note: String? = null,
    ) {
        val running get() = source != Source.NONE
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Volatile var latest: AnalysisFrame? = null
        private set
    val spectrogramRows = ConcurrentLinkedQueue<FloatArray>()
    @Volatile var config = AnalyzerConfig()
    private val resetFlag = AtomicBoolean(false)

    @Volatile private var pcmRunning = false
    private var pcmThread: Thread? = null
    private var visualizer: Visualizer? = null

    fun requestReset() = resetFlag.set(true)

    fun setError(msg: String?) = _status.update { it.copy(error = msg) }
    fun setNote(msg: String?) = _status.update { it.copy(note = msg) }

    private fun publish(frame: AnalysisFrame, row: FloatArray) {
        latest = frame
        spectrogramRows.add(row)
        while (spectrogramRows.size > 240) spectrogramRows.poll()
    }

    // ------------------------------------------------------------------ PLAYBACK CAPTURE
    /** Dipanggil oleh [PlaybackCaptureService] setelah AudioRecord siap. */
    fun startPcm(record: AudioRecord, context: Context) {
        stopVisualizer()
        stopPcm()
        val am = context.getSystemService(AudioManager::class.java)
        val sr = record.sampleRate
        latest = null
        spectrogramRows.clear()
        _status.update { Status(source = Source.PLAYBACK, sampleRate = sr, note = it.note) }
        pcmRunning = true
        pcmThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val analyzer = Analyzer(sr)
            val frames = 1024
            val buf = ShortArray(frames * 2)
            val l = FloatArray(frames)
            val r = FloatArray(frames)
            var silentSince = -1L
            try {
                record.startRecording()
                while (pcmRunning) {
                    val got = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (got < 0) {
                        _status.update { it.copy(error = "AudioRecord error $got") }
                        break
                    }
                    if (got == 0) continue
                    val fr = got / 2
                    var any = false
                    for (i in 0 until fr) {
                        val a = buf[2 * i]; val b = buf[2 * i + 1]
                        if (a.toInt() != 0 || b.toInt() != 0) any = true
                        l[i] = a / 32768f
                        r[i] = b / 32768f
                    }
                    val (frame, row) = analyzer.process(
                        l, r, fr, fr.toDouble() / sr, true, config, resetFlag.getAndSet(false)
                    )
                    publish(frame, row)

                    // Watchdog: musik aktif tapi data nol semua → capture diblokir app pemutar
                    val now = SystemClock.elapsedRealtime()
                    if (!any && am.isMusicActive) {
                        if (silentSince < 0) silentSince = now
                        if (now - silentSince > 3000 && !_status.value.captureBlocked) {
                            _status.update { it.copy(captureBlocked = true) }
                        }
                    } else if (any) {
                        silentSince = -1
                        if (_status.value.captureBlocked) _status.update { it.copy(captureBlocked = false) }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "PCM loop", t)
                _status.update { it.copy(error = "Gagal membaca audio internal: ${t.message}") }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "pcm-capture").also { it.start() }
    }

    fun stopPcm() {
        val t = pcmThread ?: return
        pcmRunning = false
        runCatching { t.join(600) }
        pcmThread = null
        _status.update { if (it.source == Source.PLAYBACK) Status(note = it.note, error = it.error) else it }
    }

    // ------------------------------------------------------------------ VISUALIZER
    /** Harus dipanggil di main thread. */
    fun startVisualizer(context: Context, note: String? = null): Boolean {
        stopAll(context)
        val am = context.getSystemService(AudioManager::class.java)
        return try {
            val v = Visualizer(0) // 0 = output mix global
            v.enabled = false
            val capSize = Visualizer.getCaptureSizeRange()[1]
            v.captureSize = capSize
            runCatching { v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED }
            val sr = v.samplingRate / 1000
            val rateMilliHz = Visualizer.getMaxCaptureRate()
            val analyzer = Analyzer(sr, fixedFftSize = capSize)
            val buf = FloatArray(capSize)
            var lastNs = 0L
            var silentSince = -1L
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    if (waveform == null) return
                    val nowNs = System.nanoTime()
                    val dur = if (lastNs == 0L) 1000.0 / rateMilliHz
                    else ((nowNs - lastNs) / 1e9).coerceIn(0.01, 0.25)
                    lastNs = nowNs
                    val n = minOf(waveform.size, buf.size)
                    var any = false
                    for (i in 0 until n) {
                        val s = (waveform[i].toInt() and 0xFF) - 128
                        if (s != 0) any = true
                        buf[i] = s / 128f
                    }
                    val (frame, row) = analyzer.process(
                        buf, buf, n, dur, false, config, resetFlag.getAndSet(false)
                    )
                    publish(frame, row)
                    val now = SystemClock.elapsedRealtime()
                    if (!any && am.isMusicActive) {
                        if (silentSince < 0) silentSince = now
                        if (now - silentSince > 4000 && !_status.value.noSignal) _status.update { it.copy(noSignal = true) }
                    } else if (any) {
                        silentSince = -1
                        if (_status.value.noSignal) _status.update { it.copy(noSignal = false) }
                    }
                }

                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, rateMilliHz, true, false)
            v.enabled = true
            visualizer = v
            latest = null
            spectrogramRows.clear()
            _status.value = Status(source = Source.VISUALIZER, sampleRate = sr, note = note)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Visualizer", t)
            _status.value = Status(error = "Mode Visualizer tidak tersedia di perangkat ini: ${t.message}")
            false
        }
    }

    fun stopVisualizer() {
        val v = visualizer ?: return
        visualizer = null
        runCatching { v.enabled = false }
        runCatching { v.release() }
        _status.update { if (it.source == Source.VISUALIZER) Status(note = it.note, error = it.error) else it }
    }

    fun stopAll(context: Context) {
        stopVisualizer()
        context.stopService(Intent(context, PlaybackCaptureService::class.java))
        stopPcm()
        _status.update { Status(note = it.note) }
    }
}
