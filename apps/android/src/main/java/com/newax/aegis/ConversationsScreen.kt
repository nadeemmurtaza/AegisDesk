package com.newax.aegis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.newax.aegis.chat.ConversationSearchHit
import com.newax.aegis.chat.ConversationSummary
import com.newax.aegis.ui.components.ConfirmDialog
import com.newax.aegis.ui.components.ConversationRow
import com.newax.aegis.ui.components.EditValueSheet
import com.newax.aegis.ui.components.EmptyState
import com.newax.aegis.ui.components.SearchBar
import com.newax.aegis.ui.state.ConversationListState
import com.newax.aegis.ui.theme.NewaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The conversation list (UI_DESIGN route 1.1), with the row actions (1.6) and
 * the debounced search (1.11) — the T3.5a chat shell. The thread itself stays
 * on [ChatScreen]; tapping a row opens it there.
 *
 * R6 note: this is where the shared `ConversationRow` (library-only since
 * T3.4) finally gets its route, and where `SearchBar` finds its first call
 * site. Every row is one focus stop; the overflow menu is the named 44 dp
 * target for rename/delete; destructive delete goes through the shared
 * confirm dialog (SC 3.3.4/3.3.6).
 */
@Composable
fun ConversationsScreen(
    vm: MainViewModel,
    padding: PaddingValues,
    onOpenThread: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    val listState = remember { ConversationListState() }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<ConversationSearchHit>>(emptyList()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var renameValue by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()

    // 1.11 — search as you type, debounced so the client-side transcript scan
    // (no FTS table; Track 2 may add a query later) does not run per keystroke.
    LaunchedEffect(query) {
        if (!listState.isSearchActive(query)) {
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        hits = withContext(Dispatchers.IO) { vm.searchChats(query) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        SearchBar(
            value         = query,
            onValueChange = { query = it },
            placeholder   = stringResource(R.string.conversations_search_hint),
            clearLabel    = stringResource(R.string.action_clear_search),
            modifier      = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            listState.isSearchActive(query) ->
                SearchResults(hits, listState, now, onOpenThread)

            conversations.isEmpty() ->
                EmptyConversations(onNewChat)

            else ->
                ConversationList(
                    conversations = conversations,
                    listState     = listState,
                    now           = now,
                    menuFor       = menuFor,
                    onMenuFor     = { menuFor = it },
                    onOpen        = onOpenThread,
                    onRename      = { summary ->
                        renameValue = summary.title
                        renameTarget = summary
                    },
                    onDelete      = { deleteTarget = it },
                )
        }
    }

    renameTarget?.let { target ->
        EditValueSheet(
            title          = stringResource(R.string.conversation_rename_title),
            value          = renameValue,
            onValueChange  = { renameValue = it },
            fieldLabel     = stringResource(R.string.conversation_rename_label),
            saveLabel      = stringResource(R.string.action_save),
            cancelLabel    = stringResource(R.string.action_cancel),
            saveEnabled    = listState.renameTitle(renameValue) != null,
            onSave         = {
                listState.renameTitle(renameValue)?.let { vm.renameConversation(target.id, it) }
                renameTarget = null
            },
            onDismiss      = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        // 1.6 delete — destructive, confirmed before it runs (SC 3.3.4/3.3.6).
        ConfirmDialog(
            title        = stringResource(R.string.conversation_delete_title),
            body         = stringResource(R.string.conversation_delete_body),
            confirmLabel = stringResource(R.string.action_delete_conversation),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm    = { vm.deleteConversation(target.id); deleteTarget = null },
            onDismiss    = { deleteTarget = null },
            destructive  = true,
        )
    }
}

// ── List ──────────────────────────────────────────────────────────────────────

@Composable
private fun ConversationList(
    conversations: List<ConversationSummary>,
    listState: ConversationListState,
    now: Long,
    menuFor: String?,
    onMenuFor: (String?) -> Unit,
    onOpen: (String) -> Unit,
    onRename: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        items(conversations, key = { it.id }) { summary ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConversationRow(
                    title     = summary.title.ifBlank { stringResource(R.string.conversation_untitled) },
                    timeLabel = listState.relativeTimeLabel(summary.updatedAtMs, now),
                    onClick   = { onOpen(summary.id) },
                    modifier  = Modifier.weight(1f),
                )
                Box {
                    // 1.1 — the row's overflow target: rename / delete (1.6).
                    IconButton(onClick = { onMenuFor(summary.id) }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.cd_conversation_actions),
                            tint               = NewaxTheme.colors.textSecondary,
                        )
                    }
                    DropdownMenu(
                        expanded          = menuFor == summary.id,
                        onDismissRequest  = { onMenuFor(null) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = { onMenuFor(null); onRename(summary) },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.action_delete_conversation),
                                    color = NewaxTheme.colors.error,
                                )
                            },
                            onClick = { onMenuFor(null); onDelete(summary) },
                        )
                    }
                }
            }
        }
    }
}

// ── Search results (1.11) ─────────────────────────────────────────────────────

@Composable
private fun SearchResults(
    hits: List<ConversationSearchHit>,
    listState: ConversationListState,
    now: Long,
    onOpen: (String) -> Unit,
) {
    if (hits.isEmpty()) {
        EmptyState(
            title    = stringResource(R.string.conversations_search_no_results),
            message  = stringResource(R.string.conversations_search_no_results_hint),
            icon     = Icons.Outlined.ChatBubbleOutline,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        items(hits, key = { it.conversationId }) { hit ->
            // A result is title + matched snippet + time; the snippet is the
            // accessible text of the row, so a screen reader hears the match.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(hit.conversationId) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        hit.title.ifBlank { stringResource(R.string.conversation_untitled) },
                        style = NewaxTheme.typography.body,
                        color = NewaxTheme.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        hit.snippet,
                        style = NewaxTheme.typography.caption,
                        color = NewaxTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listState.relativeTimeLabel(hit.updatedAtMs, now),
                        style = NewaxTheme.typography.caption,
                        color = NewaxTheme.colors.textTertiary,
                    )
                }
            }
        }
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyConversations(onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title    = stringResource(R.string.conversations_empty_title),
            message  = stringResource(R.string.conversations_empty_hint),
            icon     = Icons.Outlined.ChatBubbleOutline,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
        Button(
            onClick  = onNewChat,
            modifier = Modifier.padding(bottom = 32.dp),
        ) {
            Text(stringResource(R.string.action_new_chat), fontSize = 14.sp)
        }
    }
}
