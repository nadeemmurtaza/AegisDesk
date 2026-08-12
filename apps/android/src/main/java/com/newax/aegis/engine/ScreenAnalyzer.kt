package com.newax.aegis.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.newax.aegis.memory.EncryptedMemory

/**
 * Structured screen analysis engine. Replaces the flat text-dump approach in
 * NewaxAccessibilityService with node-type-aware parsing, sensitive field detection,
 * context correlation, and document classification.
 */
object ScreenAnalyzer {

    enum class NodeRole {
        INPUT_TEXT, INPUT_PASSWORD, INPUT_EMAIL, INPUT_PHONE, INPUT_NUMBER, INPUT_OTP,
        BUTTON, CHECKBOX, SWITCH, RADIO, IMAGE, LIST_ITEM, HEADER, LABEL,
        LINK, WEB_CONTENT, SCROLLABLE_CONTAINER, UNKNOWN
    }

    data class ScreenNode(
        val role: NodeRole,
        val text: String,             // safe to log (passwords are masked)
        val hint: String,             // placeholder/hint text
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isFocused: Boolean,
        val isChecked: Boolean,
        val isPassword: Boolean,      // true = actual content never logged
        val contentDesc: String,
        val className: String
    )

    data class InputField(
        val role: NodeRole,
        val hint: String,
        val hasContent: Boolean,      // true but value not shown for sensitive fields
        val isSensitive: Boolean,
        val fieldType: String         // "email", "otp", "password", "username", "search", "text", etc.
    )

    data class ScreenSummary(
        val appPackage: String,
        val screenTitle: String,
        val screenContext: String,    // e.g. "Login screen", "Payment form", "Chat window"
        val inputFields: List<InputField>,
        val buttons: List<String>,
        val textBlocks: List<String>,
        val images: List<String>,     // content descriptions of image nodes
        val checkboxes: List<Pair<String, Boolean>>,  // label to checked state
        val sensitiveDetection: String,               // from SensitiveInfoDetector (redacted)
        val documentClass: String,                    // from DocumentClassifier
        val toneProfile: String,                      // from ToneAnalyzer on visible text
        val contextCorrelation: String,               // brief context from memory/log/graph
        val formattedSummary: String                  // full AI-ready string
    )

    private const val MAX_NODES = 200
    private const val MAX_TEXT_LEN = 120

    fun analyze(
        root: AccessibilityNodeInfo?,
        packageName: String,
        memory: EncryptedMemory? = null,
        commLog: CommunicationLog? = null,
        graph: KnowledgeGraph? = null,
        projects: ProjectTracker? = null
    ): ScreenSummary {
        val nodes = mutableListOf<ScreenNode>()
        visitTree(root, nodes, 0)

        val inputFields = extractInputFields(nodes)
        val buttons = nodes.filter { it.role == NodeRole.BUTTON && it.text.isNotBlank() }
            .map { it.text }.distinct().take(15)
        val textBlocks = nodes.filter { it.role in textRoles && it.text.isNotBlank() }
            .map { it.text.take(MAX_TEXT_LEN) }.distinct().take(30)
        val images = nodes.filter { it.role == NodeRole.IMAGE && it.contentDesc.isNotBlank() }
            .map { it.contentDesc }.distinct().take(10)
        val checkboxes = nodes.filter { it.role in setOf(NodeRole.CHECKBOX, NodeRole.SWITCH, NodeRole.RADIO) }
            .map { Pair(it.text.ifBlank { it.contentDesc }, it.isChecked) }.take(10)

        // Screen title: first HEADER or first non-button text
        val screenTitle = nodes.firstOrNull { it.role == NodeRole.HEADER }?.text
            ?: nodes.firstOrNull { it.role == NodeRole.LABEL && it.text.length > 3 }?.text
            ?: ""

        // Combine non-sensitive visible text for analysis
        val visibleText = (listOf(screenTitle) + textBlocks +
            inputFields.filter { !it.isSensitive }.map { it.hint }).joinToString(" ")

        // Sensitive info detection on all visible (non-password) text
        val sensitiveResult = SensitiveInfoDetector.analyze(visibleText)
        val sensitiveStr = SensitiveInfoDetector.summary(sensitiveResult)

        // Document classification
        val docResult = DocumentClassifier.classify(visibleText)

        // Tone analysis
        val tone = ToneAnalyzer.analyze(visibleText)

        // Screen context type
        val screenContext = inferScreenContext(packageName, inputFields, buttons, screenTitle, visibleText)

        // Context correlation (optional – only if engines are passed)
        val contextStr = if (memory != null && commLog != null && graph != null && projects != null) {
            val packet = ContextCorrelator.buildContext(visibleText, memory, commLog, graph, projects)
            if (packet.entities.names.isNotEmpty() || packet.entities.topics.isNotEmpty())
                packet.combinedSummary.take(600)
            else ""
        } else ""

        val formatted = buildFormattedSummary(
            packageName, screenTitle, screenContext, inputFields, buttons,
            textBlocks, images, checkboxes, sensitiveStr, docResult.summary,
            tone.summary, contextStr
        )

        return ScreenSummary(
            appPackage = packageName,
            screenTitle = screenTitle,
            screenContext = screenContext,
            inputFields = inputFields,
            buttons = buttons,
            textBlocks = textBlocks,
            images = images,
            checkboxes = checkboxes,
            sensitiveDetection = sensitiveStr,
            documentClass = docResult.summary,
            toneProfile = tone.summary,
            contextCorrelation = contextStr,
            formattedSummary = formatted
        )
    }

    // --- Node tree traversal ---

    private fun visitTree(node: AccessibilityNodeInfo?, acc: MutableList<ScreenNode>, depth: Int) {
        if (node == null || acc.size >= MAX_NODES || depth > 25) return

        val role = classifyNode(node)
        val rawText = node.text?.toString()?.trim() ?: ""
        val hint = node.hintText?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val isPass = node.isPassword

        // Never expose password content — just mark that a value is present
        val safeText = when {
            isPass -> if (rawText.isNotEmpty()) "[password entered]" else ""
            rawText.length > MAX_TEXT_LEN -> rawText.take(MAX_TEXT_LEN) + "…"
            else -> rawText
        }

        if (safeText.isNotBlank() || hint.isNotBlank() || desc.isNotBlank() || role != NodeRole.UNKNOWN) {
            acc += ScreenNode(
                role = role,
                text = safeText,
                hint = hint,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isFocused = node.isFocused,
                isChecked = node.isChecked,
                isPassword = isPass,
                contentDesc = desc,
                className = node.className?.toString() ?: ""
            )
        }

        for (i in 0 until node.childCount) visitTree(node.getChild(i), acc, depth + 1)
    }

    private fun classifyNode(node: AccessibilityNodeInfo): NodeRole {
        val cls = node.className?.toString() ?: ""
        val hint = (node.hintText?.toString() ?: "").lowercase()
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()

        return when {
            node.isPassword -> NodeRole.INPUT_PASSWORD
            node.isEditable -> when {
                isOtpField(hint, text, desc, cls) -> NodeRole.INPUT_OTP
                "email" in hint || "email" in desc -> NodeRole.INPUT_EMAIL
                "phone" in hint || "mobile" in hint || "number" in hint -> NodeRole.INPUT_PHONE
                cls.contains("NumberEdit") || node.inputType.and(0xF) == 2 -> NodeRole.INPUT_NUMBER
                else -> NodeRole.INPUT_TEXT
            }
            cls.contains("Button") || node.isClickable && (cls.contains("View") && node.childCount == 0) ->
                NodeRole.BUTTON
            cls.contains("CheckBox") -> NodeRole.CHECKBOX
            cls.contains("Switch") -> NodeRole.SWITCH
            cls.contains("RadioButton") -> NodeRole.RADIO
            cls.contains("ImageView") || cls.contains("ImageButton") -> NodeRole.IMAGE
            cls.contains("TextView") && (text.length < 60 && node.parent?.className?.contains("ToolBar") == true) ->
                NodeRole.HEADER
            cls.contains("RecyclerView") || cls.contains("ListView") || cls.contains("GridView") ->
                NodeRole.SCROLLABLE_CONTAINER
            cls.contains("WebView") -> NodeRole.WEB_CONTENT
            cls.contains("TextView") -> NodeRole.LABEL
            else -> NodeRole.UNKNOWN
        }
    }

    private fun isOtpField(hint: String, text: String, desc: String, cls: String): Boolean {
        val combined = "$hint $desc $cls"
        return "otp" in combined || "one time" in combined || "verification" in combined ||
               "passcode" in combined || "digit" in combined ||
               (text.length in 4..8 && text.all { it.isDigit() })
    }

    // --- Field extraction ---

    private fun extractInputFields(nodes: List<ScreenNode>): List<InputField> {
        return nodes.filter { it.role in inputRoles }.map { node ->
            val fieldType = when (node.role) {
                NodeRole.INPUT_PASSWORD -> "password"
                NodeRole.INPUT_OTP -> "otp"
                NodeRole.INPUT_EMAIL -> "email"
                NodeRole.INPUT_PHONE -> "phone"
                NodeRole.INPUT_NUMBER -> "number"
                else -> inferTextFieldType(node.hint.lowercase() + " " + node.contentDesc.lowercase())
            }
            val isSensitive = node.isPassword ||
                fieldType in setOf("password", "otp", "cvv", "pin", "card_number", "account_number")
            InputField(
                role = node.role,
                hint = node.hint.ifBlank { node.contentDesc }.ifBlank { fieldType },
                hasContent = node.text.isNotBlank(),
                isSensitive = isSensitive,
                fieldType = fieldType
            )
        }
    }

    private fun inferTextFieldType(combined: String): String = when {
        "username" in combined || "user name" in combined -> "username"
        "email" in combined -> "email"
        "phone" in combined || "mobile" in combined -> "phone"
        "search" in combined -> "search"
        "pin" in combined -> "pin"
        "cvv" in combined || "cvc" in combined -> "cvv"
        "card" in combined -> "card_number"
        "account" in combined -> "account_number"
        "address" in combined -> "address"
        "name" in combined -> "name"
        "message" in combined || "comment" in combined || "caption" in combined -> "message"
        else -> "text"
    }

    // --- Screen context inference ---

    private fun inferScreenContext(
        pkg: String,
        fields: List<InputField>,
        buttons: List<String>,
        title: String,
        text: String
    ): String {
        val hasPassword = fields.any { it.fieldType == "password" }
        val hasOtp = fields.any { it.fieldType == "otp" }
        val hasCard = fields.any { it.fieldType == "card_number" }
        val allText = (title + " " + text + " " + buttons.joinToString()).lowercase()

        return when {
            hasOtp -> "OTP / Verification screen"
            hasPassword && fields.any { it.fieldType in setOf("username", "email") } -> "Login / Sign-in screen"
            hasCard -> "Payment / Checkout screen"
            "register" in allText || "sign up" in allText || "create account" in allText -> "Registration screen"
            "forgot" in allText && "password" in allText -> "Password reset screen"
            buttons.any { it.lowercase() in setOf("send", "reply", "message") } -> "Messaging / Chat screen"
            "settings" in allText && title.lowercase().contains("setting") -> "Settings screen"
            pkg.contains("browser") || pkg.contains("chrome") || pkg.contains("firefox") -> "Browser"
            pkg.contains("camera") -> "Camera"
            else -> "General screen"
        }
    }

    // --- Formatted summary ---

    private fun buildFormattedSummary(
        pkg: String, title: String, context: String,
        fields: List<InputField>, buttons: List<String>,
        textBlocks: List<String>, images: List<String>,
        checkboxes: List<Pair<String, Boolean>>,
        sensitiveStr: String, docClass: String,
        tone: String, correlation: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("APP: $pkg")
        if (title.isNotBlank()) sb.appendLine("SCREEN TITLE: $title")
        sb.appendLine("CONTEXT: $context")
        sb.appendLine()

        if (fields.isNotEmpty()) {
            sb.appendLine("INPUT FIELDS (${fields.size}):")
            fields.forEach { f ->
                val status = when {
                    f.isSensitive && f.hasContent -> "filled [HIDDEN]"
                    f.isSensitive -> "empty [SENSITIVE]"
                    f.hasContent  -> "filled"
                    else          -> "empty"
                }
                sb.appendLine("  • [${f.fieldType}] \"${f.hint}\" — $status")
            }
            sb.appendLine()
        }

        if (buttons.isNotEmpty()) {
            sb.appendLine("BUTTONS: ${buttons.joinToString(", ")}")
        }

        if (checkboxes.isNotEmpty()) {
            sb.appendLine("TOGGLES: ${checkboxes.joinToString(", ") { "${it.first}=${if (it.second) "ON" else "OFF" }"}}")
        }

        if (images.isNotEmpty()) {
            sb.appendLine("IMAGES: ${images.joinToString(", ")}")
        }

        if (textBlocks.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("VISIBLE TEXT:")
            textBlocks.take(20).forEach { sb.appendLine("  $it") }
        }

        sb.appendLine()
        sb.appendLine("SENSITIVITY: $sensitiveStr")
        sb.appendLine("DOC TYPE: $docClass")
        sb.appendLine("TONE: $tone")

        if (correlation.isNotBlank()) {
            sb.appendLine()
            sb.appendLine(correlation.take(400))
        }

        return sb.toString().trim()
    }

    private val inputRoles = setOf(
        NodeRole.INPUT_TEXT, NodeRole.INPUT_PASSWORD, NodeRole.INPUT_EMAIL,
        NodeRole.INPUT_PHONE, NodeRole.INPUT_NUMBER, NodeRole.INPUT_OTP
    )

    private val textRoles = setOf(NodeRole.LABEL, NodeRole.HEADER, NodeRole.LIST_ITEM, NodeRole.UNKNOWN)
}
