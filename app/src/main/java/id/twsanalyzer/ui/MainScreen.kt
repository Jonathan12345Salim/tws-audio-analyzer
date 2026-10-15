package id.twsanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.twsanalyzer.audio.AnalysisFrame
import id.twsanalyzer.audio.AudioEngine
import id.twsanalyzer.audio.AudioEngine.Source
import id.twsanalyzer.audio.OutputDevice
import kotlinx.coroutines.delay

private val TABS = listOf("Semua", "Spektrum", "Spektrogram", "Gelombang", "Level", "Band", "Stereo", "Info")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    status: AudioEngine.Status,
    settings: AppSettings,
    device: OutputDevice?,
    onStart: (Source) -> Unit,
    onStop: () -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRefreshDevice: () -> Unit,
) {
    var frozen by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val fast = remember { mutableStateOf<AnalysisFrame?>(null) }
    val slow = remember { mutableStateOf<AnalysisFrame?>(null) }
    val spectro = remember { SpectrogramState() }
    val maxDb = if (settings.tiltDbPerOct > 0f) 12f else 0f
    val minDb = settings.minDb

    // Loop per-frame layar: ambil hasil analisis terbaru (hanya memicu redraw Canvas)
    LaunchedEffect(frozen, minDb, maxDb) {
        while (true) {
            withFrameNanos { }
            if (!frozen) {
                fast.value = AudioEngine.latest
                var any = false
                while (true) {
                    val row = AudioEngine.spectrogramRows.poll() ?: break
                    spectro.push(row, minDb, maxDb)
                    any = true
                }
                if (any) spectro.commit()
            } else {
                AudioEngine.spectrogramRows.clear()
            }
        }
    }
    // Angka teks diperbarui lebih lambat agar terbaca
    LaunchedEffect(frozen) {
        while (true) {
            delay(150)
            if (!frozen) slow.value = AudioEngine.latest
        }
    }
    LaunchedEffect(Unit) {
        while (true) { onRefreshDevice(); delay(2000) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TWS Audio Analyzer") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (status.running) {
                        TextButton(onClick = { frozen = !frozen }) { Text(if (frozen) "Lanjut" else "Bekukan") }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Pengaturan")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusCard(status, device, settings, slow.value, frozen, onStart, onStop, onSettings)
            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = 8.dp,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                TABS.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            val fTop = fast.value?.freqs?.last() ?: 20000f
            val pm = Modifier.fillMaxWidth()
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        PanelTitle("Spektrum frekuensi", "dBFS · tahan/geser untuk kursor")
                        SpectrumView(fast, minDb, maxDb, pm.height(220.dp))
                        SpectrumStrip(slow.value)
                        PanelTitle("Spektrogram")
                        SpectrogramView(spectro, fTop, pm.height(170.dp))
                        PanelTitle("Gelombang (oscilloscope)")
                        WaveformView(fast, pm.height(130.dp))
                        PanelTitle("Level & loudness")
                        LevelBlock(fast, slow.value)
                        PanelTitle("Energi per band")
                        BandsView(fast, pm.height(230.dp))
                        PanelTitle("Stereo")
                        StereoBlock(fast, slow.value)
                        PanelTitle("Info")
                        InfoView(slow.value, status, device)
                        Spacer(Modifier.height(24.dp))
                    }
                    1 -> FullPanel {
                        SpectrumView(fast, minDb, maxDb, Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp))
                        SpectrumStrip(slow.value)
                    }
                    2 -> FullPanel {
                        SpectrumView(fast, minDb, maxDb, Modifier.fillMaxWidth().weight(0.35f).padding(top = 8.dp))
                        Spacer(Modifier.height(6.dp))
                        SpectrogramView(spectro, fTop, Modifier.fillMaxWidth().weight(0.65f))
                    }
                    3 -> FullPanel {
                        WaveformView(fast, Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp))
                    }
                    4 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        LevelBlock(fast, slow.value, meterHeight = 320)
                    }
                    5 -> FullPanel {
                        BandsView(fast, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))
                    }
                    6 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        StereoBlock(fast, slow.value)
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        InfoView(slow.value, status, device)
                        Spacer(Modifier.height(16.dp))
                        AboutText()
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(settings, onSettings) { showSettings = false }
    }
}

@Composable
private fun FullPanel(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), content = content)

@Composable
private fun SpectrumStrip(f: AnalysisFrame?) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        val dom = f?.dominantHz ?: Float.NaN
        Text(
            "Dominan ${fmtHz(dom)} ${if (dom.isNaN()) "" else "(" + noteName(dom) + ")"}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text("Centroid ${fmtHz(f?.centroidHz ?: Float.NaN)}", style = MaterialTheme.typography.labelSmall)
        Text("LUFS-S ${fmt(f?.lufsS ?: Float.NaN)}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LevelBlock(fast: androidx.compose.runtime.State<AnalysisFrame?>, slow: AnalysisFrame?, meterHeight: Int = 230) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LevelMeterView(fast, Modifier.weight(1f).height(meterHeight.dp))
        LevelNumbers(slow, onReset = AudioEngine::requestReset, modifier = Modifier.weight(1.3f))
    }
}

@Composable
private fun StereoBlock(fast: androidx.compose.runtime.State<AnalysisFrame?>, slow: AnalysisFrame?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VectorscopeView(fast, Modifier.weight(1f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Korelasi fase", style = MaterialTheme.typography.labelMedium, color = Palette.Label)
            CorrelationView(fast, Modifier.fillMaxWidth().height(28.dp))
            if (slow != null && slow.stereo) {
                KV("Korelasi", fmt(slow.correlation, 2))
                KV("Lebar stereo", "${fmt(slow.widthPct, 0)} %")
                KV("Balance L−R", "${fmt(slow.balanceDb)} dB")
                Text(
                    "+1 = mono, 0 = sangat lebar, negatif = fase terbalik (bass bisa hilang).",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Label,
                )
            } else {
                Text(
                    "Data stereo hanya tersedia di mode Audio internal.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Label,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    status: AudioEngine.Status,
    device: OutputDevice?,
    settings: AppSettings,
    frame: AnalysisFrame?,
    frozen: Boolean,
    onStart: (Source) -> Unit,
    onStop: () -> Unit,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        when {
                            frozen -> Palette.Orange
                            status.running -> Palette.Green
                            else -> Color.Gray
                        }
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (status.source) {
                        Source.PLAYBACK -> "Audio internal · stereo · kualitas penuh"
                        Source.VISUALIZER -> "Visualizer mix sistem · mono · 8-bit"
                        Source.NONE -> "Tidak aktif"
                    } + if (frozen) " (dibekukan)" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                "Output: ${device?.name ?: "–"}${device?.let { " · " + it.typeLabel } ?: ""}",
                style = MaterialTheme.typography.bodySmall, color = Palette.Label,
            )
            if (status.running && frame != null) {
                Text(
                    "${frame.sampleRate} Hz · FFT ${frame.fftSize} · resolusi ${fmtHz(frame.sampleRate.toFloat() / frame.fftSize)}",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Label,
                )
            }

            status.note?.let { Banner(it, Palette.Cyan) }
            status.error?.let { Banner(it, Palette.Red) }
            if (status.captureBlocked && status.source == Source.PLAYBACK) {
                Banner(
                    "Audio internal terbaca sunyi padahal musik sedang diputar. App pemutar memblokir " +
                        "penangkapan (umum di Apple Music karena DRM). Pakai mode Visualizer.",
                    Palette.Orange,
                )
            }
            if (status.noSignal && status.source == Source.VISUALIZER) {
                Banner(
                    "Visualizer belum menerima data. Coba Stop lalu Mulai lagi, atau pause/play lagunya.",
                    Palette.Orange,
                )
            }
            if (status.source == Source.VISUALIZER) {
                Text(
                    "Mode Visualizer: tetap sinyal digital output HP (bukan mic), tapi mono 8-bit & resolusi kasar.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Label,
                )
            }
            if (!status.running) {
                Text(
                    "Android meminta izin \"Mikrofon\" untuk semua jenis tangkapan audio, termasuk audio internal. " +
                        "App ini TIDAK membuka mikrofon.",
                    style = MaterialTheme.typography.labelSmall, color = Palette.Label,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!status.running) {
                    val pref = settings.preferredSource
                    if (pref == Source.VISUALIZER) {
                        Button(onClick = { onStart(Source.VISUALIZER) }) { Text("▶ Visualizer") }
                        OutlinedButton(onClick = { onStart(Source.PLAYBACK) }) { Text("▶ Audio internal") }
                    } else {
                        Button(onClick = { onStart(Source.PLAYBACK) }) { Text("▶ Audio internal") }
                        OutlinedButton(onClick = { onStart(Source.VISUALIZER) }) { Text("▶ Visualizer") }
                    }
                } else {
                    Button(onClick = onStop) { Text("■ Stop") }
                    if (status.source == Source.PLAYBACK) {
                        OutlinedButton(onClick = { onStart(Source.VISUALIZER) }) { Text("Ganti ke Visualizer") }
                    } else {
                        OutlinedButton(onClick = { onStart(Source.PLAYBACK) }) { Text("Coba Audio internal") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(8.dp),
    )
}

@Composable
private fun AboutText() {
    Text(
        "Yang dianalisis adalah sinyal digital yang dikirim HP ke TWS, sebelum di-encode codec Bluetooth. " +
            "Karakter akhir yang terdengar di telinga (codec, EQ internal TWS, driver earbud) tidak bisa diukur " +
            "tanpa mikrofon pengukuran. Efek suara HP (Dolby Atmos / Equalizer Samsung) mungkin tidak ikut terlihat.",
        style = MaterialTheme.typography.labelSmall, color = Palette.Label,
    )
}

// ============================================================ PENGATURAN

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    s: AppSettings,
    onSettings: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Pengaturan", style = MaterialTheme.typography.titleMedium)

            Text("Ukuran FFT (resolusi vs kecepatan)", style = MaterialTheme.typography.labelLarge)
            ChipRow(listOf(2048, 4096, 8192, 16384), s.fftSize, { "$it" }) { v -> onSettings { it.copy(fftSize = v) } }
            Text(
                "Lebih besar = detail bass lebih halus tapi respons lebih lambat. Mode Visualizer selalu 1024.",
                style = MaterialTheme.typography.labelSmall, color = Palette.Label,
            )
            HorizontalDivider()

            Text("Smoothing spektrum: ${fmt(s.smoothing, 2)}", style = MaterialTheme.typography.labelLarge)
            Slider(value = s.smoothing, onValueChange = { v -> onSettings { it.copy(smoothing = v) } }, valueRange = 0f..0.95f)

            SwitchRow("Garis peak hold (oranye)", s.peakHold) { v -> onSettings { it.copy(peakHold = v) } }
            HorizontalDivider()

            Text("Kemiringan (tilt) spektrum", style = MaterialTheme.typography.labelLarge)
            ChipRow(listOf(0f, 3f, 4.5f), s.tiltDbPerOct, { if (it == 0f) "Datar" else "+${fmt(it)} dB/okt" }) { v ->
                onSettings { it.copy(tiltDbPerOct = v) }
            }
            Text(
                "+3 dB/oktaf membuat musik/pink noise tampak lebih datar, memudahkan membaca keseimbangan nada.",
                style = MaterialTheme.typography.labelSmall, color = Palette.Label,
            )
            HorizontalDivider()

            Text("Rentang bawah grafik", style = MaterialTheme.typography.labelLarge)
            ChipRow(listOf(-60f, -80f, -100f, -120f), s.minDb, { "${it.toInt()} dB" }) { v -> onSettings { it.copy(minDb = v) } }
            HorizontalDivider()

            SwitchRow("Otomatis pindah ke Visualizer jika audio internal diblokir", s.autoFallback) { v ->
                onSettings { it.copy(autoFallback = v) }
            }
            SwitchRow("Layar tetap menyala saat analisis", s.keepScreenOn) { v -> onSettings { it.copy(keepScreenOn = v) } }
        }
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
