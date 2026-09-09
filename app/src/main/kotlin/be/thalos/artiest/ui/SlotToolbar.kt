package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One slot's width. [ToolItem.slots] is counted in these. */
private val SLOT = 44.dp

/** Tall enough for a slider's touch target without making the bar a wall. */
private val BAR_HEIGHT = 48.dp

/**
 * The toolbar the user builds.
 *
 * A fixed row of slots. Empty ones are tappable and open a chooser; filled ones
 * render whatever control [slotContent] draws for the item. The bar scrolls
 * horizontally if it is longer than the screen, so slot 11 is slot 11 in both
 * orientations.
 *
 * **This file knows nothing about colours, sliders or export.** It is handed
 * [slotContent] and calls it. That is what makes adding a Phase 2 control a
 * catalogue entry plus a `when` branch rather than an edit to a hand-built row,
 * and it is the reason this was worth building before the controls it will hold
 * exist.
 *
 * **Why filled slots need [arranging] and empty ones do not.** The design this
 * implements is the user's: tap an empty slot to fill it, long-press a filled
 * one to change it. The first half works directly, because an empty slot has no
 * control inside it competing for the gesture. The second half does not, for a
 * reason worth writing down rather than discovering twice: a `Slider` consumes
 * drags and presses, so a long-press over the size slider never reaches this
 * composable, and a bar where long-press works on the buttons and silently
 * fails on the sliders is worse than one where it never works. So there is an
 * **Arrange** toggle at the end of the bar. In arrange mode every slot becomes a
 * plain labelled target and the same chooser opens on a single tap — no
 * gesture races, and the added benefit that the bar cannot be rearranged by
 * accident with a palm.
 */
@Composable
fun SlotToolbar(
    layout: ToolbarLayout,
    arranging: Boolean,
    onArranging: (Boolean) -> Unit,
    onLayout: (ToolbarLayout) -> Unit,
    modifier: Modifier = Modifier,
    slotContent: @Composable (ToolItem) -> Unit,
) {
    var chooserSlot by remember { mutableStateOf<Int?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // The bar floats over the canvas, so it needs a ground of its own:
            // black ink under a black label is a toolbar that disappears
            // exactly when the drawing gets interesting.
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .height(BAR_HEIGHT),
        ) {
            var slot = 0
            while (slot < layout.slotCount) {
                val here = slot
                val placed = layout.covering(here)
                val width = placed?.item?.slots ?: 1

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.width(SLOT * width).height(BAR_HEIGHT),
                ) {
                    when {
                        placed != null && !arranging -> slotContent(placed.item)
                        placed != null -> ArrangeTarget(placed.item.short) { chooserSlot = here }
                        else -> EmptySlot { chooserSlot = here }
                    }

                    if (chooserSlot == here) {
                        ToolChooser(
                            layout = layout,
                            slot = here,
                            onDismiss = { chooserSlot = null },
                            onPick = { item ->
                                chooserSlot = null
                                onLayout(layout.place(item, here))
                            },
                            onRemove = {
                                chooserSlot = null
                                onLayout(layout.remove(here))
                            },
                        )
                    }
                }
                slot += width
            }
        }

        TextButton(
            onClick = { onArranging(!arranging) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        ) {
            Text(if (arranging) "Done" else "Arrange", fontSize = 11.sp)
        }
    }
}

/** An unfilled slot: faint, but not invisible, because it is the only way in. */
@Composable
private fun EmptySlot(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(2.dp)
            .height(32.dp)
            .width(SLOT - 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            "+",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
}

/** A filled slot, in arrange mode: the control stands aside and shows its name. */
@Composable
private fun ArrangeTarget(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(2.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * What can go in this slot.
 *
 * Items that would not fit are shown greyed rather than hidden. Hiding them
 * would leave the user comparing two chooser menus to work out why the smoothing
 * slider is offered in slot 4 and not in slot 13; greying keeps the catalogue a
 * fixed list whose entries are sometimes unavailable, which is a thing people
 * already know how to read.
 */
@Composable
private fun ToolChooser(
    layout: ToolbarLayout,
    slot: Int,
    onDismiss: () -> Unit,
    onPick: (ToolItem) -> Unit,
    onRemove: () -> Unit,
) {
    val occupant = layout.covering(slot)

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (occupant != null) {
            DropdownMenuItem(
                text = { Text("Remove ${occupant.item.label}", fontSize = 13.sp) },
                onClick = onRemove,
            )
            HorizontalDivider()
        }
        for (group in ToolGroup.entries) {
            val items = ToolItem.entries.filter { it.group == group }
            if (items.isEmpty()) continue
            Text(
                group.label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
            for (item in items) {
                val fits = layout.fits(item, slot, ignoringSlot = slot)
                val already = item == occupant?.item
                DropdownMenuItem(
                    text = {
                        Text(
                            if (already) "${item.label}  ·" else item.label,
                            fontSize = 13.sp,
                        )
                    },
                    enabled = fits && !already,
                    onClick = { onPick(item) },
                )
            }
        }
    }
}
