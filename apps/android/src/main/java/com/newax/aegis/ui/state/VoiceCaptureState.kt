package com.newax.aegis.ui.state

import com.newax.aegis.R

/**
 * The lifecycle of route 1.10 (voice capture) — docs/UI_DESIGN.md §6.3:
 * the live level meter, the running transcript, Stop (inserts into the
 * composer) and Cancel (discards). Mirrors the other plain-Kotlin holders
 * (T3.1): the recognizer is a platform seam ([com.newax.aegis.voice.VoiceCaptureSession])
 * that reports events here; every transition is pure and unit-tested.
 *
 * The one string the holder itself chooses is the nothing-recognized error:
 * it is the *outcome of the Stop decision*, not a platform failure, so it
 * cannot be owned by the recognizer seam. Everything else arrives from the
 * caller as a resolved resource id ([onError]).
 */
enum class VoiceCapturePhase { IDLE, LISTENING, DONE, ERROR }

class VoiceCaptureState {

    var phase: VoiceCapturePhase = VoiceCapturePhase.IDLE; private set

    /** The running hypothesis — the transcript shown while listening. */
    var partialText: String = ""; private set

    /** The recognizer's final result, once one arrives (silence, Stop). */
    var finalText: String? = null; private set

    /** The localized failure to show in the error phase (a string-resource id). */
    var errorLabelRes: Int? = null; private set

    /** Raw RMS from the recognizer; the UI clamps it ([clampAmplitude]). */
    var amplitude: Float = 0f; private set

    /** The recognizer is ready — the sheet switches to the level meter. */
    fun onListening() {
        if (phase == VoiceCapturePhase.LISTENING || phase == VoiceCapturePhase.DONE) return
        phase = VoiceCapturePhase.LISTENING
        partialText = ""
        finalText = null
        errorLabelRes = null
        amplitude = 0f
    }

    /** A new running hypothesis (partials are cumulative in SpeechRecognizer). */
    fun onPartial(text: String) {
        if (phase != VoiceCapturePhase.LISTENING) return
        partialText = text
    }

    /** The recognizer finished on its own (silence) — the transcript is final. */
    fun onFinal(text: String) {
        if (phase == VoiceCapturePhase.DONE || phase == VoiceCapturePhase.ERROR) return
        if (phase == VoiceCapturePhase.LISTENING) partialText = text
        finalText = text
        phase = VoiceCapturePhase.DONE
    }

    /** Live meter input; recorded only while listening (late RMS is noise). */
    fun onAmplitude(rms: Float) {
        if (phase == VoiceCapturePhase.LISTENING) amplitude = rms
    }

    /** A platform failure (permission, busy, audio, network, unavailable). */
    fun onError(labelRes: Int) {
        if (phase == VoiceCapturePhase.DONE || phase == VoiceCapturePhase.ERROR) return
        errorLabelRes = labelRes
        phase = VoiceCapturePhase.ERROR
    }

    /**
     * Stop pressed: the transcript to insert into the composer — the final
     * result wins, else the last running hypothesis. Returns null when
     * nothing was recognized; the sheet then shows the nothing-recognized
     * error instead of closing silently (an empty insert would look like a
     * no-op and the user would not know why).
     */
    fun stop(): String? {
        if (phase != VoiceCapturePhase.LISTENING && phase != VoiceCapturePhase.DONE) return null
        val transcript = (finalText ?: partialText).takeIf { it.isNotBlank() }?.trim()
        if (transcript != null) {
            phase = VoiceCapturePhase.DONE
            return transcript
        }
        errorLabelRes = R.string.voice_error_nothing_recognized
        phase = VoiceCapturePhase.ERROR
        return null
    }

    /** Cancel pressed (or the sheet dismissed): discard everything. */
    fun cancel() = reset()

    fun reset() {
        phase = VoiceCapturePhase.IDLE
        partialText = ""
        finalText = null
        errorLabelRes = null
        amplitude = 0f
    }
}
