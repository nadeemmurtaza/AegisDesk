package com.newax.aegis.engine.compiler

import com.newax.aegis.engine.procedure.ProcedureStep

object ProcedureCompiler {

    data class CompileResult(
        val steps: List<ProcedureStep>,
        val warnings: List<String>,
        val confidence: Float
    )

    fun compile(naturalLanguageSteps: String): CompileResult {
        val lines = naturalLanguageSteps.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                line.removePrefix("-").removePrefix("*").removePrefix("•")
                    .trim()
                    .replace(Regex("^STEP\\s*\\d+:?\\s*"), "")
                    .replace(Regex("^\\d+\\.?\\s*"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }

        val steps = mutableListOf<ProcedureStep>()
        val warnings = mutableListOf<String>()
        var totalConfidence = 0f

        for (line in lines) {
            val lower = line.lowercase()
            val parsed: ProcedureStep? = when {
                lower.startsWith("open ") || lower.startsWith("launch ") -> {
                    val pkg = line.substringAfterLast(" ").trim()
                    ProcedureStep.LaunchApp(pkg)
                }
                lower.startsWith("tap ") || lower.startsWith("click ") -> {
                    val target = line.substringAfter(" ").trim()
                    ProcedureStep.Tap(target)
                }
                lower.startsWith("type ") -> {
                    val text = line.substringAfter(" ").trim().removeSurrounding("\"")
                    ProcedureStep.TypeText("focused", text)
                }
                lower.startsWith("wait for ") -> {
                    val target = line.substringAfter("wait for ").trim()
                    ProcedureStep.WaitFor(target)
                }
                lower.matches(Regex("(?i)wait \\d+.*")) -> {
                    val ms = Regex("\\d+").find(lower)?.value?.toLongOrNull() ?: 500L
                    val multiplier = if (lower.contains("second")) 1000L else 1L
                    ProcedureStep.Sleep(ms * multiplier)
                }
                lower.contains("scroll down") || lower.contains("swipe down") ->
                    ProcedureStep.ScrollDown()
                lower.contains("scroll up") || lower.contains("swipe up") ->
                    ProcedureStep.ScrollUp()
                lower.contains("press back") || lower == "back" ->
                    ProcedureStep.Back()
                lower.contains("press home") || lower == "home" ->
                    ProcedureStep.Home()
                lower.startsWith("verify ") ->
                    ProcedureStep.Verify(line.substringAfter(" ").trim())
                lower.startsWith("assert package ") ->
                    ProcedureStep.AssertPackage(line.substringAfter("assert package ").trim())
                lower.startsWith("select ") && lower.contains(" in ") -> {
                    val parts = line.substringAfter("select ").split(" in ", ignoreCase = true)
                    if (parts.size == 2) ProcedureStep.SelectItem(parts[1].trim(), parts[0].trim())
                    else ProcedureStep.Tap(line.substringAfter("select ").trim())
                }
                lower.contains("dismiss") || lower.contains("allow") && lower.contains("dialog") ->
                    ProcedureStep.DismissDialog()
                else -> null
            }

            if (parsed != null) {
                steps.add(parsed)
                totalConfidence += 1f
            } else {
                warnings.add("Unparsed step: '$line' — using Tap fallback")
                steps.add(ProcedureStep.Tap(line))
                totalConfidence += 0.4f
            }
        }

        val confidence = if (lines.isEmpty()) 0f else totalConfidence / lines.size
        return CompileResult(steps, warnings, confidence)
    }

    fun compile(stepList: List<String>): CompileResult =
        compile(stepList.joinToString("\n"))

    fun decompile(steps: List<ProcedureStep>): String =
        steps.mapIndexed { i, step -> "${i + 1}. ${stepToText(step)}" }.joinToString("\n")

    private fun stepToText(step: ProcedureStep): String = when (step) {
        is ProcedureStep.LaunchApp -> "Open ${step.packageName}"
        is ProcedureStep.Tap -> "Tap '${step.target}'"
        is ProcedureStep.TypeText -> "Type \"${step.text}\" into ${step.target}"
        is ProcedureStep.WaitFor -> "Wait for '${step.target}'"
        is ProcedureStep.Sleep -> "Wait ${step.ms}ms"
        is ProcedureStep.ScrollDown -> "Scroll down"
        is ProcedureStep.ScrollUp -> "Scroll up"
        is ProcedureStep.Back -> "Press back"
        is ProcedureStep.Home -> "Press home"
        is ProcedureStep.Verify -> "Verify '${step.target}'"
        is ProcedureStep.AssertPackage -> "Assert package ${step.packageName}"
        is ProcedureStep.SelectItem -> "Select '${step.itemText}' in ${step.listTarget}"
        is ProcedureStep.TapCoord -> "Tap at (${step.x}, ${step.y})"
        is ProcedureStep.DismissDialog -> "Dismiss dialog"
        is ProcedureStep.ShareFile -> "Share file ${step.uriString}"
    }
}
