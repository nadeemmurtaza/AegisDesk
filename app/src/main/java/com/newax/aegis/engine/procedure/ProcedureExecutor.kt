package com.newax.aegis.engine.procedure

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.newax.aegis.accessibility.AegisAccessibilityService
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.grounding.ScreenGrounder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

object ProcedureExecutor {

    data class ExecutionResult(
        val success: Boolean,
        val stepsCompleted: Int,
        val totalSteps: Int,
        val failedStep: Int? = null,
        val failReason: String? = null
    )

    private const val SETTLE_POLL_MS   = 250L
    private const val SETTLE_STABLE_MS = 600L
    private const val STEP_TIMEOUT_MS  = 6000L

    suspend fun execute(
        steps: List<ProcedureStep>,
        context: Context,
        db: AegisDatabase? = null,
        procedureId: Long? = null
    ): ExecutionResult {
        val svc = AegisAccessibilityService.instance
            ?: return ExecutionResult(false, 0, steps.size, 0, "Accessibility service not running")

        var completed = 0
        for ((idx, step) in steps.withIndex()) {
            if (!coroutineContext.isActive) break
            val currentPkg = svc.currentPackage
            if (ExecutionGuard.check(context, currentPkg) == ExecutionGuard.GuardResult.BLOCKED) {
                return ExecutionResult(false, completed, steps.size, idx, "Blocked: protected package $currentPkg")
            }
            val ok = executeStep(step, svc, context)
            if (!ok) {
                val reason = "Step ${idx + 1} failed: ${step::class.simpleName}"
                return ExecutionResult(false, completed, steps.size, idx, reason)
            }
            completed++
            // Screen settle after every mutating step
            if (step !is ProcedureStep.Sleep && step !is ProcedureStep.Verify && step !is ProcedureStep.AssertPackage) {
                waitSettle(svc)
                tryDismissBlockingDialog(svc)
            }
        }
        return ExecutionResult(true, completed, steps.size)
    }

    suspend fun executeFromJson(
        stepsJson: String,
        context: Context,
        db: AegisDatabase? = null,
        procedureId: Long? = null
    ): ExecutionResult = execute(StepSerializer.deserialize(stepsJson), context, db, procedureId)

    // ── Step execution ────────────────────────────────────────────────────────

    private suspend fun executeStep(step: ProcedureStep, svc: AegisAccessibilityService, context: Context): Boolean {
        return when (step) {

            is ProcedureStep.Tap -> {
                val root = svc.getRootNode()
                val grounded = root?.let { ScreenGrounder.findTarget(it, step.target) }
                if (grounded != null && grounded.confidence >= 0.4f) {
                    svc.tapNode(grounded.node)
                } else if (step.fallbackX >= 0f) {
                    svc.tapAt(step.fallbackX, step.fallbackY)
                } else {
                    // Last resort: text search
                    svc.publicFindByText(step.target)?.let { svc.tapNode(it) } ?: false
                }
            }

            is ProcedureStep.TypeText -> {
                val root = svc.getRootNode() ?: return false
                val grounded = ScreenGrounder.findTarget(root, step.target)
                val node = grounded?.node
                    ?: root.findAccessibilityNodeInfosByText(step.target)?.firstOrNull()
                    ?: findFirstEditable(root)
                    ?: return false
                svc.tapNode(node)
                delay(200)
                svc.typeIntoNode(node, step.text, step.clearFirst)
            }

            is ProcedureStep.WaitFor -> {
                val deadline = System.currentTimeMillis() + step.timeoutMs
                var found = false
                while (System.currentTimeMillis() < deadline && !found) {
                    val root = svc.getRootNode()
                    if (root != null) {
                        val g = ScreenGrounder.findTarget(root, step.target)
                        if (g != null && g.confidence >= 0.4f) { found = true; break }
                        if (root.findAccessibilityNodeInfosByText(step.target)?.isNotEmpty() == true) { found = true; break }
                    }
                    delay(SETTLE_POLL_MS)
                }
                found
            }

            is ProcedureStep.Verify -> {
                val root = svc.getRootNode() ?: return !step.abortIfMissing
                val g = ScreenGrounder.findTarget(root, step.target)
                val found = g != null && g.confidence >= 0.4f
                    || root.findAccessibilityNodeInfosByText(step.target)?.isNotEmpty() == true
                if (!found && step.abortIfMissing) false else true
            }

            is ProcedureStep.ScrollDown -> {
                var ok = false
                repeat(step.maxSwipes) { ok = svc.scrollForward(); delay(400) }
                ok
            }

            is ProcedureStep.ScrollUp -> {
                var ok = false
                repeat(step.maxSwipes) { ok = svc.scrollBackward(); delay(400) }
                ok
            }

            is ProcedureStep.Back -> {
                repeat(step.count) { svc.globalBack(); delay(300) }
                true
            }

            is ProcedureStep.Home -> { svc.globalHome(); true }

            is ProcedureStep.Sleep -> { delay(step.ms); true }

            is ProcedureStep.LaunchApp -> {
                val i = context.packageManager.getLaunchIntentForPackage(step.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    ?: return false
                context.startActivity(i)
                delay(1500)
                true
            }

            is ProcedureStep.SelectItem -> {
                val root = svc.getRootNode() ?: return false
                // Find list then search for item within it
                val listNode = ScreenGrounder.findTarget(root, step.listTarget)?.node
                val searchRoot = listNode ?: root
                val itemNode = ScreenGrounder.findTarget(searchRoot, step.itemText)?.node
                    ?: searchRoot.findAccessibilityNodeInfosByText(step.itemText)?.firstOrNull()
                    ?: return false
                svc.tapNode(itemNode)
            }

            is ProcedureStep.TapCoord -> svc.tapAt(step.x, step.y)

            is ProcedureStep.DismissDialog -> {
                var dismissed = false
                val root = svc.getRootNode() ?: return true
                for (label in step.buttons) {
                    val node = root.findAccessibilityNodeInfosByText(label)?.firstOrNull() ?: continue
                    if (svc.tapNode(node)) { dismissed = true; delay(400); break }
                }
                dismissed // non-fatal if not found (dialog may not be present)
            }

            is ProcedureStep.AssertPackage -> {
                svc.currentPackage == step.packageName
            }

            is ProcedureStep.ShareFile -> {
                val uri = Uri.parse(step.uriString)
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = step.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(i, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun waitSettle(svc: AegisAccessibilityService) {
        var prev = ""
        var stableFor = 0L
        val deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val hash = svc.getRootNode()?.let { hashTree(it) } ?: ""
            if (hash == prev && hash.isNotEmpty()) {
                stableFor += SETTLE_POLL_MS
                if (stableFor >= SETTLE_STABLE_MS) return
            } else {
                stableFor = 0L
            }
            prev = hash
            delay(SETTLE_POLL_MS)
        }
    }

    private suspend fun tryDismissBlockingDialog(svc: AegisAccessibilityService) {
        val root = svc.getRootNode() ?: return
        val dialogs = listOf("Allow", "OK", "Continue", "Skip", "Dismiss", "Accept", "Got it")
        for (label in dialogs) {
            val node = root.findAccessibilityNodeInfosByText(label)?.firstOrNull() ?: continue
            // Only dismiss if it looks like a blocking dialog (node parent is a dialog class)
            var parent = node.parent
            var isDialog = false
            repeat(4) {
                val cls = parent?.className?.toString() ?: ""
                if (cls.contains("Dialog") || cls.contains("AlertDialog") || cls.contains("BottomSheet")) {
                    isDialog = true
                }
                parent = parent?.parent
            }
            if (isDialog) { svc.tapNode(node); delay(300); return }
        }
    }

    private fun hashTree(node: android.view.accessibility.AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun visit(n: android.view.accessibility.AccessibilityNodeInfo?) {
            if (n == null) return
            sb.append(n.className).append(n.text).append(n.contentDescription)
            for (i in 0 until n.childCount) visit(n.getChild(i))
        }
        visit(node)
        return sb.toString().hashCode().toString()
    }

    private fun findFirstEditable(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            findFirstEditable(root.getChild(i) ?: continue)?.let { return it }
        }
        return null
    }
}
