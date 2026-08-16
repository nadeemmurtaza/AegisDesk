package com.newax.aegis.ui.devconsole.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.newax.aegis.R
import com.newax.aegis.db.entity.TriggerRule
import com.newax.aegis.ui.devconsole.DevConsoleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SDF_LONG = SimpleDateFormat("MMM dd HH:mm:ss", Locale.getDefault())

private enum class EventType(val labelRes: Int) {
    NOTIFICATION(R.string.dev_trigger_event_notification),
    WINDOW_CHANGED(R.string.dev_trigger_event_window_changed),
    SCREEN_CONTENT(R.string.dev_trigger_event_screen_content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersTab(vm: DevConsoleViewModel) {
    val rules   by vm.triggerRules.collectAsState()
    val lastFired by vm.lastFired.collectAsState()

    var showFireForm by remember { mutableStateOf(false) }
    var eventType    by remember { mutableStateOf(EventType.NOTIFICATION) }
    var field1       by remember { mutableStateOf("") }
    var field2       by remember { mutableStateOf("") }
    var field3       by remember { mutableStateOf("") }
    var dropExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dev_trigger_rules_count, rules.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = { showFireForm = !showFireForm },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(if (showFireForm) stringResource(R.string.dev_trigger_hide) else stringResource(R.string.dev_trigger_inject), fontSize = 12.sp)
                }
            }
        }

        if (showFireForm) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.dev_trigger_injector),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        ExposedDropdownMenuBox(
                            expanded = dropExpanded,
                            onExpandedChange = { dropExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = stringResource(eventType.labelRes),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.dev_trigger_event_type), fontSize = 11.sp) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(dropExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            ExposedDropdownMenu(
                                expanded = dropExpanded,
                                onDismissRequest = { dropExpanded = false }
                            ) {
                                EventType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(type.labelRes), fontSize = 13.sp) },
                                        onClick = { eventType = type; dropExpanded = false }
                                    )
                                }
                            }
                        }

                        when (eventType) {
                            EventType.NOTIFICATION -> {
                                OutlinedTextField(
                                    value = field1, onValueChange = { field1 = it },
                                    label = { Text(stringResource(R.string.dev_trigger_sender), fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                OutlinedTextField(
                                    value = field2, onValueChange = { field2 = it },
                                    label = { Text(stringResource(R.string.dev_trigger_text), fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                                OutlinedTextField(
                                    value = field3, onValueChange = { field3 = it },
                                    label = { Text(stringResource(R.string.dev_trigger_package), fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                            }
                            EventType.WINDOW_CHANGED -> {
                                OutlinedTextField(
                                    value = field1, onValueChange = { field1 = it },
                                    label = { Text(stringResource(R.string.dev_trigger_package_name), fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                            }
                            EventType.SCREEN_CONTENT -> {
                                OutlinedTextField(
                                    value = field1, onValueChange = { field1 = it },
                                    label = { Text(stringResource(R.string.dev_trigger_visible_text), fontSize = 11.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3,
                                    textStyle = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Button(
                            onClick = {
                                when (eventType) {
                                    EventType.NOTIFICATION   -> vm.fireNotificationEvent(field1, field2, field3)
                                    EventType.WINDOW_CHANGED -> vm.fireWindowChanged(field1)
                                    EventType.SCREEN_CONTENT -> vm.fireScreenContent(field1)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.dev_trigger_fire), fontSize = 13.sp, color = Color.White)
                        }

                        lastFired?.let {
                            Text(
                                text = stringResource(R.string.dev_trigger_last_fired, it),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF81C784),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        if (rules.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.dev_trigger_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }

        items(rules, key = { it.id }) { rule ->
            TriggerRuleCard(rule, vm)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TriggerRuleCard(rule: TriggerRule, vm: DevConsoleViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.label.ifBlank { rule.conditionType },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (rule.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${rule.conditionType} → ${rule.actionType}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { vm.toggleRule(rule.id, it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dev_trigger_if), style = MaterialTheme.typography.labelSmall, color = Color(0xFF64B5F6))
                    Text(
                        text = rule.conditionParams.take(80),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dev_trigger_then), style = MaterialTheme.typography.labelSmall, color = Color(0xFF81C784))
                    Text(
                        text = rule.actionParams.take(80),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val lastFired = if (rule.lastFiredMs > 0L)
                    stringResource(R.string.dev_trigger_fired_at, SDF_LONG.format(Date(rule.lastFiredMs)))
                else stringResource(R.string.dev_trigger_never_fired)
                Text(
                    text = stringResource(R.string.dev_trigger_debounce, rule.debounceMs, lastFired),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { vm.deleteRule(rule.id) },
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(stringResource(R.string.dev_delete), fontSize = 11.sp, color = Color(0xFFEF5350))
                }
            }
        }
    }
}
