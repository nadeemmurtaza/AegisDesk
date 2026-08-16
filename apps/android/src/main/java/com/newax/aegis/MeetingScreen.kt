package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.ui.state.MeetingScreenState
import com.newax.aegis.ui.theme.NewaxTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MeetingScreen(vm: MainViewModel, padding: PaddingValues) {
    // Entry formatting/parsing and the start-validation come from the
    // plain-Kotlin holder (T3.1) so the decisions are unit-tested; the date
    // formatting below is the only rendering-only piece left in the screen.
    val meetState = remember { MeetingScreenState() }
    val meetings = remember(vm.memoryVersion) { vm.memory.getCategory("meetings") }
    var showDialog by remember { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf("") }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    // The pattern resolves outside remember — stringResource is @Composable and
    // the remember block is not.
    val dateFormatPattern = stringResource(R.string.meeting_date_format)
    val fmt = remember(dateFormatPattern) { SimpleDateFormat(dateFormatPattern, Locale.getDefault()) }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false; titleInput = "" },
            containerColor   = NewaxTheme.colors.surface,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text(stringResource(R.string.meeting_new_title), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = NewaxTheme.colors.textPrimary) },
            text   = {
                Column {
                    Text(stringResource(R.string.meeting_field_title), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value         = titleInput,
                        onValueChange = { titleInput = it },
                        placeholder   = { Text(stringResource(R.string.meeting_placeholder), color = NewaxTheme.colors.textTertiary) },
                        singleLine    = true,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NewaxTheme.colors.textPrimary,
                            unfocusedBorderColor = NewaxTheme.colors.border,
                            cursorColor          = NewaxTheme.colors.textPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = NewaxTheme.colors.textPrimary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (meetState.canStart(titleInput)) {
                        val entry = meetState.newEntry(titleInput, System.currentTimeMillis())
                        val updated = meetState.addMeeting(meetings, entry)
                        vm.memory.setCategory("meetings", updated)
                        vm.bumpMemoryVersion()
                        titleInput = ""
                        showDialog = false
                    }
                }) { Text(stringResource(R.string.action_start), color = NewaxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false; titleInput = "" }) {
                    Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary)
                }
            }
        )
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding      = PaddingValues(vertical = 12.dp)
    ) {
        // Header card
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.meeting_count, meetings.size), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.meeting_stored_on_device), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    }
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }

        // Start new meeting
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.textPrimary),
                elevation = CardDefaults.cardElevation(0.dp),
                onClick   = { showDialog = true }
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.meeting_start_new), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                }
            }
        }

        if (meetings.isNotEmpty()) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionLabel(stringResource(R.string.meeting_section_past)) }

            items(meetings, key = { it }) { entry ->
                val parsed = meetState.parseEntry(entry)
                val title   = parsed.title
                val dateStr = parsed.timestampMillis?.let { fmt.format(Date(it)) } ?: ""
                val isExpanded = expandedKey == entry

                Card(
                    shape     = RoundedCornerShape(16.dp),
                    colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                    border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    elevation = CardDefaults.cardElevation(0.dp),
                    onClick   = { expandedKey = if (isExpanded) null else entry }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (dateStr.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(dateStr, fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
                                }
                            }
                            Icon(
                                if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(20.dp)
                            )
                        }
                        if (isExpanded) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = NewaxTheme.colors.border)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.meeting_notes_hint), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 20.sp)
                        }
                    }
                }
            }
        } else {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Groups, contentDescription = null, tint = NewaxTheme.colors.textTertiary, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(R.string.meeting_empty_title), fontSize = 15.sp, color = NewaxTheme.colors.textSecondary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.meeting_empty_hint), fontSize = 13.sp, color = NewaxTheme.colors.textTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize   = 11.sp,
        fontWeight = FontWeight.Medium,
        color      = NewaxTheme.colors.textTertiary,
        modifier   = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}
