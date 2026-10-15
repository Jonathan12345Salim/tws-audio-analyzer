package id.twsanalyzer.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import id.twsanalyzer.R
import id.twsanalyzer.ui.MainActivity

/**
 * Foreground service yang memegang MediaProjection + AudioRecord(AudioPlaybackCapture).
 * Wajib berupa foreground service bertipe mediaProjection sejak Android 10/14.
 */
class PlaybackCaptureService : Service() {
    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "id.twsanalyzer.STOP"

        fun startIntent(ctx: Context, resultCode: Int, data: Intent) =
            Intent(ctx, PlaybackCaptureService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
    }

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            AudioEngine.setError("Data izin MediaProjection kosong.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startCapture(code, data)
        } catch (t: Throwable) {
            Log.e(TAG, "startCapture", t)
            AudioEngine.setError("Gagal memulai audio internal: ${t.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, data: Intent) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) error("Izin audio belum diberikan")

        projection?.stop()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: error("MediaProjection null")
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                AudioEngine.stopPcm()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = mp

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioEngine.PCM_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            AudioEngine.PCM_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        @Suppress("MissingPermission")
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 2, AudioEngine.PCM_RATE * 4 / 5))
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord tidak bisa diinisialisasi")
        }
        AudioEngine.startPcm(record, this)
    }

    override fun onDestroy() {
        AudioEngine.stopPcm()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Analisis audio", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_wave)
            .setContentTitle("Menganalisis audio internal")
            .setContentText("Sinyal digital yang diputar HP, bukan mikrofon")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
