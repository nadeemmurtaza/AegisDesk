package com.newax.aegis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.agents.AgentStream
import com.newax.aegis.assistant.ChatMessage
import com.newax.aegis.assistant.riskLevel
import com.newax.aegis.ui.a11y.describedAs
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.reducedMotionEnabled
import com.newax.aegis.ui.a11y.statusSemantics
import com.newax.aegis.ui.components.ApprovalCard
import com.newax.aegis.ui.components.ChatBubble as NewaxChatBubble
import com.newax.aegis.ui.components.Composer
import com.newax.aegis.ui.components.DegradedBanner
import com.newax.aegis.ui.components.ModelStatusLine
import com.newax.aegis.ui.components.StepBlock
import com.newax.aegis.ui.components.StepStatus
import com.newax.aegis.ui.components.StreamingText
import com.newax.aegis.ui.components.SuggestionGrid
import com.newax.aegis.ui.components.TypingIndicator as NewaxTypingIndicator
import com.newax.aegis.ui.risk.RiskBadgeStyle
import com.newax.aegis.ui.risk.riskBadgeStyle
import com.newax.aegis.ui.state.ChatScreenState
import com.newax.aegis.ui.state.StepStatusState
import com.newax.aegis.ui.theme.NewaxTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    vm: MainViewModel,
    padding: PaddingValues,
    onOpenModelSheet: () -> Unit,
    onOpenStepDetail: () -> Unit,
    onOpenVoiceCapture: () -> Unit,
) {
    // All chat-surface decisions live in the plain-Kotlin holder (T3.1): the
    // suggestion chips, the empty-state predicate, the scroll target and the
    // send validation are tested without Compose.
    val chatState = remember { ChatScreenState() }
    val listState = rememberLazyListState()
    // Read once for the whole chat surface; both the bubble entrance and the
    // typing indicator honour it (docs/UI_DESIGN.md §3.2, WCAG SC 2.3.3).
    val reduceMotion = reducedMotionEnabled()
    val scope = rememberCoroutineScope()

    // Follow the thread: scroll on new messages, and follow the streaming
    // bubble as it grows (T3.0c). Reduced motion disables the scroll animation
    // (docs/UI_DESIGN.md §3, WCAG SC 2.3.3).
    LaunchedEffect(vm.messages.size, vm.streamingActive, vm.streamingText) {
        val last = chatState.scrollTarget(vm.messages.size, vm.streamingActive)
        if (last >= 0) {
            if (reduceMotion) listState.scrollToItem(last)
            else listState.animateScrollToItem(last)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        // T3.5b — route 1.2 item 2: the degraded-mode banner. The model is the
        // only degradation this app has today (basic mode), and the banner's
        // action opens the model sheet (1.4) — never a dead control.
        if (!vm.modelReady) {
            DegradedBanner(
                message    = stringResource(R.string.banner_basic_mode),
                actionLabel = stringResource(R.string.banner_action_model),
                onAction   = onOpenModelSheet,
                modifier   = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val showEmpty = chatState.shouldShowEmptyState(vm.messages.size, vm.modelBusy)

        if (showEmpty) {
            EmptyState(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                chips = chatState.suggestionChips
            ) { chip -> vm.composerText = chip }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state               = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding      = PaddingValues(top = 12.dp, bottom = 4.dp)
            ) {
                items(vm.messages, key = { it.id }) { msg ->
                    // SC 2.3.3: the slide-in is decorative. Under reduced motion
                    // the bubble appears immediately — same end state, no travel.
                    AnimatedVisibility(
                        visible = true,
                        enter   = if (reduceMotion) EnterTransition.None
                                  else slideInVertically(initialOffsetY = { it / 3 }) + fadeIn(tween(200))
                    ) {
                        ChatBubble(msg)
                    }
                }
                if (vm.streamingActive) {
                    item { StreamingBubble(text = vm.streamingText, onStop = vm::stopGeneration) }
                } else if (vm.modelBusy) {
                    item { TypingIndicator() }
                }
            }
        }

        // Slice 12 — the inline step block (spec §7.2): the live agent-run
        // status in the thread. The shared StepBlock component was library-only
        // until here; its 1.9 route is this block, backed by the real
        // AgentStream bus for the active session. Live while a run is going,
        // collapsed to the final state once the session clears.
        val agentSessionId by vm.agentSessionId.collectAsState()
        val agentEvents by AgentStream.events.collectAsState()
        StepStatusBlock(
            sessionId = agentSessionId,
            events    = agentEvents,
            modifier  = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        vm.pendingAction?.let { action ->
            val riskStyle: RiskBadgeStyle = riskBadgeStyle(action.riskLevel, NewaxTheme.colors)
            // T3.4b: the shared card owns the layout, the assertive live region,
            // and the approve/reject buttons; this wrapper resolves the
            // localized strings and the contrast-verified risk chip from the
            // canonical RiskLevel (T3.0a).
            ApprovalCard(
                title = stringResource(R.string.chat_approval_required),
                summary = action.summary,
                riskLabel = stringResource(riskStyle.labelRes),
                riskColor = riskStyle.foreground,
                riskFill = riskStyle.background,
                approveLabel = stringResource(R.string.action_approve),
                rejectLabel = stringResource(R.string.action_cancel),
                onApprove = vm::approve,
                onReject = vm::reject,
                // T3.5b — route 1.9: "Details" opens the step-detail sheet
                // (risk class, gate, policy rule, audit link) before deciding.
                detailsLabel = stringResource(R.string.chat_details),
                onDetails = onOpenStepDetail,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // T3.4b: the shared composer owns the bar, the busy spinner, and the
        // send control; the mic stays a caller-supplied leading slot because it
        // is a platform action (speech recognition) and the shared module keeps
        // its icon set to material-icons-core.
        Composer(
            value         = vm.composerText,
            onValueChange = { vm.composerText = it },
            placeholder   = stringResource(R.string.chat_composer_placeholder),
            sendLabel     = stringResource(R.string.cd_send),
            busy          = vm.modelBusy,
            busyLabel     = stringResource(R.string.chat_processing_background),
            onSubmit      = { chatState.submitText(vm.composerText)?.let { vm.submit(it); vm.composerText = "" } },
            leading = {
                // T3.5c — route 1.2 "Mic → ⊞1.10": the mic opens the voice-
                // capture sheet instead of the one-shot system recognizer,
                // so the live meter and running transcript (1.10 items 1–2)
                // are possible, and Stop inserts into the composer rather
                // than submitting a half-heard phrase.
                IconButton(onClick = onOpenVoiceCapture) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.cd_voice),
                        tint = NewaxTheme.colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )
        // T3.5b — route 1.2 item 8: the model status line beneath the composer;
        // tapping it opens the model sheet (1.4). The state chip is the same
        // word+colour pair the sheet uses ([modelStateChip]) so the two cannot
        // disagree.
        val modelChip = modelStateChip(vm)
        val modelLineName = if (vm.modelName.isBlank()) stringResource(R.string.model_status_none) else vm.modelName
        ModelStatusLine(
            modelName  = modelLineName,
            stateLabel = stringResource(modelChip.labelRes),
            stateColor = modelChip.color,
            openLabel  = stringResource(R.string.model_status_manage),
            onOpen     = onOpenModelSheet,
            modifier   = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// ── Inline step block (slice 12, spec §7.2) ───────────────────────────────────
@Composable
private fun StepStatusBlock(
    sessionId: String?,
    events: List<AgentStream.Event>,
    modifier: Modifier = Modifier,
) {
    val state = remember { StepStatusState() }
    val rows = state.rowsFor(events, sessionId.orEmpty())
    val live = sessionId != null
    // Nothing to show before a session emits its first event — the block
    // appears with the run and stays (collapsed) as its footprint.
    if (!live && rows.isEmpty()) return

    // The header reflects the run's honest end state: FAILED if any step
    // failed, RUNNING while the session is live, DONE once it clears.
    val phase = when {
        rows.any { it.phase == StepStatusState.RunPhase.FAILED } -> StepStatusState.RunPhase.FAILED
        live -> StepStatusState.RunPhase.RUNNING
        else -> StepStatusState.RunPhase.DONE
    }
    val headerLabel = when (phase) {
        StepStatusState.RunPhase.RUNNING -> stringResource(R.string.step_task_in_progress)
        StepStatusState.RunPhase.DONE -> stringResource(R.string.step_task_finished)
        StepStatusState.RunPhase.FAILED -> stringResource(R.string.step_task_failed)
    }
    val headerColor = when (phase) {
        StepStatusState.RunPhase.RUNNING -> NewaxTheme.colors.warning
        StepStatusState.RunPhase.DONE -> NewaxTheme.colors.success
        StepStatusState.RunPhase.FAILED -> NewaxTheme.colors.error
    }
    // Collapses when the run ends: remember(sessionId) resets as the id
    // clears, and a finished run needs no screen space — spec: "collapsed when
    // successful, expandable".
    var expanded by remember(sessionId) { mutableStateOf(live) }

    Card(
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier  = modifier
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    // SC 4.1.2 — expand/collapse state lives on the control.
                    .statusSemantics(if (expanded) stringResource(R.string.a11y_expanded) else stringResource(R.string.a11y_collapsed))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(headerColor)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    headerLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = NewaxTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = NewaxTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded) {
                HorizontalDivider(color = NewaxTheme.colors.border)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rows.isEmpty()) {
                        Text(stringResource(R.string.step_state_starting), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    } else {
                        rows.forEachIndexed { index, row ->
                            // T3.5e — the previously library-only StepBlock
                            // (its §8 entry) lands here as the running task's
                            // step rows.
                            StepBlock(
                                index = index + 1,
                                title = row.title,
                                status = when (row.phase) {
                                    StepStatusState.RunPhase.RUNNING -> StepStatus.RUNNING
                                    StepStatusState.RunPhase.DONE -> StepStatus.DONE
                                    StepStatusState.RunPhase.FAILED -> StepStatus.FAILED
                                },
                                stateLabel = when (row.phase) {
                                    StepStatusState.RunPhase.RUNNING -> stringResource(R.string.step_state_running)
                                    StepStatusState.RunPhase.DONE -> stringResource(R.string.step_state_done)
                                    StepStatusState.RunPhase.FAILED -> stringResource(R.string.step_state_failed)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Empty State ───────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(modifier: Modifier, chips: List<Int>, onChip: (String) -> Unit) {
    // T3.2: the holder exposes chip resource ids; the labels resolve here and
    // the resolved (localized) text is what gets submitted to the pipeline.
    val chipLabels = chips.map { stringResource(it) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = NewaxTheme.colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.chat_tagline), color = NewaxTheme.colors.textSecondary, fontSize = 15.sp)
        Spacer(Modifier.height(32.dp))
        // T3.4b: the shared grid owns the chip layout, spacing, and 44 dp
        // targets; the labels resolve here and the resolved (localized) text is
        // what gets submitted to the pipeline.
        SuggestionGrid(
            suggestions = chipLabels,
            onSuggestion = onChip,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.chat_processed_on_device), color = NewaxTheme.colors.textTertiary, fontSize = 12.sp)
    }
}

// ── Chat Bubble ───────────────────────────────────────────────────────────────
// The shared component (T3.4) owns the layout and the §7.3 roles — assistant on
// `surface` with a hairline, user on `surfaceSelected`, both `textPrimary`, and
// `fillMaxWidth(0.86f)` so the bubble scales with the font instead of clipping
// at 200% (SC 1.4.4). This wrapper supplies what the shared layer cannot: the
// message model, the localized timestamp, and the platform clipboard.
@Composable
private fun ChatBubble(msg: ChatMessage) {
    val context = LocalContext.current
    val timeFormatPattern = stringResource(R.string.chat_time_format)
    val time    = remember(msg.timestamp, timeFormatPattern) {
        SimpleDateFormat(timeFormatPattern, Locale.getDefault()).format(Date(msg.timestamp))
    }

    NewaxChatBubble(
        text      = msg.text,
        fromUser  = msg.fromUser,
        timeLabel = time,
        onLongPress = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(stringResource(R.string.chat_clipboard_label), msg.text))
        }
    )
}

// ── Typing Indicator ──────────────────────────────────────────────────────────
// The shared component (T3.4) implements the whole spec: pulsing dots under
// reduced motion become a static label (SC 2.3.3), and the row is described +
// a polite live region (SC 1.4.1 / 4.1.3). This wrapper only resolves the
// localized label/description.
@Composable
private fun TypingIndicator() {
    NewaxTypingIndicator(
        label       = stringResource(R.string.chat_typing),
        description = stringResource(R.string.chat_typing_description)
    )
}

// ── Streaming Bubble ──────────────────────────────────────────────────────────
/**
 * The in-progress assistant reply (T3.0c). Text grows per emitted chunk from
 * `ModelProvider.stream()`. Stop cancels the collecting coroutine — the UI
 * stops updating, but the model call itself cannot be interrupted
 * (`ModelProvider.cancel()` is a documented no-op on both real providers), so
 * stopping ABANDONS the reply, it never aborts it. The ViewModel's
 * cancellation handler says exactly that, plainly, in the thread.
 */
@Composable
private fun StreamingBubble(text: String, onStop: () -> Unit) {
    val shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(horizontalAlignment = Alignment.Start) {
            Box(
                Modifier
                    .fillMaxWidth(0.86f)
                    .wrapContentWidth(Alignment.Start)
                    .clip(shape)
                    .border(1.dp, NewaxTheme.colors.border, shape)
                    .background(NewaxTheme.colors.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .describedAs(stringResource(R.string.chat_streaming_description))
            ) {
                Column {
                    // T3.4: streamed text is a polite live region — each chunk
                    // announces at the next pause in speech; the caret stays put.
                    StreamingText(
                        text = if (text.isBlank()) stringResource(R.string.chat_streaming) else text
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick  = onStop,
                        modifier = Modifier.height(44.dp),
                        colors   = ButtonDefaults.textButtonColors(contentColor = NewaxTheme.colors.textSecondary)
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_stop), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ── Action proposal, composer, and suggestion grid moved to the shared
// component library (T3.4b) — see the call sites in ChatScreen above:
// ApprovalCard (approval surface), Composer (input bar), SuggestionGrid
// (starter prompts). The wrappers resolve strings and platform actions; the
// shared components own layout, semantics, and the 44 dp targets.
