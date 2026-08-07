package com.newax.aegis.engine.dev.replay

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.newax.aegis.accessibility.AegisAccessibilityService
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.dev.procedure.ProcedureDebugger
import com.newax.aegis.engine.dev.procedure.StepDebugResult
import com.newax.aegis.engine.procedure.ProcedureExecutor
import com.newax.aegis.engine.procedure.ProcedureStep
import com.newax.aegis.engine.procedure.StepSerializer
import kotlinx.coroutines.delay

data class ReplayFrame(
    val stepIndex: Int,
    val stepType: String,
    val screenshotPath: String?,
    val debugResult: StepDebugResult?,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ReplaySession(
    val procedureId: Long?,
    val steps: List<ProcedureStep>,
    val frames: List<ReplayFrame>,
    val overallSuccess: Boolean,
    val failedAtStep: Int?,
    val durationMs: Long,
    val screenshotDir: String?
)

data class DiffResult(
    val stepIndex: Int,
    val hashBefore: String,
    val hashAfter: String,
    val changed: Boolean,
    val insertedNodes: Int,
    val removedNodes: Int
)

object ProcedureReplayer {

    suspend fun replay(
        steps: List<ProcedureStep>,
        context: Context,
        db: AegisDatabase? = null,
        procedureId: Long? = null,
        captureScreenshots: Boolean = false,
        screenshotDir: String? = null
    ): ReplaySession {
        val start = System.currentTimeMillis()
        val frames = mutableListOf<ReplayFrame>()
        var failedAt: Int? = null
        var success = true

        for ((i, step) in steps.withIndex()) {
            val root = AegisAccessibilityService.instance?.getRootNode()
            val hashBefore = root?.let { hashTree(it) } ?: ""

            val debugResult = ProcedureDebugger.debugStep(step, i, context, execute = true)
            frames.add(ReplayFrame(
                stepIndex = i,
                stepType = step::class.simpleName ?: "Unknown",
                screenshotPath = null,
                debugResult = debugResult
            ))

            if (!debugResult.success && debugResult.executed) {
                failedAt = i
                success = false
                break
            }
            delay(100)
        }

        return ReplaySession(
            procedureId = procedureId,
            steps = steps,
            frames = frames,
            overallSuccess = success,
            failedAtStep = failedAt,
            durationMs = System.currentTimeMillis() - start,
            screenshotDir = screenshotDir
        )
    }

    suspend fun replayFromDb(procedureId: Long, context: Context, db: AegisDatabase): ReplaySession {
        val stepsJson = try {
            val cursor = db.openHelper.readableDatabase.rawQuery(
                "SELECT stepsJson FROM procedures WHERE id = ?", arrayOf(procedureId.toString())
            )
            cursor.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }

        val steps = stepsJson?.let { runCatching { StepSerializer.deserialize(it) }.getOrDefault(emptyList()) } ?: emptyList()
        return replay(steps, context, db, procedureId)
    }

    fun diffScreens(rootA: AccessibilityNodeInfo?, rootB: AccessibilityNodeInfo?, stepIndex: Int): DiffResult {
        val hashA = rootA?.let { hashTree(it) } ?: ""
        val hashB = rootB?.let { hashTree(it) } ?: ""
        val nodesA = rootA?.let { countNodes(it) } ?: 0
        val nodesB = rootB?.let { countNodes(it) } ?: 0
        return DiffResult(
            stepIndex = stepIndex,
            hashBefore = hashA,
            hashAfter = hashB,
            changed = hashA != hashB,
            insertedNodes = maxOf(0, nodesB - nodesA),
            removedNodes = maxOf(0, nodesA - nodesB)
        )
    }

    fun formatReplay(session: ReplaySession): String = buildString {
        append("Replay: procedure=${session.procedureId} steps=${session.steps.size}\n")
        append("Result: ${if (session.overallSuccess) "PASS" else "FAIL at step ${session.failedAtStep}"} (${session.durationMs}ms)\n")
        session.frames.forEach { f ->
            val r = f.debugResult
            val status = if (r == null) "?" else if (r.success) "✓" else "✗"
            append("  [$status] Step ${f.stepIndex}: ${f.stepType}")
            if (r?.failReason != null) append(" → ${r.failReason}")
            append(" (${r?.durationMs ?: 0}ms)\n")
        }
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

    private fun countNodes(node: AccessibilityNodeInfo): Int {
        var count = 1
        for (i in 0 until node.childCount) count += countNodes(node.getChild(i) ?: continue)
        return count
    }
}
