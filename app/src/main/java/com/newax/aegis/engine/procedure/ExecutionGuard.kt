package com.newax.aegis.engine.procedure

import android.content.Context

object ExecutionGuard {

    enum class GuardResult { ALLOWED, BLOCKED }

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

    fun check(context: Context, packageName: String?): GuardResult {
        if (packageName.isNullOrBlank()) return GuardResult.ALLOWED
        return if (PROTECTED_PACKAGES.contains(packageName)) GuardResult.BLOCKED else GuardResult.ALLOWED
    }
}
