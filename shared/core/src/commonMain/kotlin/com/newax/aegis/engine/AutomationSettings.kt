package com.newax.aegis.engine

import com.newax.aegis.assistant.ProposedAction

enum class AutomationToggle(
    val key: String,
    val label: String,
    val group: String,
    val description: String,
    val sensitive: Boolean
) {
    // Navigation & UI
    AUTO_TAP(
        "auto_tap", "Auto-Tap Elements", "Navigation & UI",
        "Tap UI elements without approval", false
    ),
    AUTO_TYPE(
        "auto_type", "Auto-Type Text", "Navigation & UI",
        "Insert text into fields without approval", false
    ),
    AUTO_SCROLL(
        "auto_scroll", "Auto-Scroll / Navigate", "Navigation & UI",
        "Scroll, go home, back, recents automatically", false
    ),
    AUTO_OPEN_APP(
        "auto_open_app", "Auto-Open Apps", "Navigation & UI",
        "Launch apps by name without approval", false
    ),

    // Memory & Knowledge
    AUTO_SAVE_MEMORY(
        "auto_save_memory", "Auto-Save to Memory", "Memory & Knowledge",
        "Store facts to encrypted memory automatically", false
    ),
    AUTO_UPDATE_GRAPH(
        "auto_update_graph", "Auto-Update Knowledge Graph", "Memory & Knowledge",
        "Add nodes and edges to knowledge graph", false
    ),
    AUTO_LOG_COMMS(
        "auto_log_comms", "Auto-Log Communications", "Memory & Knowledge",
        "Log interactions to communication history", false
    ),
    AUTO_UPDATE_PROJECT(
        "auto_update_project", "Auto-Update Projects", "Memory & Knowledge",
        "Create and update project records", false
    ),

    // Calendar
    AUTO_CREATE_EVENT(
        "auto_create_event", "Auto-Create Calendar Events", "Calendar",
        "Add events to your calendar without prompt", false
    ),

    // Background Intelligence
    AUTO_HABIT_MEMORY(
        "auto_habit_memory", "Auto-Save Habit Patterns", "Background Intelligence",
        "AI learns and saves your app usage habits", false
    ),
    AUTO_GALLERY_CLEAN(
        "auto_gallery_clean", "Auto-Delete Junk Images", "Background Intelligence",
        "Remove blurry/junk photos nightly while charging", false
    ),
    AUTO_CONTACT_CLEAN(
        "auto_contact_clean", "Auto-Clean & Normalize Contacts", "Background Intelligence",
        "Normalize names, remove duplicates, merge same-person contacts", false
    ),
    AUTO_CONTACT_PROFILE(
        "auto_contact_profile", "Auto-Build Person Profiles", "Background Intelligence",
        "Analyze SMS/email history to build personality profiles for contacts", false
    ),
    AUTO_SELF_LEARNING(
        "auto_self_learning", "Self-Learning Background Scan", "Background Intelligence",
        "Continuously scan contacts, messages, call logs, images, files — extract facts as drafts for your approval", false
    ),
    AUTO_SECURITY_ALERT(
        "auto_security_alert", "Auto-Run Security Audits", "Background Intelligence",
        "Periodically audit installed app permissions", false
    ),

    // Communications — SENSITIVE (TFA required)
    AUTO_SEND_MESSAGE(
        "auto_send_message", "Auto-Send Messages", "Communications",
        "Send messages on your behalf without prompt", true
    ),
    AUTO_SEND_IMAGE(
        "auto_send_image", "Auto-Send Images", "Communications",
        "Attach and send images without prompt", true
    ),

    // Destructive Actions — SENSITIVE (TFA required)
    AUTO_DELETE_FILE(
        "auto_delete_file", "Auto-Delete Files", "Destructive Actions",
        "Permanently delete files without prompt", true
    ),
    AUTO_DELETE_CONTACT(
        "auto_delete_contact", "Auto-Delete Contacts", "Destructive Actions",
        "Delete contacts without prompt", true
    ),
    AUTO_DELETE_PROJECT(
        "auto_delete_project", "Auto-Delete Projects", "Destructive Actions",
        "Remove projects from tracker without prompt", true
    ),
    AUTO_FORGET_FACT(
        "auto_forget_fact", "Auto-Forget Memory Facts", "Destructive Actions",
        "Erase individual facts from memory without prompt", true
    ),

    // Code Execution — SENSITIVE (TFA required)
    AUTO_RUN_SCRIPT(
        "auto_run_script", "Auto-Run Scripts", "Code Execution",
        "Execute JS code in sandbox without prompt", true
    ),

    // Social Media — SENSITIVE (TFA required)
    AUTO_POST_SOCIAL(
        "auto_post_social", "Auto-Post to Social Media", "Social Media",
        "Post content to social apps without prompt", true
    ),

    // Call Agent — SENSITIVE (TFA required)
    CALL_AGENT(
        "call_agent", "Call Agent (Auto-Answer)", "Call Agent",
        "Automatically answer incoming calls and use voice AI. Default OFF. Only activates for known contacts.", true
    );

    companion object {
        fun groupedEntries(): Map<String, List<AutomationToggle>> =
            entries.groupBy { it.group }
    }
}

object AutomationSettings {
    private val lock = Any()
    private var secureSettings: SecureSettings? = null

    fun init(settings: SecureSettings) = synchronized(lock) {
        if (secureSettings != null) return
        secureSettings = settings
        com.newax.aegis.engine.apps.AppPermissionManager.init(settings)
    }

    fun isEnabled(toggle: AutomationToggle): Boolean =
        secureSettings?.getBoolean(toggle.key, false) == true

    fun setEnabled(toggle: AutomationToggle, enabled: Boolean) {
        secureSettings?.putBoolean(toggle.key, enabled)
    }

    fun enableAllNonSensitive() {
        AutomationToggle.entries.filter { !it.sensitive }.forEach { 
            secureSettings?.putBoolean(it.key, true) 
        }
    }

    fun disableAll() {
        AutomationToggle.entries.forEach { 
            secureSettings?.putBoolean(it.key, false) 
        }
    }

    /** Restore toggle states from a backup map (key → enabled). Clears existing state first. */
    fun importAll(map: Map<String, Boolean>) {
        secureSettings?.clear()
        map.forEach { (k, v) -> secureSettings?.putBoolean(k, v) }
    }

    /** Maps a ProposedAction to the toggle that controls its auto-execution. Null = always needs approval. */
    fun toggleForAction(action: ProposedAction): AutomationToggle? = when (action) {
        is ProposedAction.Tap, is ProposedAction.TapPixels -> AutomationToggle.AUTO_TAP
        is ProposedAction.Type -> AutomationToggle.AUTO_TYPE
        is ProposedAction.Scroll, is ProposedAction.Home,
        is ProposedAction.Back, is ProposedAction.Recents -> AutomationToggle.AUTO_SCROLL
        is ProposedAction.OpenApp -> AutomationToggle.AUTO_OPEN_APP
        is ProposedAction.UpdateMemory -> AutomationToggle.AUTO_SAVE_MEMORY
        is ProposedAction.ForgetFact -> AutomationToggle.AUTO_FORGET_FACT
        is ProposedAction.UpdateGraph, is ProposedAction.UpdateNode -> AutomationToggle.AUTO_UPDATE_GRAPH
        is ProposedAction.LogCommunication -> AutomationToggle.AUTO_LOG_COMMS
        is ProposedAction.UpdateProject -> AutomationToggle.AUTO_UPDATE_PROJECT
        is ProposedAction.DeleteProject -> AutomationToggle.AUTO_DELETE_PROJECT
        is ProposedAction.CreateEvent -> AutomationToggle.AUTO_CREATE_EVENT
        is ProposedAction.Send -> AutomationToggle.AUTO_SEND_MESSAGE
        is ProposedAction.SendImage -> AutomationToggle.AUTO_SEND_IMAGE
        is ProposedAction.DeleteFile -> AutomationToggle.AUTO_DELETE_FILE
        is ProposedAction.DeleteContact -> AutomationToggle.AUTO_DELETE_CONTACT
        is ProposedAction.RunScript -> AutomationToggle.AUTO_RUN_SCRIPT
        is ProposedAction.PostSocialMedia -> AutomationToggle.AUTO_POST_SOCIAL
        is ProposedAction.AuditSecurity -> AutomationToggle.AUTO_SECURITY_ALERT
        is ProposedAction.StartLearning, is ProposedAction.StopLearning,
        is ProposedAction.ScanNow -> AutomationToggle.AUTO_SELF_LEARNING
        is ProposedAction.AnalyzeContacts, is ProposedAction.MergeContacts -> AutomationToggle.AUTO_CONTACT_CLEAN
        is ProposedAction.BuildPersonProfile, is ProposedAction.ShowPersonProfile -> AutomationToggle.AUTO_CONTACT_PROFILE
        // Read-only / always-safe: PrefixSearch, SearchAll, QueryCalendar, TakeScreenshot, ToggleConnectivity
        else -> null
    }
}
