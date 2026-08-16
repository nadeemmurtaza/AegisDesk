package com.newax.aegis

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.engine.apps.AppPermissionManager
import com.newax.aegis.engine.apps.AppPermissionState
import com.newax.aegis.ui.theme.NewaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppItem(val packageName: String, val appName: String, var state: AppPermissionState)

/** Display labels for the permission states (T3.2b) — the enum name is the
 *  storage key (shared/core), the user sees a localized label. */
@Composable
private fun permissionLabel(state: AppPermissionState): String = when (state) {
    AppPermissionState.ALLOWED -> stringResource(R.string.app_perm_allowed)
    AppPermissionState.DENIED -> stringResource(R.string.app_perm_denied)
    AppPermissionState.ASK_EVERY_TIME -> stringResource(R.string.app_perm_ask_every_time)
}

@Composable
fun AppPermissionScreen(padding: PaddingValues) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val appItems = installedApps.filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }.map { appInfo ->
                AppItem(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    state = AppPermissionManager.getPermission(appInfo.packageName)
                )
            }.sortedBy { it.appName }
            apps = appItems
            loading = false
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        items(apps, key = { it.packageName }) { app ->
            Card(
                shape = RoundedCornerShape(12.dp),
                // T3.5e — token fix: this screen still carried the pre-theme
                // hardcoded palette (a T3.3 miss); it now follows the design
                // tokens like every other surface.
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.appName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                        Text(app.packageName, fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
                    }
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(permissionLabel(app.state))
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            AppPermissionState.values().forEach { state ->
                                DropdownMenuItem(
                                    text = { Text(permissionLabel(state)) },
                                    onClick = {
                                        AppPermissionManager.setPermission(app.packageName, state)
                                        app.state = state
                                        expanded = false
                                        // force recomposition trick
                                        apps = apps.toList()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
