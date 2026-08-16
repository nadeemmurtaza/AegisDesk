package com.newax.aegis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.theme.NewaxTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The knowledge-graph surface (docs/UI_DESIGN.md §8 — Lists & data:
 * `GraphCanvas` + `GraphListFallback`).
 *
 * [GraphCanvas] draws nodes on a circle layout with edges between them;
 * [GraphListFallback] is the same data as a scrollable connection list, for
 * screens (or assistive-tech profiles) where a canvas is not usable. The
 * layout and the accessible summary are pure functions ([graphNodePositions],
 * [graphAccessibleSummary]) so the geometry and the words are unit-tested
 * without Compose.
 *
 * A canvas is decorative to a screen reader: the composable carries the
 * [graphAccessibleSummary] as its description, and the canvas itself stays
 * non-interactive. Interactive graph exploration is a later slice.
 */

/** A computed node position, in canvas coordinates. */
data class GraphPosition(val x: Float, val y: Float)

/**
 * Places [nodes] on a circle inside `width` x `height`, inset by [margin].
 *
 * Deterministic: the same input always produces the same output, which keeps
 * the canvas stable across recompositions. One node is centred; two sit on
 * the horizontal diameter; more spread around the full circle.
 */
fun graphNodePositions(
    nodes: List<String>,
    width: Float,
    height: Float,
    margin: Float = 24f,
): List<GraphPosition> {
    if (nodes.isEmpty()) return emptyList()
    val cx = width / 2f
    val cy = height / 2f
    if (nodes.size == 1) return listOf(GraphPosition(cx, cy))
    val radius = min(width, height) / 2f - margin
    if (radius <= 0f) return List(nodes.size) { GraphPosition(cx, cy) }
    val step = (2.0 * PI) / nodes.size
    return nodes.indices.map { i ->
        // Start at the left (angle π): two nodes land on the horizontal
        // diameter — the documented shape — with the first node leftmost.
        val angle = PI + step * i
        GraphPosition(
            x = (cx + radius * cos(angle)).toFloat(),
            y = (cy + radius * sin(angle)).toFloat(),
        )
    }
}

/**
 * The graph's one-focus-stop description: node and edge counts plus each
 * connection, so a screen reader hears what the canvas draws
 * ("N=4, E=3. Ali connected to Work. Ali connected to Family."). The
 * connection word ([connectionLabel], e.g. "connected to") comes from the
 * caller so the module stays string-free.
 */
fun graphAccessibleSummary(
    nodes: List<String>,
    edges: List<Pair<String, String>>,
    connectionLabel: String,
): String {
    if (nodes.isEmpty()) return ""
    val head = "N=${nodes.size}, E=${edges.size}"
    if (edges.isEmpty()) return head
    return buildString {
        append(head)
        append(". ")
        append(
            edges.joinToString(". ") { (a, b) ->
                "$a $connectionLabel $b"
            },
        )
    }
}

/**
 * The canvas itself: nodes as labelled discs on a circle, edges as lines.
 * The label text is drawn with the theme's [NewaxTheme.typography.caption]
 * face, coloured to clear the node fill; the summary is the composable's
 * [contentDescription] — a canvas is decorative to assistive tech.
 */
@Composable
fun GraphCanvas(
    nodes: List<String>,
    edges: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    connectionLabel: String = "",
) {
    val textMeasurer = rememberTextMeasurer()
    val summary = graphAccessibleSummary(nodes, edges, connectionLabel)
    val nodeColors = listOf(
        NewaxTheme.colors.accent,
        NewaxTheme.colors.info,
        NewaxTheme.colors.warning,
        NewaxTheme.colors.success,
        NewaxTheme.colors.textSecondary,
    )
    val radius = 18f
    Canvas(
        modifier
            .fillMaxWidth()
            .height(240.dp)
            .semantics { if (summary.isNotBlank()) contentDescription = summary },
    ) {
        val positions = graphNodePositions(nodes, size.width, size.height)
        val centerOf = positions.mapIndexed { index, pos -> nodes[index] to pos }.toMap()
        edges.forEach { (a, b) ->
            val pa = centerOf[a]
            val pb = centerOf[b]
            if (pa != null && pb != null) {
                drawLine(
                    color = NewaxTheme.colors.borderStrong,
                    start = pa.toOffset(),
                    end = pb.toOffset(),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
        positions.forEachIndexed { index, pos ->
            val color = nodeColors[index % nodeColors.size]
            drawCircle(color = color, radius = radius, center = pos.toOffset())
            val layout = textMeasurer.measure(
                AnnotatedString(nodes[index]),
                style = NewaxTheme.typography.caption.copy(
                    fontWeight = FontWeight.Bold,
                    color = NewaxTheme.colors.surface,
                ),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = pos.x - layout.size.width / 2f,
                    y = pos.y - layout.size.height / 2f,
                ),
            )
        }
    }
}

private fun GraphPosition.toOffset(): Offset = Offset(x, y)

/**
 * The non-canvas rendering of the same graph: a scrollable list of the
 * connections, each "A connected to B" row one focus stop. Screens choose
 * this when the canvas is not appropriate (very large graphs, assistive-tech
 * profiles, reduced-data mode).
 */
@Composable
fun GraphListFallback(
    nodes: List<String>,
    edges: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    emptyLabel: String? = null,
) {    if (edges.isEmpty()) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(NewaxTheme.spacing.lg)
                .liveRegionPolite(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.List,
                contentDescription = null,
                tint = NewaxTheme.colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            Text(
                emptyLabel ?: "",
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textTertiary,
            )
        }
        return
    }
    LazyColumn(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface),
    ) {
        items(edges) { (a, b) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NewaxTheme.spacing.lg, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(NewaxTheme.colors.surfaceStrong),
                )
                Spacer(Modifier.width(NewaxTheme.spacing.md))
                Text(
                    "$a — $b",
                    style = NewaxTheme.typography.body,
                    color = NewaxTheme.colors.textPrimary,
                )
            }
            HorizontalDivider(color = NewaxTheme.colors.border)
        }
    }
}
