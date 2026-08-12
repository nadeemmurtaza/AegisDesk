package com.newax.aegis.engine.dev.dashboard

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build

data class AudioState(
    val isBluetoothScoOn: Boolean,
    val isMicrophoneMute: Boolean,
    val isMusicActive: Boolean,
    val isSpeakerphoneOn: Boolean,
    val ringerMode: String,
    val streamVolumes: Map<String, Int>,
    val audioMode: String,
    val focusState: String,
    /**
     * Audio sources currently being recorded. Not package names: the recording client's
     * package is only exposed via @SystemApi, which a normal app cannot read.
     */
    val activeRecordingSources: List<String>,
    val inputDevices: List<AudioDeviceDescription>,
    val outputDevices: List<AudioDeviceDescription>
)

data class AudioDeviceDescription(
    val type: Int,
    val typeName: String,
    val productName: String,
    val id: Int,
    val isSource: Boolean,
    val isSink: Boolean
)

object AudioDashboard {

    private fun audioSourceLabel(source: Int): String = when (source) {
        android.media.MediaRecorder.AudioSource.MIC               -> "MIC"
        android.media.MediaRecorder.AudioSource.CAMCORDER         -> "CAMCORDER"
        android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        android.media.MediaRecorder.AudioSource.UNPROCESSED       -> "UNPROCESSED"
        android.media.MediaRecorder.AudioSource.VOICE_CALL        -> "VOICE_CALL"
        android.media.MediaRecorder.AudioSource.VOICE_DOWNLINK    -> "VOICE_DOWNLINK"
        android.media.MediaRecorder.AudioSource.VOICE_UPLINK      -> "VOICE_UPLINK"
        android.media.MediaRecorder.AudioSource.DEFAULT           -> "DEFAULT"
        else                                                      -> "SOURCE_$source"
    }

    fun snapshot(context: Context): AudioState {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val streams = mapOf(
            "RING" to AudioManager.STREAM_RING,
            "MUSIC" to AudioManager.STREAM_MUSIC,
            "ALARM" to AudioManager.STREAM_ALARM,
            "NOTIFICATION" to AudioManager.STREAM_NOTIFICATION,
            "VOICE_CALL" to AudioManager.STREAM_VOICE_CALL,
            "SYSTEM" to AudioManager.STREAM_SYSTEM
        )

        val volumes = streams.entries.associate { (name, stream) ->
            name to am.getStreamVolume(stream)
        }

        val activeRecording = if (Build.VERSION.SDK_INT >= 24) {
            am.activeRecordingConfigurations.map { cfg -> audioSourceLabel(cfg.clientAudioSource) }
        } else emptyList()

        val inputDevices = if (Build.VERSION.SDK_INT >= 23) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { d -> describeDevice(d) }
        } else emptyList()

        val outputDevices = if (Build.VERSION.SDK_INT >= 23) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d -> describeDevice(d) }
        } else emptyList()

        return AudioState(
            isBluetoothScoOn = am.isBluetoothScoOn,
            isMicrophoneMute = am.isMicrophoneMute,
            isMusicActive = am.isMusicActive,
            isSpeakerphoneOn = am.isSpeakerphoneOn,
            ringerMode = ringerLabel(am.ringerMode),
            streamVolumes = volumes,
            audioMode = audioModeLabel(am.mode),
            focusState = "UNKNOWN",
            activeRecordingSources = activeRecording,
            inputDevices = inputDevices,
            outputDevices = outputDevices
        )
    }

    fun setStreamVolume(context: Context, stream: Int, volume: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(stream, volume, 0)
    }

    fun muteUnmuteMic(context: Context, mute: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isMicrophoneMute = mute
    }

    fun report(context: Context): String {
        val state = snapshot(context)
        return buildString {
            append("Audio Dashboard:\n")
            append("  Ringer: ${state.ringerMode}  Mode: ${state.audioMode}\n")
            append("  Music: ${state.isMusicActive}  Mic muted: ${state.isMicrophoneMute}\n")
            append("  Bluetooth SCO: ${state.isBluetoothScoOn}  Speakerphone: ${state.isSpeakerphoneOn}\n")
            append("  Volumes: ${state.streamVolumes.entries.joinToString(" ") { "${it.key}=${it.value}" }}\n")
            append("  Input devices: ${state.inputDevices.size}  Output: ${state.outputDevices.size}\n")
            if (state.activeRecordingSources.isNotEmpty()) {
                append("  ACTIVE RECORDING: ${state.activeRecordingSources.joinToString()}\n")
            }
        }
    }

    private fun describeDevice(d: AudioDeviceInfo) = AudioDeviceDescription(
        type = d.type,
        typeName = deviceTypeName(d.type),
        productName = d.productName.toString(),
        id = d.id,
        isSource = d.isSource,
        isSink = d.isSink
    )

    private fun deviceTypeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        else -> "TYPE_$type"
    }

    private fun ringerLabel(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> "NORMAL"
        AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
        AudioManager.RINGER_MODE_SILENT -> "SILENT"
        else -> "UNKNOWN($mode)"
    }

    private fun audioModeLabel(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "UNKNOWN($mode)"
    }
}
