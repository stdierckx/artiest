package be.thalos.artiest.ui

import android.graphics.CornerPathEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A toolbar that is any shape at all.
 *
 * ## Why this is a `Layout` and not a `Row`
 *
 * A row places things one after another along a line, and that is exactly the
 * assumption being removed. Here a placement already knows the cell it starts
 * in, so the measure pass has nothing to decide: it sizes every child to
 * `w × SLOT` by `h × SLOT` and puts it at `(x - bounds.x) × SLOT`,
 * `(y - bounds.y) × SLOT`. Ten lines, no arrangement, no alignment, and it is
 * the same identity [DropMath] inverts.
 *
 * ## Why an L reads as one shape and not as two bars that touch
 *
 * The whole trick, and it is three calls:
 *
 * ```
 * for (r in region.rects) union.op(rectPath(r), Path.Op.UNION)
 * paint.pathEffect = CornerPathEffect(radius)
 * paint.getFillPath(union, rounded)
 * ```
 *
 * `Path.op(UNION)` merges the rectangles into **one outline**, and
 * `CornerPathEffect` rounds every corner of that outline — the outer ones and,
 * which is the point, the inner one at the notch. An L then has the same radius
 * everywhere, which is what makes it look drawn rather than assembled.
 * `getFillPath` bakes the effect into a real path, so the same outline both
 * fills the ground and clips the contents; without it the ground would be
 * rounded and the clip would not, and a panel overhanging the corner would give
 * the game away. Both APIs are API 19 and `minSdk` is 29.
 *
 * ## The pen must still reach the paper
 *
 * The notch of an L is a hole in the chrome, and a stroke starting there has to
 * go to the canvas. Compose only consumes a pointer where an interactive node
 * is, so the rule is:
 *
 * > **This composable carries no pointer modifier of its own.** The ground and
 * > the outline are `drawBehind`, which draws and does not listen; only the
 * > cells are interactive; the notch has no child in it and therefore has no
 * > target.
 *
 * A `Modifier.clip` does not stop a pointer either way, which is why the answer
 * has to be "nothing is there" rather than "the shape rejects it".
 *
 * **This is the one claim in the file that has to be checked on the tablet**:
 * draw a stroke starting inside an L's notch and confirm `RejectionCounters`
 * says the pen owned it.
 *
 * ## No scrolling
 *
 * A shape is exactly as big as it is; there is nothing to scroll to. What does
 * not fit goes to overflow, which is visible and tappable — see
 * `docs/ui-expansion-plan.md`, U9. A plain bar keeps the scroll it has, because
 * a bar can be longer than the screen and always could. **Do not add a scroll
 * here**: it would put an offset back between the cell grid and the pointer,
 * which is the one thing this geometry exists to avoid.
 */
@Composable
internal fun ChromeSurface(
    surface: Surface,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    modifier: Modifier = Modifier,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val region = surface.region
    val density = LocalDensity.current
    val slotPx = with(density) { Chrome.SLOT.toPx() }
    val radiusPx = with(density) { CORNER.toPx() }
    val bounds = region.bounds

    // Rebuilt when the shape changes and never per frame. A shape changes while
    // the user is editing it, which is the one moment nobody is drawing.
    val ground = remember(region, slotPx, radiusPx) { groundPath(region, slotPx, radiusPx) }
    val shape = remember(ground) { PathShape(ground) }

    val scheme = MaterialTheme.colorScheme
    val hovered = drag.hover == surface.id
    val alpha by animateFloatAsState(
        if (hovered) 0.86f else Chrome.BAR_ALPHA,
        label = "surfaceAlpha",
    )
    val fill = scheme.surface.copy(alpha = alpha)
    val edge = if (hovered) {
        scheme.primary
    } else {
        scheme.outlineVariant.copy(alpha = Chrome.BAR_OUTLINE_ALPHA)
    }
    val strokePx = with(density) { (if (hovered) 1.5.dp else 1.dp).toPx() }
    val outline = remember(ground) { ground.asComposePath() }

    // Which cells get a child, and in what order. Placements first, so the list
    // is stable as arrange mode comes and goes: a chip keeps its index, and
    // therefore its own state, when the empty targets appear beside it.
    val cells = remember(surface, arranging) { childCells(surface, arranging) }

    Layout(
        content = {
            for ((cell, placed) in cells) {
                ShapedCell(
                    surface = surface,
                    cell = cell,
                    placed = placed,
                    layout = layout,
                    onLayout = onLayout,
                    arranging = arranging,
                    drag = drag,
                    slotContent = slotContent,
                )
            }
        },
        modifier = modifier
            .clip(shape)
            // The rule the user asked for, for shapes: the toolbar is
            // translucent and the buttons on it are not. `SlotSurface` is the
            // opaque half; these two lines are the other one, and they share
            // their numbers with `Modifier.barSkin` so a bar and an L cannot
            // drift apart.
            .drawBehind {
                drawPath(outline, fill)
                drawPath(outline, edge, style = Stroke(width = strokePx))
            }
            .onGloballyPositioned {
                drag.bounds[surface.id] = it.boundsInRoot()
                drag.runOrigin[surface.id] = it.positionInRoot()
            },
    ) { measurables, _ ->
        val width = (bounds.w * slotPx).roundToInt()
        val height = (bounds.h * slotPx).roundToInt()
        layout(width, height) {
            measurables.forEachIndexed { i, measurable ->
                val (cell, placed) = cells[i]
                val w = placed?.w ?: 1
                val h = placed?.h ?: 1
                measurable
                    .measure(Constraints.fixed((w * slotPx).roundToInt(), (h * slotPx).roundToInt()))
                    .place(
                        ((cell.x - bounds.x) * slotPx).roundToInt(),
                        ((cell.y - bounds.y) * slotPx).roundToInt(),
                    )
            }
        }
    }
}

/** One cell of a shaped surface: a control, a chip standing in for it, or a target. */
@Composable
private fun ShapedCell(
    surface: Surface,
    cell: Cell,
    placed: CellPlacement?,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    var chooser by remember { mutableStateOf(false) }

    Box {
        when {
            placed != null && !arranging ->
                SlotSurface {
                    // The axis a control is drawn along is the shape's at this
                    // cell, not the whole surface's. That is what makes one
                    // slider stand up in an L's arm and the next lie flat along
                    // its foot, which no bar could ever say.
                    slotContent(placed.item, surface.region.localAxis(cell))
                }

            placed != null ->
                ArrangeChip(
                    item = placed.item,
                    barId = surface.id,
                    cell = cell,
                    drag = drag,
                    layout = layout,
                    onLayout = onLayout,
                    onClick = { chooser = true },
                )

            arranging -> EmptyTarget { chooser = true }

            else -> Unit
        }

        if (chooser) {
            ToolChooser(
                layout = layout,
                bar = surface,
                cell = cell,
                onDismiss = { chooser = false },
                onLayout = { chooser = false; onLayout(it) },
            )
        }
    }
}

/** The placements, then — while arranging — every cell nothing is standing on. */
private fun childCells(surface: Surface, arranging: Boolean): List<Pair<Cell, CellPlacement?>> {
    val out = ArrayList<Pair<Cell, CellPlacement?>>(surface.slots.placements.size + 8)
    for (p in surface.slots.placements) out += p.cell to p
    if (arranging) {
        for (cell in surface.region.cells(surface.flow)) {
            if (surface.slots.covering(cell) == null) out += cell to null
        }
    }
    return out
}

/**
 * The outline of a shape, with every corner — inside and out — at one radius.
 *
 * See the file KDoc for why this is three calls and not a week.
 */
internal fun groundPath(
    region: CellRegion,
    slotPx: Float,
    radiusPx: Float,
): android.graphics.Path {
    val b = region.bounds
    val union = android.graphics.Path()
    for (r in region.rects) {
        val piece = android.graphics.Path()
        piece.addRect(
            (r.x - b.x) * slotPx,
            (r.y - b.y) * slotPx,
            (r.right - b.x) * slotPx,
            (r.bottom - b.y) * slotPx,
            android.graphics.Path.Direction.CW,
        )
        union.op(piece, android.graphics.Path.Op.UNION)
    }
    val rounded = android.graphics.Path()
    android.graphics.Paint()
        .apply { pathEffect = CornerPathEffect(radiusPx) }
        .getFillPath(union, rounded)
    return rounded
}

/** A [Shape] that is one fixed path. Its size is the shape's, so nothing scales. */
private class PathShape(path: android.graphics.Path) : Shape {
    private val outline = Outline.Generic(path.asComposePath())

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = outline
}

/** How round every corner of a shaped surface is. The same radius `barSkin` uses. */
private val CORNER = 20.dp
