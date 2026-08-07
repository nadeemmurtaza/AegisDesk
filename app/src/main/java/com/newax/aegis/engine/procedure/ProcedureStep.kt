package com.newax.aegis.engine.procedure

sealed class ProcedureStep {
    data class Tap(val target: String, val fallbackX: Float = -1f, val fallbackY: Float = -1f) : ProcedureStep()
    data class TypeText(val target: String, val text: String, val clearFirst: Boolean = true) : ProcedureStep()
    data class WaitFor(val target: String, val timeoutMs: Long = 4000L) : ProcedureStep()
    data class Verify(val target: String, val abortIfMissing: Boolean = true) : ProcedureStep()
    data class ScrollDown(val maxSwipes: Int = 3) : ProcedureStep()
    data class ScrollUp(val maxSwipes: Int = 3) : ProcedureStep()
    data class Back(val count: Int = 1) : ProcedureStep()
    data class Home(val dummy: Int = 0) : ProcedureStep()
    data class Sleep(val ms: Long) : ProcedureStep()
    data class LaunchApp(val packageName: String) : ProcedureStep()
    data class SelectItem(val listTarget: String, val itemText: String) : ProcedureStep()
    data class TapCoord(val x: Float, val y: Float) : ProcedureStep()
    data class DismissDialog(val buttons: List<String> = listOf("Allow", "OK", "Continue", "Skip", "Dismiss", "Accept", "Got it", "Close")) : ProcedureStep()
    data class AssertPackage(val packageName: String) : ProcedureStep()
    data class ShareFile(val uriString: String, val mimeType: String = "*/*") : ProcedureStep()
}
