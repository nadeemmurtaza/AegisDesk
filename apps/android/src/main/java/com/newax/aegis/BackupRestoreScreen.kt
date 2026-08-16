package com.newax.aegis

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.newax.aegis.ui.theme.NewaxTheme

// ── Design tokens ─────────────────────────────────────────────────────────────
private sealed class BackupStatus {
    data object Idle    : BackupStatus()
    data object Working : BackupStatus()
    data class Success(val msg: String) : BackupStatus()
    data class Error(val msg: String)   : BackupStatus()
}

@Composable
fun BackupRestoreScreen(vm: MainViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var password        by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPw          by remember { mutableStateOf(false) }
    var showConfirmPw   by remember { mutableStateOf(false) }
    var status          by remember { mutableStateOf<BackupStatus>(BackupStatus.Idle) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // File pickers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = BackupStatus.Working
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val encrypted = BackupManager.buildEncryptedBackup(
                        context, vm.memory, password.toCharArray()
                    )
                    BackupManager.writeToUri(context, uri, encrypted)
                }.onSuccess {
                    status = BackupStatus.Success(context.getString(R.string.backup_exported))
                    password        = ""
                    confirmPassword = ""
                }.onFailure { e ->
                    status = BackupStatus.Error(e.message ?: context.getString(R.string.backup_export_failed))
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingRestoreUri = uri
        showRestoreDialog = true
    }

    // ── Restore confirm dialog ────────────────────────────────────────────────
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false; pendingRestoreUri = null },
            containerColor = NewaxTheme.colors.surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = NewaxTheme.colors.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_replace_title), fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary)
                }
            },
            text = {
                Text(
                    stringResource(R.string.backup_replace_body),
                    color = NewaxTheme.colors.textSecondary, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreDialog = false
                        val uri = pendingRestoreUri ?: return@Button
                        pendingRestoreUri = null
                        status = BackupStatus.Working
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val fileBytes = BackupManager.readFromUri(context, uri)
                                    BackupManager.decryptAndRestore(context, vm.memory, fileBytes, password.toCharArray())
                                }.onSuccess {
                                    vm.refreshDrafts()
                                    status = BackupStatus.Success(context.getString(R.string.backup_restored))
                                    password = ""
                                }.onFailure { e ->
                                    val msg = when (e) {
                                        is SecurityException -> context.getString(R.string.backup_wrong_password)
                                        is IllegalArgumentException -> context.getString(R.string.backup_invalid_file)
                                        else -> e.message ?: context.getString(R.string.backup_restore_failed)
                                    }
                                    status = BackupStatus.Error(msg)
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.error)
                ) { Text(stringResource(R.string.action_restore), color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false; pendingRestoreUri = null }) {
                    Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary)
                }
            }
        )
    }

    // ── Main content ──────────────────────────────────────────────────────────
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // Encryption info card
        Card(
            shape     = RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.infoFill),
            border    = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBFDBFE)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(Modifier.padding(14.dp)) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = NewaxTheme.colors.info, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.backup_encryption_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = NewaxTheme.colors.info)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.backup_encryption_body),
                        fontSize = 12.sp, color = Color(0xFF1D4ED8), lineHeight = 18.sp
                    )
                }
            }
        }

        // ── Password fields ───────────────────────────────────────────────────
        BrSectionLabel(stringResource(R.string.backup_section_password))

        Card(
            shape     = RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
            border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it },
                    label         = { Text(stringResource(R.string.backup_field_password)) },
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon  = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(
                                if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPw) stringResource(R.string.backup_cd_hide) else stringResource(R.string.backup_cd_show),
                                tint = NewaxTheme.colors.textTertiary
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp),
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = NewaxTheme.colors.textPrimary,
                        unfocusedBorderColor = NewaxTheme.colors.border
                    )
                )

                OutlinedTextField(
                    value         = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label         = { Text(stringResource(R.string.backup_field_confirm_password)) },
                    visualTransformation = if (showConfirmPw) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon  = {
                        IconButton(onClick = { showConfirmPw = !showConfirmPw }) {
                            Icon(
                                if (showConfirmPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = NewaxTheme.colors.textTertiary
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp),
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = NewaxTheme.colors.textPrimary,
                        unfocusedBorderColor = NewaxTheme.colors.border
                    ),
                    isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                    supportingText = {
                        if (confirmPassword.isNotEmpty() && confirmPassword != password) {
                            Text(stringResource(R.string.backup_passwords_mismatch), color = NewaxTheme.colors.error, fontSize = 12.sp)
                        }
                    }
                )

                PasswordStrengthBar(password)
            }
        }

        // ── Export ────────────────────────────────────────────────────────────
        BrSectionLabel(stringResource(R.string.backup_section_export))

        Card(
            shape     = RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
            border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(NewaxTheme.colors.surfaceMuted, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.CloudUpload, null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_save_encrypted), fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.backup_save_hint),
                            fontSize = 12.sp, color = NewaxTheme.colors.textSecondary
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (password.length < 8) {
                            status = BackupStatus.Error(context.getString(R.string.backup_password_too_short))
                            return@Button
                        }
                        if (password != confirmPassword) {
                            status = BackupStatus.Error(context.getString(R.string.backup_passwords_mismatch))
                            return@Button
                        }
                        exportLauncher.launch(BackupManager.suggestedFilename())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary),
                    enabled  = status !is BackupStatus.Working
                ) {
                    Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_export_button), fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.backup_export_tip),
                    fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, lineHeight = 16.sp
                )
            }
        }

        // ── Import ────────────────────────────────────────────────────────────
        BrSectionLabel(stringResource(R.string.backup_section_restore))

        Card(
            shape     = RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
            border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(NewaxTheme.colors.surfaceMuted, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.CloudDownload, null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.backup_restore_from), fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.backup_restore_hint),
                            fontSize = 12.sp, color = NewaxTheme.colors.textSecondary
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(NewaxTheme.colors.warningFill, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Warning, null, tint = NewaxTheme.colors.warning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_replace_warning), fontSize = 12.sp, color = NewaxTheme.colors.warning, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        if (password.isEmpty()) {
                            status = BackupStatus.Error(context.getString(R.string.backup_password_first))
                            return@OutlinedButton
                        }
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textPrimary),
                    enabled  = status !is BackupStatus.Working
                ) {
                    Icon(Icons.Outlined.FileUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.backup_pick_file), fontSize = 14.sp)
                }
            }
        }

        // ── Status ────────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = status !is BackupStatus.Idle,
            enter   = fadeIn(),
            exit    = fadeOut()
        ) {
            StatusCard(status) { status = BackupStatus.Idle }
        }

        // Spinner
        AnimatedVisibility(visible = status is BackupStatus.Working) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color       = NewaxTheme.colors.textPrimary
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.backup_working), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun BrSectionLabel(text: String) {
    Text(
        text,
        fontSize   = 11.sp,
        fontWeight = FontWeight.Medium,
        color      = NewaxTheme.colors.textTertiary,
        modifier   = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun PasswordStrengthBar(password: String) {
    if (password.isEmpty()) return
    val score = calcStrength(password)
    val (label, color, fraction) = when {
        score < 2 -> Triple(stringResource(R.string.backup_strength_weak),   NewaxTheme.colors.error,   0.25f)
        score < 3 -> Triple(stringResource(R.string.backup_strength_fair),   NewaxTheme.colors.warning, 0.5f)
        score < 4 -> Triple(stringResource(R.string.backup_strength_good),   Color(0xFF65A30D), 0.75f)
        else      -> Triple(stringResource(R.string.backup_strength_strong), NewaxTheme.colors.success, 1f)
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.backup_strength_label), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary)
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress  = { fraction },
            modifier  = Modifier.fillMaxWidth().height(4.dp),
            color     = color,
            trackColor = NewaxTheme.colors.surfaceMuted
        )
    }
}

private fun calcStrength(pw: String): Int {
    var s = 0
    if (pw.length >= 12) s++
    if (pw.any { it.isUpperCase() }) s++
    if (pw.any { it.isDigit() }) s++
    if (pw.any { !it.isLetterOrDigit() }) s++
    return s
}

@Composable
private fun StatusCard(status: BackupStatus, onDismiss: () -> Unit) {
    val style = when (status) {
        is BackupStatus.Success -> StatusStyle(NewaxTheme.colors.successFill, Color(0xFF86EFAC), Icons.Outlined.CheckCircle, NewaxTheme.colors.success, status.msg)
        is BackupStatus.Error   -> StatusStyle(NewaxTheme.colors.errorFill, Color(0xFFFCA5A5), Icons.Outlined.Error, NewaxTheme.colors.error, status.msg)
        else                    -> return
    }
    val (bg, border, icon, textColor, text) = style
    Card(
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = bg),
        border    = androidx.compose.foundation.BorderStroke(1.dp, border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp, color = textColor, modifier = Modifier.weight(1f), lineHeight = 18.sp)
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.Close, null, tint = textColor, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private data class StatusStyle(
    val bg: Color,
    val border: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val textColor: Color,
    val text: String
)
