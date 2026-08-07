package com.newax.aegis.ui.devconsole.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.engine.dev.DevLogger
import com.newax.aegis.ui.devconsole.DevConsoleViewModel

private val LEVEL_COLORS = mapOf(
    DevLogger.Level.VERBOSE to Color(0xFF9E9E9E),
    DevLogger.Level.DEBUG   to Color(0xFF64B5F6),
    DevLogger.Level.INFO    to Color(0xFF81C784),
    DevLogger.Level.WARN    to Color(0xFFFFB74D),
    DevLogger.Level.ERROR   to Color(0xFFEF5350)
)

@Composable
fun LogsTab(vm: DevConsoleViewModel) {
    val allEntries by vm.logEntries.collectAsState()
    val context = LocalContext.current
    var levelFilter by remember { mutableStateOf<DevLogger.Level?>(null) }
    var tagFilter by remember { mutableStateOf("") }

    val entries = remember(allEntries, levelFilter, tagFilter) {
        allEntries
            .let { list -> if (levelFilter != null) list.filter { it.level == levelFilter } else list }
            .let { list -> if (tagFilter.isNotBlank()) list.filter { it.tag.contains(tagFilter, ignoreCase = true) } else list }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("ALL", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors()
            )
            DevLogger.Level.entries.forEach { lvl ->
                val color = LEVEL_COLORS[lvl] ?: Color.White
                FilterChip(
                    selected = levelFilter == lvl,
                    onClick = { levelFilter = if (levelFilter == lvl) null else lvl },
                    label = {
                        Text(
                            text = lvl.short,
                            fontSize = 11.sp,
                            color = if (levelFilter == lvl) Color.Black else color
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color,
                        selectedLabelColor = Color.Black
                    )
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.copyLogs(context) }, modifier = Modifier.height(32.dp)) {
                Text("Copy", fontSize = 11.sp)
            }
            OutlinedButton(onClick = { vm.clearLogs() }, modifier = Modifier.height(32.dp)) {
                Text("Clear", fontSize = 11.sp)
            }
        }

        HorizontalDivider(thickness = 0.5.dp)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
        ) {
            items(entries, key = { it.id }) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: DevLogger.Entry) {
    val color = LEVEL_COLORS[entry.level] ?: Color.White
    val timeStr = remember(entry.timestampMs) {
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(entry.timestampMs))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = timeStr,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF616161),
            modifier = Modifier.padding(top = 1.dp)
        )
        Text(
            text = "${entry.level.short}/${entry.tag}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )
        Text(
            text = entry.message,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFE0E0E0),
            modifier = Modifier.weight(1f)
        )
    }
}

