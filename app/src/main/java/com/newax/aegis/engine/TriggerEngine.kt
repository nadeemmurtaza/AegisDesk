package com.newax.aegis.engine

import android.content.Context
import android.util.Log
import com.newax.aegis.memory.EncryptedMemory
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Evaluates raw system events against user-defined rules.
 * If an event matches a rule, it forwards a trigger prompt to the AI for execution.
 */
object TriggerEngine {

    val triggerEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)

    private var memory: EncryptedMemory? = null

    /** Cooldown per event type: last fire timestamp. Prevents AI spam on rapid events. */
    private val lastFiredAt = mutableMapOf<String, Long>()
    private const val DEBOUNCE_MS = 30_000L

    fun initialize(context: Context) {
        if (memory == null) {
            memory = EncryptedMemory(context)
        }
    }

    /**
     * Called by BroadcastReceivers when a system event occurs.
     *
     * Rule format (stored in EncryptedMemory category "rules"):
     *   "ON <EVENT_TYPE> IF <keyword> THEN <action hint>"
     * Simple rules without "ON" still match by substring as before but with debounce.
     */
    fun evaluateEvent(eventType: String, eventDetails: String) {
        val now = System.currentTimeMillis()
        val lastFire = lastFiredAt[eventType] ?: 0L
        if (now - lastFire < DEBOUNCE_MS) {
            Log.d("AegisTrigger", "Event $eventType debounced.")
            return
        }

        val rules = memory?.getCategory("rules").orEmpty()
        val eventLower = eventType.lowercase()
        val detailsLower = eventDetails.lowercase()

        val relevantRules = rules.filter { rule ->
            val r = rule.lowercase()
            if (r.startsWith("on ")) {
                val afterOn = r.removePrefix("on ").trimStart()
                val ruleEvent = afterOn.substringBefore(" if ").substringBefore(" then ").trim()
                val ruleKeyword = if (afterOn.contains(" if ")) afterOn.substringAfter(" if ").substringBefore(" then ").trim() else ""
                ruleEvent == eventLower && (ruleKeyword.isEmpty() || detailsLower.contains(ruleKeyword))
            } else {
                r.contains(eventLower)
            }
        }

        if (relevantRules.isNotEmpty()) {
            lastFiredAt[eventType] = now
            Log.i("AegisTrigger", "Rule matched for event: $eventType")

            val systemPrompt = """
                [System Background Trigger]
                Event: $eventType
                Details: $eventDetails
                Matching Rules: ${relevantRules.joinToString(" | ")}

                Decide if action is needed. If not, output "Ignore".
            """.trimIndent()

            triggerEvents.tryEmit(systemPrompt)
        } else {
            Log.d("AegisTrigger", "Event $eventType ignored — no matching rules.")
        }
    }
}
