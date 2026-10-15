package id.twsanalyzer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.twsanalyzer.audio.AnalysisFrame
import id.twsanalyzer.audio.Analyzer
import id.twsanalyzer.audio.AudioEngine
import id.twsanalyzer.audio.Bands
import id.twsanalyzer.audio.OutputDevice
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ============================================================ format helper

fun fmt(v: Float, digits: Int = 1): String =
    if (v.isNaN() || v.isInfinite() || v <= -150f) "–" else String.format(Locale.US, "%.${digits}f", v)

fun fmtHz(hz: Float): String = when {
    hz.isNaN() -> "–"
    hz >= 1000f -> String.format(Locale.US, "%.2f kHz", hz / 1000f)
    else -> String.format(Locale.US, "%.1f Hz", hz)
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(hz: Float): String {
    if (hz.isNaN() || hz < 16f) return "–"
    val midi = 69f + 12f * log2(hz / 440f)
    val nearest = midi.roundToInt()
    val cents = ((midi - nearest) * 100f).roundToInt()
    val sign = if (cents >= 0) "+" else ""
    return "${NOTE_NAMES[((nearest % 12) + 12) % 12]}${nearest / 12 - 1} $sign$cents¢"
}

private val labelStyle = TextStyle(color = Palette.Label, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
private val FREQ_MARKS = floatArrayOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
private fun freqLabel(hz: Float) = if (hz >= 1000f) "${(hz / 1000).toInt()}k" else hz.toInt().toString()

private fun Modifier.panel() = this
    .clip(RoundedCornerShape(8.dp))
    .background(Palette.PanelBg)

private fun DrawScope.freqGrid(tm: TextMeasurer, fTop: Float, labels: Boolean = true) {
    val lnR = ln(fTop / 20f)
    for (hz in FREQ_MARKS) {
        if (hz > fTop) continue
        val x = ln(hz / 20f) / lnR * size.width
        drawLine(Palette.Grid, Offset(x, 0f), Offset(x, size.height), 1f)
        if (labels) drawText(tm, freqLabel(hz), Offset(x + 3f, size.height - 14.dp.toPx()), labelStyle)
    }
}

@Composable
fun PanelTitle(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Palette.Label)
        }
    }
}

// ============================================================ SPEKTRUM

@Composable
fun SpectrumView(frame: State<AnalysisFrame?>, minDb: Float, maxDb: Float, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    var cursor by remember { mutableStateOf<Float?>(null) }
    Canvas(
        modifier
            .panel()
            .pointerInput(Unit) {
                detectTapGestures(onPress = { cursor = it.x; tryAwaitRelease(); cursor = null })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { cursor = it.x },
                    onDragEnd = { cursor = null },
                    onDragCancel = { cursor = null },
                ) { ch, _ -> cursor = ch.position.x }
            }
    ) {
        val f = frame.value
        val w = size.width
        val h = size.height
        val fTop = f?.freqs?.last() ?: 20000f
        fun y(db: Float) = ((maxDb - db) / (maxDb - minDb)).coerceIn(0f, 1f) * h

        freqGrid(tm, fTop)
        val step = if (maxDb - minDb > 80f) 20 else 10
        var d = (maxDb / step).toInt() * step
        while (d > minDb) {
            val yy = y(d.toFloat())
            drawLine(Palette.Grid, Offset(0f, yy), Offset(w, yy), 1f)
            drawText(tm, "$d", Offset(3f, yy + 1f), labelStyle)
            d -= step
        }

        if (f != null) {
            val n = f.spectrumDb.size
            val fill = Path()
            val line = Path()
            val pk = Path()
            fill.moveTo(0f, h)
            for (i in 0 until n) {
                val xi = i.toFloat() / (n - 1) * w
                val yi = y(f.spectrumDb[i])
                val yp = y(f.peakDb[i])
                if (i == 0) { line.moveTo(xi, yi); pk.moveTo(xi, yp) } else { line.lineTo(xi, yi); pk.lineTo(xi, yp) }
                fill.lineTo(xi, yi)
            }
            fill.lineTo(w, h)
            fill.close()
            drawPath(
                fill,
                Brush.verticalGradient(listOf(Palette.Cyan.copy(alpha = 0.55f), Palette.Cyan.copy(alpha = 0.03f)))
            )
            drawPath(line, Palette.Cyan, style = Stroke(1.5.dp.toPx()))
            drawPath(pk, Palette.Orange.copy(alpha = 0.85f), style = Stroke(1.dp.toPx()))

            cursor?.let { cx ->
                val idx = ((cx / w) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                val xx = idx.toFloat() / (n - 1) * w
                drawLine(Color.White.copy(alpha = 0.7f), Offset(xx, 0f), Offset(xx, h), 1.dp.toPx())
                val txt = "${fmtHz(f.freqs[idx])}  ${fmt(f.spectrumDb[idx])} dB  ${noteName(f.freqs[idx])}"
                val st = labelStyle.copy(color = Color.White, fontSize = 11.sp)
                val m = tm.measure(txt, st)
                val bx = (xx + 6f).coerceAtMost(w - m.size.width - 6f).coerceAtLeast(2f)
                drawRect(Color(0xCC000000), Offset(bx - 4f, 4f), Size(m.size.width + 8f, m.size.height + 4f))
                drawText(m, topLeft = Offset(bx, 6f))
            }
        }
    }
}

// ============================================================ SPEKTROGRAM

class SpectrogramState(val cols: Int = Analyzer.POINTS, val rows: Int = 240) {
    private val pixels = IntArray(cols * rows) { 0xFF000004.toInt() }
    private val bitmap: Bitmap = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
    val image = bitmap.asImageBitmap()
    val tick = mutableIntStateOf(0)
    private val lut = IntArray(256)

    init {
        // Peta warna mirip "inferno"
        val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)
        val cols = intArrayOf(0x000004, 0x420A68, 0x932667, 0xDD513A, 0xFCA50A, 0xFCFFA4)
        for (i in 0 until 256) {
            val t = i / 255f
            var s = 0
            while (s < stops.size - 2 && t > stops[s + 1]) s++
            val u = ((t - stops[s]) / (stops[s + 1] - stops[s])).coerceIn(0f, 1f)
            val a = cols[s]; val b = cols[s + 1]
            fun ch(sh: Int) = ((a shr sh and 0xFF) * (1 - u) + (b shr sh and 0xFF) * u).toInt()
            lut[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        bitmap.setPixels(pixels, 0, this.cols, 0, 0, this.cols, rows)
    }

    fun push(row: FloatArray, minDb: Float, maxDb: Float) {
        System.arraycopy(pixels, 0, pixels, cols, cols * (rows - 1))
        val inv = 255f / (maxDb - minDb)
        for (i in 0 until min(cols, row.size)) {
            pixels[i] = lut[((row[i] - minDb) * inv).toInt().coerceIn(0, 255)]
        }
    }

    fun commit() {
        bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows)
        tick.intValue++
    }
}

@Composable
fun SpectrogramView(state: SpectrogramState, fTop: Float, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.panel()) {
        state.tick.intValue // baca agar redraw saat ada baris baru
        drawImage(
            state.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(state.cols, state.rows),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
        freqGrid(tm, fTop)
        drawText(tm, "↓ waktu (terbaru di atas)", Offset(size.width - 150.dp.toPx(), 4f), labelStyle)
    }
}

// ============================================================ WAVEFORM

@Composable
fun WaveformView(frame: State<AnalysisFrame?>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val gains = remember { floatArrayOf(1f, 2f, 4f, 8f, 16f) }
    var gi by remember { mutableIntStateOf(0) }
    Canvas(modifier.panel().pointerInput(Unit) { detectTapGestures(onTap = { gi = (gi + 1) % gains.size }) }) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        val g = gains[gi]
        drawLine(Palette.Grid, Offset(0f, mid), Offset(w, mid), 1f)
        drawLine(Palette.Grid, Offset(0f, mid - h / 4), Offset(w, mid - h / 4), 1f)
        drawLine(Palette.Grid, Offset(0f, mid + h / 4), Offset(w, mid + h / 4), 1f)
        val f = frame.value ?: return@Canvas
        fun wave(data: FloatArray, color: Color) {
            if (data.size < 2) return
            val p = Path()
            for (i in data.indices) {
                val x = i.toFloat() / (data.size - 1) * w
                val y = (mid - data[i] * g * mid).coerceIn(0f, h)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, color, style = Stroke(1.2.dp.toPx()))
        }
        wave(f.waveL, Palette.Cyan.copy(alpha = 0.9f))
        if (f.stereo) wave(f.waveR, Palette.Magenta.copy(alpha = 0.7f))
        val ms = f.waveL.size * 1000f / f.sampleRate
        val txt = "×${g.toInt()} (ketuk = zoom) · ${fmt(ms)} ms" + if (f.stereo) " · L cyan / R pink" else " · mono"
        drawText(tm, txt, Offset(4f, 4f), labelStyle)
    }
}

// ============================================================ LEVEL METER

private const val METER_MIN = -60f

@Composable
fun LevelMeterView(frame: State<AnalysisFrame?>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.panel()) {
        val f = frame.value
        val h = size.height
        val top = 16.dp.toPx()
        val bottom = h - 6.dp.toPx()
        val scaleW = 30.dp.toPx()
        val barW = (size.width - scaleW - 24.dp.toPx()) / 2
        fun y(db: Float) = top + ((0f - db) / (0f - METER_MIN)).coerceIn(0f, 1f) * (bottom - top)

        for (db in intArrayOf(0, -3, -6, -10, -20, -30, -40, -50, -60)) {
            val yy = y(db.toFloat())
            drawLine(Palette.Grid, Offset(scaleW, yy), Offset(size.width, yy), 1f)
            drawText(tm, "$db", Offset(2f, yy - 6.dp.toPx()), labelStyle)
        }
        val grad = Brush.verticalGradient(
            0f to Palette.Red, 0.05f to Palette.Yellow, 0.25f to Palette.Green, 1f to Palette.Green.copy(alpha = 0.6f),
            startY = top, endY = bottom,
        )
        fun bar(i: Int, rms: Float, pk: Float, hold: Float, clip: Boolean, label: String) {
            val x = scaleW + 8.dp.toPx() + i * (barW + 8.dp.toPx())
            drawRect(Color(0x14FFFFFF), Offset(x, top), Size(barW, bottom - top))
            val yp = y(pk)
            drawRect(Color(0x55FFFFFF), Offset(x, yp), Size(barW, bottom - yp))
            val yr = y(rms)
            drawRect(grad, Offset(x + barW * 0.15f, yr), Size(barW * 0.7f, bottom - yr))
            val yh = y(hold)
            drawLine(Palette.Orange, Offset(x, yh), Offset(x + barW, yh), 2.dp.toPx())
            drawRect(if (clip) Palette.Red else Color(0x22FF5252), Offset(x, 0f), Size(barW, top - 4f))
            drawText(tm, if (clip) "CLIP" else label, Offset(x + barW / 2 - 10.dp.toPx(), 0f), labelStyle.copy(color = Color.White))
        }
        if (f == null) return@Canvas
        bar(0, f.rmsDbL, f.peakDbL, f.peakHoldDbL, f.clipL, if (f.stereo) "L" else "M")
        bar(1, f.rmsDbR, f.peakDbR, f.peakHoldDbR, f.clipR, if (f.stereo) "R" else "M")
    }
}

@Composable
fun LevelNumbers(f: AnalysisFrame?, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Loudness (LUFS)", style = MaterialTheme.typography.labelMedium, color = Palette.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigValue("M", fmt(f?.lufsM ?: Float.NaN))
            BigValue("S", fmt(f?.lufsS ?: Float.NaN))
            BigValue("I", fmt(f?.lufsI ?: Float.NaN))
        }
        Spacer(Modifier.height(4.dp))
        KV("Peak L / R", "${fmt(f?.peakDbL ?: Float.NaN)} / ${fmt(f?.peakDbR ?: Float.NaN)} dBFS")
        KV("RMS L / R", "${fmt(f?.rmsDbL ?: Float.NaN)} / ${fmt(f?.rmsDbR ?: Float.NaN)} dBFS")
        KV("Peak maks", "${fmt(f?.maxPeakDb ?: Float.NaN)} dBFS")
        KV("Crest factor", "${fmt(f?.crestDb ?: Float.NaN)} dB")
        KV("Sampel clipping", "${f?.clipCount ?: 0}")
        OutlinedButton(onClick = onReset, modifier = Modifier.padding(top = 4.dp)) { Text("Reset I / peak / clip") }
    }
}

@Composable
private fun BigValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.Label)
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = Palette.Label, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

// ============================================================ BAND ENERGI

@Composable
fun BandsView(frame: State<AnalysisFrame?>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val nameStyle = labelStyle.copy(color = Color.White, fontSize = 11.sp)
    Canvas(modifier.panel()) {
        val f = frame.value
        val n = Bands.names.size
        val rowH = size.height / n
        val nameW = 120.dp.toPx()
        val valW = 92.dp.toPx()
        val barX = nameW
        val barW = size.width - nameW - valW
        for (i in 0 until n) {
            val y0 = i * rowH
            drawText(tm, Bands.names[i], Offset(6f, y0 + rowH / 2 - 14.dp.toPx()), nameStyle)
            drawText(tm, "${Bands.ranges[i]} Hz", Offset(6f, y0 + rowH / 2), labelStyle)
            drawRect(Color(0x14FFFFFF), Offset(barX, y0 + rowH * 0.25f), Size(barW, rowH * 0.5f))
            if (f == null) continue
            val db = f.bandsDb[i]
            val t = ((db + 80f) / 80f).coerceIn(0f, 1f)
            val color = lerpColor(Palette.Cyan, Palette.Orange, i / (n - 1f))
            drawRect(color, Offset(barX, y0 + rowH * 0.25f), Size(barW * t, rowH * 0.5f))
            drawText(
                tm, "${fmt(db)} dB  ${fmt(f.bandsPct[i], 0)}%",
                Offset(barX + barW + 6f, y0 + rowH / 2 - 7.dp.toPx()), nameStyle
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f
)

// ============================================================ STEREO

@Composable
fun VectorscopeView(frame: State<AnalysisFrame?>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    val gain = remember { floatArrayOf(4f) }
    Canvas(modifier.aspectRatio(1f).panel()) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 * 0.95f
        drawCircle(Palette.Grid, r, c, style = Stroke(1f))
        drawLine(Palette.Grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        drawLine(Palette.Grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        val d = r * 0.7071f
        drawLine(Palette.Grid, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), 1f)
        drawLine(Palette.Grid, Offset(c.x + d, c.y - d), Offset(c.x - d, c.y + d), 1f)
        drawText(tm, "M", Offset(c.x + 3f, c.y - r), labelStyle)
        drawText(tm, "L", Offset(c.x - d - 10f, c.y - d - 10f), labelStyle)
        drawText(tm, "R", Offset(c.x + d + 2f, c.y - d - 10f), labelStyle)
        drawText(tm, "S", Offset(c.x + r - 12f, c.y + 2f), labelStyle)
        val f = frame.value ?: return@Canvas
        var mx = 0f
        for (i in f.scopeX.indices) mx = max(mx, max(abs(f.scopeX[i]), abs(f.scopeY[i])))
        val target = 0.9f / max(mx, 0.01f)
        gain[0] = if (target < gain[0]) target else min(target, gain[0] * 1.03f)
        val g = gain[0] * r
        val pts = ArrayList<Offset>(f.scopeX.size)
        for (i in f.scopeX.indices) pts.add(Offset(c.x + f.scopeX[i] * g, c.y - f.scopeY[i] * g))
        drawPoints(pts, PointMode.Points, Palette.Green.copy(alpha = 0.55f), 2.dp.toPx(), StrokeCap.Round)
        if (!f.stereo) drawText(tm, "Visualizer = mono", Offset(4f, size.height - 14.dp.toPx()), labelStyle)
    }
}

@Composable
fun CorrelationView(frame: State<AnalysisFrame?>, modifier: Modifier = Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier.panel()) {
        val w = size.width
        val h = size.height
        drawRect(Palette.Red.copy(alpha = 0.25f), Offset.Zero, Size(w / 2, h))
        drawRect(Palette.Green.copy(alpha = 0.25f), Offset(w / 2, 0f), Size(w / 2, h))
        drawLine(Palette.Label, Offset(w / 2, 0f), Offset(w / 2, h), 1f)
        drawText(tm, "-1", Offset(3f, 2f), labelStyle)
        drawText(tm, "0", Offset(w / 2 + 3f, 2f), labelStyle)
        drawText(tm, "+1", Offset(w - 18.dp.toPx(), 2f), labelStyle)
        val corr = frame.value?.correlation ?: return@Canvas
        if (corr.isNaN()) return@Canvas
        val x = (corr + 1f) / 2f * w
        drawLine(if (corr < 0) Palette.Red else Palette.Green, Offset(x, 0f), Offset(x, h), 4.dp.toPx())
    }
}

// ============================================================ INFO

@Composable
fun InfoView(f: AnalysisFrame?, status: AudioEngine.Status, device: OutputDevice?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        KV("Frekuensi dominan", if (f == null || f.dominantHz.isNaN()) "–" else "${fmtHz(f.dominantHz)} · ${fmt(f.dominantDb)} dB")
        KV("Nada terdekat", noteName(f?.dominantHz ?: Float.NaN))
        KV("Spectral centroid (kecerahan)", fmtHz(f?.centroidHz ?: Float.NaN))
        val bw = f?.bandwidthHz ?: Float.NaN
        KV("Bandwidth efektif sumber", fmtHz(bw))
        if (!bw.isNaN() && f?.stereo == true) {
            val txt = when {
                bw < 15500f -> "Terpotong rendah: kemungkinan lossy bitrate rendah atau sumber memang begitu."
                bw < 19500f -> "Khas kompresi lossy (AAC/MP3/streaming) atau rekaman yang di-low-pass."
                else -> "Full-band. Bisa lossless/hi-bitrate (tidak menjamin lossless)."
            }
            Text(txt, style = MaterialTheme.typography.labelSmall, color = Palette.Label)
        }
        Spacer(Modifier.height(6.dp))
        KV("Sumber", when (status.source) {
            AudioEngine.Source.PLAYBACK -> "Audio internal (PCM 16-bit stereo)"
            AudioEngine.Source.VISUALIZER -> "Visualizer mix sistem (8-bit mono)"
            AudioEngine.Source.NONE -> "Tidak aktif"
        })
        if (f != null) {
            KV("Sample rate analisis", "${f.sampleRate} Hz")
            KV("Ukuran FFT", "${f.fftSize}")
            KV("Resolusi frekuensi", fmtHz(f.sampleRate.toFloat() / f.fftSize))
            KV("Panjang jendela", "${fmt(f.fftSize * 1000f / f.sampleRate)} ms")
            if (f.stereo) {
                KV("Korelasi L/R", fmt(f.correlation, 2))
                KV("Lebar stereo", "${fmt(f.widthPct, 0)} %")
                KV("Balance (L−R)", "${fmt(f.balanceDb)} dB")
            }
        }
        Spacer(Modifier.height(6.dp))
        KV("Output audio", device?.name ?: "–")
        KV("Jenis", device?.typeLabel ?: "–")
        if (device != null) {
            if (device.sampleRates.isNotEmpty()) KV("Sample rate didukung", device.sampleRates.joinToString(", "))
            if (device.channelCounts.isNotEmpty()) KV("Channel", device.channelCounts.joinToString(", "))
            if (device.encodings.isNotEmpty()) KV("Encoding", device.encodings.joinToString(", "))
            if (device.isBluetooth) {
                Text(
                    "Codec Bluetooth (SBC/AAC/SSC/LC3) tidak bisa dibaca app biasa. Cek di Pengaturan > Opsi " +
                        "pengembang > Codec Audio Bluetooth, atau di app Galaxy Wearable.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Label,
                )
            }
        }
    }
}
