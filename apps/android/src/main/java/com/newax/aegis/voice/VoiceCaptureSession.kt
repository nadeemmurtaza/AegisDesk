package com.newax.aegis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.newax.aegis.R

/**
 * The platform seam for route 1.10 (voice capture): owns the
 * [SpeechRecognizer] lifecycle (create → start → cancel → destroy) and
 * translates its callbacks into [Listener] events.
 *
 * The sheet needs two things the `RecognizerIntent` activity cannot provide —
 * the live level meter ([RecognitionListener.onRmsChanged]) and the running
 * transcript ([RecognitionListener.onPartialResults]) — which is why the
 * composer's mic opens a session against this seam instead of the one-shot
 * system recognizer dialog. The state machine the events feed lives in
 * [com.newax.aegis.ui.state.VoiceCaptureState], which is pure and tested.
 *
 * Errors arrive as string-resource ids ([Listener.onError]): the recognizer's
 * integer codes are mapped here once, so the sheet never branches on Android
 * error constants. `commonMain` stays untouched — this is androidMain-only.
 */
class VoiceCaptureSession(private val context: Context) {

    /** Callbacks translated from the recognizer, delivered on the main thread. */
    interface Listener {
        fun onReady()
        fun onRmsChanged(rms: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(labelRes: Int)
    }

    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null

    /** True when this device has a recognizer to talk to. */
    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Starts a capture session. Creates the recognizer on first use (it binds
     * to the system recognition service — no point holding that while the
     * user never uses the mic) and hands it the offline, partial-results
     * intent the sheet's live surfaces need.
     */
    fun start(listener: Listener) {
        val recognizer = ensureRecognizer() ?: return
        this.listener = listener
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onRmsChanged(rmsdB: Float) = listener.onRmsChanged(rmsdB)
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) listener.onPartial(text)
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                listener.onFinal(text)
            }
            override fun onError(error: Int) = listener.onError(errorLabelRes(error))
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // The running transcript (route 1.10 item 2).
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Offline-first (ARCHITECTURE.md rule 8): the product refuses the
            // INTERNET permission, so the recognizer must never fall back to a
            // network service as a silent behaviour change.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
    }

    /**
     * Ends the current session without delivering a result — used by Stop
     * (the transcript was already read off the state) and Cancel/dismiss.
     * The recognizer stays alive for the next capture.
     */
    fun cancel() {
        recognizer?.cancel()
        listener = null
    }

    /** Releases the recognizer for good — call once, from the owner's dispose. */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (recognizer == null && available) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
        return recognizer
    }

    /** The recognizer's integer codes → one localized string, resolved here. */
    private fun errorLabelRes(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            R.string.voice_error_nothing_recognized
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            R.string.voice_error_permission
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            R.string.voice_error_busy
        SpeechRecognizer.ERROR_AUDIO ->
            R.string.voice_error_audio
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            R.string.voice_error_network
        else ->
            R.string.voice_error_generic
    }
}
