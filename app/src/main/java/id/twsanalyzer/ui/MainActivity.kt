package id.twsanalyzer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import id.twsanalyzer.CrashReporter
import id.twsanalyzer.audio.AudioEngine
import id.twsanalyzer.audio.AudioEngine.Source
import id.twsanalyzer.audio.OutputDeviceMonitor
import id.twsanalyzer.audio.PlaybackCaptureService

class MainActivity : ComponentActivity() {
    private lateinit var settings: SettingsStore
    private lateinit var devices: OutputDeviceMonitor
    private var pendingSource: Source? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val s = pendingSource
        pendingSource = null
        if (hasAudioPermission()) {
            if (s != null) startSource(s)
        } else {
            AudioEngine.setError(
                "Izin audio ditolak. Android mewajibkan izin ini untuk menangkap audio internal " +
                    "(mikrofon tetap tidak dipakai). Aktifkan di Pengaturan > Aplikasi > TWS Audio Analyzer > Izin."
            )
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            AudioEngine.setError(null)
            ContextCompat.startForegroundService(
                this, PlaybackCaptureService.startIntent(this, res.resultCode, data)
            )
        } else {
            AudioEngine.setError("Izin menangkap audio internal dibatalkan.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = SettingsStore(applicationContext)
        devices = OutputDeviceMonitor(applicationContext)
        val lastCrash = CrashReporter.take(this)

        setContent {
            AppTheme {
                var crashReport by remember { mutableStateOf(lastCrash) }
                crashReport?.let { report ->
                    CrashReportScreen(report) { crashReport = null }
                    return@AppTheme
                }

                val st by settings.flow.collectAsState()
                val status by AudioEngine.status.collectAsState()
                val device by devices.current.collectAsState()

                LaunchedEffect(st.keepScreenOn, status.running) {
                    if (st.keepScreenOn && status.running) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // Fallback otomatis: Apple Music & beberapa app DRM memblokir AudioPlaybackCapture
                LaunchedEffect(status.captureBlocked) {
                    if (status.captureBlocked && status.source == Source.PLAYBACK && st.autoFallback) {
                        val ok = AudioEngine.startVisualizer(
                            this@MainActivity,
                            note = "App pemutar memblokir penangkapan audio internal (umum di Apple Music " +
                                "karena DRM). Otomatis pindah ke mode Visualizer."
                        )
                        if (ok) settings.update { it.copy(preferredSource = Source.VISUALIZER) }
                    }
                }

                MainScreen(
                    status = status,
                    settings = st,
                    device = device,
                    onStart = ::startSource,
                    onStop = { AudioEngine.stopAll(this) },
                    onSettings = settings::update,
                    onRefreshDevice = devices::refresh,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        devices.start()
    }

    override fun onStop() {
        devices.stop()
        super.onStop()
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startSource(source: Source) {
        if (!hasAudioPermission()) {
            pendingSource = source
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permLauncher.launch(perms.toTypedArray())
            return
        }
        AudioEngine.setNote(null)
        AudioEngine.setError(null)
        when (source) {
            Source.PLAYBACK -> {
                AudioEngine.stopAll(this)
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val intent = if (Build.VERSION.SDK_INT >= 34) {
                    mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                } else {
                    mpm.createScreenCaptureIntent()
                }
                projectionLauncher.launch(intent)
            }
            Source.VISUALIZER -> AudioEngine.startVisualizer(this)
            Source.NONE -> AudioEngine.stopAll(this)
        }
        if (source != Source.NONE) settings.update { it.copy(preferredSource = source) }
    }
}
