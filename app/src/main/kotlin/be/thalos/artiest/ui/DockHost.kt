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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The chrome: a grid, whatever toolbars have been drawn on it, and the mode in
 * which they are drawn.
 *
 * ## What this knows and what it does not
 *
 * It knows where a control goes and nothing about what a control is. It is
 * handed [slotContent] and calls it, exactly as `SlotToolbar` was — the contract
 * that made adding a Phase 2 control a catalogue entry plus a `when` branch is
 * unchanged, and it carries one extra argument: the [Axis] the item is being
 * drawn along, because a slider standing in an L's arm has to be a vertical
 * slider and only the shape knows which way it runs there.
 *
 * ## One grid
 *
 * There are no edges and nothing floats. Every surface is a shape somebody drew
 * on the screen's own cell grid, and `region.bounds` is where it is. This file
 * offsets each one by two numbers and has nothing else to decide — no
 * alignment, no mirroring, no fraction of the window, and no scroll. See
 * `docs/ui-grid-plan.md`.
 *
 * ## Why rearranging is a mode at all
 *
 * `SlotToolbar`'s KDoc recorded the reason and it has not changed: a `Slider`
 * consumes drags and presses, so no long-press or drag over the size slider ever
 * reaches the surface underneath it. A gesture that works on the buttons and
 * silently fails on the sliders is worse than one that never works. Arrange mode
 * is where every cell becomes a plain target with no live control inside it, so
 * there is nothing to race.
 *
 * ## Two gestures inside the one mode
 *
 * A drag cannot be both moving a control and painting a shape, so arrange mode
 * has a switch:
 *
 * - **Tools** — the surfaces answer the pointer. Drag a control to move it, tap
 *   an empty cell to choose one, drag a surface's grip to move the whole thing.
 * - **Shape** — the board answers the pointer. Paint cells to grow the surface
 *   the stroke started on, or to make a new one; turn the pen over to rub out.
 *
 * Outside arrange mode nothing here handles a drag at all, so a drag over the
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
     * A tool already on a surface but outside the filter keeps working and keeps
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

    // Which half of arrange mode is on. Tools by default: arriving in arrange
    // mode with the pen already painting would mean a stray touch draws a
    // toolbar, and the thing people come here to do most often is move a button.
    var shaping by remember { mutableStateOf(false) }
    var subtract by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .systemBarsPadding()
            // positionInRoot, not boundsInRoot: the second is clipped to the
            // parent, so on a screen where this box is inset every cell would be
            // measured against a frame that is not the one it is drawn in.
            .onGloballyPositioned { drag.hostOrigin = it.positionInRoot() },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val hostWidth = with(density) { maxWidth.toPx() }
            val hostHeight = with(density) { maxHeight.toPx() }

            // The chrome's own grid: how many whole cells fit on this glass.
            // Everything a shape can be is measured against it.
            val gridW = (hostWidth / slotPx).toInt().coerceAtLeast(1)
            val gridH = (hostHeight / slotPx).toInt().coerceAtLeast(1)
            SideEffect {
                drag.gridW = gridW
                drag.gridH = gridH
            }

            // A surface that has never been on a screen is put on this one, and
            // saved. It happens once per surface ever — on the first run, and on
            // the first launch after a build that had docks. See Anchor.
            LaunchedEffect(layout, gridW, gridH) {
                if (layout.hasAnchors) onLayout(layout.settled(gridW, gridH))
            }

            // Clamp, overflow, say so — and **clamp for the screen, not for the
            // file**. A workspace made on a tablet and opened on a phone has to
            // come back whole when it goes home again, so what is cut here is
            // cut on the way to the glass and `layout` is untouched. When
            // nothing has to be cut, `fitted.layout` is the same instance that
            // came in, which is what makes "a layout that fits both ways round
            // does not move" true rather than merely likely.
            val fitted = remember(layout, gridW, gridH) { layout.fittedTo(gridW, gridH) }
            val shown = fitted.layout

            // What the instruments overlay reports. Written here rather than
            // counted there, because only this scope knows what is on screen
            // after the clamp -- see ChromeCounters for the number that decides
            // whether the workspace system may ship.
            SideEffect {
                ChromeCounters.surfaces = shown.surfaces.size
                ChromeCounters.cells = shown.all().size
            }

            // Under the toolbars while they are the targets, and over them while
            // the pen is. It is the same board either way: a surface's own cells
            // answer a pointer in arrange mode — a chip is dragged, an empty one
            // is tapped — so a board underneath them could never rub one out,
            // and a board over them while nothing is being painted would swallow
            // both gestures. It is still below the arrange bar and the Done
            // button, which have to stay reachable from inside the mode.
            if (arranging && !shaping) {
                GridBoard(shown, onLayout, gridW, gridH, painting = false, subtract = false)
            }

            for (surface in shown.surfaces) {
                SurfaceView(
                    surface = surface,
                    layout = shown,
                    onLayout = onLayout,
                    arranging = arranging,
                    drag = drag,
                    filter = filter,
                    onFilter = onFilter,
                    overflow = fitted.overflow[surface.id].orEmpty(),
                    slotPx = slotPx,
                    gridW = gridW,
                    gridH = gridH,
                    slotContent = slotContent,
                )
            }

            if (arranging && shaping) {
                GridBoard(shown, onLayout, gridW, gridH, painting = true, subtract = subtract)
            }

            // Above the bottom of the screen and centred, which is where the
            // hand is not: the arrange affordance is in the bottom corner and
            // the toolbars are wherever they were drawn.
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
                    ArrangeBar(
                        shaping = shaping,
                        onShaping = { shaping = it },
                        subtract = subtract,
                        onSubtract = { subtract = it },
                        onReset = { onLayout(DockLayout.STARTER.settled(gridW, gridH)) },
                    )
                }
            }

            ArrangeButton(
                arranging = arranging,
                onArranging = onArranging,
                modifier = Modifier.align(Alignment.BottomEnd).padding(Chrome.EDGE_INSET),
            )

            DragGhost(drag)
        }
    }
}

/**
 * One surface, put where its cells are.
 *
 * Two numbers and an offset. Everything that used to be here — which edge,
 * which alignment, how thick, how far scrolled, where the grip goes relative to
 * the run — was the dock model, and the grid answers all of it with
 * `bounds.x × SLOT, bounds.y × SLOT`.
 *
 * The grip and the overflow chevron sit **beside** the shape rather than on it,
 * because a control covering a cell is a cell nothing can be dropped into, and
 * arrange mode is when dropping happens.
 */
@Composable
private fun SurfaceView(
    surface: Surface,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    filter: CatalogueFilter,
    onFilter: (CatalogueFilter) -> Unit,
    overflow: List<ToolItem>,
    slotPx: Float,
    gridW: Int,
    gridH: Int,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    if (surface.isEmpty && !arranging && overflow.isEmpty()) return
    val b = surface.region.bounds

    // Where it is being dragged to, while it is being dragged. Local, so the
    // layout is written once when the hand lifts rather than on every event.
    var carried by remember(surface.id) { mutableStateOf<Cell?>(null) }
    val at = carried ?: Cell(b.x, b.y)

    Box(
        Modifier.offset {
            IntOffset((at.x * slotPx).roundToInt(), (at.y * slotPx).roundToInt())
        }
    ) {
        ChromeSurface(
            surface = surface,
            layout = layout,
            onLayout = onLayout,
            arranging = arranging,
            drag = drag,
            filter = filter,
            onFilter = onFilter,
            slotContent = slotContent,
        )

        if (arranging) {
            SurfaceGrip(
                slotPx = slotPx,
                origin = Cell(b.x, b.y),
                size = b,
                gridW = gridW,
                gridH = gridH,
                onCarry = { carried = it },
                onDrop = {
                    carried?.let { onLayout(layout.moveSurface(surface.id, it)) }
                    carried = null
                },
                modifier = Modifier.align(Alignment.TopStart).offset((-9).dp, (-9).dp),
            )
        }

        // Never optional, because it is the promise that no control silently
        // disappears when a screen turns out to be smaller than the one a
        // workspace was drawn on.
        OverflowChevron(
            overflow,
            surface.axis,
            Modifier.align(Alignment.TopEnd).offset(10.dp, (-9).dp),
            slotContent,
        )
    }
}

/**
 * The handle that moves a whole toolbar.
 *
 * It is dragged by this rather than by its body, and that is not decoration:
 * the body is full of controls and of cells to drop things into, and a toolbar
 * you move by grabbing its middle is a toolbar that moves when you meant to
 * move a button.
 *
 * The position is reported as a cell all the way through — the drag rounds to
 * cells as it goes, so what is seen while dragging is exactly what lands.
 */
@Composable
private fun SurfaceGrip(
    slotPx: Float,
    origin: Cell,
    size: CellRect,
    gridW: Int,
    gridH: Int,
    onCarry: (Cell?) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier,
) {
    val carry by rememberUpdatedState(onCarry)
    val drop by rememberUpdatedState(onDrop)
    val from by rememberUpdatedState(origin)
    val extent by rememberUpdatedState(size)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                CircleShape,
            )
            .pointerInput(Unit) {
                var start = Cell(0, 0)
                var travelled = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        start = from
                        travelled = Offset.Zero
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        travelled += delta
                        if (slotPx > 0f) {
                            val b = extent
                            carry(
                                Cell(
                                    (start.x + (travelled.x / slotPx).roundToInt())
                                        .coerceIn(0, (gridW - b.w).coerceAtLeast(0)),
                                    (start.y + (travelled.y / slotPx).roundToInt())
                                        .coerceIn(0, (gridH - b.h).coerceAtLeast(0)),
                                )
                            )
                        }
                    },
                    onDragEnd = { drop() },
                    onDragCancel = { carry(null) },
                )
            },
    ) {
        Icon(
            ToolIcons.grip,
            "Move this toolbar",
            Modifier.size(11.dp),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * The opaque part.
 *
 * This is the user's rule made into a composable: *"the toolbars are
 * translucent, with only the button opaque"*. The surface behind it is painted
 * at [Chrome.BAR_ALPHA]; this is painted at 1, so a button reads as something
 * solid you press and the surface reads as a hint at where the buttons live.
 *
 * It fills its cell rather than wrapping its content, so a one-cell button and
 * a four-cell slider are the same object at two lengths and the run stays on
 * the grid.
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

/** A filled cell in arrange mode: the control stands aside so it can be moved. */
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
                        drag.hover = drag.surfaceAt(layout, root)?.id
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

/** An unfilled cell in arrange mode: faint, but not invisible, because it is the way in. */
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
 * **Accumulating deltas is wrong whenever anything moves underneath.** The
 * per-event delta is measured in the chip's own coordinates, so anything that
 * moves the chip mid-drag is lost. So the position is read absolutely, every
 * event, through [LayoutCoordinates.localToRoot]. It cannot drift, because
 * nothing is being added up.
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

/**
 * The switch between the two halves of arrange mode, and the way out of a bad
 * layout.
 *
 * The hint changes with the mode because the two gestures have nothing in
 * common: one moves a control and one draws a toolbar, and a single sentence
 * covering both would describe neither.
 */
@Composable
private fun ArrangeBar(
    shaping: Boolean,
    onShaping: (Boolean) -> Unit,
    subtract: Boolean,
    onSubtract: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = Chrome.BAR_OUTLINE_ALPHA),
                RoundedCornerShape(14.dp),
            )
            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Text(
            if (shaping) {
                "draw a toolbar · turn the pen over to rub out"
            } else {
                "drag a control anywhere, or tap a cell to change it"
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Chip("Tools", lit = !shaping) { onShaping(false) }
        Chip("Shape", lit = shaping) { onShaping(true) }
        if (shaping) Chip("Rub out", lit = subtract) { onSubtract(!subtract) }
        Chip("Reset", lit = false, onClick = onReset)
    }
}

@Composable
private fun Chip(label: String, lit: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        label,
        fontSize = 11.sp,
        color = if (lit) scheme.onPrimary else scheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (lit) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
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
 * What can go in this cell.
 *
 * ## Tabs, because the list outgrew a menu
 *
 * The catalogue was one scrolling list with headings, and by the time it had
 * panels in it the headings were off the bottom. The tabs are [ToolGroup] — the
 * same grouping the headings were, promoted to something you can aim at — and
 * they are the user's request: *"it needs tabs, that groups related buttons and
 * panels together"*.
 *
 * The search field sits above them and is **not** part of the tabs. It searches
 * the entire catalogue, filter or no filter, tab or no tab. That is trap 2's
 * mitigation from `docs/workspace-plan.md` and it is not optional: hiding a tool
 * is a promise it was not needed, and a promise you cannot take back is a
 * promise nobody should make.
 *
 * ## Why an entry is greyed rather than hidden
 *
 * `SlotToolbar` recorded the reason and it stands: greying keeps the catalogue a
 * fixed list whose entries are sometimes unavailable, which is a thing people
 * already know how to read, where hiding leaves the user comparing two menus to
 * work out why the smoothing slider is offered in one cell and not another.
 *
 * **A panel is never greyed any more**, and that was the user's other report:
 * *"the panels are not selectable"*. They needed a seven-by-eleven hole in the
 * shape to stand in. They now need the one cell they are anchored to and hang
 * off the rest — see [CellRegion.accepts].
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
    val groups = remember(filter) {
        ToolGroup.entries.filter { g -> ToolItem.entries.any { it.group == g && it in filter } }
    }
    var tab by remember(groups) { mutableStateOf(groups.firstOrNull() ?: ToolGroup.DRAW) }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss, modifier = Modifier.width(292.dp)) {
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
            DropdownMenuItem(
                text = { Text("Remove ${occupant.item.label}", fontSize = 13.sp) },
                leadingIcon = { Icon(ToolIcons.close, null, Modifier.size(17.dp)) },
                onClick = { onLayout(layout.remove(bar.id, cell)) },
            )
        }
        if (!bar.isEmpty) {
            // Close everything on this toolbar up, in its fill order. A menu
            // item and not an ambient behaviour: a toolbar that tidied itself
            // every time something was added would be a toolbar where Export
            // moves. See DockLayout.tidy.
            DropdownMenuItem(
                text = { Text("Tidy this toolbar", fontSize = 13.sp) },
                leadingIcon = { Icon(ToolIcons.tidy, null, Modifier.size(17.dp)) },
                onClick = { onLayout(layout.tidy(bar.id)) },
            )
        }
        if (occupant != null || !bar.isEmpty) HorizontalDivider()

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            for (group in groups) {
                Chip(group.label, lit = group == tab) { tab = group }
            }
        }

        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            for (item in ToolItem.entries.filter { it.group == tab && it in filter }) {
                val fits = layout.fits(bar.id, item, cell, ignoring = cell)
                val already = item == occupant?.item
                val elsewhere = !already && item in layout
                DropdownMenuItem(
                    text = {
                        Text(
                            // Named rather than ticked: an item already on some
                            // other toolbar is not unavailable, it is about to
                            // move, and saying so is the difference between a
                            // menu and a guess.
                            if (elsewhere) "${item.label}  ·  on another toolbar" else item.label,
                            fontSize = 13.sp,
                        )
                    },
                    leadingIcon = {
                        ToolIcons.of(item)?.let { Icon(it, null, Modifier.size(17.dp)) }
                    },
                    enabled = fits && !already,
                    onClick = { onLayout(layout.place(bar.id, item, cell)) },
                    modifier = Modifier.fillMaxWidth(),
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
 * catalogue, always there, above every tab.
 *
 * A hit that the current filter does not offer is shown with *add* beside it.
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

// ---------------------------------------------------------------------------
// Drag bookkeeping
// ---------------------------------------------------------------------------

/**
 * The in-flight drag.
 *
 * It used to carry every bar's rectangle and every run's origin, collected by
 * the bars themselves through `onGloballyPositioned`, because a bar's position
 * depended on what was in it and on how far it had been scrolled. None of that
 * is true on a grid: a cell is at `x × SLOT, y × SLOT` from [hostOrigin], and
 * the surface under a point is whichever one owns that cell. So the whole
 * bookkeeping is four numbers.
 */
internal class DockDrag {
    var carrying by mutableStateOf<DockedItem?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var hover by mutableStateOf<String?>(null)
    var hostOrigin by mutableStateOf(Offset.Zero)

    var slotPx: Float = 0f
    var gridW: Int = 0
    var gridH: Int = 0

    /** Which cell of the chrome a root point is over, or null off the grid. */
    fun cellAt(point: Offset): Cell? =
        DropMath.cellAt(point - hostOrigin, slotPx, gridW, gridH)

    /** Which surface a root point is over, or null for bare paper. */
    fun surfaceAt(layout: DockLayout, point: Offset): Surface? =
        cellAt(point)?.let { layout.surfaceAt(it) }

    /**
     * Finish a drag. Null when the drop changes nothing, which the caller reads
     * as *keep what you had*.
     *
     * **A drop on bare paper makes a new toolbar where it landed.** That is not
     * a fallback, it is the gesture: dragging a control off the chrome and onto
     * the paper is how everyone tries to detach one, and it does exactly that.
     * The toolbar is placed under the finger rather than at a default, so what
     * you get is where you let go.
     */
    fun drop(layout: DockLayout): DockLayout? {
        val held = carrying ?: return null
        val where = pointer
        clear()
        val cell = cellAt(where) ?: return null
        val target = layout.surfaceAt(cell)
            ?: return layout.addSurface(held.item, cell).first
        return layout.move(held.surface.id, held.cell, target.id, cell)
    }

    fun clear() {
        carrying = null
        hover = null
    }
}
