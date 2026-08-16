package com.newax.aegis.ui.state

import com.newax.aegis.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T3.5c — the voice-capture state machine (route 1.10). The recognizer is a
 * platform seam; every transition the sheet can be in is a pure decision here,
 * verified on the JVM without Compose or Android: the partial/final text rules,
 * the amplitude gate, the error phase, Stop's transcript pick, and the late-
 * event guards (a recognizer can deliver a result after Stop was pressed).
 */
class VoiceCaptureStateTest {

    @Test
    fun `fresh state is idle with no text`() {
        val s = VoiceCaptureState()
        assertEquals(VoiceCapturePhase.IDLE, s.phase)
        assertEquals("", s.partialText)
        assertNull(s.finalText)
        assertNull(s.errorLabelRes)
        assertEquals(0f, s.amplitude)
    }

    @Test
    fun `ready transition starts listening`() {
        val s = VoiceCaptureState()
        s.onListening()
        assertEquals(VoiceCapturePhase.LISTENING, s.phase)
    }

    @Test
    fun `partial text updates while listening and is ignored otherwise`() {
        val s = VoiceCaptureState()
        s.onPartial("hello") // before listening — no recognizer result can arrive here, but guard anyway
        assertEquals("", s.partialText)
        s.onListening()
        s.onPartial("hello")
        s.onPartial("hello world")
        assertEquals("hello world", s.partialText)
    }

    @Test
    fun `final result wins over partials and closes the capture`() {
        val s = VoiceCaptureState()
        s.onListening()
        s.onPartial("hello")
        s.onFinal("hello world")
        assertEquals(VoiceCapturePhase.DONE, s.phase)
        assertEquals("hello world", s.finalText)
        // A late partial after the final is noise — keep the final hypothesis.
        s.onPartial("stale")
        assertEquals("hello world", s.partialText)
    }

    @Test
    fun `amplitude is recorded only while listening`() {
        val s = VoiceCaptureState()
        s.onAmplitude(4f) // before listening — ignored
        assertEquals(0f, s.amplitude)
        s.onListening()
        s.onAmplitude(3.5f)
        assertEquals(3.5f, s.amplitude)
        s.onFinal("done")
        s.onAmplitude(9f) // after done — ignored
        assertEquals(3.5f, s.amplitude)
    }

    @Test
    fun `error moves to the error phase and later errors are ignored`() {
        val s = VoiceCaptureState()
        s.onListening()
        s.onError(R.string.voice_error_busy)
        assertEquals(VoiceCapturePhase.ERROR, s.phase)
        assertEquals(R.string.voice_error_busy, s.errorLabelRes)
        s.onError(R.string.voice_error_generic)
        assertEquals(R.string.voice_error_busy, s.errorLabelRes)
    }

    @Test
    fun `stop returns the last running hypothesis from listening`() {
        val s = VoiceCaptureState()
        s.onListening()
        s.onPartial("  open the files  ")
        assertEquals("open the files", s.stop())
        assertEquals(VoiceCapturePhase.DONE, s.phase)
    }

    @Test
    fun `stop returns the final when one already arrived`() {
        val s = VoiceCaptureState()
        s.onListening()
        s.onPartial("hello")
        s.onFinal("hello world")
        assertEquals("hello world", s.stop())
    }

    @Test
    fun `stop with nothing recognized moves to the nothing-recognized error`() {
        val s = VoiceCaptureState()
        s.onListening()
        assertNull(s.stop())
        assertEquals(VoiceCapturePhase.ERROR, s.phase)
        assertEquals(R.string.voice_error_nothing_recognized, s.errorLabelRes)
    }

    @Test
    fun `cancel discards everything back to idle`() {
        val s = VoiceCaptureState()
        s.onListening()
        s.onPartial("hello")
        s.onAmplitude(2f)
        s.cancel()
        assertEquals(VoiceCapturePhase.IDLE, s.phase)
        assertEquals("", s.partialText)
        assertNull(s.finalText)
        assertNull(s.errorLabelRes)
        assertEquals(0f, s.amplitude)
    }

    @Test
    fun `late recognizer events after stop are ignored`() {
        val s = VoiceCaptureState()
        s.onListening()
        s.onPartial("hello")
        assertEquals("hello", s.stop())
        // The recognizer was cancelled on Stop, but a straggler event must not
        // resurrect or overwrite the captured transcript.
        s.onFinal("late final")
        s.onError(R.string.voice_error_generic)
        assertEquals(VoiceCapturePhase.DONE, s.phase)
        assertNull(s.finalText)
        assertNull(s.errorLabelRes)
    }
}
