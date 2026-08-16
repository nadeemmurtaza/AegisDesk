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
import androidx.compose.ui.res.stringResource
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
import com.newax.aegis.ui.theme.NewaxTheme

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
                .setTitle(context.getString(R.string.auto_biometric_title))
                .setSubtitle(context.getString(R.string.auto_biometric_subtitle))
                .setNegativeButtonText(context.getString(R.string.action_cancel))
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
            setupSecret = TotpManager.generateSecret()
            TotpManager.enroll(setupSecret)
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
            containerColor = NewaxTheme.colors.surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = NewaxTheme.colors.warning, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auto_totp_verify_title), fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary)
                }
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.auto_totp_verify_body, pendingToggle?.label),
                        fontSize = 14.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = totpCode,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { totpCode = it; totpError = false } },
                        label = { Text(stringResource(R.string.auto_code_label), color = NewaxTheme.colors.textTertiary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = totpError,
                        supportingText = if (totpError) { { Text(stringResource(R.string.auto_code_invalid), color = NewaxTheme.colors.error) } } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NewaxTheme.colors.textPrimary, unfocusedBorderColor = NewaxTheme.colors.border)
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
                    colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                ) { Text(stringResource(R.string.action_verify)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    authState = SettingsAuthState.IDLE; pendingToggle = null; totpCode = ""; totpError = false
                }) { Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary) }
            }
        )
    }

    // ── TOTP setup dialog ─────────────────────────────────────────────────────
    if (authState == SettingsAuthState.TOTP_SETUP) {
        val qrBitmap: Bitmap? = remember(setupSecret) { TotpManager.qrBitmap(320) }
        AlertDialog(
            onDismissRequest = {
                if (setupSecret.isNotEmpty()) TotpManager.clearEnrollment()
                authState = SettingsAuthState.IDLE; setupSecret = ""; setupCode = ""; setupError = false
            },
            containerColor = NewaxTheme.colors.surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = NewaxTheme.colors.textPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auto_totp_setup_title), fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.auto_totp_setup_steps), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp)
                    Spacer(Modifier.height(12.dp))

                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.auto_cd_qr),
                            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    Text(stringResource(R.string.auto_enter_key_manually), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(NewaxTheme.colors.surfaceMuted)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            setupSecret.chunked(4).joinToString(" "),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            color = NewaxTheme.colors.textPrimary,
                            letterSpacing = 1.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.auto_account_label), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = setupCode,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { setupCode = it; setupError = false } },
                        label = { Text(stringResource(R.string.auto_confirm_code), color = NewaxTheme.colors.textTertiary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = setupError,
                        supportingText = if (setupError) { { Text(stringResource(R.string.auto_code_mismatch), color = NewaxTheme.colors.error) } } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NewaxTheme.colors.textPrimary, unfocusedBorderColor = NewaxTheme.colors.border)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (TotpManager.verify(setupCode)) {
                            authState = SettingsAuthState.IDLE; setupSecret = ""; setupCode = ""
                            vm.bumpAutomationVersion()
                        } else {
                            setupError = true
                        }
                    },
                    enabled = setupCode.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                ) { Text(stringResource(R.string.action_activate_2fa)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    TotpManager.clearEnrollment()
                    authState = SettingsAuthState.IDLE; setupSecret = ""; setupCode = ""; setupError = false
                }) { Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary) }
            }
        )
    }

    // ── Master control card ───────────────────────────────────────────────────
    AutoSettingsLabel(stringResource(R.string.auto_section_control))
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_functions_automated, totalEnabled, AutomationToggle.entries.size), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.auto_biometric_required), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                }
                AutoBadge(enabled = totalEnabled > 0)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = ::requestMasterDisable,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, NewaxTheme.colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary)
                ) { Text(stringResource(R.string.action_disable_all), fontSize = 13.sp) }
                Button(
                    onClick = ::requestMasterEnable,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                ) { Text(stringResource(R.string.action_max_auto), fontSize = 13.sp) }
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // ── 2FA security card ─────────────────────────────────────────────────────
    AutoSettingsLabel(stringResource(R.string.auto_section_2fa))
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = BorderStroke(1.dp, if (TotpManager.isEnrolled) Color(0xFF86EFAC) else Color(0xFFFCA5A5)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (TotpManager.isEnrolled) Icons.Rounded.VerifiedUser else Icons.Rounded.GppBad,
                    contentDescription = null,
                    tint = if (TotpManager.isEnrolled) NewaxTheme.colors.success else NewaxTheme.colors.error,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (TotpManager.isEnrolled) stringResource(R.string.auto_2fa_active) else stringResource(R.string.auto_2fa_not_setup),
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary
                    )
                    Text(
                        if (TotpManager.isEnrolled) stringResource(R.string.auto_2fa_active_desc)
                        else stringResource(R.string.auto_2fa_not_setup_desc),
                        fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 17.sp
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
                        border = BorderStroke(1.dp, NewaxTheme.colors.error.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.error)
                    ) { Text(stringResource(R.string.action_remove_2fa), fontSize = 13.sp) }
                } else {
                    Button(
                        onClick = ::start2faSetup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                    ) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.auto_setup_with_authenticator), fontSize = 13.sp)
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
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = NewaxTheme.colors.warning, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.auto_locked_warning), fontSize = 12.sp, color = NewaxTheme.colors.warning)
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))

    // ── Automation toggle groups ───────────────────────────────────────────────
    AutoSettingsLabel(stringResource(R.string.auto_section_functions))

    groups.forEach { (groupName, toggles) ->
        val isSensitiveGroup = groupName in sensitiveGroups
        val groupEnabled = remember(version) { toggles.count { AutomationSettings.isEnabled(it) } }
        val isExpanded = expandedGroups[groupName] == true

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
            border = BorderStroke(1.dp, if (isSensitiveGroup) Color(0xFFFCA5A5).copy(alpha = 0.6f) else NewaxTheme.colors.border),
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
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = NewaxTheme.colors.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(groupName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                    if (isSensitiveGroup) {
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(NewaxTheme.colors.error.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(stringResource(R.string.auto_tfa_badge), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NewaxTheme.colors.error) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("$groupEnabled/${toggles.size}", fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                    Column {
                        HorizontalDivider(color = NewaxTheme.colors.border)
                        toggles.forEachIndexed { idx, toggle ->
                            val enabled = remember(version) { AutomationSettings.isEnabled(toggle) }
                            val locked = toggle.sensitive && !TotpManager.isEnrolled
                            AutomationToggleRow(
                                toggle = toggle,
                                enabled = enabled,
                                locked = locked,
                                onToggle = { if (!locked) requestToggle(toggle) }
                            )
                            if (idx < toggles.lastIndex) HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 16.dp))
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
                Text(toggle.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (locked) NewaxTheme.colors.textTertiary else NewaxTheme.colors.textPrimary)
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = NewaxTheme.colors.textTertiary, modifier = Modifier.size(12.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (locked) stringResource(R.string.auto_locked_hint) else toggle.description,
                fontSize = 12.sp, color = NewaxTheme.colors.textTertiary, lineHeight = 17.sp
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
            .background(if (enabled) Color(0xFFDCFCE7) else NewaxTheme.colors.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            if (enabled) stringResource(R.string.auto_badge_active) else stringResource(R.string.auto_badge_manual),
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (enabled) NewaxTheme.colors.success else NewaxTheme.colors.textTertiary
        )
    }
}

@Composable
private fun AutoSettingsLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textTertiary,
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

