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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import com.newax.aegis.R
import com.newax.aegis.db.entity.FileObject
import com.newax.aegis.ui.devconsole.DevConsoleViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SDF = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())

@Composable
fun FilesTab(vm: DevConsoleViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val stats    by vm.fileStats.collectAsState()
    val recent   by vm.recentFiles.collectAsState()
    val status   by vm.indexStatus.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(10.dp)) }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.dev_file_index_count, stats.total),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))

                    StatBar(label = stringResource(R.string.dev_stat_total), value = stats.total, max = maxOf(stats.total, 1), color = MaterialTheme.colorScheme.primary)
                    StatBar(label = stringResource(R.string.dev_stat_duplicates), value = stats.duplicates, max = maxOf(stats.total, 1), color = Color(0xFFEF5350))
                    StatBar(label = stringResource(R.string.dev_stat_unindexed), value = stats.unindexed, max = maxOf(stats.total, 1), color = Color(0xFFFFA726))
                    StatBar(label = stringResource(R.string.dev_stat_needs_text), value = stats.needsText, max = maxOf(stats.total, 1), color = Color(0xFF64B5F6))
                    StatBar(label = stringResource(R.string.dev_stat_needs_entities), value = stats.needsEntities, max = maxOf(stats.total, 1), color = Color(0xFFBA68C8))
                    StatBar(label = stringResource(R.string.dev_stat_needs_visual), value = stats.needsVisual, max = maxOf(stats.total, 1), color = Color(0xFF4DB6AC))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LabelValue(stringResource(R.string.dev_label_text_rows), stats.textContent.toString())
                        LabelValue(stringResource(R.string.dev_label_entity_rows), stats.entityLinks.toString())
                    }
                }
            }
        }

        item {
            status?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A237E).copy(alpha = 0.4f))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = msg, style = MaterialTheme.typography.bodySmall, color = Color(0xFF90CAF9))
                    TextButton(onClick = { vm.clearIndexStatus() }, modifier = Modifier.height(24.dp)) {
                        Text(stringResource(R.string.dev_dismiss), fontSize = 11.sp)
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.dev_index_actions),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { vm.runScanAll() },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.dev_scan_all), fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { vm.refreshAll() },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                        ) {
                            Text(stringResource(R.string.dev_refresh), fontSize = 11.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StageButton(stringResource(R.string.dev_stage_text), Color(0xFF64B5F6), Modifier.weight(1f)) { vm.runTextExtraction() }
                        StageButton(stringResource(R.string.dev_stage_entities), Color(0xFFBA68C8), Modifier.weight(1f)) { vm.runEntityExtraction() }
                        StageButton(stringResource(R.string.dev_stage_visual), Color(0xFF4DB6AC), Modifier.weight(1f)) { vm.runVisualIndexing() }
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.dev_recent_files_count, recent.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        items(recent) { fo ->
            FileObjectRow(fo)
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StageButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.85f))
    ) {
        Text(label, fontSize = 11.sp, color = Color.Black)
    }
}

@Composable
private fun StatBar(label: String, value: Int, max: Int, color: Color) {
    val fraction = if (max > 0) value.toFloat() / max else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FileObjectRow(fo: FileObject) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(indexStateColor(fo.indexState))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fo.filename,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = humanSize(context, fo.sizeBytes),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("·", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = fo.mimeType.take(24),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = SDF.format(Date(fo.modifiedMs)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "idx=${fo.indexState}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                fontFamily = FontFamily.Monospace,
                color = indexStateColor(fo.indexState)
            )
        }
    }
    Spacer(Modifier.height(3.dp))
}

private fun indexStateColor(state: Int) = when {
    state and FileObject.INDEX_STATE_FULL == FileObject.INDEX_STATE_FULL -> Color(0xFF66BB6A)
    state > 0 -> Color(0xFFFFA726)
    else      -> Color(0xFF9E9E9E)
}

private fun humanSize(context: android.content.Context, bytes: Long) = when {
    bytes > 1_048_576 -> context.getString(R.string.dev_human_mb, "%.1f".format(bytes / 1_048_576.0))
    bytes > 1024      -> context.getString(R.string.dev_human_kb, bytes / 1024)
    else              -> context.getString(R.string.dev_human_b, bytes)
}
