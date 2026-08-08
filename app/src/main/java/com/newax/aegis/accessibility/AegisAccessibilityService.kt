package com.newax.aegis.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.newax.aegis.assistant.ProposedAction

class AegisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AegisAccessibilityService? = null
            private set
    }

    /** Last known foreground package — updated on every WINDOW_STATE_CHANGED event. */
    @Volatile var currentPackage: String = ""
        private set

    val chatHistory = java.util.Collections.synchronizedList(mutableListOf<String>())

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            currentPackage = pkg
            com.newax.aegis.engine.HabitTracker.logAppOpen(pkg)
            com.newax.aegis.engine.trigger.TriggerEngine.onWindowChanged(pkg)
        }
    }

    override fun onInterrupt() = Unit
    override fun onDestroy() { instance = null; super.onDestroy() }

    /**
     * Returns a structured, AI-ready screen summary with:
     *  - Typed input fields (password fields never expose content)
     *  - Button labels, checkboxes, image descriptions
     *  - Sensitive info detection (redacted output only)
     *  - Document classification and tone analysis
     *  - Optional context correlation with memory/log/graph
     */
    fun screenSummary(
        memory: com.newax.aegis.memory.EncryptedMemory? = null,
        commLog: com.newax.aegis.engine.CommunicationLog? = null,
        graph: com.newax.aegis.engine.KnowledgeGraph? = null,
        projects: com.newax.aegis.engine.ProjectTracker? = null
    ): String {
        val summary = com.newax.aegis.engine.ScreenAnalyzer.analyze(
            root = rootInActiveWindow,
            packageName = currentPackage.ifBlank { "unknown" },
            memory = memory,
            commLog = commLog,
            graph = graph,
            projects = projects
        )

        // Maintain chatHistory with safe (non-sensitive) text blocks for context
        synchronized(chatHistory) {
            summary.textBlocks.forEach { block ->
                if (block !in chatHistory) {
                    chatHistory += block
                    if (chatHistory.size > 50) chatHistory.removeAt(0)
                }
            }
        }

        val historyStr = synchronized(chatHistory) { chatHistory.takeLast(10).joinToString("\n") }
        return "Recent Context:\n$historyStr\n\n" + summary.formattedSummary
    }

    /** Returns the raw ScreenSummary object for callers that need structured data. */
    fun screenSummaryStructured(
        memory: com.newax.aegis.memory.EncryptedMemory? = null,
        commLog: com.newax.aegis.engine.CommunicationLog? = null,
        graph: com.newax.aegis.engine.KnowledgeGraph? = null,
        projects: com.newax.aegis.engine.ProjectTracker? = null
    ): com.newax.aegis.engine.ScreenAnalyzer.ScreenSummary =
        com.newax.aegis.engine.ScreenAnalyzer.analyze(
            root = rootInActiveWindow,
            packageName = currentPackage.ifBlank { "unknown" },
            memory = memory, commLog = commLog, graph = graph, projects = projects
        )

    fun execute(action: ProposedAction): Boolean = when (action) {
        is ProposedAction.TapPixels -> {
            val path = android.graphics.Path().apply { moveTo(action.x, action.y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
            true
        }
        is ProposedAction.Tap -> findByText(action.label)?.clickUpTree() == true
        is ProposedAction.Type -> {
            if (action.text.startsWith("FINALIZE_POST|")) {
                val altTag = action.text.substringAfter("FINALIZE_POST|")
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                
                // Wait 2 seconds for the Share Intent UI to fully load
                handler.postDelayed({
                    // Step 1: Scan for Alt text button dynamically
                    val altBtn = findByText("Alt text") ?: findByText("Edit Image") ?: findByDescription("Alt text")
                    if (altBtn != null && altTag.isNotBlank() && altTag != "null") {
                        altBtn.clickUpTree()
                        handler.postDelayed({
                            focusedEditable()?.setNodeText(altTag)
                            (findByText("Save") ?: findByText("Done"))?.clickUpTree()
                            
                            // Step 2: Post
                            handler.postDelayed({
                                (findByText("Post") ?: findByText("Publish") ?: findByText("Share"))?.clickUpTree()
                            }, 1000)
                        }, 1000)
                    } else {
                        // Just Post
                        (findByText("Post") ?: findByText("Publish") ?: findByText("Share"))?.clickUpTree()
                    }
                }, 2000)
                true
            } else {
                focusedEditable()?.setNodeText(action.text) == true
            }
        }
        is ProposedAction.Send -> {
            val editable = focusedEditable() ?: firstEditable()
            editable?.setNodeText(action.text) == true &&
                (findByText("Send") ?: findByDescription("Send"))?.clickUpTree() == true
        }
        is ProposedAction.SendImage -> false
        is ProposedAction.OpenApp -> openApp(action.name)
        is ProposedAction.Scroll -> scroll(action.forward)
        is ProposedAction.ToggleConnectivity -> {
            // Wake Screen safely. There is no non-deprecated PowerManager flag to wake the
            // screen from a background Service (the API 33+ replacement, Activity.setTurnScreenOn,
            // only applies to a foreground Activity), so this stays suppressed rather than dropped.
            @Suppress("DEPRECATION")
            val wakeLockFlags = android.os.PowerManager.PARTIAL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val wakeLock = pm.newWakeLock(wakeLockFlags, "Aegis:ConnectivityToggle")
            wakeLock.acquire(3000)
            
            // Open Quick Settings
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            
            // Give UI a moment to open, then tap toggles
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                findByText("Wi-Fi")?.clickUpTree()
                findByText("Bluetooth")?.clickUpTree()
                findByText("Mobile data")?.clickUpTree()
                
                // Close Quick Settings
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 1000)
            true
        }
        is ProposedAction.QueryCalendar -> false
        is ProposedAction.CreateEvent -> false
        is ProposedAction.UpdateMemory -> false
        ProposedAction.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        ProposedAction.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        ProposedAction.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        else -> false // Handle all the background-only actions like AuditSecurity, PostSocialMedia etc.
    }

    // ── Public direct-execution API (used by ProcedureExecutor) ──────────────

    fun getRootNode(): android.view.accessibility.AccessibilityNodeInfo? = rootInActiveWindow

    fun tapNode(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        var n: android.view.accessibility.AccessibilityNodeInfo? = node
        repeat(5) {
            if (n?.isClickable == true) return n!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            n = n?.parent
        }
        return false
    }

    fun typeIntoNode(node: android.view.accessibility.AccessibilityNodeInfo, text: String, clear: Boolean = true): Boolean {
        if (clear) {
            val sel = android.os.Bundle().apply {
                putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
            }
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun scrollForward(node: android.view.accessibility.AccessibilityNodeInfo? = null): Boolean {
        val target = node ?: findScrollable(rootInActiveWindow) ?: return false
        return target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollBackward(node: android.view.accessibility.AccessibilityNodeInfo? = null): Boolean {
        val target = node ?: findScrollable(rootInActiveWindow) ?: return false
        return target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun publicFindByText(text: String): android.view.accessibility.AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    fun publicFindByDescription(desc: String): android.view.accessibility.AccessibilityNodeInfo? =
        findByDescription(desc)

    private fun findScrollable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    private fun openApp(name: String): Boolean {
        val apps = packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
        )
        val match = apps.firstOrNull {
            it.loadLabel(packageManager).toString().equals(name, true) ||
                it.activityInfo.packageName.substringAfterLast('.').equals(name.replace(" ", ""), true)
        } ?: return false
        val intent = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    private fun scroll(forward: Boolean): Boolean {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        val node = visit(rootInActiveWindow) ?: return false
        return node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun findByText(text: String): AccessibilityNodeInfo? =
        rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()

    private fun findByDescription(description: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString()?.equals(description, true) == true) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(rootInActiveWindow)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private fun firstEditable(): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(rootInActiveWindow)
    }

    private fun AccessibilityNodeInfo.setNodeText(value: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun AccessibilityNodeInfo.clickUpTree(): Boolean {
        var node: AccessibilityNodeInfo? = this
        repeat(5) {
            if (node?.isClickable == true) return node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node?.parent
        }
        return false
    }
}
