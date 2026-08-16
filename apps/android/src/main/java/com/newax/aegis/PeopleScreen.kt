package com.newax.aegis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.engine.ContactsManager
import com.newax.aegis.engine.PersonIntelligence
import com.newax.aegis.engine.learning.PersonFactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import com.newax.aegis.ui.components.ChevronRow
import com.newax.aegis.ui.components.EmptyState
import com.newax.aegis.ui.components.PersonRow
import com.newax.aegis.ui.components.TopBar
import com.newax.aegis.ui.theme.NewaxTheme

// Design tokens — mirrored from MainActivity (private to that file)
@Composable
fun PeopleScreen(vm: MainViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<PersonFactStore.PersonImportance?>(null) }
    val people = remember { mutableStateListOf<PersonFactStore.PersonImportance>() }

    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            PersonFactStore.getTopPeople(vm.db, 50)
        }
        people.clear()
        people.addAll(list)
    }

    if (selected == null) {
        PeopleListView(people, padding) { selected = it }
    } else {
        PersonDetailView(vm, selected!!, padding) { selected = null }
    }
}

// ── List ──────────────────────────────────────────────────────────────────────

@Composable
private fun PeopleListView(
    people: List<PersonFactStore.PersonImportance>,
    padding: PaddingValues,
    onSelect: (PersonFactStore.PersonImportance) -> Unit
) {
    if (people.isEmpty()) {
        // T3.4: the shared empty surface.
        EmptyState(
            title    = stringResource(R.string.people_empty),
            message  = stringResource(R.string.people_empty_hint),
            icon     = Icons.Outlined.Groups,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
        return
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text(
                pluralStringResource(R.plurals.people_count, people.size, people.size),
                fontSize = 13.sp,
                color = NewaxTheme.colors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(people, key = { it.name }) { person ->
            // T3.4c: the shared row — one focus stop whose name is the person
            // plus their detail (docs/UI_DESIGN.md §3.4).
            PersonRow(
                name        = person.name,
                detailLabel = personDetailLabel(person),
                scoreLabel  = "${(person.score * 100).toInt()}%",
                scoreColor  = scoreColor(person.score),
                onClick     = { onSelect(person) },
                leading     = {
                    AvatarCircle(
                        person.name.firstOrNull()?.uppercaseChar() ?: '?',
                        person.name,
                        size = 44.dp,
                        fontSize = 18.sp
                    )
                }
            )
        }
    }
}

/**
 * The list row's detail line — sources · mentions · last seen — kept as a
 * plain helper so the shared [PersonRow] receives one label (the plurals are
 * deliberately the same raw-English form the row always used; a locale pass
 * will move them to resources).
 */
private fun personDetailLabel(person: PersonFactStore.PersonImportance): String =
    buildString {
        append("${person.sourceCount} source${if (person.sourceCount != 1) "s" else ""}")
        append(" · ${person.totalMentions} mention${if (person.totalMentions != 1) "s" else ""}")
        if (person.lastSeenMs > 0L) append(" · ${relativeDate(person.lastSeenMs)}")
    }

// ── Detail ────────────────────────────────────────────────────────────────────

@Composable
private fun PersonDetailView(
    vm: MainViewModel,
    person: PersonFactStore.PersonImportance,
    padding: PaddingValues,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val facts = remember { mutableStateListOf<PersonFactStore.PersonFact>() }
    var intelProfile by remember { mutableStateOf<PersonIntelligence.PersonIntelligenceProfile?>(null) }

    LaunchedEffect(person.name) {
        val loaded = withContext(Dispatchers.IO) { PersonFactStore.factsFor(vm.db, person.name) }
        facts.clear()
        facts.addAll(loaded)

        val profile = withContext(Dispatchers.IO) {
            runCatching {
                val mgr = ContactsManager(context, vm.memory)
                val contacts = mgr.loadAllContacts()
                val match = contacts.firstOrNull { c ->
                    c.displayName.equals(person.name, ignoreCase = true) ||
                    c.displayName.contains(person.name, ignoreCase = true)
                }
                if (match != null) PersonIntelligence(context, vm.memory).loadProfile(match.contactId)
                else null
            }.getOrNull()
        }
        intelProfile = profile
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        // T3.4c: the shared shell TopBar — back + title, one control with a
        // named back arrow (docs/UI_DESIGN.md §8 — Shell).
        TopBar(
            title    = person.name,
            onBack   = onBack,
            backLabel = stringResource(R.string.cd_back)
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            PersonHeaderCard(person)

            intelProfile?.let { IntelligenceCard(it) }

            val grouped = facts.groupBy { it.category }
            if (grouped.isEmpty()) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Text(
                        stringResource(R.string.people_empty_facts, person.name),
                        fontSize = 13.sp,
                        color = NewaxTheme.colors.textSecondary,
                        modifier = Modifier.padding(16.dp),
                        lineHeight = 19.sp
                    )
                }
            } else {
                // Sort: work first, then others alphabetically, personal last
                val sortedCategories = grouped.keys.sortedWith(compareBy {
                    when (it) { "work" -> "0"; "personal" -> "z$it"; else -> it }
                })
                sortedCategories.forEach { category ->
                    FactCategorySection(category, grouped[category] ?: emptyList())
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Cards ─────────────────────────────────────────────────────────────────────

@Composable
private fun PersonHeaderCard(person: PersonFactStore.PersonImportance) {
    val score = person.score
    val scoreColor = scoreColor(score)
    val initial = person.name.firstOrNull()?.uppercaseChar() ?: '?'

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(initial, person.name, size = 56.dp, fontSize = 22.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(person.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MiniChip(stringResource(R.string.people_sources, person.sourceCount), Color(0xFFE0F2FE), Color(0xFF0284C7))
                        MiniChip(stringResource(R.string.people_mentions, person.totalMentions), Color(0xFFF0FDF4), Color(0xFF16A34A))
                        MiniChip("${(score * 100).toInt()}%", scoreColor.copy(alpha = 0.14f), scoreColor)
                    }
                }
            }
            if (person.lastSeenMs > 0L) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = NewaxTheme.colors.border)
                Spacer(Modifier.height(10.dp))
                Row {
                    Text(stringResource(R.string.people_last_seen), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
                    Text(
                        SimpleDateFormat(
                            "MMM d, yyyy",
                            // Read from the composition-local Configuration so a
                            // locale change recomposes; Locale.getDefault() does
                            // not. The list is never empty in practice, so the
                            // fallback is defensive — and must not itself be
                            // getDefault(), or the observability is lost again.
                            ConfigurationCompat.getLocales(LocalConfiguration.current)[0]
                                ?: Locale.ROOT,
                        ).format(Date(person.lastSeenMs)),
                        fontSize = 12.sp,
                        color = NewaxTheme.colors.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun IntelligenceCard(profile: PersonIntelligence.PersonIntelligenceProfile) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            // T3.4: the shared chevron row — expand state is a stateDescription
            // on the control, never the glyph (docs/UI_DESIGN.md §3.4).
            ChevronRow(
                title      = stringResource(R.string.people_intel_title),
                expanded   = expanded,
                onToggle   = { expanded = !expanded },
                stateLabel = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed),
                modifier   = Modifier.padding(16.dp)
            )

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(bottom = 12.dp))

                    if (profile.aiSummary.isNotBlank()) {
                        Text(profile.aiSummary, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp)
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(bottom = 12.dp))
                    }

                    val relLabel = profile.relationship.name.lowercase().replace('_', ' ')
                        .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntelRow(stringResource(R.string.people_intel_relationship), relLabel)
                        IntelRow(stringResource(R.string.people_intel_sentiment), profile.sentimentTowardMe.replaceFirstChar(Char::uppercase))
                        IntelRow(stringResource(R.string.people_intel_frequency), profile.communicationFrequency.replaceFirstChar(Char::uppercase))
                        IntelRow(stringResource(R.string.people_intel_messages), stringResource(R.string.people_intel_messages_value, profile.totalMessagesIn, profile.totalMessagesOut))
                        if (profile.avgResponseGapHours > 0f) {
                            IntelRow(stringResource(R.string.people_intel_avg_response), "${"%.1f".format(profile.avgResponseGapHours)}h")
                        }
                        val formalLabel = when {
                            profile.formalityScore > 0.7f -> stringResource(R.string.people_formal_very)
                            profile.formalityScore > 0.4f -> stringResource(R.string.people_formal_moderate)
                            else -> stringResource(R.string.people_formal_casual)
                        }
                        IntelRow(stringResource(R.string.people_intel_style), formalLabel)
                        if (profile.initiatesConversation) IntelRow(stringResource(R.string.people_intel_initiates), stringResource(R.string.people_initiates_value))
                        if (profile.languagesDetected.isNotEmpty()) {
                            IntelRow(stringResource(R.string.people_intel_languages), profile.languagesDetected.joinToString(", "))
                        }
                        if (profile.topicKeywords.isNotEmpty()) {
                            IntelRow(stringResource(R.string.people_intel_topics), profile.topicKeywords.take(6).joinToString(", "))
                        }
                    }

                    if (profile.personalityTraits.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.people_personality), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            profile.personalityTraits.joinToString(" · "),
                            fontSize = 12.sp,
                            color = NewaxTheme.colors.textSecondary,
                            lineHeight = 18.sp
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(bottom = 12.dp))
                    ScoreBar(stringResource(R.string.people_score_intimacy), profile.intimacyScore, Color(0xFFEC4899))
                    Spacer(Modifier.height(10.dp))
                    ScoreBar(stringResource(R.string.people_score_trust), profile.trustScore, Color(0xFF6366F1))
                }
            }
        }
    }
}

@Composable
private fun FactCategorySection(category: String, facts: List<PersonFactStore.PersonFact>) {
    var expanded by remember(category) { mutableStateOf(true) }
    val label = category.replaceFirstChar(Char::uppercase)
    val dotColor = categoryDotColor(category)

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            // T3.4: shared chevron row with the category dot as the leading
            // slot; expand state announced on the control.
            ChevronRow(
                title      = "$label (${facts.size})",
                expanded   = expanded,
                onToggle   = { expanded = !expanded },
                stateLabel = stringResource(if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed),
                modifier   = Modifier.padding(16.dp),
                leading    = {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                }
            )

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    facts.forEach { fact ->
                        HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 16.dp))
                        FactRow(fact)
                    }
                }
            }
        }
    }
}

@Composable
private fun FactRow(fact: PersonFactStore.PersonFact) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(fact.fact, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, lineHeight = 19.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                if (fact.source.isNotBlank()) append(fact.source.take(36))
                append(" · ${(fact.confidence * 100).toInt()}% conf")
                if (fact.timestampMs > 0L) append(" · ${relativeDate(fact.timestampMs)}")
            },
            fontSize = 11.sp,
            color = NewaxTheme.colors.textTertiary
        )
    }
}

// ── Shared widgets ────────────────────────────────────────────────────────────

@Composable
private fun AvatarCircle(initial: Char, name: String, size: androidx.compose.ui.unit.Dp, fontSize: androidx.compose.ui.unit.TextUnit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(avatarColorFor(name)),
        contentAlignment = Alignment.Center
    ) {
        Text(initial.toString(), fontSize = fontSize, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun MiniChip(label: String, bg: Color, fg: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 11.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IntelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = NewaxTheme.colors.textTertiary, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ScoreBar(label: String, value: Float, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
            Text("${(value * 100).toInt()}%", fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(NewaxTheme.colors.border)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun scoreColor(score: Float): Color = when {
    score >= 0.6f -> Color(0xFF16A34A)
    score >= 0.3f -> Color(0xFFD97706)
    else          -> Color(0xFF8D8D87)
}

private fun categoryDotColor(category: String): Color = when (category.lowercase()) {
    "work"     -> Color(0xFF3B82F6)
    "health"   -> Color(0xFFEF4444)
    "events"   -> Color(0xFFF97316)
    "family"   -> Color(0xFF8B5CF6)
    "finance"  -> Color(0xFF16A34A)
    "places"   -> Color(0xFF06B6D4)
    "habits"   -> Color(0xFFD97706)
    "contacts" -> Color(0xFF64748B)
    else       -> Color(0xFF6366F1)
}

private fun avatarColorFor(name: String): Color {
    val palette = listOf(
        Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899),
        Color(0xFFEF4444), Color(0xFFF97316), Color(0xFF16A34A),
        Color(0xFF06B6D4), Color(0xFF6366F1)
    )
    return palette[name.hashCode().and(0x7FFFFFFF) % palette.size]
}

private fun relativeDate(ms: Long): String {
    val days = (System.currentTimeMillis() - ms) / (1000L * 60 * 60 * 24)
    return when {
        days < 1   -> "today"
        days == 1L -> "yesterday"
        days < 7   -> "${days}d ago"
        days < 30  -> "${days / 7}w ago"
        days < 365 -> "${days / 30}mo ago"
        else       -> "${days / 365}y ago"
    }
}
