package com.newax.aegis.authority

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.assistant.confirmationWarning
import com.newax.aegis.assistant.mayAutoExecute
import com.newax.aegis.assistant.requiresBiometric
import com.newax.aegis.assistant.riskLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface AuthorityEvent {
    data class RequestApproval(val action: ProposedAction, val warning: String?) : AuthorityEvent
    data class RequestBiometric(val action: ProposedAction) : AuthorityEvent
    data class Approved(val action: ProposedAction) : AuthorityEvent
    data class Rejected(val action: ProposedAction, val reason: String) : AuthorityEvent
}

class AuthorityManager {
    private val _events = MutableSharedFlow<AuthorityEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AuthorityEvent> = _events.asSharedFlow()

    fun evaluate(action: ProposedAction, origin: ActionOrigin, autoExecuteEnabled: Boolean) {
        if (mayAutoExecute(action, origin, autoExecuteEnabled)) {
            if (requiresBiometric(action)) {
                _events.tryEmit(AuthorityEvent.RequestBiometric(action))
            } else {
                _events.tryEmit(AuthorityEvent.Approved(action))
            }
        } else {
            val warning = action.confirmationWarning
            _events.tryEmit(AuthorityEvent.RequestApproval(action, warning))
        }
    }

    fun approve(action: ProposedAction, biometricAuthenticated: Boolean = false) {
        if (requiresBiometric(action) && !biometricAuthenticated) {
            _events.tryEmit(AuthorityEvent.RequestBiometric(action))
        } else {
            _events.tryEmit(AuthorityEvent.Approved(action))
        }
    }

    fun reject(action: ProposedAction, reason: String = "User rejected action") {
        _events.tryEmit(AuthorityEvent.Rejected(action, reason))
    }
}
