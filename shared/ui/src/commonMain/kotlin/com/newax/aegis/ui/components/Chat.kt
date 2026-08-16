package com.newax.aegis.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.newax.aegis.ui.a11y.describedAs
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.reducedMotionEnabled
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * Shared chat components (docs/UI_DESIGN.md §8 — Chat).
 *
 * The chat surface is where the app's accessibility obligations are most
 * visible (docs/UI_DESIGN.md §7.3, §3):
 *  - bubble roles follow the spec: assistant on `surface` with a hairline
 *    border, user on `surfaceSelected`, both `textPrimary`;
 *  - bubbles scale with the font: `fillMaxWidth(0.86f)` + wrap content, never
 *    a fixed dp cap that clips at 200% font scale (SC 1.4.4);
 *  - the typing indicator honours reduced motion (SC 2.3.3) and announces
 *    itself as a polite live region;
 *  - streamed text arrives in a polite live region — the caret never moves
 *    (SC 2.1.2/4.3.1).
 */

/**
 * A single message bubble.
 *
 * @param fromUser true renders the user's bubble (`surfaceSelected`,
 *   right-aligned, square lower-left corner); false renders the assistant's
 *   (`surface` + hairline border, left-aligned, square lower-right corner) —
 *   the roles the spec fixes in docs/UI_DESIGN.md §7.3.
 * @param timeLabel an optional pre-formatted timestamp shown under the bubble.
 * @param onLongPress an optional long-press action (e.g. copy-to-clipboard) —
 *   wired by the caller because the clipboard is a platform seam.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    text: String,
    fromUser: Boolean,
    modifier: Modifier = Modifier,
    timeLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (fromUser) 18.dp else 4.dp,
        bottomEnd = if (fromUser) 4.dp else 18.dp,
    )
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                // A fraction of the available width scales with the container
                // at 200% font scale; a fixed dp cap would clip (SC 1.4.4).
                .fillMaxWidth(0.86f)
                .wrapContentWidth(if (fromUser) Alignment.End else Alignment.Start)
                .clip(shape)
                .then(if (fromUser) Modifier else Modifier.border(1.dp, NewaxTheme.colors.border, shape))
                .background(if (fromUser) NewaxTheme.colors.surfaceSelected else NewaxTheme.colors.surface)
                .then(
                    if (onLongPress != null) {
                        Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text,
                color = NewaxTheme.colors.textPrimary,
                style = NewaxTheme.typography.body,
            )
        }
        if (timeLabel != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                timeLabel,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textTertiary,
            )
        }
    }
}

/**
 * "The assistant is working" — announced, and motion-optional.
 *
 * Two accessibility obligations are met here (docs/UI_DESIGN.md §3):
 *  - SC 2.3.3: the pulsing dots are an unbounded `infiniteRepeatable`. Under
 *    reduced motion they are replaced by a static label rather than simply
 *    removed, so the state stays visible.
 *  - SC 1.4.1 / 4.1.3: three animating dots convey nothing to a screen reader.
 *    The row carries a description and is a polite live region, so the state
 *    is announced without interrupting whatever is being read.
 *
 * @param label the visible static text ("Thinking…") shown under reduced
 *   motion.
 * @param description the screen-reader description ("Newax Aegis is
 *   thinking").
 */
@Composable
fun TypingIndicator(
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
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (reduceMotion) {
            Text(
                label,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textSecondary,
            )
            return@Row
        }
        val infiniteTransition = rememberInfiniteTransition(label = "typing")
        repeat(3) { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(NewaxTheme.colors.textSecondary.copy(alpha = alpha)),
            )
        }
    }
}

/**
 * Streamed assistant text: a polite live region so each emitted chunk is
 * announced at the next pause in speech, without moving focus (SC 2.1.2).
 * The caret stays where the user put it.
 */
@Composable
fun StreamingText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.liveRegionPolite(),
        color = NewaxTheme.colors.textPrimary,
        style = NewaxTheme.typography.bodyLong,
    )
}
