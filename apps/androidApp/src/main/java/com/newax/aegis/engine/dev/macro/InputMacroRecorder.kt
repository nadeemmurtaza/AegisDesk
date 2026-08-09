package com.newax.aegis.engine.dev.macro

import android.view.accessibility.AccessibilityEvent
import com.newax.aegis.engine.compiler.ProcedureCompiler
import com.newax.aegis.engine.procedure.ProcedureStep
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class MacroEvent(
    val timestampMs: Long,
    val type: MacroEventType,
    val packageName: String,
    val text: String,
    val className: String,
    val contentDesc: String
)

enum class MacroEventType { TAP, TYPE, SCROLL, WINDOW_CHANGE, FOCUS }

data class RecordedMacro(
    val name: String,
    val events: List<MacroEvent>,
    val steps: List<ProcedureStep>,
    val recordedMs: Long,
    val durationMs: Long
)

object InputMacroRecorder {

    private val recording = AtomicBoolean(false)
    private val events = CopyOnWriteArrayList<MacroEvent>()
    private var recordStart = 0L
    private var currentMacroName = ""
    private val savedMacros = CopyOnWriteArrayList<RecordedMacro>()

    fun startRecording(name: String = "macro_${System.currentTimeMillis()}") {
        if (recording.getAndSet(true)) return
        events.clear()
        recordStart = System.currentTimeMillis()
        currentMacroName = name
    }

    fun stopRecording(): RecordedMacro? {
        if (!recording.getAndSet(false)) return null
        val captured = events.toList()
        val steps = eventsToSteps(captured)
        val macro = RecordedMacro(
            name = currentMacroName,
            events = captured,
            steps = steps,
            recordedMs = recordStart,
            durationMs = System.currentTimeMillis() - recordStart
        )
        savedMacros.add(macro)
        events.clear()
        return macro
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!recording.get()) return
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> MacroEventType.TAP
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> MacroEventType.TYPE
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> MacroEventType.SCROLL
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> MacroEventType.WINDOW_CHANGE
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> MacroEventType.FOCUS
            else -> return
        }
        events.add(MacroEvent(
            timestampMs = System.currentTimeMillis(),
            type = type,
            packageName = event.packageName?.toString() ?: "",
            text = event.text.joinToString("") { it.toString() }.take(100),
            className = event.className?.toString() ?: "",
            contentDesc = event.contentDescription?.toString() ?: ""
        ))
    }

    private fun eventsToSteps(events: List<MacroEvent>): List<ProcedureStep> {
        val steps = mutableListOf<ProcedureStep>()
        var i = 0
        while (i < events.size) {
            val e = events[i]
            when (e.type) {
                MacroEventType.WINDOW_CHANGE -> {
                    if (e.packageName.isNotBlank() && (steps.isEmpty() || (steps.last() as? ProcedureStep.LaunchApp)?.packageName != e.packageName)) {
                        steps.add(ProcedureStep.LaunchApp(e.packageName))
                    }
                }
                MacroEventType.TAP -> {
                    val target = e.contentDesc.ifBlank { e.text }.ifBlank { e.className.substringAfterLast('.') }
                    if (target.isNotBlank()) steps.add(ProcedureStep.Tap(target))
                }
                MacroEventType.TYPE -> {
                    if (e.text.isNotBlank()) {
                        steps.add(ProcedureStep.TypeText("focused", e.text))
                    }
                }
                MacroEventType.SCROLL -> {
                    steps.add(ProcedureStep.ScrollDown())
                }
                MacroEventType.FOCUS -> {}
            }
            if (i < events.size - 1) {
                val gapMs = events[i + 1].timestampMs - e.timestampMs
                if (gapMs > 1500 && gapMs < 10000) steps.add(ProcedureStep.Sleep(minOf(gapMs, 2000L)))
            }
            i++
        }
        return steps
    }

    fun liveEvents(): List<MacroEvent> = events.toList()
    fun savedMacros(): List<RecordedMacro> = savedMacros.toList()
    val isRecording: Boolean get() = recording.get()

    fun exportMacroAsText(macro: RecordedMacro): String =
        ProcedureCompiler.decompile(macro.steps)

    fun deleteMacro(name: String): Boolean =
        savedMacros.removeIf { it.name == name }
}
