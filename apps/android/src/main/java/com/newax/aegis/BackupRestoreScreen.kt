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
import com.newax.aegis.ui.theme.NewaxLightColors

// ── Design tokens ─────────────────────────────────────────────────────────────
private val BR_Surface      = NewaxLightColors.surface
private val BR_SurfaceMuted = NewaxLightColors.surfaceMuted
private val BR_TextPri      = NewaxLightColors.textPrimary
private val BR_TextSec      = NewaxLightColors.textSecondary
private val BR_TextTer      = NewaxLightColors.textTertiary
private val BR_Border       = NewaxLightColors.border
private val BR_Green        = NewaxLightColors.success
private val BR_GreenBg      = NewaxLightColors.successFill
private val BR_Red          = NewaxLightColors.error
private val BR_RedBg        = NewaxLightColors.errorFill
private val BR_Amber        = NewaxLightColors.warning
private val BR_AmberBg      = NewaxLightColors.warningFill
private val BR_Blue         = NewaxLightColors.info
private val BR_BlueBg       = NewaxLightColors.infoFill
private val BR_Primary      = NewaxLightColors.textPrimary

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
                    status = BackupStatus.Success("Backup exported. Save the password — it cannot be recovered.")
                    password        = ""
                    confirmPassword = ""
                }.onFailure { e ->
                    status = BackupStatus.Error(e.message ?: "Export failed")
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
            containerColor = BR_Surface,
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = BR_Red, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Replace all data?", fontWeight = FontWeight.SemiBold, color = BR_TextPri)
                }
            },
            text = {
                Text(
                    "This will erase your current memory, settings, and drafts and replace them with the backup. This cannot be undone. Make sure you have the correct password before continuing.",
                    color = BR_TextSec, lineHeight = 20.sp
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
                                    status = BackupStatus.Success("Backup restored successfully.")
                                    password = ""
                                }.onFailure { e ->
                                    val msg = when (e) {
                                        is SecurityException -> "Wrong password or corrupted file."
                                        is IllegalArgumentException -> "Invalid backup file."
                                        else -> e.message ?: "Restore failed"
                                    }
                                    status = BackupStatus.Error(msg)
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BR_Red)
                ) { Text("Restore", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false; pendingRestoreUri = null }) {
                    Text("Cancel", color = BR_TextSec)
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
            colors    = CardDefaults.cardColors(containerColor = BR_BlueBg),
            border    = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBFDBFE)),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(Modifier.padding(14.dp)) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = BR_Blue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Military-grade encryption", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = BR_Blue)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "AES-256-GCM · PBKDF2-SHA256 (310,000 iterations) · 32-byte random salt\n" +
                        "Your password is never stored anywhere. Without it, the backup is unreadable.",
                        fontSize = 12.sp, color = Color(0xFF1D4ED8), lineHeight = 18.sp
                    )
                }
            }
        }

        // ── Password fields ───────────────────────────────────────────────────
        BrSectionLabel("Backup Password")

        Card(
            shape     = RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = BR_Surface),
            border    = androidx.compose.foundation.BorderStroke(1.dp, BR_Border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = password,
                    onValueChange = { password = it },
                    label         = { Text("Password") },
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon  = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(
                                if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPw) "Hide" else "Show",
                                tint = BR_TextTer
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp),
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = BR_Primary,
                        unfocusedBorderColor = BR_Border
                    )
                )

                OutlinedTextField(
                    value         = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label         = { Text("Confirm password (for export)") },
                    visualTransformation = if (showConfirmPw) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon  = {
                        IconButton(onClick = { showConfirmPw = !showConfirmPw }) {
                            Icon(
                                if (showConfirmPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = null,
                                tint = BR_TextTer
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(12.dp),
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = BR_Primary,
                        unfocusedBorderColor = BR_Border
                    ),
                    isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                    supportingText = {
                        if (confirmPassword.isNotEmpty() && confirmPassword != password) {
                            Text("Passwords do not match", color = BR_Red, fontSize = 12.sp)
                        }
                    }
                )

                PasswordStrengthBar(password)
            }
        }

        // ── Export ────────────────────────────────────────────────────────────
        BrSectionLabel("Export")

        Card(
            shape     = RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = BR_Surface),
            border    = androidx.compose.foundation.BorderStroke(1.dp, BR_Border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(BR_SurfaceMuted, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.CloudUpload, null, tint = BR_TextSec, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Save encrypted backup", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = BR_TextPri)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Save to Google Drive, device storage, or any location",
                            fontSize = 12.sp, color = BR_TextSec
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (password.length < 8) {
                            status = BackupStatus.Error("Password must be at least 8 characters")
                            return@Button
                        }
                        if (password != confirmPassword) {
                            status = BackupStatus.Error("Passwords do not match")
                            return@Button
                        }
                        exportLauncher.launch(BackupManager.suggestedFilename())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = BR_Primary),
                    enabled  = status !is BackupStatus.Working
                ) {
                    Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export backup (.aeb)", fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tip: Save to Google Drive for cross-device restore. The file opens only with your password.",
                    fontSize = 11.sp, color = BR_TextTer, lineHeight = 16.sp
                )
            }
        }

        // ── Import ────────────────────────────────────────────────────────────
        BrSectionLabel("Restore")

        Card(
            shape     = RoundedCornerShape(18.dp),
            colors    = CardDefaults.cardColors(containerColor = BR_Surface),
            border    = androidx.compose.foundation.BorderStroke(1.dp, BR_Border),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(BR_SurfaceMuted, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Outlined.CloudDownload, null, tint = BR_TextSec, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Restore from backup", fontWeight = FontWeight.Medium, fontSize = 14.sp, color = BR_TextPri)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Pick a .aeb file from Google Drive or your device",
                            fontSize = 12.sp, color = BR_TextSec
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(BR_AmberBg, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Warning, null, tint = BR_Amber, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("All current data will be replaced and cannot be recovered", fontSize = 12.sp, color = BR_Amber, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        if (password.isEmpty()) {
                            status = BackupStatus.Error("Enter the backup password first")
                            return@OutlinedButton
                        }
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, BR_Border),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = BR_TextPri),
                    enabled  = status !is BackupStatus.Working
                ) {
                    Icon(Icons.Outlined.FileUpload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Pick backup file", fontSize = 14.sp)
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
                    color       = BR_Primary
                )
                Spacer(Modifier.width(10.dp))
                Text("Working… (key derivation may take a few seconds)", fontSize = 12.sp, color = BR_TextSec)
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
        color      = BR_TextTer,
        modifier   = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun PasswordStrengthBar(password: String) {
    if (password.isEmpty()) return
    val score = calcStrength(password)
    val (label, color, fraction) = when {
        score < 2 -> Triple("Weak",   BR_Red,   0.25f)
        score < 3 -> Triple("Fair",   BR_Amber, 0.5f)
        score < 4 -> Triple("Good",   Color(0xFF65A30D), 0.75f)
        else      -> Triple("Strong", BR_Green, 1f)
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Password strength", fontSize = 11.sp, color = BR_TextTer)
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress  = { fraction },
            modifier  = Modifier.fillMaxWidth().height(4.dp),
            color     = color,
            trackColor = BR_SurfaceMuted
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
        is BackupStatus.Success -> StatusStyle(BR_GreenBg, Color(0xFF86EFAC), Icons.Outlined.CheckCircle, BR_Green, status.msg)
        is BackupStatus.Error   -> StatusStyle(BR_RedBg, Color(0xFFFCA5A5), Icons.Outlined.Error, BR_Red, status.msg)
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
