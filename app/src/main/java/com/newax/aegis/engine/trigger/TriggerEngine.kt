package com.newax.aegis.engine.trigger

import android.content.Context
import android.os.BatteryManager
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.TriggerRule
import com.newax.aegis.engine.CalendarQueries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

object TriggerEngine {

    // Kept for backward compat with MainViewModel.triggerEvents.collect { }
    val triggerEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var db: AegisDatabase? = null
    private var appContext: Context? = null
    private var onAction: ((TriggerRule, Map<String, String>) -> Unit)? = null

    // ── Startup ───────────────────────────────────────────────────────────────

    fun start(
        context: Context,
        database: AegisDatabase,
        actionCallback: (rule: TriggerRule, eventContext: Map<String, String>) -> Unit
    ) {
        db = database
        appContext = context.applicationContext
        onAction = actionCallback
        startTimePoller()
        startCalendarPoller()
        startBatteryPoller()
    }

    // ── Event ingest (called by notification listener, accessibility service) ─

    fun onNotification(sender: String, text: String, packageName: String = "") {
        scope.launch {
            val rules = db?.triggerDao()?.rulesByCondition(TriggerRule.COND_NOTIFICATION_FROM).orEmpty() +
                        db?.triggerDao()?.rulesByCondition(TriggerRule.COND_KEYWORD_IN_NOTIF).orEmpty()
            val ctx = mapOf("sender" to sender, "text" to text, "package" to packageName)
            rules.forEach { rule -> tryFire(rule, ctx) }
        }
    }

    fun onWindowChanged(packageName: String) {
        scope.launch {
            val rules = db?.triggerDao()?.rulesByCondition(TriggerRule.COND_APP_OPENED).orEmpty()
            val ctx = mapOf("package" to packageName)
            rules.forEach { rule -> tryFire(rule, ctx) }
        }
    }

    fun onScreenContent(visibleText: String) {
        scope.launch {
            val rules = db?.triggerDao()?.rulesByCondition(TriggerRule.COND_SCREEN_CONTENT).orEmpty()
            val ctx = mapOf("text" to visibleText)
            rules.forEach { rule -> tryFire(rule, ctx) }
        }
    }

    // Legacy text-rule evaluator (kept for backward compat)
    fun evaluateEvent(eventType: String, eventDetails: String) {
        onNotification(eventType, eventDetails)
    }

    // ── Rule management helpers ───────────────────────────────────────────────

    fun addRule(rule: TriggerRule): Long = db?.triggerDao()?.insert(rule) ?: -1L
    fun removeRule(id: Long) { db?.triggerDao()?.deleteById(id) }
    fun enableRule(id: Long, on: Boolean) { db?.triggerDao()?.setEnabled(id, on) }
    fun allRules(): List<TriggerRule> = db?.triggerDao()?.allRules().orEmpty()

    // ── Condition evaluation ──────────────────────────────────────────────────

    private fun tryFire(rule: TriggerRule, eventCtx: Map<String, String>) {
        val now = System.currentTimeMillis()
        if (now - rule.lastFiredMs < rule.debounceMs) return
        if (!matchesCondition(rule, eventCtx)) return
        db?.triggerDao()?.stampFired(rule.id, now)
        fireAction(rule, eventCtx)
    }

    private fun matchesCondition(rule: TriggerRule, ctx: Map<String, String>): Boolean {
        val params = runCatching { JSONObject(rule.conditionParams) }.getOrNull() ?: return false
        return when (rule.conditionType) {
            TriggerRule.COND_NOTIFICATION_FROM -> {
                val contact = params.optString("contact").lowercase()
                val sender  = ctx["sender"]?.lowercase() ?: ""
                contact.isBlank() || sender.contains(contact)
            }
            TriggerRule.COND_APP_OPENED -> {
                val pkg = params.optString("package")
                pkg.isBlank() || ctx["package"] == pkg
            }
            TriggerRule.COND_KEYWORD_IN_NOTIF -> {
                val keyword = params.optString("keyword").lowercase()
                val text    = (ctx["text"] ?: "").lowercase()
                val sender  = (ctx["sender"] ?: "").lowercase()
                keyword.isBlank() || text.contains(keyword) || sender.contains(keyword)
            }
            TriggerRule.COND_TIME_OF_DAY -> {
                val hour   = params.optInt("hour", -1)
                val minute = params.optInt("minute", 0)
                val cal    = java.util.Calendar.getInstance()
                cal.get(java.util.Calendar.HOUR_OF_DAY) == hour &&
                    cal.get(java.util.Calendar.MINUTE) in minute..minute + 1
            }
            TriggerRule.COND_BATTERY_BELOW -> {
                val threshold = params.optInt("percent", 20)
                val bm = appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
                level < threshold
            }
            TriggerRule.COND_CALENDAR_SOON -> {
                val minutesBefore = params.optInt("minutesBefore", 15)
                val nowMs = System.currentTimeMillis()
                val windowMs = minutesBefore * 60_000L
                val ctx2 = appContext ?: return false
                val events = CalendarQueries.query(ctx2, nowMs, nowMs + windowMs + 60_000L, 5)
                events.any { it.startMs in nowMs..(nowMs + windowMs) }
            }
            TriggerRule.COND_SCREEN_CONTENT -> {
                val keyword = params.optString("keyword").lowercase()
                val text    = (ctx["text"] ?: "").lowercase()
                keyword.isBlank() || text.contains(keyword)
            }
            else -> false
        }
    }

    // ── Action execution ──────────────────────────────────────────────────────

    private fun fireAction(rule: TriggerRule, ctx: Map<String, String>) {
        val params = runCatching { JSONObject(rule.actionParams) }.getOrNull() ?: JSONObject()
        when (rule.actionType) {
            TriggerRule.ACTION_SUBMIT_TO_AI -> {
                val template = params.optString("prompt", "[Trigger: ${rule.label}]")
                val prompt   = fillTemplate(template, ctx)
                triggerEvents.tryEmit("[System Background Trigger]\n$prompt")
                onAction?.invoke(rule, ctx)
            }
            TriggerRule.ACTION_LAUNCH_APP -> {
                val pkg = params.optString("package")
                if (pkg.isNotBlank()) {
                    val ctx2 = appContext ?: return
                    val i = ctx2.packageManager.getLaunchIntentForPackage(pkg)
                        ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    if (i != null) ctx2.startActivity(i)
                }
                onAction?.invoke(rule, ctx)
            }
            TriggerRule.ACTION_REMEMBER_FACT -> {
                // emit to AI with memory instruction
                val fact   = params.optString("fact", "")
                val prompt = "Remember this fact: $fact"
                triggerEvents.tryEmit(prompt)
                onAction?.invoke(rule, ctx)
            }
            TriggerRule.ACTION_NOTIFY_USER -> {
                // Show Android notification - handled by caller via onAction callback
                onAction?.invoke(rule, ctx)
            }
            TriggerRule.ACTION_EXECUTE_PROCEDURE -> {
                // Emit to AI for procedure execution
                val procId = params.optLong("procedureId", -1L)
                val prompt = "[Execute procedure id=$procId triggered by ${rule.label}]"
                triggerEvents.tryEmit(prompt)
                onAction?.invoke(rule, ctx)
            }
        }
    }

    private fun fillTemplate(template: String, ctx: Map<String, String>): String {
        var result = template
        ctx.forEach { (k, v) -> result = result.replace("{$k}", v) }
        return result
    }

    // ── Background pollers ────────────────────────────────────────────────────

    private fun startTimePoller() {
        scope.launch {
            while (isActive) {
                delay(60_000L) // check every minute
                val rules = db?.triggerDao()?.rulesByCondition(TriggerRule.COND_TIME_OF_DAY).orEmpty()
                rules.forEach { rule -> tryFire(rule, emptyMap()) }
            }
        }
    }

    private fun startCalendarPoller() {
        scope.launch {
            while (isActive) {
                delay(60_000L)
                val rules = db?.triggerDao()?.rulesByCondition(TriggerRule.COND_CALENDAR_SOON).orEmpty()
                rules.forEach { rule -> tryFire(rule, emptyMap()) }
            }
        }
    }

    private fun startBatteryPoller() {
        scope.launch {
            while (isActive) {
                delay(120_000L) // check every 2 minutes
                val rules = db?.triggerDao()?.rulesByCondition(TriggerRule.COND_BATTERY_BELOW).orEmpty()
                rules.forEach { rule -> tryFire(rule, emptyMap()) }
            }
        }
    }
}
