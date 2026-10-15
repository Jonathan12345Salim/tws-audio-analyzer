package id.twsanalyzer.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class AnalyzerConfig(
    val fftSize: Int = 4096,
    val smoothing: Float = 0.6f,
    val peakHold: Boolean = true,
    val tiltDbPerOct: Float = 0f,
)

object Bands {
    val names = arrayOf("Sub-bass", "Bass", "Low-mid", "Mid", "Upper-mid", "Presence", "Brilliance")
    val ranges = arrayOf("20–60", "60–250", "250–500", "500–2k", "2k–4k", "4k–6k", "6k–20k")
    val edges = floatArrayOf(20f, 60f, 250f, 500f, 2000f, 4000f, 6000f, 20000f)
}

/** Satu snapshot hasil analisis. Semua level dalam dBFS kecuali disebut lain. */
class AnalysisFrame(
    val sampleRate: Int,
    val fftSize: Int,
    val stereo: Boolean,
    val freqs: FloatArray,
    val spectrumDb: FloatArray,
    val peakDb: FloatArray,
    val waveL: FloatArray,
    val waveR: FloatArray,
    val scopeX: FloatArray,
    val scopeY: FloatArray,
    val rmsDbL: Float, val rmsDbR: Float,
    val peakDbL: Float, val peakDbR: Float,
    val peakHoldDbL: Float, val peakHoldDbR: Float,
    val clipL: Boolean, val clipR: Boolean,
    val clipCount: Long,
    val maxPeakDb: Float,
    val lufsM: Float, val lufsS: Float, val lufsI: Float,
    val dominantHz: Float, val dominantDb: Float,
    val centroidHz: Float,
    val bandwidthHz: Float,
    val bandsDb: FloatArray,
    val bandsPct: FloatArray,
    val correlation: Float,
    val widthPct: Float,
    val balanceDb: Float,
    val crestDb: Float,
    val silent: Boolean,
)

/**
 * Analyzer realtime. Dipanggil dari SATU thread sumber audio (tidak thread-safe).
 * @param fixedFftSize jika diisi, abaikan pengaturan FFT (dipakai mode Visualizer = 1024).
 */
class Analyzer(val sampleRate: Int, private val fixedFftSize: Int? = null) {
    companion object {
        const val POINTS = 256
        const val MAX_FFT = 16384
        const val WAVE = 1024
        const val SCOPE = 1024
        const val FLOOR_DB = -160f
        private const val MASK = MAX_FFT - 1

        fun db10(x: Double): Float = if (x <= 1e-16) FLOOR_DB else (10.0 * log10(x)).toFloat()
        fun db10(x: Float): Float = db10(x.toDouble())
        fun db20(x: Float): Float = if (x <= 1e-8f) FLOOR_DB else (20.0 * log10(x.toDouble())).toFloat()
    }

    val fMax = min(20000f, sampleRate / 2f)
    val freqs = FloatArray(POINTS) { 20f * (fMax / 20f).pow(it / (POINTS - 1f)) }

    private val ringL = FloatArray(MAX_FFT)
    private val ringR = FloatArray(MAX_FFT)
    private var writePos = 0
    private var filled = 0

    private var fftSize = 0
    private lateinit var fft: Fft
    private lateinit var window: FloatArray
    private lateinit var re: FloatArray
    private lateinit var im: FloatArray
    private lateinit var power: FloatArray
    private lateinit var avgPower: FloatArray
    private lateinit var ptLo: IntArray
    private lateinit var ptHi: IntArray
    private lateinit var ptPos: FloatArray
    private var binHz = 0f
    private var ampNorm = 0f   // |X|^2 -> amplitudo sinus^2 (sinus full-scale = 0 dB)
    private var powNorm = 0f   // sum |X|^2 -> mean square (Parseval, jendela Hann)

    private val smooth = FloatArray(POINTS) { FLOOR_DB }
    private val peak = FloatArray(POINTS) { FLOOR_DB }
    private val peakAge = FloatArray(POINTS)

    private var msL = 0.0
    private var msR = 0.0
    private var peakDispL = FLOOR_DB
    private var peakDispR = FLOOR_DB
    private var holdL = FLOOR_DB
    private var holdR = FLOOR_DB
    private var holdTimerL = 0f
    private var holdTimerR = 0f
    private var clipTimerL = 0f
    private var clipTimerR = 0f
    private var clipCount = 0L
    private var maxPeakDb = FLOOR_DB
    private var eLL = 0.0
    private var eRR = 0.0
    private var eLR = 0.0
    private val loudness = LoudnessMeter(sampleRate)

    private fun setupFft(n: Int) {
        fftSize = n
        fft = Fft(n)
        window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }
        re = FloatArray(n)
        im = FloatArray(n)
        power = FloatArray(n / 2 + 1)
        avgPower = FloatArray(n / 2 + 1)
        binHz = sampleRate.toFloat() / n
        val a = 4f / n
        ampNorm = a * a
        powNorm = 16f / (3f * n.toFloat() * n.toFloat())
        val half = n / 2
        ptLo = IntArray(POINTS)
        ptHi = IntArray(POINTS)
        ptPos = FloatArray(POINTS)
        for (i in 0 until POINTS) {
            val lo = if (i == 0) freqs[0] else sqrt(freqs[i - 1] * freqs[i])
            val hi = if (i == POINTS - 1) freqs[i] else sqrt(freqs[i] * freqs[i + 1])
            ptLo[i] = ceil(lo / binHz).toInt().coerceIn(1, half)
            ptHi[i] = floor(hi / binHz).toInt().coerceIn(0, half)
            ptPos[i] = freqs[i] / binHz
        }
        smooth.fill(FLOOR_DB)
        peak.fill(FLOOR_DB)
    }

    fun process(
        l: FloatArray, r: FloatArray, n: Int, durationSec: Double,
        stereo: Boolean, cfg: AnalyzerConfig, reset: Boolean,
    ): Pair<AnalysisFrame, FloatArray> {
        val size = fixedFftSize ?: cfg.fftSize.coerceIn(512, MAX_FFT)
        if (size != fftSize) setupFft(size)
        if (reset) {
            loudness.resetIntegrated()
            clipCount = 0
            maxPeakDb = FLOOR_DB
        }
        val dt = durationSec.toFloat().coerceAtLeast(1e-4f)

        // ---- Sampel: ring buffer + meter ----
        var pkL = 0f; var pkR = 0f
        var sLL = 0.0; var sRR = 0.0; var sLR = 0.0
        var clipsL = 0; var clipsR = 0
        for (i in 0 until n) {
            val a = l[i]; val b = r[i]
            ringL[writePos] = a; ringR[writePos] = b
            writePos = (writePos + 1) and MASK
            val aa = abs(a); val bb = abs(b)
            if (aa > pkL) pkL = aa
            if (bb > pkR) pkR = bb
            if (aa >= 0.9999f) clipsL++
            if (bb >= 0.9999f) clipsR++
            sLL += a * a; sRR += b * b; sLR += a * b
        }
        filled = min(MAX_FFT, filled + n)
        val nn = n.coerceAtLeast(1)

        val aRms = 1.0 - exp(-dt / 0.3)
        msL += (sLL / nn - msL) * aRms
        msR += (sRR / nn - msR) * aRms
        val rmsDbL = db10(msL)
        val rmsDbR = db10(msR)
        val pkDbL = db20(pkL)
        val pkDbR = db20(pkR)
        peakDispL = max(pkDbL, peakDispL - 20f * dt)
        peakDispR = max(pkDbR, peakDispR - 20f * dt)
        if (pkDbL >= holdL) { holdL = pkDbL; holdTimerL = 0f } else {
            holdTimerL += dt; if (holdTimerL > 2f) { holdL = peakDispL; holdTimerL = 0f }
        }
        if (pkDbR >= holdR) { holdR = pkDbR; holdTimerR = 0f } else {
            holdTimerR += dt; if (holdTimerR > 2f) { holdR = peakDispR; holdTimerR = 0f }
        }
        maxPeakDb = max(maxPeakDb, max(pkDbL, pkDbR))
        if (clipsL > 0) { clipTimerL = 2f; clipCount += clipsL } else clipTimerL = max(0f, clipTimerL - dt)
        if (clipsR > 0) { clipTimerR = 2f; clipCount += clipsR } else clipTimerR = max(0f, clipTimerR - dt)

        eLL += (sLL / nn - eLL) * aRms
        eRR += (sRR / nn - eRR) * aRms
        eLR += (sLR / nn - eLR) * aRms
        val denom = sqrt(eLL * eRR)
        val correlation = if (!stereo) 1f else if (denom > 1e-12) (eLR / denom).toFloat().coerceIn(-1f, 1f) else Float.NaN
        val eM = (eLL + eRR + 2 * eLR) / 4
        val eS = (eLL + eRR - 2 * eLR) / 4
        val widthPct = if (!stereo) 0f else if (eM + eS > 1e-12) (eS / (eM + eS) * 100).toFloat() else Float.NaN
        val balanceDb = rmsDbL - rmsDbR

        loudness.process(l, r, n, durationSec)

        // ---- FFT: rata-rata daya L & R (satu FFT kompleks: L = real, R = imajiner) ----
        // Tidak memakai mix mono, jadi konten yang fasenya berlawanan tidak hilang.
        val start = (writePos - size) and MASK
        for (i in 0 until size) {
            val idx = (start + i) and MASK
            re[i] = ringL[idx] * window[i]
            im[i] = ringR[idx] * window[i]
        }
        fft.transform(re, im)
        val half = size / 2
        val aAvg = (1.0 - exp(-dt / 1.5)).toFloat()
        for (k in 0..half) {
            val m = if (k == 0) 0 else size - k
            val p = (re[k] * re[k] + im[k] * im[k] + re[m] * re[m] + im[m] * im[m]) * 0.25f
            power[k] = p
            avgPower[k] += (p - avgPower[k]) * aAvg
        }

        // ---- Titik tampilan (log 20 Hz – 20 kHz) ----
        val row = FloatArray(POINTS)
        val outSpec = FloatArray(POINTS)
        val outPeak = FloatArray(POINTS)
        val rel = if (cfg.smoothing <= 0f) 0f else cfg.smoothing.pow(dt * 47f)
        val att = 1f - if (cfg.smoothing <= 0f) 0f else (cfg.smoothing * 0.4f).pow(dt * 47f)
        for (i in 0 until POINTS) {
            val p: Float = if (ptHi[i] >= ptLo[i]) {
                var m = 0f
                for (k in ptLo[i]..ptHi[i]) if (power[k] > m) m = power[k]
                m
            } else {
                val pos = ptPos[i]
                val k0 = floor(pos).toInt().coerceIn(0, half - 1)
                val fr = (pos - k0).coerceIn(0f, 1f)
                power[k0] * (1f - fr) + power[k0 + 1] * fr
            }
            var d = db10(p * ampNorm)
            if (cfg.tiltDbPerOct != 0f) d += cfg.tiltDbPerOct * log2(freqs[i] / 1000f)
            row[i] = d
            val s = smooth[i]
            smooth[i] = if (d > s) s + (d - s) * att else d + (s - d) * rel
            outSpec[i] = smooth[i]
            if (!cfg.peakHold) {
                peak[i] = smooth[i]
            } else if (smooth[i] >= peak[i]) {
                peak[i] = smooth[i]; peakAge[i] = 0f
            } else {
                peakAge[i] += dt
                if (peakAge[i] > 1f) peak[i] = max(smooth[i], peak[i] - 20f * dt)
            }
            outPeak[i] = peak[i]
        }

        // ---- Band, centroid, frekuensi dominan ----
        val kMin = ceil(20f / binHz).toInt().coerceIn(1, half)
        val kMax = floor(min(20000f, fMax) / binHz).toInt().coerceIn(kMin, half)
        var total = 0.0; var weighted = 0.0
        var bestK = kMin; var bestP = -1f
        for (k in kMin..kMax) {
            val p = power[k]
            total += p
            weighted += p * k * binHz
            if (p > bestP) { bestP = p; bestK = k }
        }
        val bandsDb = FloatArray(Bands.names.size)
        val bandsPct = FloatArray(Bands.names.size)
        for (b in Bands.names.indices) {
            val lo = ceil(Bands.edges[b] / binHz).toInt().coerceIn(1, half)
            val hi = (ceil(Bands.edges[b + 1] / binHz).toInt() - 1).coerceIn(0, half)
            var s = 0.0
            for (k in lo..hi) s += power[k]
            bandsDb[b] = db10(s * powNorm)
            bandsPct[b] = if (total > 0) (s / total * 100).toFloat() else 0f
        }
        val centroid = if (total > 1e-12) (weighted / total).toFloat() else Float.NaN

        var dominantHz = Float.NaN
        var dominantDb = db10(bestP * ampNorm)
        if (dominantDb > -80f && bestK in 1 until half) {
            val y0 = db10(power[bestK - 1]); val y1 = db10(power[bestK]); val y2 = db10(power[bestK + 1])
            val den = y0 - 2 * y1 + y2
            val off = if (abs(den) > 1e-6f) (0.5f * (y0 - y2) / den).coerceIn(-0.5f, 0.5f) else 0f
            dominantHz = (bestK + off) * binHz
            dominantDb -= 0.25f * (y0 - y2) * off // koreksi amplitudo puncak (interpolasi parabola)
        }

        // ---- Bandwidth efektif (deteksi cutoff, mis. kompresi lossy) ----
        var ref = FLOOR_DB
        val r0 = ceil(200f / binHz).toInt().coerceIn(1, half)
        val r1 = floor(5000f / binHz).toInt().coerceIn(r0, half)
        for (k in r0..r1) { val d = db10(avgPower[k] * ampNorm); if (d > ref) ref = d }
        var bandwidth = Float.NaN
        if (ref > -90f) {
            val thr = max(ref - 60f, -115f)
            val kStop = floor(1000f / binHz).toInt().coerceAtLeast(1)
            var k = half
            while (k > kStop) {
                if (db10(avgPower[k] * ampNorm) > thr) { bandwidth = k * binHz; break }
                k--
            }
        }

        // ---- Waveform (dengan trigger zero-crossing) ----
        val contiguous = if (stereo) filled else n
        val w = min(WAVE, contiguous / 2).coerceAtLeast(1)
        val end = writePos
        var trig = -1
        for (j in 0 until w) {
            val idx = (end - 2 * w + j) and MASK
            val prev = (idx - 1) and MASK
            val m = ringL[idx] + ringR[idx]
            val mp = ringL[prev] + ringR[prev]
            if (mp < 0f && m >= 0f) { trig = end - 2 * w + j; break }
        }
        val ws = if (trig >= 0) trig else end - w
        val waveL = FloatArray(w) { ringL[(ws + it) and MASK] }
        val waveR = FloatArray(w) { ringR[(ws + it) and MASK] }

        // ---- Vectorscope (goniometer) ----
        val sc = min(SCOPE, contiguous).coerceAtLeast(1)
        val scopeX = FloatArray(sc)
        val scopeY = FloatArray(sc)
        for (i in 0 until sc) {
            val idx = (end - sc + i) and MASK
            val a = ringL[idx]; val b = ringR[idx]
            scopeX[i] = (b - a) * 0.70710677f
            scopeY[i] = (a + b) * 0.70710677f
        }

        val maxRms = max(rmsDbL, rmsDbR)
        val frame = AnalysisFrame(
            sampleRate = sampleRate, fftSize = size, stereo = stereo,
            freqs = freqs, spectrumDb = outSpec, peakDb = outPeak,
            waveL = waveL, waveR = waveR, scopeX = scopeX, scopeY = scopeY,
            rmsDbL = rmsDbL, rmsDbR = rmsDbR,
            peakDbL = peakDispL, peakDbR = peakDispR,
            peakHoldDbL = holdL, peakHoldDbR = holdR,
            clipL = clipTimerL > 0f, clipR = clipTimerR > 0f,
            clipCount = clipCount, maxPeakDb = maxPeakDb,
            lufsM = loudness.momentary, lufsS = loudness.shortTerm, lufsI = loudness.integrated,
            dominantHz = dominantHz, dominantDb = dominantDb,
            centroidHz = centroid, bandwidthHz = bandwidth,
            bandsDb = bandsDb, bandsPct = bandsPct,
            correlation = correlation, widthPct = widthPct, balanceDb = balanceDb,
            crestDb = max(holdL, holdR) - maxRms,
            silent = maxRms < -90f,
        )
        return frame to row
    }
}
