package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.ui.a11y.statusSemantics
import com.newax.aegis.ui.components.ConfirmDialog
import com.newax.aegis.ui.components.SearchBar
import com.newax.aegis.ui.components.SectionHeader
import com.newax.aegis.ui.state.MemoryScreenState
import com.newax.aegis.ui.state.MemorySearchState
import com.newax.aegis.ui.theme.NewaxTheme

// display labels resolve through strings.xml, with the holder's title-case
// fallback for any key a newer build stored that this one does not know.
private fun categoryLabelRes(category: String): Int? = when (category) {
    "personal" -> R.string.memory_category_personal
    "business" -> R.string.memory_category_business
    "education" -> R.string.memory_category_education
    "relationships" -> R.string.memory_category_relationships
    "goals" -> R.string.memory_category_goals
    "pain_points" -> R.string.memory_category_pain_points
    "rules" -> R.string.memory_category_rules
    else -> null
}

@Composable
private fun categoryLabel(state: MemoryScreenState, category: String): String {
    val res = categoryLabelRes(category)
    return if (res != null) stringResource(res) else state.displayName(category)
}

// ── Memory Screen ─────────────────────────────────────────────────────────────
@Composable
fun MemoryScreen(vm: MainViewModel, padding: PaddingValues) {
    // Category inventory, counts, display names and fact parsing all come from
    // the plain-Kotlin holder (T3.1) so they are unit-tested.
    val memState = remember { MemoryScreenState() }
    val searchState = remember { MemorySearchState() }
    val categories = memState.categories
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    val allCats = remember(vm.memoryVersion) { vm.memory.getAllCategories() }
    val totalCount = memState.totalCount(allCats)
    // Route 2.1 item 1 / 2.2: the search field. Ranking is the encrypted
    // store's TF-IDF (`relevant`); this screen only maps hits back to their
    // owning category (2.3's editor) and highlights the matched term.
    val searchActive = searchState.isActive(searchQuery)
    val searchResults = remember(searchQuery, vm.memoryVersion) {
        if (searchActive) vm.memory.relevant(searchQuery) else emptyList()
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
            // Route 2.1 item 1 — the shared SearchBar (its 1.11 route landed in
            // ConversationsScreen; the 2.1 route is here).
            SearchBar(
                value         = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder   = stringResource(R.string.memory_search_hint),
                clearLabel    = stringResource(R.string.cd_clear)
            )
        }

        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.memory_count, totalCount), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.memory_encrypted_on_device), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    }
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (searchActive) {
            // Route 2.2 — ranked hits replace the category list while the
            // query is active. A hit opens its owning category's editor (2.3),
            // which is where the fact can be edited or deleted.
            item { SectionLabel(stringResource(R.string.memory_search_results_label, searchResults.size, searchQuery.trim())) }
            if (searchResults.isEmpty()) {
                item { EmptyChip(stringResource(R.string.memory_search_none)) }
            } else {
                items(searchResults) { fact ->
                    MemorySearchHitCard(
                        fact      = fact,
                        query     = searchQuery,
                        category  = searchState.categoryOf(fact, allCats),
                        search    = searchState,
                        memState  = memState,
                        onOpen    = { cat ->
                            expandedCategory = cat
                            searchQuery = ""
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        } else {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionLabel(stringResource(R.string.memory_section_categories)) }

        items(categories) { cat ->
            val entries = allCats[cat].orEmpty()
            MemoryCategoryCard(
                category = cat,
                entries  = entries,
                expanded = expandedCategory == cat,
                onToggle = { expandedCategory = if (expandedCategory == cat) null else cat },
                onSave   = { updated -> vm.memory.setCategory(cat, updated); vm.bumpMemoryVersion() },
                state    = memState
            )
        }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.memory_section_knowledge_graph)) }

        val nodes = com.newax.aegis.engine.KnowledgeGraph.getAllNodes()
        if (nodes.isEmpty()) {
            item { EmptyChip(stringResource(R.string.memory_empty_graph)) }
        } else {
            items(nodes) { node ->
                Card(
                    shape  = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(node.id, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                        if (node.properties.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            node.properties.forEach { (k, v) ->
                                Text("$k: $v", fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.memory_section_projects)) }

        val projects = com.newax.aegis.engine.ProjectTracker.getAllProjects()
        if (projects.isEmpty()) {
            item { EmptyChip(stringResource(R.string.memory_empty_projects)) }
        } else {
            items(projects) { p ->
                Card(
                    shape  = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.id, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                        Text("${p.status} — ${p.notes}", fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.memory_section_communication_log)) }

        val logs = com.newax.aegis.engine.CommunicationLog.getAllLogs().sortedByDescending { it.timestamp }
        if (logs.isEmpty()) {
            item { EmptyChip(stringResource(R.string.memory_empty_log)) }
        } else {
            items(logs.take(20)) { log ->
                Card(
                    shape  = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(log.contact, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                        Text(log.summary, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.fillMaxWidth(),
                border   = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary)
            ) { Text(stringResource(R.string.memory_clear_all), fontSize = 14.sp) }
        }
    }

    if (showClearDialog) {
        // T3.4: the shared confirm dialog — erasing all memory is destructive
        // and gets the error treatment.
        ConfirmDialog(
            title        = stringResource(R.string.memory_clear_title),
            body         = stringResource(R.string.memory_clear_body),
            confirmLabel = stringResource(R.string.action_clear),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm    = { vm.memory.forgetAll(); vm.bumpMemoryVersion(); showClearDialog = false },
            onDismiss    = { showClearDialog = false },
            destructive  = true
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    // T3.4: the shared section header — `heading()` semantics so screen
    // readers navigate by heading.
    SectionHeader(title = text)
}

@Composable
// ── Search hit (route 2.2) ────────────────────────────────────────────────────
@Composable
private fun MemorySearchHitCard(
    fact: String,
    query: String,
    category: String?,
    search: MemorySearchState,
    memState: MemoryScreenState,
    onOpen: (String?) -> Unit
) {
    // SC 1.4.1: the matched term is emphasized with weight AND colour — the
    // range is computed by the pure holder, never by the composable.
    val highlighted: AnnotatedString = remember(fact, query) {
        val range = search.highlightRange(fact, query)
        if (range == null) AnnotatedString(fact)
        else buildAnnotatedString {
            append(fact.substring(0, range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = NewaxTheme.colors.textPrimary)) {
                append(fact.substring(range))
            }
            append(fact.substring(range.last + 1))
        }
    }
    Card(
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(category) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                highlighted,
                fontSize = 14.sp,
                color = NewaxTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (category != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.memory_search_category, categoryLabel(memState, category)),
                    fontSize = 11.sp,
                    color = NewaxTheme.colors.textTertiary
                )
            }
        }
    }
}

private fun EmptyChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 13.sp, color = NewaxTheme.colors.textTertiary) }
}

@Composable
private fun MemoryCategoryCard(
    category: String,
    entries: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (List<String>) -> Unit,
    state: MemoryScreenState
) {
    var draft by remember(category) { mutableStateOf(entries.joinToString("\n")) }

    Card(
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    // The chevron below is the only expanded/collapsed cue, and
                    // an icon swap conveys nothing to a screen reader. State
                    // belongs on the control, not the glyph (SC 4.1.2) — the
                    // icon stays contentDescription = null because the row's
                    // text already names it.
                    .statusSemantics(if (expanded) stringResource(R.string.a11y_expanded) else stringResource(R.string.a11y_collapsed))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    categoryLabel(state, category),
                    fontWeight = FontWeight.Medium,
                    fontSize   = 14.sp,
                    color      = NewaxTheme.colors.textPrimary,
                    modifier   = Modifier.weight(1f)
                )
                Text("${entries.size}", fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = NewaxTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = NewaxTheme.colors.border)
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value         = draft,
                        onValueChange = { draft = it },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text(stringResource(R.string.memory_one_fact_per_line), color = NewaxTheme.colors.textTertiary, fontSize = 12.sp) },
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NewaxTheme.colors.textPrimary,
                            unfocusedBorderColor = NewaxTheme.colors.border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = NewaxTheme.colors.textPrimary),
                        minLines = 2
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onSave(state.parseFacts(draft)) },
                        modifier = Modifier.align(Alignment.End),
                        colors   = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                    ) { Text(stringResource(R.string.action_save), fontSize = 14.sp) }
                }
            }
        }
    }
}
