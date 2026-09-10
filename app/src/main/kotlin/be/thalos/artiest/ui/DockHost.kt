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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
    floatingAt: Offset,
    onFloatingAt: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val density = LocalDensity.current
    val drag = remember { DockDrag() }
    val slotPx = with(density) { Chrome.SLOT.toPx() }
    SideEffect { drag.slotPx = slotPx }

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

            for (dock in Dock.entries) {
                if (dock == Dock.FLOATING) continue
                DockBar(
                    dock = dock,
                    layout = layout,
                    onLayout = onLayout,
                    arranging = arranging,
                    drag = drag,
                    modifier = Modifier
                        .align(dock.alignment())
                        .padding(Chrome.EDGE_INSET)
                        // A horizontal bar in arrange mode is every slot wide,
                        // which on a tablet is very nearly the whole screen —
                        // and the arrange affordance lives in the bottom
                        // corner. Keeping the corners clear costs a bar two
                        // slots of length it did not need and saves the one
                        // control that gets you out of the mode from being
                        // underneath the bar you are editing.
                        .then(
                            if (dock.axis == Axis.HORIZONTAL) {
                                Modifier.padding(horizontal = CORNER_CLEARANCE)
                            } else {
                                Modifier
                            }
                        ),
                    slotContent = slotContent,
                )
            }

            FloatingPanel(
                layout = layout,
                onLayout = onLayout,
                arranging = arranging,
                drag = drag,
                at = floatingAt,
                onAt = onFloatingAt,
                hostWidth = hostWidth,
                hostHeight = hostHeight,
                slotContent = slotContent,
            )

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
                ArrangeHint(onReset = { onLayout(DockLayout.STARTER) })
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
 * A single dock, drawn.
 *
 * Absent entirely when it is empty and nobody is arranging: an edge with no
 * controls on it should look like an edge, not like a bar someone forgot to
 * fill. In arrange mode it appears whatever is in it, because an invisible dock
 * cannot be dropped into and *the user can decide where they attach* is the
 * whole feature.
 */
@Composable
private fun DockBar(
    dock: Dock,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    modifier: Modifier,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val bar = layout.bar(dock)
    if (bar.isEmpty && !arranging) return

    val hovered = drag.hover == dock
    Box(
        modifier
            .barSkin(hovered)
            .onGloballyPositioned { drag.bounds[dock] = it.boundsInRoot() }
            .padding(Chrome.BAR_PADDING),
    ) {
        DockRun(dock, layout, onLayout, arranging, drag, slotContent)
    }
}

/** The run of slots inside a bar, along the dock's axis. */
@Composable
private fun DockRun(
    dock: Dock,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val bar = layout.bar(dock)
    val vertical = dock.axis == Axis.VERTICAL
    val scroll = rememberScrollState()

    // Only as far as the last item, unless arranging. See the file KDoc.
    val extent = if (arranging) bar.slotCount else bar.placements.maxOfOrNull { it.endSlot } ?: 0

    val cells: @Composable () -> Unit = {
        var slot = 0
        while (slot < extent) {
            val here = slot
            val placed = bar.covering(here)
            SlotCell(
                dock = dock,
                slot = here,
                placed = placed,
                layout = layout,
                onLayout = onLayout,
                arranging = arranging,
                drag = drag,
                slotContent = slotContent,
            )
            slot += placed?.item?.slots ?: 1
        }
    }

    if (vertical) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .width(Chrome.BAR_THICKNESS - Chrome.BAR_PADDING * 2)
                .heightIn(max = 640.dp)
                .verticalScroll(scroll)
                .onGloballyPositioned { drag.runOrigin[dock] = it.positionInRoot() },
        ) { cells() }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .height(Chrome.BAR_THICKNESS - Chrome.BAR_PADDING * 2)
                .widthIn(max = 1000.dp)
                .horizontalScroll(scroll)
                // Inside the scroll, so this moves as the bar is scrolled and
                // the slot arithmetic never has to know a scroll offset exists.
                // positionInRoot and not boundsInRoot: the second is clipped to
                // the viewport, so a bar scrolled off its start would report the
                // visible edge and every drop would land short.
                .onGloballyPositioned { drag.runOrigin[dock] = it.positionInRoot() },
        ) { cells() }
    }
}

/**
 * One cell: a control, a chip standing in for it, or an empty target.
 *
 * The size is decided here and in one place, because it is the number the drop
 * arithmetic in [DockDrag.slotAt] inverts. If a cell were ever a different width
 * from `slots × SLOT`, a drop would land in the wrong slot and nothing would say
 * why.
 */
@Composable
private fun SlotCell(
    dock: Dock,
    slot: Int,
    placed: Placement?,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val vertical = dock.axis == Axis.VERTICAL
    var chooser by remember { mutableStateOf(false) }

    val span = (placed?.item?.slots ?: 1)
    // An unfilled slot out of arrange mode is a group separator, not a hole.
    val cells = if (placed == null && !arranging) 0.28f else span.toFloat()
    val length = Chrome.SLOT * cells
    val thickness = Chrome.BAR_THICKNESS - Chrome.BAR_PADDING * 2

    Box(
        contentAlignment = Alignment.Center,
        modifier = if (vertical) {
            Modifier.width(thickness).height(length)
        } else {
            Modifier.height(thickness).width(length)
        },
    ) {
        when {
            placed != null && !arranging ->
                SlotSurface { slotContent(placed.item, dock.axis) }

            placed != null ->
                ArrangeChip(
                    item = placed.item,
                    dock = dock,
                    slot = slot,
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
                dock = dock,
                slot = slot,
                onDismiss = { chooser = false },
                onLayout = { chooser = false; onLayout(it) },
            )
        }
    }
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
private fun SlotSurface(content: @Composable () -> Unit) {
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
private fun ArrangeChip(
    item: ToolItem,
    dock: Dock,
    slot: Int,
    drag: DockDrag,
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    onClick: () -> Unit,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Stands in for the ripple that went with Modifier.clickable. See
    // carryGesture for why the clickable had to go.
    var pressed by remember { mutableStateOf(false) }
    val lifted = drag.carrying?.dock == dock && drag.carrying?.slot == slot

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
            .pointerInput(item, dock, slot) {
                carryGesture(
                    coords = { coords },
                    onPressed = { pressed = it },
                    onPick = { drag.carrying = DockedItem(dock, Placement(item, slot)) },
                    onMove = { root ->
                        drag.pointer = root
                        drag.hover = drag.dockAt(root)
                    },
                    onDrop = { onLayout(drag.drop(layout) ?: layout) },
                    onCancel = { drag.clear() },
                    onTap = onClick,
                )
            },
    ) {
        val glyph = ToolIcons.of(item)
        if (glyph != null && item.slots <= 1) {
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
private fun EmptyTarget(onClick: () -> Unit) {
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
private fun FloatingPanel(
    layout: DockLayout,
    onLayout: (DockLayout) -> Unit,
    arranging: Boolean,
    drag: DockDrag,
    at: Offset,
    onAt: (Offset) -> Unit,
    hostWidth: Float,
    hostHeight: Float,
    slotContent: @Composable (ToolItem, Axis) -> Unit,
) {
    val dock = Dock.FLOATING
    if (layout.bar(dock).isEmpty && !arranging) return

    var sizePx by remember { mutableStateOf(Offset.Zero) }
    val free = Offset(
        (hostWidth - sizePx.x).coerceAtLeast(0f),
        (hostHeight - sizePx.y).coerceAtLeast(0f),
    )
    val hovered = drag.hover == dock

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { IntOffset((at.x * free.x).roundToInt(), (at.y * free.y).roundToInt()) }
            .barSkin(hovered)
            .onGloballyPositioned {
                drag.bounds[dock] = it.boundsInRoot()
                sizePx = Offset(it.size.width.toFloat(), it.size.height.toFloat())
            }
            .padding(Chrome.BAR_PADDING),
    ) {
        Grip { delta ->
            if (free.x > 0f && free.y > 0f) {
                onAt(
                    Offset(
                        (at.x + delta.x / free.x).coerceIn(0f, 1f),
                        (at.y + delta.y / free.y).coerceIn(0f, 1f),
                    )
                )
            }
        }
        DockRun(dock, layout, onLayout, arranging, drag, slotContent)
    }
}

/** Two columns of dots. The only part of the floating panel that is a handle. */
@Composable
private fun Grip(onDrag: (Offset) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .width(18.dp)
            .height(Chrome.BAR_THICKNESS - Chrome.BAR_PADDING * 2)
            .pointerInput(Unit) {
                detectDragGestures { change, delta ->
                    change.consume()
                    onDrag(delta)
                }
            }
            .padding(horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
private fun ToolChooser(
    layout: DockLayout,
    dock: Dock,
    slot: Int,
    onDismiss: () -> Unit,
    onLayout: (DockLayout) -> Unit,
) {
    val occupant = layout.bar(dock).covering(slot)

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (occupant != null) {
            Text(
                "Move ${occupant.item.label} to",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp),
            )
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                for (target in Dock.entries) {
                    val moved = layout.move(dock, slot, target)
                    DockTarget(
                        dock = target,
                        enabled = target != dock && moved != null,
                        onClick = { moved?.let(onLayout) },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text("Remove ${occupant.item.label}", fontSize = 13.sp) },
                leadingIcon = { Icon(ToolIcons.close, null, Modifier.size(17.dp)) },
                onClick = { onLayout(layout.remove(dock, slot)) },
            )
            HorizontalDivider()
        }

        for (group in ToolGroup.entries) {
            val items = ToolItem.entries.filter { it.group == group }
            if (items.isEmpty()) continue
            Text(
                group.label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
            for (item in items) {
                val fits = layout.fits(dock, item, slot, ignoringSlot = slot)
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
                                "${item.label}  ·  ${layout.locate(item)?.dock?.label}"
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
                    onClick = { onLayout(layout.place(dock, item, slot)) },
                )
            }
        }
    }
}

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
private class DockDrag {
    var carrying by mutableStateOf<DockedItem?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var hover by mutableStateOf<Dock?>(null)
    var hostOrigin by mutableStateOf(Offset.Zero)

    /** Each bar's visible rectangle, for deciding which dock a point is over. */
    val bounds = mutableStateMapOf<Dock, Rect>()

    /**
     * Where each dock's run of slots begins, in root coordinates.
     *
     * Reported by the run itself from inside its own scroll container, so it
     * already carries the scroll offset and the bar's padding and any handle
     * sitting in front of it. That is the whole reason it exists: the slot
     * arithmetic used to add a padding constant and a scroll value back by
     * hand, and every one of those terms was a chance to be a few slots out.
     */
    val runOrigin = mutableStateMapOf<Dock, Offset>()

    var slotPx: Float = 0f

    fun dockAt(point: Offset): Dock? =
        Dock.entries.firstOrNull { bounds[it]?.contains(point) == true }

    /**
     * Which slot of [dock] the point is over.
     *
     * The inverse of [SlotCell]'s sizing, and it is only correct because that
     * sizing is `slots × SLOT` with no exceptions. Measured from [runOrigin],
     * which is the run's own position and therefore already scrolled — there is
     * nothing here to add back and nothing to get wrong.
     */
    fun slotAt(dock: Dock, point: Offset): Int? {
        val origin = runOrigin[dock] ?: return null
        if (slotPx <= 0f) return null
        val along = if (dock.axis == Axis.HORIZONTAL) point.x - origin.x else point.y - origin.y
        return (along / slotPx).toInt().coerceAtLeast(0)
    }

    /**
     * Finish a drag. Null when the drop changes nothing, which the caller reads
     * as *keep what you had*.
     *
     * A drop that lands on no dock at all is not a mistake to be undone — it is
     * how an item is sent to the floating panel, which is the only dock with no
     * edge to aim at. That makes "drag it out onto the paper" the gesture for
     * *detach*, which is the gesture everyone already tries.
     */
    fun drop(layout: DockLayout): DockLayout? {
        val held = carrying ?: return null
        val target = dockAt(pointer) ?: Dock.FLOATING
        val slot = slotAt(target, pointer)
        clear()
        return layout.move(held.dock, held.slot, target, slot)
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
