package com.newax.aegis.desktop.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.desktop.DesktopModelProviderHolder
import com.newax.aegis.desktop.ui.state.ChatState
import com.newax.aegis.desktop.ui.state.ThreadMessageUi
import com.newax.aegis.desktop.ui.state.ThreadUiModel
import com.newax.aegis.ui.a11y.heading
import com.newax.aegis.ui.a11y.liveRegionAssertive
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.statusSemantics
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * Route 1.2 — the thread, the product's main surface (docs/UI_DESIGN.md §6.3):
 * header with New chat, the message list (user bubbles end-aligned on
 * surfaceSelected, assistant bubbles start-aligned on surface), live streaming
 * into the assistant bubble, and the composer with Send ↔ Stop.
 *
 * States: loading (DB read), error (honest named failure + retry), empty (the
 * six first-message suggestions), and content. A11y: streamed text is a polite
 * live region, failures are assertive, the composer's Send is disabled with a
 * state description while no draft exists.
 */
@Composable
fun ThreadScreen(
    state: ChatState,
    onNewChat: () -> Unit,
) {
    val thread by state.thread.collectAsState()
    val draft by state.draft.collectAsState()
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        // ── Header (1.2.1) ─────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Newax Aegis",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = NewaxTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewChat) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "New chat",
                    tint = NewaxTheme.colors.textSecondary,
                )
            }
        }
        HorizontalDivider(color = NewaxTheme.colors.border)

        when (val model = thread) {
            is ThreadUiModel.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Loading conversation…",
                    fontSize = 13.sp,
                    color = NewaxTheme.colors.textTertiary,
                )
            }

            is ThreadUiModel.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Could not open chat",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = NewaxTheme.colors.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        model.message,
                        fontSize = 13.sp,
                        color = NewaxTheme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { state.openConversation(state.activeId.value ?: return@TextButton) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }

            is ThreadUiModel.Content -> {
                if (model.messages.isEmpty()) {
                    EmptyThread(state)
                } else {
                    MessageList(model.messages, listState, onRetry = state::retryFailed, modifier = Modifier.weight(1f))
                }
                Composer(
                    state = state,
                    draft = draft,
                    streaming = model.streaming,
                )
            }
        }
    }
}

/** The empty thread — brand mark, promise line, six first-message suggestions (§1.2). */
@Composable
private fun EmptyThread(state: ChatState) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Your private, on-device assistant.",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = NewaxTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "No Internet permission · everything encrypted on this device · you approve every action.",
            fontSize = 14.sp,
            color = NewaxTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Try asking",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = NewaxTheme.colors.textTertiary,
            modifier = Modifier.heading(),
        )
        Spacer(Modifier.height(10.dp))
        // The six first-message suggestions map to real intents (§1.2); tapping
        // one drops it into the composer as the first message.
        val suggestions = listOf(
            "What's on my screen?",
            "Open an app",
            "Draft a reply",
            "What do you remember?",
            "Set a reminder",
            "Find a file",
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { suggestion ->
                        Surface(
                            onClick = { state.setDraft(suggestion) },
                            shape = RoundedCornerShape(12.dp),
                            color = NewaxTheme.colors.surface,
                            border = BorderStroke(1.dp, NewaxTheme.colors.borderStrong),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                suggestion,
                                fontSize = 13.sp,
                                color = NewaxTheme.colors.textPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The transcript, oldest first, auto-scrolled to the newest message. */
@Composable
private fun MessageList(
    messages: List<ThreadMessageUi>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message = message, onRetry = onRetry)
        }
    }
}

/** One bubble — user end-aligned on surfaceSelected, assistant start-aligned on surface. */
@Composable
private fun MessageBubble(message: ThreadMessageUi, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (message.fromUser) 14.dp else 4.dp,
                bottomEnd = if (message.fromUser) 4.dp else 14.dp,
            ),
            color = if (message.fromUser) NewaxTheme.colors.surfaceSelected else NewaxTheme.colors.surface,
            border = if (message.fromUser) null else BorderStroke(1.dp, NewaxTheme.colors.border),
            modifier = Modifier.widthIn(max = 640.dp),
        ) {
            val liveText = when {
                message.text.isNotBlank() -> message.text
                message.streaming -> "…"
                else -> ""
            }
            Text(
                liveText,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = NewaxTheme.colors.textPrimary,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .then(if (message.streaming) Modifier.liveRegionPolite() else Modifier)
                    .then(if (message.failed) Modifier.statusSemantics("Reply failed") else Modifier),
            )
        }
        // Honest failure block with the retry ladder (§1.2): Retry re-streams
        // the same prompt through the state holder.
        if (message.failed) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Retry", fontSize = 12.sp)
            }
        }
    }
}

/**
 * The composer — growing text field, Send ↔ Stop, model status line beneath
 * (route 1.2 items 7 + 8). Send is disabled while no draft exists; Stop
 * appears while streaming.
 */
@Composable
private fun Composer(
    state: ChatState,
    draft: String,
    streaming: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { state.setDraft(it) },
            placeholder = { Text("Message Newax Aegis…", fontSize = 14.sp, color = NewaxTheme.colors.textTertiary) },
            minLines = 1,
            maxLines = 5,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NewaxTheme.colors.borderStrong,
                unfocusedBorderColor = NewaxTheme.colors.border,
                focusedContainerColor = NewaxTheme.colors.surface,
                unfocusedContainerColor = NewaxTheme.colors.surface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (streaming) state.stop() else state.send()
            }),
            trailingIcon = {
                IconButton(
                    onClick = { if (streaming) state.stop() else state.send() },
                    enabled = streaming || draft.isNotBlank(),
                ) {
                    Icon(
                        if (streaming) Icons.Rounded.Stop else Icons.Rounded.Send,
                        contentDescription = if (streaming) "Stop streaming" else "Send message",
                        tint = NewaxTheme.colors.accent,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val provider = DesktopModelProviderHolder.current()
            val modelState by provider.state.collectAsState()
            StateDot(modelStateColor(modelState), size = 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                "${provider.descriptor.modelName} · ${modelState.label()}",
                fontSize = 11.sp,
                color = NewaxTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
