package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.ui.a11y.liveRegionAssertive
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The content-block family beyond copy/code/step (docs/UI_DESIGN.md §7's ten
 * kinds; §8 — Blocks): image, image generation, documents, MCQ, thought, the
 * artifact chip and panel, and the copyable text box.
 *
 * Renders are string-free — every label, caption, alt and state word is a
 * parameter the caller localizes. Image loading is a platform concern, so
 * [ImageBlock]/[ImageGenBlock] take an [image] **slot** the caller fills with
 * its own loader (Coil on Android, painterResource elsewhere) — `commonMain`
 * never imports an image library.
 *
 * Decision logic that can be tested without Compose is extracted as plain
 * functions next to their component: [mcqOptions], [clampProgress],
 * [artifactAccessibleName], [documentRowAccessibleName].
 */

/**
 * Copyable text (docs/UI_DESIGN.md §7): a bordered surface card with a Copy
 * action beneath. The visible text is the card's accessible name — no
 * duplicate `contentDescription`; the copy state is the [CopyButton]'s word
 * ("Copy" → "Copied ✓"), a change to visible text, not an icon swap.
 */
@Composable
fun CopyableTextBox(
    text: String,
    copyLabel: String,
    copiedLabel: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.md),
    ) {
        Text(
            text,
            style = NewaxTheme.typography.bodyLong,
            color = NewaxTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(NewaxTheme.spacing.sm))
        CopyButton(copyLabel, copiedLabel, onCopy)
    }
}

/**
 * An image block (docs/UI_DESIGN.md §7 — Image): rounded card at natural
 * width, caption below.
 *
 * @param image the image itself, drawn by the caller's loader inside the
 *   clipped card — its semantics are replaced by [alt] (or [caption] when
 *   [alt] is null) so the reader hears the description, not a broken image.
 * @param onTap opens the image viewer (route 1.7) when provided.
 */
@Composable
fun ImageBlock(
    image: @Composable () -> Unit,
    caption: String,
    modifier: Modifier = Modifier,
    alt: String? = null,
    onTap: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier
                .clip(NewaxTheme.shapes.card)
                .semantics(mergeDescendants = true) {
                    contentDescription = alt ?: caption
                    if (onTap != null) role = Role.Button
                }
                .then(
                    if (onTap != null) {
                        Modifier
                            .minimumTouchTarget()
                            .clickable(onClick = onTap)
                    } else {
                        Modifier
                    }
                ),
        ) { image() }
        Spacer(Modifier.height(NewaxTheme.spacing.xs))
        Text(
            caption,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textTertiary,
        )
    }
}

/** The lifecycle of an image-generation request (docs/UI_DESIGN.md §7). */
enum class ImageGenPhase { GENERATING, DONE, FAILED }

/**
 * Clamps [progress] to [0f, 1f] — a model emitting 1.4 or -0.1 must not
 * stretch the bar or leave it negative.
 */
fun clampProgress(progress: Float): Float = progress.coerceIn(0f, 1f)

/**
 * The image-generation block (docs/UI_DESIGN.md §7 — Image generation):
 * prompt + progress + Cancel while generating; the image on success; a red
 * error line + Retry on failure.
 *
 * Progress is a polite live region; the failure line is assertive.
 */
@Composable
fun ImageGenBlock(
    phase: ImageGenPhase,
    prompt: String,
    cancelLabel: String,
    retryLabel: String,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    errorMessage: String? = null,
    image: (@Composable () -> Unit)? = null,
    caption: String? = null,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm),
    ) {
        when (phase) {
            ImageGenPhase.GENERATING -> {
                Text(
                    prompt,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textSecondary,
                )
                LinearProgressIndicator(
                    progress = { clampProgress(progress) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .liveRegionPolite(),
                    color = NewaxTheme.colors.accent,
                    trackColor = NewaxTheme.colors.surfaceStrong,
                )
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.minimumTouchTarget(),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = NewaxTheme.colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(NewaxTheme.spacing.xs))
                    Text(cancelLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary)
                }
            }
            ImageGenPhase.DONE -> {
                if (image != null) {
                    ImageBlock(
                        image = image,
                        caption = caption.orEmpty(),
                        alt = caption,
                    )
                }
            }
            ImageGenPhase.FAILED -> {
                Text(
                    prompt,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textSecondary,
                )
                Text(
                    errorMessage.orEmpty(),
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.error,
                    modifier = Modifier.liveRegionAssertive(),
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.minimumTouchTarget(),
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = NewaxTheme.colors.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(NewaxTheme.spacing.xs))
                    Text(retryLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.error)
                }
            }
        }
    }
}

/**
 * One row of the documents card (docs/UI_DESIGN.md §7 — Documents).
 *
 * @param typeLabel the file kind ("PDF", "DOCX") — also drives the leading
 *   letter tile (decorative; the accessible name below is the meaning).
 * @param detailLabel optional page/word count.
 */
data class DocumentRow(
    val filename: String,
    val typeLabel: String,
    val sizeLabel: String,
    val onClick: () -> Unit,
    val detailLabel: String? = null,
)

/**
 * The row's full accessible name — every part, blank parts dropped, so a
 * screen reader announces one focus stop: "report.pdf, PDF, 2.4 MB, 12 pages".
 */
fun documentRowAccessibleName(row: DocumentRow): String =
    listOf(row.filename, row.typeLabel, row.sizeLabel, row.detailLabel)
        .filter { it.isNotBlank() }
        .joinToString(", ")

/**
 * The documents card (docs/UI_DESIGN.md §7 — Documents): a hairline card of
 * rows, each one focus stop with its full name (SC 1.4.1 — never the icon
 * alone).
 */
@Composable
fun DocumentsContainer(
    rows: List<DocumentRow>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = NewaxTheme.colors.border)
            Row(
                Modifier
                    .fillMaxWidth()
                    .minimumTouchTarget()
                    .semantics(mergeDescendants = true) {
                        contentDescription = documentRowAccessibleName(row)
                        role = Role.Button
                    }
                    .clickable(onClick = row.onClick)
                    .padding(horizontal = NewaxTheme.spacing.md, vertical = NewaxTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(NewaxTheme.shapes.pill)
                        .background(NewaxTheme.colors.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        row.typeLabel.take(1).uppercase(),
                        style = NewaxTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                        color = NewaxTheme.colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(NewaxTheme.spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.filename,
                        style = NewaxTheme.typography.body,
                        color = NewaxTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val detail = listOf(row.sizeLabel, row.detailLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = NewaxTheme.typography.caption,
                            color = NewaxTheme.colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The MCQ block (docs/UI_DESIGN.md §7 — MCQ/choice): question line, 2–4
 * option rows, and [customLabel] always last.
 *
 * Each row is a radio control ([Role.RadioButton]) with a 44 dp target; the
 * question is visible text above the group. One MCQ per message.
 */
fun mcqOptions(options: List<String>, customLabel: String): List<String> {
    val withCustom = if (options.lastOrNull() == customLabel) options else options + customLabel
    return withCustom.distinct()
}

@Composable
fun McqCard(
    question: String,
    options: List<String>,
    customLabel: String,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            question,
            style = NewaxTheme.typography.body,
            color = NewaxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(NewaxTheme.spacing.xs))
        mcqOptions(options, customLabel).forEach { option ->
            val isSelected = option == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .minimumTouchTarget()
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = NewaxTheme.spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null, // the row is the control — one focus stop, one tap target
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(NewaxTheme.spacing.sm))
                Text(
                    option,
                    style = NewaxTheme.typography.body,
                    color = NewaxTheme.colors.textPrimary,
                )
            }
        }
    }
}

/**
 * The thought block (docs/UI_DESIGN.md §7 — Thought): a collapsible
 * "Thinking" header; expanded, the reasoning shows on `surfaceSelected`.
 *
 * The expand state is a [stateDescription] on the control (the header row),
 * never on the chevron glyph. [streaming] makes the reasoning a polite live
 * region — it is announced at a pause, and never steals focus. Collapse state
 * is the caller's (it persists per conversation).
 */
@Composable
fun ThoughtContainer(
    title: String,
    reasoning: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    expandedStateLabel: String,
    collapsedStateLabel: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) expandedStateLabel else collapsedStateLabel
                }
                .clickable(onClick = onToggle)
                .padding(horizontal = NewaxTheme.spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = NewaxTheme.typography.label,
                color = NewaxTheme.colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = NewaxTheme.colors.textTertiary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            HorizontalDivider(color = NewaxTheme.colors.border)
            Text(
                reasoning,
                style = NewaxTheme.typography.caption.copy(fontSize = 13.sp),
                color = NewaxTheme.colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NewaxTheme.colors.surfaceSelected)
                    .then(if (streaming) Modifier.liveRegionPolite() else Modifier)
                    .padding(NewaxTheme.spacing.md),
            )
        }
    }
}

/**
 * The artifact chip's full accessible name — title, type and size, blank parts
 * dropped ("Q3 report, PDF, 2.4 MB").
 */
fun artifactAccessibleName(title: String, typeLabel: String, sizeLabel: String): String =
    listOf(title, typeLabel, sizeLabel)
        .filter { it.isNotBlank() }
        .joinToString(", ")

/**
 * The artifact chip (docs/UI_DESIGN.md §7 — Artifact chip): the compact
 * pointer to the artifact panel (route 1.3). One focus stop whose name
 * includes the type; the icon and glyphs are decorative.
 */
@Composable
fun ArtifactChip(
    title: String,
    typeLabel: String,
    sizeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .minimumTouchTarget()
            .semantics(mergeDescendants = true) {
                contentDescription = artifactAccessibleName(title, typeLabel, sizeLabel)
                role = Role.Button
            }
            .clip(NewaxTheme.shapes.pill)
            .background(NewaxTheme.colors.surfaceSelected)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = NewaxTheme.spacing.md, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Star,
            contentDescription = null,
            tint = NewaxTheme.colors.accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(NewaxTheme.spacing.xs))
        Text(
            title,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(NewaxTheme.spacing.xs))
        Text(
            listOf(typeLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(" · "),
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textTertiary,
        )
    }
}

/**
 * The artifact panel's inner scaffold (docs/UI_DESIGN.md §7.1): heading +
 * close, then the artifact's content in a scrollable body. The container —
 * full-screen sheet on compact, 40% side sheet on medium, third pane on
 * expanded — is the caller's layout; this is the part that is identical
 * everywhere.
 */
@Composable
fun ArtifactPanel(
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(NewaxTheme.colors.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .minimumTouchTarget()
                .padding(horizontal = NewaxTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = NewaxTheme.typography.heading,
                color = NewaxTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.minimumTouchTarget(),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = closeLabel,
                    tint = NewaxTheme.colors.textSecondary,
                )
            }
        }
        HorizontalDivider(color = NewaxTheme.colors.border)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(NewaxTheme.spacing.md),
            content = content,
        )
    }
}
