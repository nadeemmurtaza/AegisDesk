package com.newax.aegis.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.newax.aegis.ui.a11y.describedAs
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.a11y.reducedMotionEnabled
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The voice family (docs/UI_DESIGN.md §8 — Voice): enrollment, the listening
 * indicator, and the streaming transcript preview. The microphone itself is a
 * platform seam (`VoiceRecognizer`, docs/UI_DESIGN.md §9) — these components
 * render its states, they never record.
 */

/**
 * Clamps a voice amplitude to [0, 1] — a recognizer emitting 1.4 or -0.1 must
 * not stretch the bars or leave them negative.
 */
fun clampAmplitude(amplitude: Float): Float = amplitude.coerceIn(0f, 1f)

/**
 * The listening indicator: five bars whose heights follow [amplitude], with a
 * static label under reduced motion (SC 2.3.3) and a polite live region so the
 * state is announced without stealing focus.
 *
 * @param label the visible static text ("Listening…") shown under reduced
 *   motion.
 * @param description the screen-reader description of the state.
 */
@Composable
fun ListeningIndicator(
    amplitude: Float,
    label: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = reducedMotionEnabled()
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .describedAs(description)
            .liveRegionPolite(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (reduceMotion) {
            Text(label, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textSecondary)
            return@Row
        }
        val infiniteTransition = rememberInfiniteTransition(label = "listening")
        repeat(5) { i ->
            val phase by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(420, delayMillis = i * 90),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            val heightPx = 10f + clampAmplitude(amplitude) * 18f * (0.6f + 0.4f * phase)
            Box(
                Modifier
                    .size(width = 4.dp, height = heightPx.dp)
                    .clip(CircleShape)
                    .background(NewaxTheme.colors.accent),
            )
        }
    }
}

/**
 * The live transcript preview: streamed speech as text in a polite live
 * region — announced at the next pause, never stealing focus (SC 2.1.2).
 * [placeholder] shows before the first token arrives.
 */
@Composable
fun TranscriptPreview(
    text: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    if (text.isBlank() && placeholder != null) {
        Text(
            placeholder,
            modifier = modifier.liveRegionPolite(),
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textTertiary,
            textAlign = TextAlign.Center,
        )
        return
    }
    Text(
        text,
        modifier = modifier.liveRegionPolite(),
        style = NewaxTheme.typography.bodyLong,
        color = NewaxTheme.colors.textPrimary,
    )
}

/**
 * The voice-enrollment sheet (docs/UI_DESIGN.md §8 — VoiceEnrollSheet): the
 * phrase to repeat, the listening state, and the record/stop control. Built
 * on [Sheet]; the recognizer itself is the caller's platform seam.
 *
 * @param phrase the enrollment phrase shown to the user.
 * @param statusLabel the current step's state ("Repeat the phrase", "Voice
 *   saved") — announced politely.
 * @param listening drives the [ListeningIndicator] vs the record button.
 */
@Composable
fun VoiceEnrollSheet(
    phrase: String,
    statusLabel: String,
    recordLabel: String,
    stopLabel: String,
    cancelLabel: String,
    listening: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    amplitude: Float = 0f,
) {
    Sheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = statusLabel,
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.md),
        ) {
            Text(
                phrase,
                style = NewaxTheme.typography.heading,
                color = NewaxTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (listening) {
                ListeningIndicator(
                    amplitude = amplitude,
                    label = stopLabel,
                    description = statusLabel,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (listening) {
                    TextButton(
                        onClick = onStop,
                        modifier = Modifier.minimumTouchTarget(),
                    ) { Text(stopLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.error) }
                } else {
                    IconButton(
                        onClick = onRecord,
                        modifier = Modifier.minimumTouchTarget(),
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(NewaxTheme.colors.error)
                                .describedAs(recordLabel),
                        )
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(cancelLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
        }
    }
}
