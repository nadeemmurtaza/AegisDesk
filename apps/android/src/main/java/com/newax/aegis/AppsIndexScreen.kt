package com.newax.aegis

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.engine.apps.AppScanner
import com.newax.aegis.ui.components.SearchBar
import com.newax.aegis.ui.state.AppsIndexState
import com.newax.aegis.ui.theme.NewaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Route 4.3 — Apps index. Reached from Capabilities (4.1 → 4.3): fuzzy search
 * over installed apps, rebuild the deterministic app index, and launch an app
 * through the assistant's FLOW C path — `vm.submit("open <name>")` hits the
 * registered `open_app` intent, which resolves to a typed action that must
 * pass through the authority spine before the accessibility service launches
 * it. Launching is never a direct Intent here: the assistant path is the one
 * guarded sink.
 */
@Composable
fun AppsIndexScreen(vm: MainViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    val state = remember { AppsIndexState() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppsIndexState.AppRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var rebuilding by remember { mutableStateOf(false) }
    var indexedCount by remember { mutableStateOf(0) }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val rows = installed
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { info ->
                    AppsIndexState.AppRow(
                        name = pm.getApplicationLabel(info).toString(),
                        packageName = info.packageName
                    )
                }
                .sortedBy { it.name.lowercase() }
            apps = rows
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
    ) {
        SearchBar(
            value         = query,
            onValueChange = { query = it },
            placeholder   = stringResource(R.string.apps_index_search_hint),
            clearLabel    = stringResource(R.string.cd_clear),
            modifier      = Modifier.padding(top = 12.dp)
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    rebuilding = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { AppScanner.scan(context, vm.db) }
                        }
                        refresh()
                        indexedCount = apps.size
                        rebuilding = false
                    }
                },
                enabled = !rebuilding,
                border  = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                colors  = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary)
            ) { Text(stringResource(R.string.apps_index_rebuild), fontSize = 14.sp) }
            Spacer(Modifier.width(12.dp))
            if (rebuilding) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = NewaxTheme.colors.textSecondary)
            } else if (indexedCount > 0) {
                Text(stringResource(R.string.apps_index_rebuilt, indexedCount), fontSize = 12.sp, color = NewaxTheme.colors.success)
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NewaxTheme.colors.textSecondary)
            }
        } else {
            val visible = state.filter(query, apps)
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (apps.isEmpty()) stringResource(R.string.apps_index_empty)
                        else stringResource(R.string.apps_index_no_match),
                        color = NewaxTheme.colors.textTertiary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(visible, key = { it.packageName }) { row ->
                        AppsIndexRow(
                            row   = row,
                            onOpen = { vm.submit("open ${row.name}") },
                            launchLabel = stringResource(R.string.apps_index_launch, row.name)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsIndexRow(row: AppsIndexState.AppRow, onOpen: () -> Unit, launchLabel: String) {
    Card(
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.packageName, fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Rounded.OpenInNew,
                    contentDescription = launchLabel,
                    tint = NewaxTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
