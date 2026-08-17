package com.newax.aegis.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.desktop.DesktopModelProviderHolder
import com.newax.aegis.desktop.ui.state.ChatState
import com.newax.aegis.desktop.ui.state.ConversationGroupUi
import com.newax.aegis.desktop.ui.state.ConversationListUiModel
import com.newax.aegis.desktop.ui.state.ConversationRowUi
import com.newax.aegis.ui.a11y.heading
import com.newax.aegis.ui.a11y.statusSemantics
import com.newax.aegis.ui.theme.NewaxTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The sidebar destinations — the desktop IA's section links (§5.1 mapping). */
enum class SidebarSection {
    TASKS, CAPABILITIES, SETTINGS,
}

/** Sub-views inside Capabilities (4.1 status · 4.3 apps) and Settings (5.3.1 policy · 5.3.1.3 audit). */
enum class SidebarSubView {
    STATUS, APPS, POLICY, AUDIT,
}

/**
 * Route 1.1 — the conversation list, rendered as the desktop sidebar: brand
 * header, New chat, dated conversation groups (Today / Yesterday / MMM d),
 * the section links (Tasks · Capabilities · Settings), and the model status
 * footer. An empty list shows the honest empty state with New chat.
 */
@Composable
fun ConversationSidebar(
    state: ChatState,
    selectedSection: SidebarSection?,
    selectedSubView: SidebarSubView?,
    onSelectSection: (SidebarSection, SidebarSubView?) -> Unit,
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 280.dp,
) {
    val conversations by state.conversations.collectAsState()

    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(vertical = 12.dp),
    ) {
        // ── Brand header (1.1.2) ───────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Newax Aegis",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = NewaxTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewChat) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "New chat",
                    tint = NewaxTheme.colors.accent,
                )
            }
        }

        // ── Conversation groups (1.1.5–1.1.6) ──────────────────────────────
        when (val model = conversations) {
            is ConversationListUiModel.Loading -> Box(
                Modifier.fillMaxWidth().padding(16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Loading conversations…", fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
            }

            is ConversationListUiModel.Error -> Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Conversations unavailable",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = NewaxTheme.colors.error,
                )
                Spacer(Modifier.height(2.dp))
                Text(model.message, fontSize = 11.sp, color = NewaxTheme.colors.textSecondary)
            }

            is ConversationListUiModel.Content -> {
                if (model.groups.isEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "No conversations yet",
                            fontSize = 13.sp,
                            color = NewaxTheme.colors.textSecondary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "New chat starts one.",
                            fontSize = 11.sp,
                            color = NewaxTheme.colors.textTertiary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                    ) {
                        model.groups.forEach { group ->
                            item(key = "header-${group.label}") { GroupHeader(group.label) }
                            items(group.conversations, key = { it.id }) { row ->
                                ConversationRow(
                                    row = row,
                                    active = state.activeId.value == row.id,
                                    onClick = { onOpenConversation(row.id) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ── Section links (1.1.7) — the §5.1 IA mapping ────────────────────
        HorizontalDivider(color = NewaxTheme.colors.border)
        SectionLink(
            icon = Icons.Rounded.Flag,
            label = "Tasks",
            selected = selectedSection == SidebarSection.TASKS,
            onClick = { onSelectSection(SidebarSection.TASKS, null) },
        )
        SectionLink(
            icon = Icons.Rounded.Shield,
            label = "Capabilities",
            selected = selectedSection == SidebarSection.CAPABILITIES,
            onClick = { onSelectSection(SidebarSection.CAPABILITIES, SidebarSubView.STATUS) },
        )
        SectionLink(
            icon = Icons.Rounded.Apps,
            label = "Apps",
            selected = selectedSection == SidebarSection.CAPABILITIES && selectedSubView == SidebarSubView.APPS,
            onClick = { onSelectSection(SidebarSection.CAPABILITIES, SidebarSubView.APPS) },
        )
        SectionLink(
            icon = Icons.Rounded.Gavel,
            label = "Policy",
            selected = selectedSection == SidebarSection.SETTINGS && selectedSubView == SidebarSubView.POLICY,
            onClick = { onSelectSection(SidebarSection.SETTINGS, SidebarSubView.POLICY) },
        )
        SectionLink(
            icon = Icons.Rounded.History,
            label = "Audit",
            selected = selectedSection == SidebarSection.SETTINGS && selectedSubView == SidebarSubView.AUDIT,
            onClick = { onSelectSection(SidebarSection.SETTINGS, SidebarSubView.AUDIT) },
        )

        // ── Model status footer (1.1.8) ────────────────────────────────────
        HorizontalDivider(color = NewaxTheme.colors.border)
        ModelStatusFooter()
    }
}

@Composable
private fun GroupHeader(label: String) {
    Text(
        label,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = NewaxTheme.colors.textTertiary,
        modifier = Modifier
            .heading()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ConversationRow(row: ConversationRowUi, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (active) NewaxTheme.colors.surfaceSelected else androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.title,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = NewaxTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    relativeTime(row.updatedAtMs, System.currentTimeMillis()),
                    fontSize = 10.sp,
                    color = NewaxTheme.colors.textTertiary,
                )
            }
            if (row.preview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    row.preview,
                    fontSize = 12.sp,
                    color = NewaxTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) NewaxTheme.colors.accent else NewaxTheme.colors.textSecondary,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) NewaxTheme.colors.textPrimary else NewaxTheme.colors.textSecondary,
        )
        if (selected) {
            Spacer(Modifier.weight(1f))
            StateDot(NewaxTheme.colors.accent, size = 6.dp)
        }
    }
}

@Composable
private fun ModelStatusFooter() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val provider = DesktopModelProviderHolder.current()
        val modelState by provider.state.collectAsState()
        StateDot(modelStateColor(modelState), size = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            provider.descriptor.modelName,
            fontSize = 11.sp,
            color = NewaxTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .statusSemantics("Model ${modelState.label()}"),
        )
    }
}

/** "12:04" today, "Yesterday", or "Aug 12" — the relative row timestamp (1.1.6). */
fun relativeTime(epochMs: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(epochMs)
    val date = instant.atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (date) {
        today -> TIME_FORMATTER.format(instant.atZone(zone))
        today.minusDays(1) -> "Yesterday"
        else -> DATE_FORMATTER.format(date)
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
