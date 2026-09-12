package be.thalos.artiest.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import be.thalos.artiest.engine.brush.BrushEntry
import be.thalos.artiest.engine.brush.BrushOrigin
import be.thalos.artiest.ink.BrushSwatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The brush shelf: every brush you have, shown as the mark it makes.
 *
 * ## Why the three toggles on the bar stay
 *
 * A tool you reach for fifty times an hour should be one tap and not two taps
 * through a panel, so `ToolItem.PEN`, `PENCIL` and `MARKER` remain one-slot
 * toggles. **The shelf is the full list; the buttons are the favourites.** That
 * is also why it is its own catalogue entry rather than a bigger version of a
 * button — `ToolItem`'s rule that an item lives in exactly one place.
 *
 * ## Why a row is a picture and not a word
 *
 * A list of thirty names is a list you have to read, and a name does not tell
 * you what a brush does. Every row is a real stroke drawn by the real engine —
 * see `BrushSwatch`, which explains why the two cheaper previews were refused —
 * so the shelf can only ever show what the brush actually does on the page.
 *
 * ## The state that is easy to get wrong
 *
 * **The brush text is the truth about how it draws, the id is the truth about
 * which row is lit, and a user who has moved a slider is still holding the
 * pencil.** With three brushes that rule was invisible; with a shelf it becomes
 * visible UI, and it takes three pieces:
 *
 * - a selected row that has been edited shows a **modified dot**;
 * - **Save as new brush** writes what is in the hand as its own entry, and
 *   **Revert** puts the row back;
 * - picking a different row while modified **discards the edit**, the way it
 *   always has. It does not silently save — and the dot is what makes that fair
 *   rather than surprising.
 *
 * See `docs/brush-shelf-plan.md`.
 */
@Composable
fun BrushesButton(
    entries: List<BrushEntry>,
    currentId: String,
    eraserId: String?,
    modified: Boolean,
    ink: Int,
    onPick: (BrushEntry) -> Unit,
    onUseAsEraser: (BrushEntry) -> Unit,
    onSaveAs: (String) -> Unit,
    onRevert: () -> Unit,
    onRename: (BrushEntry, String) -> Unit,
    onDelete: (BrushEntry) -> Unit,
    onFixate: (Cell) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(38.dp).onGloballyPositioned { here = it.positionInRoot() },
    ) {
        IconToolButton(
            icon = ToolIcons.brushes,
            label = "Brushes",
            onClick = { open = true },
            selected = open,
        )
        if (!open) return@Box
        Popup(
            popupPositionProvider = remember(gap) { PanelPosition(gap) },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 14.dp,
                modifier = Modifier.width(PANEL_WIDTH),
            ) {
                ShelfBody(
                    entries = entries,
                    currentId = currentId,
                    eraserId = eraserId,
                    modified = modified,
                    ink = ink,
                    // Picking a brush closes the shelf. A palette stays open
                    // because mixing is a series of tries; picking a tool is
                    // one decision, and the next thing you do is draw.
                    onPick = { open = false; onPick(it) },
                    onUseAsEraser = onUseAsEraser,
                    onSaveAs = onSaveAs,
                    onRevert = onRevert,
                    onRename = onRename,
                    onDelete = onDelete,
                ) {
                    // The same one sentence every panel's fixate is: the panel
                    // becomes a control on a toolbar of its own. See
                    // docs/panels-plan.md.
                    Spacer(Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable {
                                open = false
                                onFixate(
                                    DropMath.cellBeside(here, view.width, view.height, slotPx),
                                )
                            },
                    ) {
                        Icon(
                            ToolIcons.pin,
                            "Keep this panel on screen",
                            Modifier.size(15.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The same shelf, kept. See [ToolItem.BRUSH_SHELF].
 *
 * The body is shared with the popup rather than copied, which is the point:
 * fixating changes where the shelf is drawn and nothing about what it is. Two
 * copies would be two lists that drift apart, and the one you were not looking
 * at would be the wrong one.
 */
@Composable
fun BrushShelfCard(
    entries: List<BrushEntry>,
    currentId: String,
    eraserId: String?,
    modified: Boolean,
    ink: Int,
    onPick: (BrushEntry) -> Unit,
    onUseAsEraser: (BrushEntry) -> Unit,
    onSaveAs: (String) -> Unit,
    onRevert: () -> Unit,
    onRename: (BrushEntry, String) -> Unit,
    onDelete: (BrushEntry) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        ShelfBody(
            entries = entries,
            currentId = currentId,
            eraserId = eraserId,
            modified = modified,
            ink = ink,
            onPick = onPick,
            onUseAsEraser = onUseAsEraser,
            onSaveAs = onSaveAs,
            onRevert = onRevert,
            onRename = onRename,
            onDelete = onDelete,
            modifier = Modifier.fillMaxSize(),
            // Whatever the header and the two actions leave. A constant,
            // because those parts are — and a list that guesses its own height
            // inside a scrollable is a list that measures wrong.
            listMaxHeight = (maxHeight - CARD_FURNITURE).coerceAtLeast(80.dp),
        )
    }
}

@Composable
private fun ShelfBody(
    entries: List<BrushEntry>,
    currentId: String,
    eraserId: String?,
    modified: Boolean,
    ink: Int,
    onPick: (BrushEntry) -> Unit,
    onUseAsEraser: (BrushEntry) -> Unit,
    onSaveAs: (String) -> Unit,
    onRevert: () -> Unit,
    onRename: (BrushEntry, String) -> Unit,
    onDelete: (BrushEntry) -> Unit,
    modifier: Modifier = Modifier,
    listMaxHeight: Dp = LIST_MAX_HEIGHT,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BrushEntry?>(null) }
    var deleting by remember { mutableStateOf<BrushEntry?>(null) }
    val current = entries.firstOrNull { it.id == currentId }

    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        ) {
            Text("Brushes", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size}", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                trailing()
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(
            Modifier.heightIn(max = listMaxHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (entry in entries) {
                BrushRow(
                    entry = entry,
                    selected = entry.id == currentId,
                    erasing = entry.id == eraserId,
                    modified = modified && entry.id == currentId,
                    ink = ink,
                    onPick = { onPick(entry) },
                    onUseAsEraser = { onUseAsEraser(entry) },
                    onRename = { renaming = entry },
                    onDelete = { deleting = entry },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShelfAction("Save as new…", enabled = true, onClick = { saving = true })
            // Only offered when there is something to undo. A Revert that is
            // always lit is a button whose meaning you have to test.
            ShelfAction("Revert", enabled = modified, onClick = onRevert)
        }
    }

    if (saving) {
        NameDialog(
            title = "Save this brush",
            confirm = "Save",
            // Named after the brush it was tuned from, which is almost always
            // what it is: "Pencil 2" beats an empty box you have to fill in
            // before you can find out whether saving worked.
            initial = current?.let { nextName(it.label, entries) } ?: "Brush",
            onDismiss = { saving = false },
            onConfirm = { saving = false; onSaveAs(it) },
        )
    }
    renaming?.let { entry ->
        NameDialog(
            title = "Rename brush",
            confirm = "Rename",
            initial = entry.label,
            onDismiss = { renaming = null },
            onConfirm = { renaming = null; onRename(entry, it) },
        )
    }
    deleting?.let { entry ->
        ConfirmDelete(
            entry = entry,
            onDismiss = { deleting = null },
            onConfirm = { deleting = null; onDelete(entry) },
        )
    }
}

/**
 * One brush: its mark, its name, and where it came from.
 *
 * The swatch is the width of the row and the name sits above it, rather than
 * the other way round. A row is told apart by its picture, so the picture gets
 * the space — and a name is legible at 12sp in a strip, where a stroke squeezed
 * into a 60dp square is not.
 */
@Composable
private fun BrushRow(
    entry: BrushEntry,
    selected: Boolean,
    erasing: Boolean,
    modified: Boolean,
    ink: Int,
    onPick: () -> Unit,
    onUseAsEraser: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onPick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.label,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (modified) {
                // The whole of "you have moved this since you picked it". A
                // word would not fit and a warning colour would be a scold;
                // a dot is the convention every editor with unsaved state uses.
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(scheme.primary),
                )
                Spacer(Modifier.width(6.dp))
            }
            if (erasing) {
                // A rubber beside the name, because "which brush erases" is a
                // second current-ness and the lit row already means the first.
                // Nothing else on the row could carry it: the swatch is the
                // mark the brush makes, and it makes the same one either way.
                Icon(
                    ToolIcons.eraser,
                    "Erases with this brush",
                    Modifier.size(14.dp),
                    tint = scheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            run {
                Box {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable { menu = true },
                    ) {
                        Icon(
                            ToolIcons.grip,
                            "More",
                            Modifier.size(11.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                    if (menu) {
                        DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                            // Offered on every row, built-ins included: the
                            // eraser wants a *shape*, and the pen's hard edge
                            // and the pencil's soft one are the two most
                            // obvious rubbers this app ships.
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (erasing) "Stop erasing with this"
                                        else "Use as eraser",
                                        fontSize = 13.sp,
                                    )
                                },
                                onClick = { menu = false; onUseAsEraser() },
                            )
                            if (entry.removable) {
                                DropdownMenuItem(
                                    text = { Text("Rename…", fontSize = 13.sp) },
                                    onClick = { menu = false; onRename() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete…", fontSize = 13.sp) },
                                    onClick = { menu = false; onDelete() },
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Swatch(
            entry = entry,
            ink = ink,
            modifier = Modifier
                .fillMaxWidth()
                .height(SWATCH_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .background(SWATCH_PAPER),
        )
    }
}

/**
 * The brush's own mark, rendered once and then remembered.
 *
 * Rendered on [Dispatchers.Default] and never on the thread that is drawing:
 * `docs/brush-shelf-plan.md` makes that a stop condition, because a shelf that
 * stutters the pen is a worse shelf than no shelf. The cache lives in
 * [BrushSwatches] rather than in a `remember` here, because a `remember` in a
 * scrolling row dies with the row and would re-render every brush each time it
 * came back on screen.
 *
 * A cache hit is taken **synchronously**, in the `remember` initialiser, so a
 * brush that has already been drawn does not blink through a frame of nothing
 * on its way back.
 */
@Composable
private fun Swatch(entry: BrushEntry, ink: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.roundToPx() }
        val h = with(density) { maxHeight.roundToPx() }
        var bitmap by remember(entry.stamp, w, h, ink) {
            mutableStateOf<Bitmap?>(BrushSwatches.get(BrushSwatches.keyOf(entry, w, h, ink)))
        }
        LaunchedEffect(entry.stamp, w, h, ink) {
            if (bitmap != null || w <= 0 || h <= 0) return@LaunchedEffect
            bitmap = withContext(Dispatchers.Default) { BrushSwatches.render(entry, w, h, ink) }
        }
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                entry.label,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.None,
            )
        }
    }
}

@Composable
private fun ShelfAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.3f),
        )
    }
}

/**
 * Deleting is asked about, and saving is not.
 *
 * The asymmetry is the point: saving makes a thing and deleting unmakes one
 * that cannot be got back, because a brush is a tuning somebody arrived at by
 * dragging sliders rather than a file they have a copy of elsewhere.
 */
@Composable
private fun ConfirmDelete(entry: BrushEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ${entry.label}?", fontSize = 16.sp) },
        text = { Text("This cannot be undone.", fontSize = 13.sp) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Keep it") }
        },
    )
}

/** "Pencil", then "Pencil 2", then "Pencil 3": the first name not already used. */
private fun nextName(base: String, entries: List<BrushEntry>): String {
    val taken = entries.map { it.label }.toSet()
    if (base !in taken) return base
    var n = 2
    while (n < 100) {
        val name = "$base $n"
        if (name !in taken) return name
        n++
    }
    return base
}

private val PANEL_WIDTH = 264.dp

/**
 * Three rows before it scrolls in the popup. Fewer than the layers panel's
 * four, because a brush row is taller: it is a name and a picture, and the
 * picture is the part that has to be big enough to recognise.
 */
private val LIST_MAX_HEIGHT = 252.dp

/** How much of a docked shelf is not the list: the header and the actions. */
private val CARD_FURNITURE = 104.dp

/**
 * A row is two cells tall, and this is the picture's share of it.
 *
 * `BrushSwatch.ASPECT` is 3:1, so a 240dp strip wants 80dp to be undistorted —
 * more than a row can spend. It is drawn at the strip's own aspect instead and
 * the stroke simply has less height to wander in, which costs nothing that
 * matters: the mark's *width* is what tells two brushes apart.
 */
private val SWATCH_HEIGHT = 46.dp

/**
 * Paper, near enough, and deliberately not the theme's surface.
 *
 * `LayersPanel`'s thumbnail argument word for word: a swatch is ink on nothing,
 * so a dark theme would put black graphite on dark grey and every brush would
 * look like an empty box.
 */
private val SWATCH_PAPER = Color(0xFFE6E3DC)
