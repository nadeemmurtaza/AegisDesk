package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.ui.components.SectionHeader
import com.newax.aegis.ui.components.SettingsRow
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The route-tree section homes (T3.5d — compact IA). The drawer now lists the
 * spec's four sections (Memory · Tasks · Capabilities · Settings) instead of
 * eighteen flat entries; a section with sub-routes gets a home that lists
 * them (docs/UI_DESIGN.md §6.4/§6.5 — 2.x and 3.x), while single-landing
 * sections (Capabilities 4.1, Settings 5) link straight to their screens.
 *
 * Rows are the shared [SettingsRow] — one focus stop, 44 dp floor, button
 * role — and the section title is a [SectionHeader] heading, so a screen
 * reader hears the section on entry. These are static navigation lists:
 * there is no async data, so loading/empty/error states do not apply (R13's
 * states are for state-bearing screens; a nav list has none).
 */
@Composable
fun MemoryHomeScreen(
    padding: PaddingValues,
    draftCount: Int,
    onOpen: (Screen) -> Unit,
) {
    SectionHomeScreen(
        padding = padding,
        titleRes = R.string.nav_memory,
        subtitleRes = R.string.section_home_memory_subtitle,
        entries = listOf(
            SectionEntry(R.string.nav_memory, R.string.memory_home_memory_desc, onOpen = { onOpen(Screen.Memory) }),
            SectionEntry(R.string.nav_people, R.string.memory_home_people_desc, onOpen = { onOpen(Screen.People) }),
            SectionEntry(R.string.nav_drafts, R.string.memory_home_drafts_desc, badge = draftCount, onOpen = { onOpen(Screen.Drafts) }),
            SectionEntry(R.string.nav_meeting, R.string.memory_home_meeting_desc, onOpen = { onOpen(Screen.Meeting) }),
            SectionEntry(R.string.nav_agent_memory, R.string.memory_home_agent_memory_desc, onOpen = { onOpen(Screen.AgentMemory) }),
        ),
    )
}

@Composable
fun TasksHomeScreen(
    padding: PaddingValues,
    onOpen: (Screen) -> Unit,
) {
    SectionHomeScreen(
        padding = padding,
        titleRes = R.string.nav_tasks,
        subtitleRes = R.string.section_home_tasks_subtitle,
        entries = listOf(
            SectionEntry(R.string.nav_goals, R.string.tasks_home_goals_desc, onOpen = { onOpen(Screen.Goals) }),
            SectionEntry(R.string.nav_agents, R.string.tasks_home_agents_desc, onOpen = { onOpen(Screen.Agents) }),
            SectionEntry(R.string.nav_skills, R.string.tasks_home_skills_desc, onOpen = { onOpen(Screen.Skills) }),
        ),
    )
}

private data class SectionEntry(
    val labelRes: Int,
    val descriptionRes: Int? = null,
    val badge: Int = 0,
    val onOpen: () -> Unit,
)

@Composable
private fun SectionHomeScreen(
    padding: PaddingValues,
    titleRes: Int,
    subtitleRes: Int,
    entries: List<SectionEntry>,
) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item { SectionHeader(title = stringResource(titleRes)) }
        item {
            Text(
                stringResource(subtitleRes),
                fontSize = 13.sp,
                color = NewaxTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        items(entries.size) { index ->
            val entry = entries[index]
            SettingsRow(
                title = stringResource(entry.labelRes),
                subtitle = entry.descriptionRes?.let { stringResource(it) },
                onClick = entry.onOpen,
                trailing = {
                    // The same text-on-accent-disc badge the drawer uses —
                    // never colour alone (the count is announced as text).
                    if (entry.badge > 0) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(Color(0xFFF97316))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (entry.badge > 99) stringResource(R.string.badge_overflow) else entry.badge.toString(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                },
            )
        }
    }
}
