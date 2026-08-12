package com.newax.aegis.engine.dev.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.newax.aegis.accessibility.NewaxAccessibilityService
import com.newax.aegis.engine.grounding.ScreenGrounder
import java.util.concurrent.CopyOnWriteArrayList

data class NodeInfo(
    val className: String,
    val text: String,
    val contentDesc: String,
    val hint: String,
    val viewId: String,
    val depth: Int,
    val childCount: Int,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isEnabled: Boolean,
    val isFocused: Boolean,
    val isVisibleToUser: Boolean,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val actions: List<String>
)

data class ScreenSignature(
    val packageName: String,
    val nodeCount: Int,
    val interactiveCount: Int,
    val treeHash: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class FocusChain(
    val nodes: List<NodeInfo>,
    val focusedIndex: Int
)

data class GroundingScoreResult(
    val target: String,
    val candidates: List<ScoredNode>
)

data class ScoredNode(
    val text: String,
    val contentDesc: String,
    val className: String,
    val score: Float,
    val bounds: String
)

object AccessibilityInspector {

    private const val EVENT_BUFFER_MAX = 200
    private val eventBuffer = CopyOnWriteArrayList<AccessibilityEventRecord>()

    data class AccessibilityEventRecord(
        val timestampMs: Long,
        val eventType: String,
        val packageName: String,
        val className: String,
        val text: String
    )

    fun dumpNodeTree(maxDepth: Int = 20): String {
        val root = NewaxAccessibilityService.instance?.getRootNode()
            ?: return "Accessibility service not running"
        return buildString {
            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > maxDepth) return
                val indent = "  ".repeat(depth)
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
                val txt = node.text?.toString()?.take(40) ?: ""
                val desc = node.contentDescription?.toString()?.take(40) ?: ""
                val label = txt.ifBlank { desc }.ifBlank { cls }
                val flags = buildString {
                    if (node.isClickable) append("C")
                    if (node.isEditable) append("E")
                    if (node.isScrollable) append("S")
                    if (node.isFocused) append("F")
                    if (!node.isEnabled) append("X")
                }
                append("$indent[$flags] $cls: $label\n")
                for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
            }
            visit(root, 0)
        }
    }

    fun flatNodes(): List<NodeInfo> {
        val root = NewaxAccessibilityService.instance?.getRootNode() ?: return emptyList()
        val result = mutableListOf<NodeInfo>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val actions = mutableListOf<String>()
            for (action in node.actionList) {
                actions.add(action.label?.toString() ?: "action:${action.id}")
            }
            result.add(NodeInfo(
                className = node.className?.toString() ?: "",
                text = node.text?.toString() ?: "",
                contentDesc = node.contentDescription?.toString() ?: "",
                hint = node.hintText?.toString() ?: "",
                viewId = node.viewIdResourceName ?: "",
                depth = depth,
                childCount = node.childCount,
                isClickable = node.isClickable,
                isLongClickable = node.isLongClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isCheckable = node.isCheckable,
                isChecked = node.isChecked,
                isEnabled = node.isEnabled,
                isFocused = node.isFocused,
                isVisibleToUser = node.isVisibleToUser,
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
                actions = actions
            ))
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        return result
    }

    fun signature(): ScreenSignature {
        val svc = NewaxAccessibilityService.instance
        val root = svc?.getRootNode()
        if (root == null) return ScreenSignature(svc?.currentPackage ?: "", 0, 0, "")
        var count = 0
        var interactive = 0
        val sb = StringBuilder()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            count++
            if (node.isClickable || node.isEditable) interactive++
            sb.append(node.className).append(node.text).append(node.contentDescription)
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)
        return ScreenSignature(
            packageName = svc.currentPackage,
            nodeCount = count,
            interactiveCount = interactive,
            treeHash = sb.hashCode().toString()
        )
    }

    fun focusChain(): FocusChain {
        val nodes = flatNodes()
        val focusedIdx = nodes.indexOfFirst { it.isFocused }
        return FocusChain(nodes.filter { it.isFocused || it.isClickable || it.isEditable }, focusedIdx)
    }

    fun scoreGrounding(target: String): GroundingScoreResult {
        val root = NewaxAccessibilityService.instance?.getRootNode()
            ?: return GroundingScoreResult(target, emptyList())
        val best = ScreenGrounder.findTarget(root, target)
        val nodes = flatNodes()
        val scored = nodes.map { n ->
            val isMatch = n.text == (best?.matchedText ?: "") || n.contentDesc == (best?.matchedText ?: "")
            ScoredNode(
                text = n.text,
                contentDesc = n.contentDesc,
                className = n.className,
                score = if (isMatch) best!!.confidence else 0f,
                bounds = "${n.boundsLeft},${n.boundsTop}-${n.boundsRight},${n.boundsBottom}"
            )
        }.filter { it.score > 0f }.sortedByDescending { it.score }
        return GroundingScoreResult(target, scored)
    }

    fun recordEvent(event: AccessibilityEvent) {
        val record = AccessibilityEventRecord(
            timestampMs = System.currentTimeMillis(),
            eventType = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString() ?: "",
            className = event.className?.toString() ?: "",
            text = event.text.joinToString(", ") { it.toString() }.take(100)
        )
        eventBuffer.add(record)
        if (eventBuffer.size > EVENT_BUFFER_MAX) eventBuffer.removeAt(0)
    }

    fun recentEvents(n: Int = 50): List<AccessibilityEventRecord> =
        eventBuffer.takeLast(n)

    fun interactiveNodes(): List<NodeInfo> =
        flatNodes().filter { it.isClickable || it.isEditable || it.isScrollable }

    fun actionAvailability(): Map<String, List<String>> =
        flatNodes().filter { it.actions.isNotEmpty() }
            .associate { "${it.className}:${it.text.take(20)}" to it.actions }
}
