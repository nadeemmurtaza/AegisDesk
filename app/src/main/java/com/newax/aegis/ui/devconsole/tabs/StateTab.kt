package com.newax.aegis.ui.devconsole.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.ui.devconsole.DevConsoleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SDF = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun StateTab(vm: DevConsoleViewModel) {
    val engine by vm.engine.collectAsState()
    val memEntries by vm.memEntries.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }

        item {
            SectionCard(title = "ResourceGovernor") {
                StatRow("Heavy job running", if (engine.heavyRunning) "YES" else "no",
                    if (engine.heavyRunning) Color(0xFFFFA726) else Color(0xFF66BB6A))
                StatRow("Critical running", if (engine.critRunning) "YES" else "no",
                    if (engine.critRunning) Color(0xFFEF5350) else Color(0xFF66BB6A))
                StatRow("Queue depth", engine.queued.toString())
                StatRow("Memory pressure", "${engine.pressure} / 5",
                    pressureColor(engine.pressure))
                StatRow("Jobs completed", engine.completed.toString())
                StatRow("Jobs failed", engine.failed.toString(),
                    if (engine.failed > 0) Color(0xFFEF5350) else MaterialTheme.colorScheme.onSurface)
            }
        }

        item {
            SectionCard(title = "OpportunisticScheduler") {
                StatRow("Registered tasks", engine.schedulerRegistered.toString())
                StatRow("Run count", engine.schedulerRunCount.toString())
                val lastRun = if (engine.schedulerLastRunMs > 0L)
                    SDF.format(Date(engine.schedulerLastRunMs)) else "never"
                StatRow("Last run", lastRun)
            }
        }

        item {
            SectionCard(title = "EncryptedMemory  (${memEntries.size} keys)") {
                if (memEntries.isEmpty()) {
                    Text(
                        text = "no entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }

        items(memEntries) { entry ->
            MemoryEntryRow(entry)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MemoryEntryRow(entry: DevConsoleViewModel.MemoryEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.key,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = entry.type,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        Text(
            text = entry.preview,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            text = "${entry.sizeBytes} bytes",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.padding(top = 6.dp), thickness = 0.5.dp)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(valueColor)
            )
            Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = valueColor)
        }
    }
}

private fun pressureColor(level: Int) = when {
    level >= 4 -> Color(0xFFEF5350)
    level >= 2 -> Color(0xFFFFA726)
    else       -> Color(0xFF66BB6A)
}
