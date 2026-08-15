package com.newax.aegis.engine.procedure

/**
 * The screen-level guard on procedure execution.
 *
 * Two checks, both pure so they are unit-testable without a device:
 *
 *  - [check] — never act inside a protected app at all (settings, installers,
 *    password managers, authenticators, banking).
 *  - [checkWithContext] — the pre-flight verification from `COMPUTER_USE.md` §5:
 *    confirm the screen is still what the plan assumed *before* each step.
 *
 * ### What changed and why
 *
 * `checkWithContext` was written and then called from nowhere — verified with a
 * repo-wide search that found only its own declaration. A safety check nothing
 * invokes is worse than an absent one, because the code reads as protected. It
 * is now called from [ProcedureExecutor] on every step.
 *
 * Three further faults were fixed while wiring it:
 *
 *  1. A `Context` parameter neither function ever used, which made the guard
 *     need a device to test for no reason.
 *  2. A package mismatch reported `WRONG_PERSON`. An app switching under you is
 *     not a person mix-up, and an audit entry saying so is actively misleading.
 *  3. `GuardContext` carried `expectedPersonEntityId`, `expectedFileId` and
 *     `isDestructiveAction` that **nothing read**. Setting them looked like
 *     protection and bought none, so they are gone rather than silently ignored.
 *     Re-add them with the code that enforces them, not before.
 */
object ExecutionGuard {

    enum class GuardResult { ALLOWED, BLOCKED }

    enum class BlockReason {
        /** The current app is on the never-automate list. */
        PROTECTED_PACKAGE,

        /**
         * The foreground app is not the one the procedure was recorded against.
         * Something moved under the procedure — abort rather than adapt, because
         * adapting to an unexpected screen is improvising against an adversary.
         */
        UNEXPECTED_PACKAGE,

        /** The step spends money. Refused here; it needs the authority spine. */
        FINANCIAL_ACTION,
    }

    /**
     * What the plan assumed. Every field here is actually checked — see the
     * class KDoc for the ones that were removed for not being.
     */
    data class GuardContext(
        /** The package the procedure was recorded against, or null to skip the check. */
        val expectedPackage: String? = null,
        val isFinancialAction: Boolean = false,
    )

    private val PROTECTED_PACKAGES = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.android.systemui",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.google.android.gms",
        "com.android.keychain",
        "com.android.biometric",
        "com.android.server.biometrics",
        "com.samsung.android.biometrics",
        "com.oneplus.security",
        "com.huawei.security",
        "com.android.vpndialogs",
        "com.android.certinstaller",
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.twilio.authy",
        "org.shadowsocks",
        "com.lastpass.lpandroid",
        "com.lastpass.authenticator",
        "com.agilebits.onepassword",
        "com.dashlane",
        "com.keepassdroid",
        "keepass2android.keepass2android",
        "com.google.android.apps.payments",
        "com.google.android.pay",
        "com.samsung.android.spay",
        "com.apple.android.music",
        "com.paypal.android.p2pmobile",
        "pk.gov.nadra",
        "pk.gov.sbp",
        "pk.com.hbl.hblmobilebanking",
        "com.meezan.meezan",
        "com.bankislami.mobilebanking",
        "com.ubldigital.ubl",
        "com.nayapay.app",
        "com.easypaisa",
        "com.jazzcash.jazzcash",
        "com.standard.chartered",
        "pk.habib.habibmetro",
        "com.faysal.faysalmobile",
        "com.alfalah.alfalah"
    )

    /** True when [packageName] is on the never-automate list. */
    fun isProtected(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && PROTECTED_PACKAGES.contains(packageName)

    /**
     * A null or blank package is ALLOWED: the accessibility service reports null
     * between screens, and blocking every transition would stop all automation.
     * The per-step [checkWithContext] is what catches a move to the wrong app.
     */
    fun check(packageName: String?): GuardResult =
        if (isProtected(packageName)) GuardResult.BLOCKED else GuardResult.ALLOWED

    /**
     * Pre-flight: is it still safe, and still the right screen, to act here?
     *
     * Order matters. The protected-package check runs first and unconditionally,
     * so a procedure that somehow names a password manager as its expected
     * package is still blocked.
     */
    fun checkWithContext(
        currentPackage: String?,
        guardContext: GuardContext,
    ): Pair<GuardResult, BlockReason?> {
        if (isProtected(currentPackage)) {
            return GuardResult.BLOCKED to BlockReason.PROTECTED_PACKAGE
        }
        if (guardContext.isFinancialAction) {
            return GuardResult.BLOCKED to BlockReason.FINANCIAL_ACTION
        }
        // Only compare when both are known. A null current package means "between
        // screens", not "wrong screen".
        if (guardContext.expectedPackage != null &&
            currentPackage != null &&
            currentPackage != guardContext.expectedPackage
        ) {
            return GuardResult.BLOCKED to BlockReason.UNEXPECTED_PACKAGE
        }
        return GuardResult.ALLOWED to null
    }
}
