package com.newax.aegis.engine

import android.content.Context

// Backward-compat delegate — all calls forward to engine.trigger.TriggerEngine.
object TriggerEngine {

    val triggerEvents get() = com.newax.aegis.engine.trigger.TriggerEngine.triggerEvents

    fun initialize(context: Context) {}

    fun evaluateEvent(eventType: String, eventDetails: String) {
        com.newax.aegis.engine.trigger.TriggerEngine.onNotification(eventType, eventDetails)
    }
}
