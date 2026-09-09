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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * The ink, as a button that is the colour it sets.
 *
 * The bar shows the answer and the panel holds the controls, which is the way
 * round a toolbar wants: the one thing you need to know at a glance about the
 * ink is what colour it is, and the one thing you need a whole panel for is
 * changing it. Before this, four slots of the bar were spent on five fixed
 * swatches — a placeholder whose own KDoc said so — and the wheel that has
 * existed in this package since Phase 4's groundwork was not reachable from
 * anywhere in the app.
 *
 * [onCommit] fires when the panel closes rather than on every sample of a drag.
 * The wheel emits continuously — that is what makes it feel like a wheel — and a
 * recent-colours list fed from that would be eight shades of the same drag.
 */
@Composable
fun ColourButton(
    ink: Int,
    onInk: (Int) -> Unit,
    palette: List<Int>,
    recent: List<Int>,
    onCommit: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(38.dp)) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(ink))
                .border(
                    // Light on the outside, dark on the inside, for the reason
                    // ColorWheel's puck carries two rings: there is no single
                    // ring colour that stays visible against every ink, and
                    // black ink on a dark bar with a dark ring is a hole.
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    shape = CircleShape,
                )
                .clickable { open = true },
        )
        if (open) {
            ColourPanel(
                ink = ink,
                onInk = onInk,
                palette = palette,
                recent = recent,
                onDismiss = { open = false; onCommit(ink) },
            )
        }
    }
}

/**
 * The wheel, with the colours you already have beneath it.
 *
 * Three ways to the same setting, in the order you reach for them: the palette
 * for the handful of colours that are always wanted, the recents for the ones
 * this drawing is made of, and the wheel for a colour that is not yet either.
 * The wheel is at the top because it is the reason the panel exists; the rows
 * are beneath it because a row of swatches is a shortcut and a shortcut that
 * comes first is just a shorter palette.
 *
 * **It is a [Popup] and not a `DropdownMenu`, and that is not a style choice.**
 * The first version was a menu, and it crashed the app the first time the
 * button was pressed:
 *
 * ```
 * IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout
 * layouts is not supported. This includes components that are built on top of
 * SubcomposeLayout, such as lazy lists, BoxWithConstraints, TabRow, etc.
 * ```
 *
 * `DropdownMenu` sizes its column with `IntrinsicSize.Max`, [ColorWheel]'s disc
 * is a `BoxWithConstraints` — it has to be, it rasterises itself at whatever
 * size it is given — and a `BoxWithConstraints` cannot answer an intrinsic
 * question, because its content does not exist until it knows its constraints.
 * Nothing about the wheel or the menu was wrong; they simply cannot be nested.
 * A popup asks its content to measure itself and then places it, which is the
 * question this content can answer.
 */
@Composable
private fun ColourPanel(
    ink: Int,
    onInk: (Int) -> Unit,
    palette: List<Int>,
    recent: List<Int>,
    onDismiss: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { PanelPosition(gap) },
        onDismissRequest = onDismiss,
        // Focusable, or an outside tap is a stray touch on the canvas and the
        // panel stays open under the mark it just made.
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 14.dp,
            modifier = Modifier.width(PANEL_WIDTH),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Colour",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        hexOf(ink),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Spacer(Modifier.height(10.dp))
                ColorWheel(argb = ink, onColorChange = onInk, modifier = Modifier.fillMaxWidth())

                Spacer(Modifier.height(14.dp))
                SwatchRow("Palette", palette, ink, onInk)

                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SwatchRow("Recent", recent, ink, onInk)
                }
            }
        }
    }
}

/**
 * Below the button if there is room, and always on the screen.
 *
 * The button that opens this can be docked to any of five places, so there is
 * no fixed side that is right — a panel that always opens to the right is off
 * the screen from the right edge. Below-and-centred is the guess, and the clamp
 * is what makes the guess safe: a panel that would hang off any edge is slid
 * back in rather than being placed somewhere clever.
 */
private class PanelPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + anchorBounds.width / 2 - popupContentSize.width / 2
        val y = anchorBounds.bottom + gap
        return IntOffset(
            x.coerceIn(gap, maxOf(gap, windowSize.width - popupContentSize.width - gap)),
            y.coerceIn(gap, maxOf(gap, windowSize.height - popupContentSize.height - gap)),
        )
    }
}

@Composable
private fun SwatchRow(label: String, colours: List<Int>, ink: Int, onInk: (Int) -> Unit) {
    Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(5.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for (colour in colours) {
            Swatch(colour, selected = colour == ink) { onInk(colour) }
        }
    }
}

@Composable
private fun Swatch(colour: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(colour))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/** `#RRGGBB`. Alpha is not shown because the app's ink has none — see [ColorWheel]. */
private fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * Wide enough for a wheel you can aim with a fingertip.
 *
 * The disc ends up about 236dp across, which puts a full hue sweep at roughly
 * 740dp of travel around the rim — enough that adjacent degrees are further
 * apart than the finger is wide, which is the whole difference between picking a
 * colour and hunting for one.
 */
private val PANEL_WIDTH = 268.dp
