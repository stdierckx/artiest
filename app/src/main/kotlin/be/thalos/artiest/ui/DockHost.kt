package be.thalos.artiest.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The chrome: four edge docks, a floating panel, and the mode in which they can
 * be rearranged.
 *
 * ## What this knows and what it does not
 *
 * It knows where a control goes and nothing about what a control is. It is
 * handed [slotContent] and calls it, exactly as `SlotToolbar` was — the contract
 * that made adding a Phase 2 control a catalogue entry plus a `when` branch is
 * unchanged, and it now carries one extra argument: the [Axis] the item is being
 * drawn along, because a slider laid down the left edge has to be a vertical
 * slider and only the dock knows which edge it is.
 *
 * ## Two modes, and why the bar is a different length in each
 *
 * Out of arrange mode a bar shows **only as far as its last item**, and an empty
 * slot between two items is drawn as a narrow gap rather than as an empty box.
 * That is what makes the default layout's grouping visible: the gap between the
 * eraser and the colour, or between redo and export, is a slot nobody filled,
 * and it reads as a group separator instead of as a hole.
 *
 * In arrange mode every slot appears at full width, because a slot you cannot
 * see is a slot you cannot drop into. So the bars grow when arranging starts.
 * The alternative — full-width empty slots all the time — is the layout this
 * replaces, and four edges of it is a picture frame around the drawing.
 *
 * ## Why rearranging is a mode at all
 *
 * `SlotToolbar`'s KDoc recorded the reason and it has not changed: a `Slider`
 * consumes drags and presses, so no long-press or drag over the size slider ever
 * reaches the bar underneath it. A gesture that works on the buttons and
 * silently fails on the sliders is worse than one that never works. Arrange mode
 * is where every slot becomes a plain target with no live control inside it, so
 * there is nothing to race.
 *
 * The UI plan made that a stop condition for the dock work — *"U5 needs a
 * gesture that races the canvas outside Arrange mode. Stop."* — and it is kept:
 * outside arrange mode nothing here handles a drag at all, so a drag over the
 * size slider is a size change, which is correct.
 */
@Composable
fun DockHost(
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    onArranging: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What the chooser offers. **Only the chooser** — see [CatalogueFilter].
     *
     * A tool already on a bar but outside the filter keeps working and keeps
     * its cell, which is what makes switching workspace safe rather than
     * destructive.
     */
    filter: CatalogueFilter = CatalogueFilter.EVERYTHING,
    onFilter: (CatalogueFilter) -> Unit = {},
    /**
     * Anything else that belongs in arrange mode, drawn above the hint.
     *
     * A slot rather than a parameter, so that this file keeps knowing where a
     * control goes and nothing about what a control is. The workspace switcher
     * lives here, and this file has never heard of a workspace.
     */
    arrangeExtras: @Composable () -> Unit = {},
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val density = LocalDensity.current
    val drag = remember { DockDrag() }
    val slotPx = with(density) { Chrome.SLOT.toPx() }
    SideEffect { drag.slotPx = slotPx }

    // Which surface is being redrawn, if any. One at a time, because the board
    // covers the screen and two boards would be two answers to "what is under
    // the pen".
    var shaping by remember { mutableStateOf<String?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .systemBarsPadding()
            // positionInRoot, not boundsInRoot: the second is clipped to the
            // parent, so on a screen where this box is inset the ghost would be
            // drawn against a frame that is not the one it is measured in.
            .onGloballyPositioned { drag.hostOrigin = it.positionInRoot() },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val hostWidth = with(density) { maxWidth.toPx() }
            val hostHeight = with(density) { maxHeight.toPx() }
            SideEffect { drag.hostSize = Offset(hostWidth, hostHeight) }

            // The chrome's own grid: how many whole cells fit on this glass.
            // Everything a shape can be is measured against it.
            val gridW = (hostWidth / slotPx).toInt().coerceAtLeast(1)
            val gridH = (hostHeight / slotPx).toInt().coerceAtLeast(1)

            for (bar in layout.edges) {
                BarView(
                    bar = bar,
                    layout = layout,
                    onLayout = onLayout,
                    arranging = arranging,
                    onDraw = { shaping = bar.id },
                    drag = drag,
                    filter = filter,
                    onFilter = onFilter,
                    modifier = Modifier
                        .align(bar.dock.alignment())
                        .padding(Chrome.EDGE_INSET)
                        // A horizontal bar in arrange mode is every slot wide,
                        // which on a tablet is very nearly the whole screen —
                        // and the arrange affordance lives in the bottom
                        // corner. Keeping the corners clear costs a bar two
                        // slots of length it did not need and saves the one
                        // control that gets you out of the mode from being
                        // underneath the bar you are editing.
                        .then(
                            if (bar.axis == Axis.HORIZONTAL) {
                                Modifier.padding(horizontal = CORNER_CLEARANCE)
                            } else {
                                Modifier
                            }
                        ),
                    slotContent = slotContent,
                )
            }

            // After the edges, so a bar the user placed himself wins the
            // overlap. He put it there; the edge was always going to be there.
            for (bar in layout.floating) {
                FloatingBarView(
                    bar = bar,
                    layout = layout,
                    onLayout = onLayout,
                    arranging = arranging,
                    onDraw = { shaping = bar.id },
                    drag = drag,
                    filter = filter,
                    onFilter = onFilter,
                    hostWidth = hostWidth,
                    hostHeight = hostHeight,
                    slotContent = slotContent,
                )
            }

            // Above the bottom dock and centred, which is the one strip of
            // canvas that is guaranteed clear in arrange mode: the side docks
            // run nearly the full height, so a hint in either bottom corner
            // lands on one of them.
            AnimatedVisibility(
                visible = arranging,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = Chrome.BAR_THICKNESS + Chrome.EDGE_INSET * 3),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    arrangeExtras()
                    Spacer(Modifier.height(8.dp))
                    ArrangeHint(onReset = { onLayout(DockLayout.STARTER) })
                }
            }

            ArrangeButton(
                arranging = arranging,
                onArranging = onArranging,
                modifier = Modifier.align(Alignment.BottomEnd).padding(Chrome.EDGE_INSET),
            )

            DragGhost(drag)

            // Last, and over everything: while a shape is being drawn it is the
            // only thing on the screen that answers a pointer.
            shaping?.let { id ->
                val surface = layout.surface(id)
                if (surface == null) {
                    shaping = null
                } else {
                    ShapeEditor(
                        surface = surface,
                        gridW = gridW,
                        gridH = gridH,
                        onCancel = { shaping = null },
                        onApply = { region ->
                            shaping = null
                            onLayout(layout.reshape(id, region))
                        },
                    )
                }
            }
        }
    }
}

private fun Dock.alignment(): Alignment = when (this) {
    Dock.LEFT -> Alignment.CenterStart
    Dock.RIGHT -> Alignment.CenterEnd
    Dock.TOP -> Alignment.TopCenter
    Dock.BOTTOM -> Alignment.BottomCenter
    Dock.FLOATING -> Alignment.TopStart
}

// ---------------------------------------------------------------------------
// One bar
// ---------------------------------------------------------------------------

/**
 * One surface on an edge, drawn.
 *
 * Absent entirely when it is empty and nobody is arranging: an edge with no
 * controls on it should look like an edge, not like a bar someone forgot to
 * fill. In arrange mode it appears whatever is on it, because an invisible bar
 * cannot be dropped into and *the user can decide where they attach* is the
 * whole feature.
 *
 * **There are two renderers here on purpose, and it is not a hedge.** A bar is
 * a line and has things a shape does not: it scrolls when it is longer than the
 * screen, it stops at its last item so an unfilled cell reads as a group
 * separator, and it grows across itself to hold a panel. A shape scrolls
 * nowhere, is exactly as big as it was drawn, and has a notch the pen goes
 * through. Making one renderer do both would mean the strip growing a shape's
 * special cases and the shape growing a bar's, and the first casualty would be
 * the day-one behaviour every existing user already has. So a strip is rendered
 * by exactly the code that rendered it before shapes existed, and anything else
 * goes to [ChromeSurface].
 */
@Composable
private fun BarView(
    bar: Surface,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    onDraw: () -> Unit,
    drag: DockDrag,
    filter: CatalogueFilter,
    onFilter: (CatalogueFilter) -> Unit,
    modifier: Modifier,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    if (bar.isEmpty && !arranging) return

    val shapeButton: @Composable () -> Unit = {
        ShapeButton(bar, layout, onLayout, onDraw)
    }

    if (!bar.region.isStrip) {
        if (!arranging) {
            ChromeSurface(
                bar, layout, onLayout, arranging, drag, filter, onFilter, modifier, slotContent,
            )
        } else {
            // Beside the shape rather than on it: a button covering a cell is a
            // cell nothing can be dropped into, and arrange mode is when
            // dropping happens.
            Row(verticalAlignment = Alignment.Top, modifier = modifier) {
                shapeButton()
                ChromeSurface(
                    bar, layout, onLayout, arranging, drag, filter, onFilter, Modifier, slotContent,
                )
            }
        }
        return
    }

    Box(
        modifier
            .barSkin(drag.hover == bar.id)
            .onGloballyPositioned { drag.bounds[bar.id] = it.boundsInRoot() }
            .padding(Chrome.BAR_PADDING),
    ) {
        if (!arranging) {
            BarRun(bar, layout, onLayout, arranging, drag, filter, onFilter, slotContent)
        } else if (bar.axis == Axis.VERTICAL) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                shapeButton()
                BarRun(bar, layout, onLayout, arranging, drag, filter, onFilter, slotContent)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                shapeButton()
                BarRun(bar, layout, onLayout, arranging, drag, filter, onFilter, slotContent)
            }
        }
    }
}

/**
 * The run of slots inside a bar, along its axis.
 *
 * **A bar is as thick as its thickest item.** Everything that lives inside a
 * bar is one cell deep and this is `BAR_THICKNESS`, exactly as before; a panel
 * is ten cells deep and the bar grows to hold it. That is the whole of the
 * second dimension at render time, and it is the reading of *"just another Ui
 * element in another toolbar, just a little bigger"* that needed no new layer:
 * a bar holding a big thing is a big bar. It is also what a docked palette does
 * in every program that has one.
 *
 * The consequence, stated rather than discovered: putting a panel on an edge
 * makes that whole edge deep, and the buttons beside it sit against the screen
 * edge rather than floating in the middle of it. That is the user's choice to
 * make — *"if it is not a good place, his choice"* — and the way out is one
 * drag.
 */
@Composable
private fun BarRun(
    bar: Surface,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    filter: CatalogueFilter,
    onFilter: (CatalogueFilter) -> Unit,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val vertical = bar.axis == Axis.VERTICAL
    // `bar.thickness()`, which asks the *placements* how deep they are, and not
    // the catalogue. Asking the catalogue was a defect with a visible face:
    // shrinking a fixated colour panel made the panel smaller and left the
    // toolbar it sits on at its original size, so the wheel ended up floating
    // in a grey rectangle twice its height. `CellPlacement` says in its own
    // KDoc why it is carried rather than derived — a resized panel's size is a
    // property of the placement — and this was the one reader that did not
    // believe it. Sharing `thickness()` with the grip beside it is also what
    // that function exists for.
    val thickness = bar.thickness()
    val scroll = rememberScrollState()

    // Only as far as the last item, unless arranging. See the file KDoc.
    val extent = if (arranging) {
        bar.slotCount
    } else {
        bar.slots.placements.maxOfOrNull { bar.slotOf(it.cell) + bar.spanOf(it) } ?: 0
    }

    val cells: @Composable () -> Unit = {
        var slot = 0
        while (slot < extent) {
            val here = bar.cellAt(slot)
            val placed = bar.slots.covering(here)
            SlotCell(
                bar = bar,
                cell = here,
                placed = placed,
                thickness = thickness,
                layout = layout,
                onLayout = onLayout,
                arranging = arranging,
                drag = drag,
                filter = filter,
                onFilter = onFilter,
                slotContent = slotContent,
            )
            slot += placed?.let { bar.spanOf(it) } ?: 1
        }
    }

    if (vertical) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .width(thickness)
                .heightIn(max = 640.dp)
                .verticalScroll(scroll)
                .onGloballyPositioned { drag.runOrigin[bar.id] = it.positionInRoot() },
        ) { cells() }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .height(thickness)
                .widthIn(max = 1000.dp)
                .horizontalScroll(scroll)
                // Inside the scroll, so this moves as the bar is scrolled and
                // the slot arithmetic never has to know a scroll offset exists.
                // positionInRoot and not boundsInRoot: the second is clipped to
                // the viewport, so a bar scrolled off its start would report the
                // visible edge and every drop would land short.
                .onGloballyPositioned { drag.runOrigin[bar.id] = it.positionInRoot() },
        ) { cells() }
    }
}

/**
 * One cell: a control, a chip standing in for it, or an empty target.
 *
 * The size is decided here and in one place, because it is the number the drop
 * arithmetic in [DockDrag.cellAt] inverts. If a cell were ever a different width
 * from `cells × SLOT`, a drop would land in the wrong place and nothing would
 * say why.
 */
@Composable
private fun SlotCell(
    bar: Surface,
    cell: Cell,
    placed: CellPlacement?,
    thickness: Dp,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    filter: CatalogueFilter,
    onFilter: (CatalogueFilter) -> Unit,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val vertical = bar.axis == Axis.VERTICAL
    var chooser by remember { mutableStateOf(false) }

    val span = placed?.let { bar.spanOf(it) } ?: 1
    // An unfilled slot out of arrange mode is a group separator, not a hole.
    val cells = if (placed == null && !arranging) 0.28f else span.toFloat()
    val length = Chrome.SLOT * cells
    // How deep this item is, which may be less than the bar. A button on a bar
    // that also holds a panel keeps its own size and sits against the screen
    // edge rather than floating in the middle of a deep bar.
    // An empty slot is one cell deep whatever the bar is. Letting it inherit
    // the bar's depth turns the two spare slots beside a fixated panel into two
    // 440dp columns of dashed outline, which reads as three panels rather than
    // one panel and some room.
    val across = minOf(Chrome.SLOT * (placed?.let { bar.depthOf(it) } ?: 1), thickness)

    Box(
        modifier = if (vertical) {
            Modifier.width(thickness).height(length)
        } else {
            Modifier.height(thickness).width(length)
        },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(bar.dock.crossAlignment())
                .then(
                    if (vertical) {
                        Modifier.width(across).height(length)
                    } else {
                        Modifier.height(across).width(length)
                    }
                ),
        ) {
            when {
                placed != null && !arranging ->
                    SlotSurface { slotContent(placed.item, bar.axis) }

                placed != null ->
                    ArrangeChip(
                        item = placed.item,
                        barId = bar.id,
                        cell = cell,
                        drag = drag,
                        layout = layout,
                        onLayout = onLayout,
                        onClick = { chooser = true },
                    )

                arranging -> EmptyTarget { chooser = true }

                else -> Unit
            }
        }

        if (chooser) {
            ToolChooser(
                layout = layout,
                bar = bar,
                cell = cell,
                filter = filter,
                onFilter = onFilter,
                onDismiss = { chooser = false },
                onLayout = { chooser = false; onLayout(it) },
            )
        }
    }
}

/**
 * Which side of a deep bar its shallow items sit against.
 *
 * The screen edge, so that a button on a bar made deep by a panel stays where
 * the hand expects it rather than drifting into the middle. A floating bar has
 * no edge to sit against, so it uses its leading side.
 */
private fun Dock.crossAlignment(): Alignment = when (this) {
    Dock.LEFT, Dock.FLOATING -> Alignment.CenterStart
    Dock.RIGHT -> Alignment.CenterEnd
    Dock.TOP -> Alignment.TopCenter
    Dock.BOTTOM -> Alignment.BottomCenter
}

/**
 * The opaque part.
 *
 * This is the user's rule made into a composable: *"the toolbars are
 * translucent, with only the button opaque"*. The bar behind it is painted at
 * [Chrome.BAR_ALPHA]; this is painted at 1, so a button reads as something
 * solid you press and the bar reads as a hint at where the buttons live.
 *
 * It fills its cell rather than wrapping its content, so a one-slot button and
 * a four-slot slider are the same object at two lengths and the run stays on
 * the slot grid.
 */
@Composable
internal fun SlotSurface(content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

/** A filled slot in arrange mode: the control stands aside so it can be moved. */
@Composable
internal fun ArrangeChip(
    item: ToolItem,
    barId: String,
    cell: Cell,
    drag: DockDrag,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    onClick: () -> Unit,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Stands in for the ripple that went with Modifier.clickable. See
    // carryGesture for why the clickable had to go.
    var pressed by remember { mutableStateOf(false) }
    val lifted = drag.carrying?.surface?.id == barId && drag.carrying?.cell == cell

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (pressed) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (pressed) 1f else 0.55f),
                RoundedCornerShape(13.dp),
            )
            .alpha(if (lifted) 0.25f else 1f)
            .onGloballyPositioned { coords = it }
            .pointerInput(item, barId, cell) {
                carryGesture(
                    coords = { coords },
                    onPressed = { pressed = it },
                    onPick = {
                        val bar = layout.surface(barId)
                        val here = bar?.slots?.covering(cell)
                        drag.carrying = if (bar != null && here != null) {
                            DockedItem(bar, here)
                        } else {
                            null
                        }
                    },
                    onMove = { root ->
                        drag.pointer = root
                        drag.hover = drag.surfaceAt(root)
                    },
                    onDrop = { onLayout(drag.drop(layout) ?: layout) },
                    onCancel = { drag.clear() },
                    onTap = onClick,
                )
            },
    ) {
        val glyph = ToolIcons.of(item)
        if (glyph != null && item.cellsWide <= 1 && item.cellsTall <= 1) {
            Icon(glyph, item.label, Modifier.size(Chrome.GLYPH), MaterialTheme.colorScheme.onSurface)
        } else {
            Text(
                item.short,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** An unfilled slot in arrange mode: faint, but not invisible, because it is the way in. */
@Composable
internal fun EmptyTarget(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(3.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Icon(
            ToolIcons.plus,
            "Add a control",
            Modifier.size(15.dp),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}

/**
 * Pick a control up, carry it, put it down — or, if it never moved, a tap.
 *
 * ## Why this is written out rather than `detectDragGestures`
 *
 * Two bugs, both of which made docking by drag impossible, and both of which
 * came from the convenient version rather than from anything about docks.
 *
 * **The first movement counted twice.** `detectDragGestures` calls
 * `onDragStart` with the pointer's position at the moment the drag threshold is
 * crossed, and then *immediately* calls `onDrag` with the amount by which that
 * same movement overshot the threshold. Seeding a position from the first and
 * adding the second puts the carried control ahead of the finger by exactly how
 * far past the threshold the first flick went — a few pixels if you start
 * slowly, most of a button if you start fast. Measured on the tablet at 78 and
 * at 125 pixels on two drags, each within a pixel of that prediction.
 *
 * **Accumulating deltas is wrong whenever anything moves underneath.** A bar is
 * a scroll container, and while a chip is being dragged along its own bar the
 * bar can scroll — so the chip moves under the finger, and the per-event delta,
 * which is measured in the chip's own coordinates, reports *less* than the
 * finger travelled. The control then trails the hand, which is the half of this
 * that a user actually notices.
 *
 * So: the position is read absolutely, every event, through
 * [LayoutCoordinates.localToRoot]. It cannot drift, because nothing is being
 * added up. And the gesture is consumed from the pointer-down, so the bar
 * underneath never starts scrolling in the first place — a child sees the main
 * pass before its parent, and a consumed move cancels the parent's own
 * threshold detection.
 *
 * ## What consuming the down costs
 *
 * `Modifier.clickable`, which is why [onTap] exists: with the down consumed,
 * nothing downstream will ever see a click, so the tap has to be recognised
 * here. [onPressed] is the other half of the same bill — the ripple goes with
 * the `clickable`, and a control that does not acknowledge being touched reads
 * as broken long before anybody works out that it was a gesture conflict.
 */
private suspend fun PointerInputScope.carryGesture(
    coords: () -> LayoutCoordinates?,
    onPressed: (Boolean) -> Unit = {},
    onPick: () -> Unit,
    onMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onTap: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        onPressed(true)

        var carrying = false
        val lifted = drag(down.id) { change ->
            change.consume()
            if (!carrying && (change.position - down.position).getDistance() > slop) {
                carrying = true
                onPick()
            }
            if (carrying) {
                // localToRoot every time, rather than a running total. The
                // fallback is the raw local position, which is only reached
                // before the first layout pass and is wrong by an inset rather
                // than by a whole gesture.
                onMove(coords()?.localToRoot(change.position) ?: change.position)
            }
        }

        onPressed(false)
        when {
            carrying && lifted -> onDrop()
            carrying -> onCancel()
            lifted -> onTap()
        }
    }
}

// ---------------------------------------------------------------------------
// The floating panel
// ---------------------------------------------------------------------------

/**
 * The dock that is not attached to anything.
 *
 * It is dragged by a grip rather than by its body, and that is not decoration:
 * the body is full of live controls, and a panel you move by grabbing its
 * middle is a panel that moves when you meant to change the brush size. The
 * grip is the one part of it that does nothing else.
 *
 * Its position is kept as a fraction of the window in each axis, so it survives
 * rotation without a second saved position — see `DockStore.loadFloatingAt` for
 * why that trade was taken.
 */
@Composable
private fun FloatingBarView(
    bar: Surface,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    onDraw: () -> Unit,
    drag: DockDrag,
    filter: CatalogueFilter,
    onFilter: (CatalogueFilter) -> Unit,
    hostWidth: Float,
    hostHeight: Float,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val at = bar.spot ?: BarSpot(0.3f, 0.4f)
    var sizePx by remember { mutableStateOf(Offset.Zero) }
    val free = Offset(
        (hostWidth - sizePx.x).coerceAtLeast(0f),
        (hostHeight - sizePx.y).coerceAtLeast(0f),
    )

    // Where the finger is while the bar is being dragged by its grip, so that
    // letting go over an edge can dock it there.
    var overEdge by remember { mutableStateOf<String?>(null) }
    var gripCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // A shape paints its own ground, so the row around it must not paint one
    // too -- a rounded rectangle behind an L is the picture the shape exists to
    // stop being. In arrange mode the row keeps it: the grip and the X have to
    // be findable over a drawing, and arrange mode is where furniture belongs.
    val shaped = !bar.region.isStrip
    val thickness = if (shaped) Chrome.SLOT * bar.region.bounds.h else bar.thickness()

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .offset { IntOffset((at.x * free.x).roundToInt(), (at.y * free.y).roundToInt()) }
            .then(if (!shaped || arranging) Modifier.barSkin(drag.hover == bar.id) else Modifier)
            .onGloballyPositioned {
                drag.bounds[bar.id] = it.boundsInRoot()
                sizePx = Offset(it.size.width.toFloat(), it.size.height.toFloat())
            }
            .padding(Chrome.BAR_PADDING),
    ) {
        // Only while arranging. A grip and an X on every floating panel all the
        // time is chrome the user is paying for in screen with nothing to show
        // for it -- the panel is the thing they wanted on screen, not its
        // furniture. Arrange mode is where the furniture lives.
        if (arranging) {
            Grip(
                at = at,
                free = free,
                height = thickness,
                coords = { gripCoords },
                onPointer = { root ->
                    // Edges only, and asked for by name rather than through the
                    // general hit test: the bar being dragged is under the
                    // finger by definition -- it is following it -- so the
                    // general test would answer with the bar itself, every time,
                    // and nothing would ever dock.
                    overEdge = drag.edgeAt(root)
                    drag.hover = overEdge
                },
                onRelease = {
                    val edge = overEdge
                    overEdge = null
                    drag.hover = null
                    // Dropped on an edge: the bar's contents go there and the
                    // bar closes. Dropped anywhere else it simply stays where it
                    // was let go, which the move below has already done.
                    if (edge != null) layout.dockInto(bar.id, edge)?.let(onLayout)
                },
                modifier = Modifier.onGloballyPositioned { gripCoords = it },
            ) { spot -> onLayout(layout.moveSurface(bar.id, spot)) }
            ShapeButton(bar, layout, onLayout, onDraw)
        }

        if (shaped) {
            ChromeSurface(
                bar, layout, onLayout, arranging, drag, filter, onFilter, Modifier, slotContent,
            )
        } else {
            BarRun(bar, layout, onLayout, arranging, drag, filter, onFilter, slotContent)
        }

        if (arranging) {
            // A bar has a length and a depth, so it can be dragged bigger. A
            // shape has neither -- it is reshaped rather than resized, which is
            // its own gesture and its own menu.
            if (!shaped) {
                ResizeHandle(bar) { along, across ->
                    onLayout(layout.resizeFloating(bar.id, along, across))
                }
            }
            CloseBar { onLayout(layout.closeSurface(bar.id)) }
        }
    }
}

/**
 * The corner you pull to make a floating bar bigger.
 *
 * It reports cells rather than pixels, because that is the only unit the layout
 * has: a bar is a whole number of slots long and a whole number of cells deep,
 * and a handle that reported pixels would be asking the model to hold a size it
 * cannot represent. The consequence is that it moves in steps of 44dp, which
 * reads as deliberate rather than as lag once you know the grid is there.
 */
@Composable
private fun ResizeHandle(bar: Surface, onResize: (Int, Int) -> Unit) {
    val density = LocalDensity.current
    val slotPx = with(density) { Chrome.SLOT.toPx() }
    val resize by rememberUpdatedState(onResize)
    val along by rememberUpdatedState(bar.slotCount)
    val across by rememberUpdatedState(bar.depthCells)

    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .width(18.dp)
            .height(bar.thickness())
            .pointerInput(Unit) {
                var fromAlong = 0
                var fromAcross = 0
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        fromAlong = along
                        fromAcross = across
                        travelled = Offset.Zero
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        travelled += delta
                        if (slotPx > 0f) {
                            resize(
                                fromAlong + (travelled.x / slotPx).roundToInt(),
                                fromAcross + (travelled.y / slotPx).roundToInt(),
                            )
                        }
                    },
                )
            },
    ) {
        Icon(
            ToolIcons.resize,
            "Resize this toolbar",
            Modifier.size(14.dp).padding(bottom = 2.dp),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/**
 * The X on a floating bar.
 *
 * Only floating bars have one, and that asymmetry is the type's: an edge has
 * nowhere to go and no way to be brought back, so it can only be emptied. A
 * floating bar is a thing the user made, so closing it closes it — with
 * everything on it, which is what makes closing a fixated panel one tap rather
 * than a hunt through a menu for the control that put it there.
 */
@Composable
private fun CloseBar(onClose: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 2.dp)
            .size(22.dp)
            .clip(CircleShape)
            .clickable(onClick = onClose),
    ) {
        Icon(
            ToolIcons.close,
            "Close this toolbar",
            Modifier.size(13.dp),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/**
 * How thick a bar is across its axis: one cell, or as deep as its deepest item.
 *
 * Shared by the run and by the handle beside it, because a grip one button tall
 * on a bar eleven cells tall is a grip nobody can find — and getting it from one
 * place is what stops the two disagreeing by a padding.
 */
private fun Surface.thickness(): Dp =
    maxOf(Chrome.BAR_THICKNESS - Chrome.BAR_PADDING * 2, Chrome.SLOT * depthCells)

/**
 * Two columns of dots, and the only part of a floating bar that is a handle.
 *
 * It is dragged by this rather than by its body, and that is not decoration:
 * the body is full of live controls, and a bar you move by grabbing its middle
 * is a bar that moves when you meant to change the brush size.
 */
@Composable
private fun Grip(
    at: BarSpot,
    free: Offset,
    height: Dp,
    coords: () -> LayoutCoordinates?,
    onPointer: (Offset) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    onMove: (BarSpot) -> Unit,
) {
    // Held through rememberUpdatedState because the pointerInput below is keyed
    // on Unit -- it has to be, or every recomposition during a drag would cancel
    // the gesture producing the recompositions -- and a value captured once
    // would be the bar's position when the finger went down, forever.
    val current by rememberUpdatedState(at)
    val room by rememberUpdatedState(free)
    val move by rememberUpdatedState(onMove)
    val pointer by rememberUpdatedState(onPointer)
    val released by rememberUpdatedState(onRelease)
    val where by rememberUpdatedState(coords)

    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .width(18.dp)
            .height(height)
            .pointerInput(Unit) {
                // Both of these live in the gesture's own coroutine, so they
                // survive the whole drag and are not affected by when
                // recomposition happens. Adding each delta to the *composed*
                // position instead loses every event that arrives before the
                // next frame: a fast drag then moves the bar a fraction of the
                // distance the finger went, which is exactly what it did.
                var base = Offset.Zero
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        base = Offset(current.x, current.y)
                        travelled = Offset.Zero
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        travelled += delta
                        val f = room
                        if (f.x > 0f && f.y > 0f) {
                            BarSpot.of(
                                base.x + travelled.x / f.x,
                                base.y + travelled.y / f.y,
                            )?.let(move)
                        }
                        // Read absolutely, not added up: the bar is moving under
                        // the finger by design, and localToRoot is the only
                        // reading that is still true while it does.
                        where()?.let { pointer(it.localToRoot(change.position)) }
                    },
                    onDragEnd = { released() },
                    onDragCancel = { released() },
                )
            }
            .padding(horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The dots mark the top of the handle rather than running its length: a
        // column of forty dots down the side of a panel is a texture, not a
        // control. The whole strip is draggable either way.
        Spacer(Modifier.height(6.dp))
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) {
                    Box(
                        Modifier
                            .size(2.5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Arrange
// ---------------------------------------------------------------------------

/**
 * The way into and out of arrange mode, and the only always-visible chrome.
 *
 * It is in the bottom corner and it is small and dim when off, because it is
 * not a drawing control and it should not be the brightest thing on the screen.
 * When on, it goes to the accent and grows a Reset beside it — the reset is only
 * reachable from inside the mode, which is the one place where wiping the
 * layout is a thing somebody might actually mean.
 */
@Composable
private fun ArrangeButton(
    arranging: Boolean,
    onArranging: (Boolean) -> Unit,
    modifier: Modifier,
) {
    Box(modifier) {
        val tint by animateFloatAsState(if (arranging) 1f else 0.55f, label = "arrange")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (arranging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = Chrome.BAR_ALPHA)
                    }
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.BAR_OUTLINE_ALPHA),
                    CircleShape,
                )
                .clickable { onArranging(!arranging) },
        ) {
            Icon(
                if (arranging) ToolIcons.check else ToolIcons.arrange,
                if (arranging) "Done arranging" else "Arrange the toolbars",
                Modifier.size(20.dp),
                if (arranging) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = tint)
                },
            )
        }
    }
}

/** What arrange mode is for, said once, with the way out of a bad layout beside it. */
@Composable
private fun ArrangeHint(onReset: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.BAR_OUTLINE_ALPHA),
                RoundedCornerShape(14.dp),
            )
            .padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Text(
            "drag a control to any edge, or tap a slot to change it",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Reset",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onReset)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** What is under the finger during a drag, drawn where the finger is. */
@Composable
private fun DragGhost(drag: DockDrag) {
    val carrying = drag.carrying ?: return
    val size = 44.dp
    val half = with(LocalDensity.current) { size.toPx() / 2f }
    val origin = drag.hostOrigin

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset {
                IntOffset(
                    (drag.pointer.x - origin.x - half).roundToInt(),
                    (drag.pointer.y - origin.y - half).roundToInt(),
                )
            }
            .size(size)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.primary)
            .border(1.dp, MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(13.dp)),
    ) {
        val glyph = ToolIcons.of(carrying.item)
        if (glyph != null) {
            Icon(glyph, carrying.item.label, Modifier.size(22.dp), MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(carrying.item.short, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

// ---------------------------------------------------------------------------
// The chooser
// ---------------------------------------------------------------------------

/**
 * What can go in this slot, and where this slot's occupant can go instead.
 *
 * Items that would not fit are shown greyed rather than hidden, for the reason
 * `SlotToolbar` recorded: greying keeps the catalogue a fixed list whose entries
 * are sometimes unavailable, which is a thing people already know how to read,
 * where hiding leaves the user comparing two menus to work out why the smoothing
 * slider is offered in one slot and not another.
 *
 * The row of five frames at the top is the other half of *the user can decide
 * where they attach*. Dragging is the direct way and it is better; this is the
 * way that works with one finger, on a small screen, without a gesture, and it
 * is the one that will still work when a drop lands on a dock that is full — the
 * menu says so by greying the frame.
 */
@Composable
internal fun ToolChooser(
    layout: DockLayout,
    bar: Surface,
    cell: Cell,
    filter: CatalogueFilter,
    onFilter: (CatalogueFilter) -> Unit,
    onDismiss: () -> Unit,
    onLayout: (DockLayout) -> Unit,
) {
    val occupant = bar.slots.covering(cell)
    var query by remember { mutableStateOf("") }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        // The escape hatch, and it is not optional.
        //
        // A filter is a promise that what it hid was not needed, and the whole
        // feature turns on that promise being recoverable in one tap. So this
        // searches the **entire catalogue**, filter or no filter, and offering
        // something adds it to filter.show — a decision the user made, recorded
        // as one, rather than a hole punched in the rules. See CatalogueFilter.
        ToolSearch(
            query = query,
            onQuery = { query = it },
            layout = layout,
            bar = bar,
            cell = cell,
            filter = filter,
            onFilter = onFilter,
            onLayout = onLayout,
        )
        if (query.isNotBlank()) return@DropdownMenu

        if (occupant != null) {
            Text(
                "Move ${occupant.item.label} to",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                for (target in Dock.EDGES) {
                    val moved = layout.move(bar.id, cell, target.id)
                    DockTarget(
                        dock = target,
                        enabled = target.id != bar.id && moved != null,
                        onClick = { moved?.let(onLayout) },
                    )
                }
                // The fifth target makes a bar rather than filling one, which
                // is the only way to reach a floating bar from a menu: there
                // may be none yet, and there may be six.
                DockTarget(
                    dock = Dock.FLOATING,
                    enabled = true,
                    onClick = {
                        onLayout(layout.addFloating(occupant.item, NEW_BAR_SPOT).first)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Remove ${occupant.item.label}", fontSize = 13.sp) },
                leadingIcon = { Icon(ToolIcons.close, null, Modifier.size(17.dp)) },
                onClick = { onLayout(layout.remove(bar.id, cell)) },
            )
            HorizontalDivider()
        }

        for (group in ToolGroup.entries) {
            // The filter belongs here and nowhere else. A tool already on a bar
            // but outside it keeps working and keeps its cell -- see
            // CatalogueFilter, and the rule that makes switching workspace a
            // change of menu rather than a change of toolbar.
            val items = ToolItem.entries.filter { it.group == group && it in filter }
            if (items.isEmpty()) continue
            Text(
                group.label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
            for (item in items) {
                val fits = layout.fits(bar.id, item, cell, ignoring = cell)
                val already = item == occupant?.item
                val elsewhere = !already && item in layout
                DropdownMenuItem(
                    text = {
                        Text(
                            // Named rather than ticked: an item already on some
                            // other bar is not unavailable, it is about to move,
                            // and saying which edge it is leaving is the
                            // difference between a menu and a guess.
                            if (elsewhere) {
                                "${item.label}  ·  ${layout.locate(item)?.surface?.dock?.label}"
                            } else {
                                item.label
                            },
                            fontSize = 13.sp,
                        )
                    },
                    leadingIcon = {
                        ToolIcons.of(item)?.let { Icon(it, null, Modifier.size(17.dp)) }
                    },
                    enabled = fits && !already,
                    onClick = { onLayout(layout.place(bar.id, item, cell)) },
                )
            }
        }
    }
}

/**
 * Find any control there is, whatever this workspace offers.
 *
 * This is trap 2's mitigation, and the workspace plan called it *"not
 * optional"*: **hiding a tool is a promise it was not needed**, and a promise
 * you cannot take back is a promise nobody should make. One field, the whole
 * catalogue, always there.
 *
 * A hit that the current filter does not offer is shown with *Add* beside it.
 * Tapping it does two things and says so: the control goes in the cell, and its
 * id goes into `filter.show`, so it is offered from then on. Adding to `show`
 * rather than taking it out of `hide` is deliberate — see [CatalogueFilter]: it
 * records a decision, and it survives a later change to the groups.
 */
@Composable
private fun ToolSearch(
    query: String,
    onQuery: (String) -> Unit,
    layout: DockLayout,
    bar: Surface,
    cell: Cell,
    filter: CatalogueFilter,
    onFilter: (CatalogueFilter) -> Unit,
    onLayout: (DockLayout) -> Unit,
) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Icon(
                    ToolIcons.search,
                    null,
                    Modifier.size(15.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.padding(start = 8.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            "Find any control",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    field()
                }
            }
        },
    )

    if (query.isBlank()) return

    val needle = query.trim().lowercase()
    // The whole catalogue. That is the point of it.
    val hits = ToolItem.entries.filter {
        needle in it.label.lowercase() || needle in it.id || needle in it.short.lowercase()
    }
    if (hits.isEmpty()) {
        Text(
            "nothing called that",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp),
        )
        return
    }

    for (item in hits.take(MAX_HITS)) {
        val offered = item in filter
        val fits = layout.fits(bar.id, item, cell, ignoring = cell)
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, fontSize = 13.sp)
                    if (!offered) {
                        Text(
                            "  ·  add to this workspace",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            leadingIcon = { ToolIcons.of(item)?.let { Icon(it, null, Modifier.size(17.dp)) } },
            enabled = fits,
            onClick = {
                if (!offered) onFilter(filter.offering(item))
                onLayout(layout.place(bar.id, item, cell))
            },
        )
    }
}

/** A menu is a menu, not a list of everything. Refine the word instead. */
private const val MAX_HITS = 8

@Composable
private fun DockTarget(dock: Dock, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(2.dp)
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(ToolIcons.of(dock), dock.label, Modifier.size(19.dp), tint)
    }
}

// ---------------------------------------------------------------------------
// Shared skin and drag bookkeeping
// ---------------------------------------------------------------------------

/**
 * The translucent ground every bar shares.
 *
 * One function so that the five docks cannot drift apart, and so that the rule
 * the user asked for lives in exactly one place: this is the translucent half,
 * and [SlotSurface] is the opaque half.
 */
@Composable
private fun Modifier.barSkin(hovered: Boolean): Modifier {
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    val alpha by animateFloatAsState(
        if (hovered) 0.86f else Chrome.BAR_ALPHA,
        label = "barAlpha",
    )
    return this
        .clip(shape)
        .background(scheme.surface.copy(alpha = alpha))
        .border(
            width = if (hovered) 1.5.dp else 1.dp,
            color = if (hovered) {
                scheme.primary
            } else {
                scheme.outlineVariant.copy(alpha = Chrome.BAR_OUTLINE_ALPHA)
            },
            shape = shape,
        )
}

/**
 * The in-flight drag, and the bars' positions on screen.
 *
 * The bounds are collected by the bars themselves through `onGloballyPositioned`
 * rather than computed, because a bar's length depends on what is in it and on
 * how far it has been scrolled, and re-deriving that here would be a second
 * implementation of the layout that is only wrong sometimes.
 *
 * Everything in it is in **root coordinates**, which is the only frame all five
 * docks share. [hostOrigin] converts back to the host's own frame for the one
 * thing that is drawn rather than hit-tested: the ghost under the finger.
 */
internal class DockDrag {
    var carrying by mutableStateOf<DockedItem?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var hover by mutableStateOf<String?>(null)
    var hostOrigin by mutableStateOf(Offset.Zero)

    /** The chrome's usable size, for turning a drop point into a bar position. */
    var hostSize by mutableStateOf(Offset.Zero)

    /** Each bar's visible rectangle, for deciding which dock a point is over. */
    val bounds = mutableStateMapOf<String, Rect>()

    /**
     * Where each dock's run of slots begins, in root coordinates.
     *
     * Reported by the run itself from inside its own scroll container, so it
     * already carries the scroll offset and the bar's padding and any handle
     * sitting in front of it. That is the whole reason it exists: the slot
     * arithmetic used to add a padding constant and a scroll value back by
     * hand, and every one of those terms was a chance to be a few slots out.
     */
    val runOrigin = mutableStateMapOf<String, Offset>()

    var slotPx: Float = 0f

    /**
     * Which bar the point is over, or null for bare canvas.
     *
     * Floating bars are asked first, and in reverse order, so that the topmost
     * of two overlapping bars is the one you hit — which is the one you can see.
     */
    /**
     * Which edge the point is over, if any. Floating bars are not answers here:
     * an edge is the only thing a floating bar can be docked into.
     */
    fun edgeAt(point: Offset): String? =
        Dock.EDGES.firstOrNull { bounds[it.id]?.contains(point) == true }?.id

    fun surfaceAt(point: Offset): String? =
        bounds.entries
            .filter { it.value.contains(point) }
            .map { it.key }
            .maxByOrNull { if (it.startsWith(DockLayout.FLOAT_PREFIX)) 1 else 0 }

    /**
     * Which cell of [surface] the point is over.
     *
     * The inverse of [SlotCell]'s sizing, and it is only correct because that
     * sizing is `cells × SLOT` with no exceptions. Measured from [runOrigin],
     * which is the run's own position and therefore already scrolled — there is
     * nothing here to add back and nothing to get wrong.
     *
     * Still one-dimensional, because the renderer still draws a line of cells.
     * When it draws a shape this becomes two of the same division — see
     * `docs/ui-expansion-plan.md`, U3 — and the identity it inverts does not
     * change, which is the reason that step is cheap.
     */
    fun cellAt(surface: Surface, point: Offset): Cell? {
        val origin = runOrigin[surface.id] ?: return null
        return DropMath.cellAt(surface.region, point - origin, slotPx)
    }

    /**
     * Finish a drag. Null when the drop changes nothing, which the caller reads
     * as *keep what you had*.
     *
     * **A drop on bare canvas makes a new floating bar where it landed.** That
     * is not a fallback, it is the gesture: dragging a control off the chrome
     * and onto the paper is how everyone tries to detach one, and now it does
     * exactly that. The bar is placed under the finger rather than at a default,
     * so what you get is where you let go.
     */
    fun drop(layout: DockLayout): DockLayout? {
        val held = carrying ?: return null
        val where = pointer
        clear()
        val target = surfaceAt(where)?.let { layout.surface(it) }
            ?: return layout.addFloating(held.item, spotAt(where)).first
        return layout.move(held.surface.id, held.cell, target.id, cellAt(target, where))
    }

    /** A root point as a fraction of the chrome, for a bar that is about to exist. */
    private fun spotAt(point: Offset): BarSpot {
        if (hostSize.x <= 0f || hostSize.y <= 0f) return BarSpot(0.3f, 0.4f)
        val local = point - hostOrigin
        return BarSpot.of(local.x / hostSize.x, local.y / hostSize.y) ?: BarSpot(0.3f, 0.4f)
    }

    fun clear() {
        carrying = null
        hover = null
    }
}

/**
 * How much of each end of a horizontal bar is left empty.
 *
 * Enough for the arrange affordance in the bottom corner plus its inset. It is
 * a length, not a slot count, because the corner it protects is measured in dp
 * and the bar is measured in slots, and pretending they are the same unit is
 * how a control ends up half under a button on one screen and not on another.
 */
private val CORNER_CLEARANCE = 58.dp

/**
 * Where a floating bar made from the menu appears.
 *
 * A drag knows where the finger let go; a menu does not, so this is a guess —
 * clear of the left tools and above the bottom sliders — and the drag that
 * fixes it is the same one that made the bar reachable in the first place.
 */
private val NEW_BAR_SPOT = BarSpot(0.34f, 0.38f)
