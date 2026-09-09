package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import be.thalos.artiest.doc.LayerInfo
import be.thalos.artiest.doc.LayerOp
import androidx.compose.foundation.Image

/**
 * The layers panel, as a button that opens it.
 *
 * **A panel and not a bar**, for the reason the colour wheel is a panel: the
 * list is as long as the drawing has sheets, every row carries a picture, a
 * name and three controls, and there is a slider underneath. None of that fits
 * in a toolbar, and what a toolbar can usefully show about layers at a glance
 * is nothing — so it shows a way in.
 *
 * **Every change leaves through [onOp] and none of it is applied here.** The
 * stack lives on the render thread; this composable holds no layer state of its
 * own beyond which row is being renamed. What it draws is [layers], a snapshot
 * the render thread published, which means the panel is always showing what the
 * document actually contains rather than what the last press was supposed to
 * have done. The lag is one frame and the alternative is two sources of truth.
 *
 * [onOpenChange] is how the thumbnails get built at all: they cost a full-page
 * read each and are only worth building while someone is looking at them. See
 * `LayerStack.wantThumbnails`.
 */
@Composable
fun LayersButton(
    layers: List<LayerInfo>,
    activeId: Int,
    maxLayers: Int,
    onOp: (LayerOp) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: () -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
        IconToolButton(
            icon = ToolIcons.layers,
            label = "Layers",
            onClick = { open = true },
            selected = open,
        )
        if (open) {
            // Paired with the popup's own lifetime rather than with `open`, so
            // that a panel dismissed by a tap outside -- which never runs the
            // `onDismissRequest` path's sibling code -- still turns the
            // thumbnails off. A flag left on means every stroke for the rest of
            // the session pays for a picture nobody is looking at.
            DisposableEffect(Unit) {
                onOpenChange(true)
                onDispose { onOpenChange(false) }
            }
            LayersPanel(
                layers = layers,
                activeId = activeId,
                maxLayers = maxLayers,
                onOp = onOp,
                onAdd = onAdd,
                onDuplicate = onDuplicate,
                onDismiss = { open = false },
            )
        }
    }
}

@Composable
private fun LayersPanel(
    layers: List<LayerInfo>,
    activeId: Int,
    maxLayers: Int,
    onOp: (LayerOp) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    // Which row is having its name typed into, by id. Kept here and not in the
    // snapshot because it is a property of this panel being open, not of the
    // document -- and because a `Popup` drops its `remember` state when it
    // closes, which is exactly the right lifetime for it.
    var editing by remember { mutableIntStateOf(0) }

    Popup(
        popupPositionProvider = remember(gap) { PanelPosition(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 14.dp,
            modifier = Modifier.width(PANEL_WIDTH),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                ) {
                    Text(
                        "Layers",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${layers.size} of $maxLayers",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Top of the drawing at the top of the list. The stack is
                // published bottom-first because that is the order it is
                // composited in, and every layers panel anyone has used shows
                // it the other way up -- the sheet nearest the viewer nearest
                // the top of the screen.
                Column(
                    Modifier
                        .heightIn(max = LIST_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (i in layers.indices.reversed()) {
                        val info = layers[i]
                        LayerRow(
                            info = info,
                            active = info.id == activeId,
                            editing = editing == info.id,
                            canMoveUp = i < layers.size - 1,
                            canMoveDown = i > 0,
                            onSelect = {
                                editing = 0
                                onOp(LayerOp.SetActive(info.id))
                            },
                            onRename = { editing = info.id },
                            onRenamed = { name ->
                                editing = 0
                                val trimmed = name.trim()
                                // An empty name is a row you cannot tell from
                                // its neighbour, so it is declined rather than
                                // stored -- the old name is still there and the
                                // user can try again.
                                if (trimmed.isNotEmpty() && trimmed != info.name) {
                                    onOp(LayerOp.SetName(info.id, trimmed))
                                }
                            },
                            onVisible = { onOp(LayerOp.SetVisible(info.id, !info.visible)) },
                            onUp = { onOp(LayerOp.Move(info.id, i + 1)) },
                            onDown = { onOp(LayerOp.Move(info.id, i - 1)) },
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                val active = layers.firstOrNull { it.id == activeId }
                if (active != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    ) {
                        Text(
                            "Opacity",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = active.opacity,
                            onValueChange = { onOp(LayerOp.SetOpacity(active.id, it)) },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            percent(active.opacity),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(34.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Add and Duplicate go out as bare intents rather than as
                    // `LayerOp`s, because both of those carry a `Layer` and a
                    // `Layer` is a 27.19 MiB bitmap that has to be allocated by
                    // whoever knows the document's size. The panel knows how
                    // many sheets there are and nothing else about them, which
                    // is the right amount for a panel to know.
                    PanelAction(
                        ToolIcons.add,
                        "New layer",
                        enabled = layers.size < maxLayers,
                        onClick = onAdd,
                    )
                    PanelAction(
                        ToolIcons.duplicate,
                        "Duplicate layer",
                        enabled = layers.size < maxLayers,
                        onClick = onDuplicate,
                    )
                    PanelAction(
                        ToolIcons.trash,
                        "Delete layer",
                        // The last sheet is not deletable -- see `LayerStack`.
                        // Dimmed rather than hidden, so the button does not
                        // move around as sheets come and go.
                        enabled = layers.size > 1,
                        onClick = { onOp(LayerOp.Delete(activeId)) },
                    )
                }
            }
        }
    }
}

/**
 * One sheet: what it looks like, what it is called, and whether it is showing.
 *
 * The thumbnail sits on a **fixed pale ground**, not on a theme colour, and
 * that is the difference between a thumbnail and a black square. A layer is
 * alpha-carrying by design — `Document`'s invariant, paper is never in the
 * pixels — so the thumbnail is ink on nothing, and the app's dark theme would
 * put black graphite on a dark grey card. The ground stands in for the paper,
 * which is what the sheet will actually be seen against.
 *
 * The hairline around it is the other half: an empty sheet renders as nothing
 * at all, and without a border there is no way to tell an empty layer from a
 * thumbnail that has not been built yet.
 */
@Composable
private fun LayerRow(
    info: LayerInfo,
    active: Boolean,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onRenamed: (String) -> Unit,
    onVisible: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) scheme.primaryContainer else scheme.surfaceContainer)
            .clickable(onClick = onSelect)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Box(
            Modifier
                .size(THUMB_WIDTH, THUMB_HEIGHT)
                .clip(RoundedCornerShape(4.dp))
                .background(THUMB_PAPER)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(4.dp)),
        ) {
            val bmp = info.thumbnail
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(THUMB_WIDTH, THUMB_HEIGHT),
                )
            }
        }

        if (editing) {
            // A raw text field rather than an `OutlinedTextField`, which is
            // 56dp tall on its own and would make every row in the list jump
            // when one of them is being renamed.
            var text by remember(info.id) { mutableStateOf(info.name) }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = scheme.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(scheme.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            RowAction(ToolIcons.check, "Done", true) { onRenamed(text) }
        } else {
            Text(
                text = info.name,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (info.visible) scheme.onSurface else scheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.weight(1f).clickable(onClick = onRename),
            )
            RowAction(
                if (info.visible) ToolIcons.visible else ToolIcons.hidden,
                if (info.visible) "Hide layer" else "Show layer",
                true,
                onVisible,
            )
            RowAction(ToolIcons.up, "Move up", canMoveUp, onUp)
            RowAction(ToolIcons.down, "Move down", canMoveDown, onDown)
        }
    }
}

/**
 * A small square target inside a row.
 *
 * 30dp and not the toolbar's 44: three of them plus a thumbnail and a name have
 * to fit across a 272dp panel, and these are pressed with a pen tip rather than
 * hunted for with a thumb.
 */
@Composable
private fun RowAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            icon,
            label,
            Modifier.size(17.dp),
            tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurface.copy(alpha = 0.25f),
        )
    }
}

/** One of the three buttons along the bottom. Wider, because they are the verbs. */
@Composable
private fun PanelAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 44.dp, height = 36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            icon,
            label,
            Modifier.size(19.dp),
            tint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.25f),
        )
    }
}

private fun percent(v: Float): String = "${(v * 100f + 0.5f).toInt()}%"

private val PANEL_WIDTH = 272.dp

/**
 * About four rows before it scrolls, which leaves the panel shorter than the
 * screen at the eight-sheet cap while still showing most drawings whole.
 */
private val LIST_MAX_HEIGHT = 232.dp

/** The document's aspect, near enough, at a size that reads at arm's length. */
/**
 * Paper, near enough, and deliberately not `document.paperColor`: the panel
 * would have to be handed the document to read it, and the one case where they
 * differ — a coloured page — is not one the app can reach yet.
 */
private val THUMB_PAPER = Color(0xFFE6E3DC)

private val THUMB_WIDTH = 52.dp
private val THUMB_HEIGHT = 34.dp
