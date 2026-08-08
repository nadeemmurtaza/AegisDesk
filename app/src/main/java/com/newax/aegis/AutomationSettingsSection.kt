package com.newax.aegis

import android.graphics.Bitmap
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.newax.aegis.engine.AutomationSettings
import com.newax.aegis.engine.AutomationToggle
import com.newax.aegis.engine.TotpManager

private val BG           = Color(0xFFF7F7F5)
private val Surface      = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val SurfaceStr   = Color(0xFFE7E7E2)
private val Primary      = Color(0xFF1B1B1A)
private val TextPri      = Color(0xFF1B1B1A)
private val TextSec      = Color(0xFF686864)
private val TextTer      = Color(0xFF8D8D87)
private val Border       = Color(0xFFD8D8D3)
private val Red          = Color(0xFFDC2626)
private val Amber        = Color(0xFFD97706)
private val Green        = Color(0xFF16A34A)

// ── State holder for the settings biometric/TOTP flow ────────────────────────
private enum class SettingsAuthState { IDLE, BIOMETRIC_PENDING, TOTP_PENDING, TOTP_SETUP }

/**
 * The full Automation section that lives inside SettingsScreen.
 * Handles all biometric + TOTP auth flows internally.
 */
@Composable
fun AutomationSettingsSection(vm: MainViewModel) {
    val context = LocalContext.current as FragmentActivity

    // Auth flow state
    var authState by remember { mutableStateOf(SettingsAuthState.IDLE) }
    var pendingToggle by remember { mutableStateOf<AutomationToggle?>(null) }
    var pendingMasterEnable by remember { mutableStateOf(false) }
    var pendingMasterDisable by remember { mutableStateOf(false) }
    var totpCode by remember { mutableStateOf("") }
    var totpError by remember { mutableStateOf(false) }

    // TOTP setup state
    var setupSecret by remember { mutableStateOf("") }
    var setupCode by remember { mutableStateOf("") }
    var setupError by remember { mutableStateOf(false) }

    // Recompose when toggles change
    val version = vm.automationVersion

    // Expansion state per group
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val groups = AutomationToggle.groupedEntries()
    val sensitiveGroups = setOf("Communications", "Destructive Actions", "Code Execution", "Social Media")

    val totalEnabled = remember(version) {
        AutomationToggle.entries.count { AutomationSettings.isEnabled(it) }
    }

    // ── Biometric launcher ────────────────────────────────────────────────────
    fun launchBiometric(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        BiometricPrompt(context, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(code: Int, err: CharSequence) {
                authState = SettingsAuthState.IDLE
                pendingToggle = null
                pendingMasterEnable = false
                pendingMasterDisable = false
            }
            override fun onAuthenticationFailed() {}
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Aegis Automation Security")
                .setSubtitle("Verify identity to change automation settings")
                .setNegativeButtonText("Cancel")
                .build()
        )
    }

    fun requestToggle(toggle: AutomationToggle) {
        pendingToggle = toggle
        launchBiometric {
            if (toggle.sensitive && TotpManager.isEnrolled) {
                authState = SettingsAuthState.TOTP_PENDING
            } else if (toggle.sensitive && !TotpManager.isEnrolled) {
                // Sensitive toggle but no 2FA enrolled → block
                authState = SettingsAuthState.IDLE
                pendingToggle = null
            } else {
                AutomationSettings.setEnabled(toggle, !AutomationSettings.isEnabled(toggle))
                vm.bumpAutomationVersion()
                authState = SettingsAuthState.IDLE
                pendingToggle = null
            }
        }
    }

    fun requestMasterEnable() {
        pendingMasterEnable = true
        launchBiometric {
            AutomationSettings.enableAllNonSensitive()
            vm.bumpAutomationVersion()
            pendingMasterEnable = false
            authState = SettingsAuthState.IDLE
        }
    }

    fun requestMasterDisable() {
        pendingMasterDisable = true
        launchBiometric {
            AutomationSettings.disableAll()
            vm.bumpAutomationVersion()
            pendingMasterDisable = false
            authState = SettingsAuthState.IDLE
        }
    }

    fun start2faSetup() {
        launchBiometric {
            // Generate and hold the secret in Compose state only — do not enroll yet.
            // Enrollment happens only after the user confirms a valid authenticator code.
            setupSecret = TotpManager.generateSecret()
            setupCode = ""
            setupError = false
            authState = SettingsAuthState.TOTP_SETUP
        }
    }

    // ── TOTP verify dialog (for enabling sensitive toggle) ───────────────────
    if (authState == SettingsAuthState.TOTP_PENDING) {
        AlertDialog(
            onDismissRequest = {
                authState = SettingsAuthState.IDLE
                pendingToggle = null
                totpCode = ""; totpError = false
            },
            containerColor = Surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = Amber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Two-Factor Verification", fontWeight = FontWeight.SemiBold, color = TextPri)
                }
            },
            text = {
                Column {
                    Text(
                        "Enter the 6-digit code from Google Authenticator to enable:\n\"${pendingToggle?.label}\"",
                        fontSize = 14.sp, color = TextSec, lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = totpCode,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { totpCode = it; totpError = false } },
                        label = { Text("6-digit code", color = TextTer) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = totpError,
                        supportingText = if (totpError) { { Text("Invalid code. Try again.", color = Red) } } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, unfocusedBorderColor = Border)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (TotpManager.verify(totpCode)) {
                            pendingToggle?.let { t ->
                                AutomationSettings.setEnabled(t, !AutomationSettings.isEnabled(t))
                                vm.bumpAutomationVersion()
                            }
                            authState = SettingsAuthState.IDLE; pendingToggle = null; totpCode = ""
                        } else {
                            totpError = true
                        }
                    },
                    enabled = totpCode.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Verify") }
            },
            dismissButton = {
                TextButton(onClick = {
                    authState = SettingsAuthState.IDLE; pendingToggle = null; totpCode = ""; totpError = false
                }) { Text("Cancel", color = TextSec) }
            }
        )
    }

    // ── TOTP setup dialog ─────────────────────────────────────────────────────
    if (authState == SettingsAuthState.TOTP_SETUP) {
        val qrBitmap: Bitmap? = remember(setupSecret) { TotpManager.qrBitmapForSecret(setupSecret, 320) }
        AlertDialog(
            onDismissRequest = {
                authState = SettingsAuthState.IDLE; setupSecret = ""; setupCode = ""; setupError = false
            },
            containerColor = Surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = Primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Set up Google Authenticator", fontWeight = FontWeight.SemiBold, color = TextPri)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("1. Open Google Authenticator\n2. Tap + → Scan QR code OR enter key manually\n3. Enter the 6-digit code to confirm", fontSize = 13.sp, color = TextSec, lineHeight = 19.sp)
                    Spacer(Modifier.height(12.dp))

                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Text("Or enter key manually:", fontSize = 12.sp, color = TextTer)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceMuted)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            setupSecret.chunked(4).joinToString(" "),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            color = TextPri,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Account: Aegis:AegisDevice", fontSize = 11.sp, color = TextTer)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = setupCode,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { setupCode = it; setupError = false } },
                        label = { Text("Confirm 6-digit code", color = TextTer) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = setupError,
                        supportingText = if (setupError) { { Text("Code mismatch. Check Google Authenticator.", color = Red) } } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary, unfocusedBorderColor = Border)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (TotpManager.verifyCode(setupCode, setupSecret)) {
                            TotpManager.enroll(setupSecret)  // persist only after code confirmed
                            authState = SettingsAuthState.IDLE; setupSecret = ""; setupCode = ""
                            vm.bumpAutomationVersion()
                        } else {
                            setupError = true
                        }
                    },
                    enabled = setupCode.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Activate 2FA") }
            },
            dismissButton = {
                TextButton(onClick = {
                    authState = SettingsAuthState.IDLE; setupSecret = ""; setupCode = ""; setupError = false
                }) { Text("Cancel", color = TextSec) }
            }
        )
    }

    // ── Master control card ───────────────────────────────────────────────────
    AutoSettingsLabel("Automation Control")
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$totalEnabled / ${AutomationToggle.entries.size} functions automated", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text("Biometric required to change any toggle", fontSize = 12.sp, color = TextSec)
                }
                AutoBadge(enabled = totalEnabled > 0)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = ::requestMasterDisable,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSec)
                ) { Text("Disable All", fontSize = 13.sp) }
                Button(
                    onClick = ::requestMasterEnable,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Max Auto Mode", fontSize = 13.sp) }
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // ── 2FA security card ─────────────────────────────────────────────────────
    AutoSettingsLabel("Two-Factor Authentication (2FA)")
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, if (TotpManager.isEnrolled) Color(0xFF86EFAC) else Color(0xFFFCA5A5)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (TotpManager.isEnrolled) Icons.Rounded.VerifiedUser else Icons.Rounded.GppBad,
                    contentDescription = null,
                    tint = if (TotpManager.isEnrolled) Green else Red,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (TotpManager.isEnrolled) "2FA Active" else "2FA Not Set Up",
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri
                    )
                    Text(
                        if (TotpManager.isEnrolled) "Sensitive toggles require Google Authenticator code"
                        else "Required to enable sensitive automations (messages, deletions, scripts)",
                        fontSize = 12.sp, color = TextSec, lineHeight = 17.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (TotpManager.isEnrolled) {
                    OutlinedButton(
                        onClick = {
                            launchBiometric {
                                TotpManager.clearEnrollment()
                                AutomationToggle.entries.filter { it.sensitive }
                                    .forEach { AutomationSettings.setEnabled(it, false) }
                                vm.bumpAutomationVersion()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Red.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)
                    ) { Text("Remove 2FA", fontSize = 13.sp) }
                } else {
                    Button(
                        onClick = ::start2faSetup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Set up with Google Authenticator", fontSize = 13.sp)
                    }
                }
            }
            if (!TotpManager.isEnrolled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEF3C7))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sensitive toggles are locked until 2FA is active.", fontSize = 12.sp, color = Amber)
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // ── Automation toggle groups ───────────────────────────────────────────────
    AutoSettingsLabel("Automation Functions")

    groups.forEach { (groupName, toggles) ->
        val isSensitiveGroup = groupName in sensitiveGroups
        val groupEnabled = remember(version) { toggles.count { AutomationSettings.isEnabled(it) } }
        val isExpanded = expandedGroups[groupName] == true

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            border = BorderStroke(1.dp, if (isSensitiveGroup) Color(0xFFFCA5A5).copy(alpha = 0.6f) else Border),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Column {
                // Group header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expandedGroups[groupName] = !isExpanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSensitiveGroup) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(groupName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPri, modifier = Modifier.weight(1f))
                    if (isSensitiveGroup) {
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(Red.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("TFA", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Red) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("$groupEnabled/${toggles.size}", fontSize = 12.sp, color = TextTer)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null, tint = TextSec, modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column {
                        HorizontalDivider(color = Border)
                        toggles.forEachIndexed { idx, toggle ->
                            val enabled = remember(version) { AutomationSettings.isEnabled(toggle) }
                            val locked = toggle.sensitive && !TotpManager.isEnrolled
                            AutomationToggleRow(
                                toggle = toggle,
                                enabled = enabled,
                                locked = locked,
                                onToggle = { if (!locked) requestToggle(toggle) }
                            )
                            if (idx < toggles.lastIndex) HorizontalDivider(color = Border, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationToggleRow(
    toggle: AutomationToggle,
    enabled: Boolean,
    locked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !locked, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(toggle.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (locked) TextTer else TextPri)
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = TextTer, modifier = Modifier.size(12.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (locked) "Set up 2FA first to enable this" else toggle.description,
                fontSize = 12.sp, color = TextTer, lineHeight = 17.sp
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { if (!locked) onToggle() },
            enabled = !locked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = if (toggle.sensitive) Color(0xFFDC2626) else Color(0xFF1B1B1A),
                uncheckedThumbColor = Color(0xFF8D8D87),
                uncheckedTrackColor = Color(0xFFE7E7E2)
            )
        )
    }
}

@Composable
private fun AutoBadge(enabled: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) Color(0xFFDCFCE7) else SurfaceMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            if (enabled) "Active" else "Manual",
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (enabled) Green else TextTer
        )
    }
}

@Composable
private fun AutoSettingsLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTer,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

