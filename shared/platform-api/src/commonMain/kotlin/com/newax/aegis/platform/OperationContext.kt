package com.newax.aegis.platform

import com.newax.aegis.assistant.ActionOrigin
import kotlin.random.Random

/**
 * Authority metadata every privileged operation must carry (ARCHITECTURE.md RULE 4):
 * who/what requested it, where the requesting text came from, and the audit id that
 * lets the ledger reconstruct the decision (RULE 8). Platform-free: no time source,
 * no OS identifiers — the audit id is a random per-call token so the contract
 * compiles for any future KMP target.
 */
data class OperationContext(
    val caller: String,
    val origin: ActionOrigin,
    val auditId: String,
) {
    companion object {
        /** Creates a context with a fresh audit id for the given caller and origin. */
        fun create(caller: String, origin: ActionOrigin): OperationContext =
            OperationContext(caller = caller, origin = origin, auditId = "$caller-${Random.nextLong()}")
    }
}
