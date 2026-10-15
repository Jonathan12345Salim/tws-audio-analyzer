package id.twsanalyzer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OutputDevice(
    val name: String,
    val typeLabel: String,
    val isBluetooth: Boolean,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<String>,
)

/** Mendeteksi ke perangkat mana audio media sedang dikeluarkan (TWS, speaker, dll). */
class OutputDeviceMonitor(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _current = MutableStateFlow<OutputDevice?>(null)
    val current: StateFlow<OutputDevice?> = _current

    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() = am.unregisterAudioDeviceCallback(cb)

    @SuppressLint("MissingPermission", "NewApi")
    fun refresh() {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var chosen: AudioDeviceInfo? = null
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
                val routed = am.getAudioDevicesForAttributes(attrs).firstOrNull()
                if (routed != null) {
                    chosen = outs.firstOrNull {
                        it.type == routed.type && routed.address.isNotEmpty() && it.address == routed.address
                    } ?: outs.firstOrNull { it.type == routed.type }
                }
            }
        }
        if (chosen == null) chosen = outs.minByOrNull { priority(it.type) }
        _current.value = chosen?.let(::toInfo)
    }

    private fun priority(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 2
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
        else -> 20
    }

    private fun typeLabel(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE Speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "LE Audio Broadcast (Auracast)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (mode telepon)"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker HP"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphone kabel"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset kabel"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset / DAC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio / DAC"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "Tipe $type"
    }

    private fun isBt(type: Int) = type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    private fun encName(e: Int) = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM8"
        else -> "enc $e"
    }

    private fun toInfo(d: AudioDeviceInfo): OutputDevice {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker internal"
            else -> d.productName?.toString() ?: "Tidak diketahui"
        }
        return OutputDevice(
            name = name,
            typeLabel = typeLabel(d.type),
            isBluetooth = isBt(d.type),
            sampleRates = d.sampleRates.toList().sorted(),
            channelCounts = d.channelCounts.toList().distinct().sorted(),
            encodings = d.encodings.map(::encName).distinct(),
        )
    }
}
