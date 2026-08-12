package com.newax.aegis.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.desktop.ui.state.AppsScreenState
import com.newax.aegis.platform.windows.AppIndexEntry

/** How many index entries one screen shows before the "… and N more" note (CLI showed 40). */
private const val VISIBLE_ENTRIES = 200

/**
 * Apps screen — the Start Menu app index with a search box (the `apps [query]`
 * CLI logic lifted into a window). States: no index (Windows-only surface),
 * empty (no apps indexed), no match, content.
 */
@Composable
fun AppsScreen(state: AppsScreenState) {
    val query by state.query.collectAsState()
    val entries = state.matches()
    val index = state.index()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // ── Header + search ────────────────────────────────────────────────
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceColor),
            border = BorderStroke(1.dp, BorderColor),
            elevation = CardDefaults.cardElevation(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 10.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Apps · Start Menu index",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TextPrimaryColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        index == null -> "Not initialized — the Start Menu index is Windows-only"
                        query.isBlank() -> "${entries.size} installed apps"
                        entries.isEmpty() -> "No apps match \"$query\""
                        else -> "${entries.size} matches for \"$query\""
                    },
                    fontSize = 13.sp,
                    color = TextSecondaryColor
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { state.setQuery(it) },
                    placeholder = { Text("Search installed apps…", fontSize = 14.sp, color = TextTertiaryColor) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TextSecondaryColor,
                        unfocusedBorderColor = BorderColor,
                        focusedContainerColor = SurfaceColor,
                        unfocusedContainerColor = SurfaceColor
                    ),
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = TextTertiaryColor)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── States ─────────────────────────────────────────────────────────
        when {
            index == null -> Box(Modifier.fillMaxSize()) {
                EmptyState(
                    "App index unavailable",
                    "The Start Menu index only exists on Windows — this app is showing its honest platform state.",
                )
            }
            entries.isEmpty() && query.isBlank() -> Box(Modifier.fillMaxSize()) {
                EmptyState("No apps indexed", "The Start Menu Programs folders contained no shortcuts.")
            }
            entries.isEmpty() -> Box(Modifier.fillMaxSize()) {
                EmptyState("No apps match \"$query\"", "Try a shorter or different query.")
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(entries.take(VISIBLE_ENTRIES), key = { it.lnkPath }) { AppCard(it) }
                if (entries.size > VISIBLE_ENTRIES) {
                    item {
                        Text(
                            "… and ${entries.size - VISIBLE_ENTRIES} more",
                            fontSize = 12.sp,
                            color = TextTertiaryColor,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(entry: AppIndexEntry) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimaryColor)
                    Spacer(Modifier.height(2.dp))
                    Text(entry.category, fontSize = 11.sp, color = TextTertiaryColor)
                }
                Tag(entry.lnkPath.substringAfterLast('.').ifBlank { "lnk" }.uppercase(), TextSecondaryColor)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                entry.lnkPath,
                fontSize = 11.5.sp,
                color = TextSecondaryColor,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
