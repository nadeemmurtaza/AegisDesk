package com.newax.aegis.engine.dev.procedure

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.newax.aegis.accessibility.NewaxAccessibilityService
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.engine.grounding.ScreenGrounder
import com.newax.aegis.engine.procedure.ProcedureExecutor
import com.newax.aegis.engine.procedure.ProcedureStep
import kotlinx.coroutines.delay

data class StepDebugResult(
    val stepIndex: Int,
    val stepType: String,
    val stepDetail: String,
    val expectedTarget: String?,
    val groundingMatches: List<GroundingCandidate>,
    val bestConfidence: Float,
    val executed: Boolean,
    val success: Boolean,
    val failReason: String?,
    val durationMs: Long,
    val screenHashBefore: String,
    val screenHashAfter: String
)

data class GroundingCandidate(
    val text: String,
    val contentDesc: String,
    val className: String,
    val confidence: Float,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isEnabled: Boolean
)

data class ProcedureDebugSession(
    val procedureId: Long?,
    val totalSteps: Int,
    val results: List<StepDebugResult>,
    val overallSuccess: Boolean,
    val failedAtStep: Int?,
    val totalMs: Long
)

object ProcedureDebugger {

    suspend fun debugStep(
        step: ProcedureStep,
        stepIndex: Int,
        context: Context,
        execute: Boolean = false
    ): StepDebugResult {
        val svc = NewaxAccessibilityService.instance
        val root = svc?.getRootNode()
        val hashBefore = root?.let { hashTree(it) } ?: ""
        val start = System.currentTimeMillis()

        val target = extractTarget(step)
        val candidates = root?.let { findCandidates(it, target) } ?: emptyList()
        val bestConf = candidates.maxOfOrNull { it.confidence } ?: 0f

        var executed = false
        var success = false
        var failReason: String? = null

        if (execute && svc != null) {
            try {
                val fullResult = ProcedureExecutor.execute(listOf(step), context)
                executed = true
                success = fullResult.success
                failReason = fullResult.failReason
            } catch (e: Exception) {
                executed = true
                failReason = e.message
            }
        }

        delay(200)
        val hashAfter = svc?.getRootNode()?.let { hashTree(it) } ?: ""

        return StepDebugResult(
            stepIndex = stepIndex,
            stepType = step::class.simpleName ?: "Unknown",
            stepDetail = stepToString(step),
            expectedTarget = target,
            groundingMatches = candidates.take(5),
            bestConfidence = bestConf,
            executed = executed,
            success = success,
            failReason = failReason,
            durationMs = System.currentTimeMillis() - start,
            screenHashBefore = hashBefore,
            screenHashAfter = hashAfter
        )
    }

    suspend fun debugAll(
        steps: List<ProcedureStep>,
        context: Context,
        execute: Boolean = false,
        db: NewaxDatabase? = null,
        procedureId: Long? = null
    ): ProcedureDebugSession {
        val start = System.currentTimeMillis()
        val results = mutableListOf<StepDebugResult>()
        var failedAt: Int? = null

        for ((i, step) in steps.withIndex()) {
            val r = debugStep(step, i, context, execute)
            results.add(r)
            if (execute && !r.success && r.executed) {
                failedAt = i
                break
            }
        }

        return ProcedureDebugSession(
            procedureId = procedureId,
            totalSteps = steps.size,
            results = results,
            overallSuccess = failedAt == null,
            failedAtStep = failedAt,
            totalMs = System.currentTimeMillis() - start
        )
    }

    fun inspectCurrentScreen(): List<GroundingCandidate> {
        val root = NewaxAccessibilityService.instance?.getRootNode() ?: return emptyList()
        return collectAllNodes(root).map { node ->
            GroundingCandidate(
                text = node.text?.toString() ?: "",
                contentDesc = node.contentDescription?.toString() ?: "",
                className = node.className?.toString() ?: "",
                confidence = 0f,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isEnabled = node.isEnabled
            )
        }
    }

    fun groundTarget(target: String): List<GroundingCandidate> {
        val root = NewaxAccessibilityService.instance?.getRootNode() ?: return emptyList()
        return findCandidates(root, target)
    }

    private fun findCandidates(root: AccessibilityNodeInfo, target: String?): List<GroundingCandidate> {
        if (target.isNullOrBlank()) return collectAllNodes(root).map { toCandidateWithConf(it, "", 0f) }
        val result = ScreenGrounder.findTarget(root, target)
        val best = result?.node
        val bestConf = result?.confidence ?: 0f

        return collectAllNodes(root)
            .map { node ->
                val conf = if (node == best) bestConf else scoreSimple(node, target)
                toCandidateWithConf(node, target, conf)
            }
            .filter { it.confidence > 0.1f }
            .sortedByDescending { it.confidence }
    }

    private fun toCandidateWithConf(node: AccessibilityNodeInfo, target: String, conf: Float) =
        GroundingCandidate(
            text = node.text?.toString() ?: "",
            contentDesc = node.contentDescription?.toString() ?: "",
            className = node.className?.toString() ?: "",
            confidence = conf,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isEnabled = node.isEnabled
        )

    private fun scoreSimple(node: AccessibilityNodeInfo, target: String): Float {
        if (!node.isVisibleToUser) return 0f
        val t = target.lowercase()
        val texts = listOfNotNull(
            node.text?.toString()?.lowercase(),
            node.contentDescription?.toString()?.lowercase()
        )
        return texts.maxOfOrNull { text ->
            when {
                text == t -> 0.9f
                text.contains(t) -> 0.6f
                t.contains(text) && text.length > 2 -> 0.4f
                else -> 0f
            }
        } ?: 0f
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            list.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)
        return list
    }

    private fun hashTree(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun visit(n: AccessibilityNodeInfo?) {
            if (n == null) return
            sb.append(n.className).append(n.text).append(n.contentDescription)
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(node)
        return sb.toString().hashCode().toString()
    }

    private fun extractTarget(step: ProcedureStep): String? = when (step) {
        is ProcedureStep.Tap -> step.target
        is ProcedureStep.TypeText -> step.target
        is ProcedureStep.WaitFor -> step.target
        is ProcedureStep.Verify -> step.target
        is ProcedureStep.SelectItem -> "${step.itemText} in ${step.listTarget}"
        is ProcedureStep.LaunchApp -> step.packageName
        is ProcedureStep.AssertPackage -> step.packageName
        else -> null
    }

    private fun stepToString(step: ProcedureStep): String = when (step) {
        is ProcedureStep.LaunchApp -> "LaunchApp(${step.packageName})"
        is ProcedureStep.Tap -> "Tap(${step.target})"
        is ProcedureStep.TypeText -> "TypeText(${step.target}, \"${step.text}\")"
        is ProcedureStep.WaitFor -> "WaitFor(${step.target})"
        is ProcedureStep.Sleep -> "Sleep(${step.ms}ms)"
        is ProcedureStep.ScrollDown -> "ScrollDown(${step.maxSwipes})"
        is ProcedureStep.ScrollUp -> "ScrollUp(${step.maxSwipes})"
        is ProcedureStep.Back -> "Back(${step.count})"
        is ProcedureStep.Home -> "Home"
        is ProcedureStep.Verify -> "Verify(${step.target})"
        is ProcedureStep.AssertPackage -> "AssertPackage(${step.packageName})"
        is ProcedureStep.SelectItem -> "SelectItem(${step.itemText} in ${step.listTarget})"
        is ProcedureStep.TapCoord -> "TapCoord(${step.x}, ${step.y})"
        is ProcedureStep.DismissDialog -> "DismissDialog(${step.buttons})"
        is ProcedureStep.ShareFile -> "ShareFile(${step.uriString})"
    }
}
