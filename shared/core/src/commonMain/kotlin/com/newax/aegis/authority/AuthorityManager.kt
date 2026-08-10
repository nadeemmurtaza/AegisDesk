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

    /**
     * Applies a [PolicyEngine] evaluation as an authority event — the richer policy
     * path (ARCHITECTURE.md rule 3: "a richer PolicyEngine as it evolves"). The
     * legacy [evaluate] remains for direct callers; this is how PolicyEngine
     * decisions reach the same approval UI flow.
     */
    fun apply(evaluation: PolicyEvaluation) {
        val event = when (evaluation.decision) {
            PolicyDecision.AUTO_EXECUTE -> AuthorityEvent.Approved(evaluation.action)
            PolicyDecision.REQUIRE_APPROVAL -> AuthorityEvent.RequestApproval(
                evaluation.action,
                evaluation.action.confirmationWarning,
            )
            PolicyDecision.REQUIRE_STRONG -> AuthorityEvent.RequestBiometric(evaluation.action)
            PolicyDecision.DENY -> AuthorityEvent.Rejected(evaluation.action, evaluation.reason)
        }
        _events.tryEmit(event)
    }
}
