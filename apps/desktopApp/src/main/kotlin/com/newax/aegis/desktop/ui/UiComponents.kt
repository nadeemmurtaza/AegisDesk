package com.newax.aegis.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.ui.state.ExportState
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelState
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.PrivilegeLevel

/** Status dot color per capability status — Android CapabilitiesScreen mapping. */
fun statusColor(status: CapabilityStatus): Color = when (status) {
    CapabilityStatus.READY -> ReadyColor
    CapabilityStatus.MISSING_PERMISSION -> WarningColor
    CapabilityStatus.MISSING_CREDENTIAL -> Color(0xFFF97316)
    CapabilityStatus.DISABLED -> MutedColor
    CapabilityStatus.UNAVAILABLE -> ErrorColor
    CapabilityStatus.NOT_SUPPORTED -> NotSupportedColor
}

/** "Ready", "Missing permission", … — the CapabilitiesScreen label mapping. */
fun CapabilityStatus.label(): String =
    name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** "Read-only", "Standard", "High-impact", "Critical". */
fun PrivilegeLevel.label(): String = when (this) {
    PrivilegeLevel.READ_ONLY -> "Read-only"
    PrivilegeLevel.STANDARD -> "Standard"
    PrivilegeLevel.HIGH_IMPACT_SYSTEM -> "High-impact"
    PrivilegeLevel.CRITICAL -> "Critical"
}

/** Goal state chip label — the GoalsScreen mapping. */
fun GoalState.label(): String = when (this) {
    GoalState.OPEN -> "Open"
    GoalState.ACTIVE -> "Active"
    GoalState.BLOCKED -> "Blocked"
    GoalState.COMPLETED -> "Completed"
    GoalState.ABANDONED -> "Abandoned"
}

/** Goal state dot color — the GoalsScreen mapping. */
fun GoalState.dotColor(): Color = when (this) {
    GoalState.OPEN -> MutedColor
    GoalState.ACTIVE -> ReadyColor
    GoalState.BLOCKED -> ErrorColor
    GoalState.COMPLETED -> ReadyColor
    GoalState.ABANDONED -> MutedColor
}

/** Model lifecycle label — the CapabilitiesScreen ModelProviderCard mapping. */
fun ModelState.label(): String = when (this) {
    ModelState.NOT_INSTALLED -> "Not installed"
    ModelState.LOADING -> "Loading"
    ModelState.READY -> "Ready"
    ModelState.ERROR -> "Error"
    ModelState.CLOSED -> "Closed"
}

fun modelStateColor(state: ModelState): Color = when (state) {
    ModelState.READY -> ReadyColor
    ModelState.LOADING -> WarningColor
    ModelState.ERROR -> ErrorColor
    ModelState.NOT_INSTALLED -> NotSupportedColor
    ModelState.CLOSED -> NotSupportedColor
}

/** Runtime format label — LiteRT-LM / GGUF / unknown. */
fun ModelFormat.label(): String = when (this) {
    ModelFormat.LITERTLM -> "LiteRT-LM"
    ModelFormat.GGUF -> "GGUF"
    ModelFormat.UNKNOWN -> "Format unknown"
}

/** Human-readable byte size ("1.4 GB", "512 MB", …). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576 -> "${"%.0f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Human-readable duration ("512 ms", "1.4 s") — the audit summary and run meta lines. */
fun formatDurationMs(ms: Long): String = when {
    ms >= 1_000 -> "${"%.1f".format(ms / 1000.0)} s"
    else -> "$ms ms"
}

/** Rounded pill with muted background — the Android Tag component. */
@Composable
fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SurfaceMutedColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** Colored status pill — the Android StatusChip component. */
@Composable
fun StatusChip(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Colored dot — the Android state-dot component. */
@Composable
fun StateDot(color: Color, size: Dp = 12.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/** Small monospace key · value line — the Android MetaRow component. */
@Composable
fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = TextTertiaryColor, modifier = Modifier.weight(0.35f))
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = TextSecondaryColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.65f)
        )
    }
}

/** Centered empty/error state with icon dot, title and optional hint — the Android pattern. */
@Composable
fun EmptyState(title: String, hint: String?, iconColor: Color = TextTertiaryColor) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StateDot(iconColor, size = 12.dp)
            Spacer(Modifier.height(14.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextSecondaryColor)
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    hint,
                    fontSize = 13.sp,
                    color = TextTertiaryColor,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    }
}

/** Outcome of the last CSV export — shared by the Audit and Policy tabs (idle, written path, or honest failure). */
@Composable
fun ExportStatusLine(state: ExportState, idleHint: String = "Exports land in ~/.aegis/ as CSV") {
    when (state) {
        is ExportState.Idle -> Text(
            idleHint,
            fontSize = 12.sp,
            color = TextTertiaryColor
        )
        is ExportState.Done -> Text(
            "Exported → $state.path",
            fontSize = 12.sp,
            color = ReadyColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        is ExportState.Failed -> Text(
            state.message,
            fontSize = 12.sp,
            color = ErrorColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
