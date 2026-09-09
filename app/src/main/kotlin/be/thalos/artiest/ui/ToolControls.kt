package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A control that is a glyph and a hit target, and nothing else.
 *
 * The whole slot is the target, not the glyph inside it — a 21dp icon is about
 * half the width of a fingertip, and a button you have to aim at is a button
 * that gets missed while your other hand is holding a pen against the screen.
 *
 * **[selected] is a background and not a tint.** A lit tool has to be readable
 * as lit from the corner of the eye, without comparing it to its neighbour, and
 * a coloured glyph on the same ground as an uncoloured one fails that at arm's
 * length. Filling the slot does not.
 */
@Composable
fun IconToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) scheme.primary else scheme.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(Chrome.GLYPH),
            tint = when {
                selected -> scheme.onPrimary
                enabled -> scheme.onSurface
                // Dim rather than hidden: Undo with nothing to undo is still
                // where Undo is, and a button that disappears when it is unusable
                // moves everything beside it.
                else -> scheme.onSurface.copy(alpha = 0.28f)
            },
        )
    }
}

/**
 * A slider that lies along whichever edge it is docked to.
 *
 * The number beside it is not decoration. These are the controls whose effect is
 * invisible until the next stroke — a smoothing change does nothing to the ink
 * already down, and `Brush.sizeMax` is the *upper* end of a pressure curve, so
 * at a light touch moving it changes nothing at all. Without the readout that
 * reads as a broken slider.
 *
 * On a vertical edge the icon goes above and the value below, and the track
 * fills what is left. Reading a value that is one character narrower than the
 * bar is worse than reading it sideways, but only just, and it is the reason the
 * vertical form shows fewer digits.
 */
@Composable
fun ToolSlider(
    icon: ImageVector,
    label: String,
    value: Float,
    from: Float,
    to: Float,
    places: Int,
    axis: Axis,
    onChange: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val text = format(value, places)

    if (axis == Axis.HORIZONTAL) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Eight and not four. At four the slider's active track begins
            // exactly where the glyph ends, and a size icon of three graded
            // dots reads as the first inch of its own track.
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 8.dp),
        ) {
            Icon(icon, label, Modifier.size(17.dp), scheme.onSurfaceVariant)
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = from..to,
                modifier = Modifier.weight(1f),
            )
            Text(
                text,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.width(26.dp),
            )
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(vertical = 7.dp),
        ) {
            Icon(icon, label, Modifier.size(16.dp), scheme.onSurfaceVariant)
            VerticalSlider(
                value = value,
                onValueChange = onChange,
                from = from,
                to = to,
                modifier = Modifier.weight(1f).width(34.dp),
            )
            Text(
                text,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurface,
            )
        }
    }
}

/**
 * The Material slider, turned a quarter turn, with more at the top.
 *
 * Rotating the real component rather than drawing a new one is deliberate: a
 * hand-built vertical track would be a second slider to keep in step with the
 * theme, and would have to re-earn the thumb size, the touch slack and the
 * press feedback that this one already has. The rotation is done in the layout
 * as well as the graphics layer, so the composable it sits in measures a tall
 * narrow box rather than a wide flat one that has been drawn sideways.
 *
 * Value increases upward, which is the direction the rotation happens to give
 * and is also the only direction that is not surprising.
 */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    from: Float,
    to: Float,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = from..to,
        modifier = Modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-placeable.width, 0)
                }
            }
            .then(modifier),
    )
}

/**
 * A short text badge for a control with no glyph of its own.
 *
 * There is one left — the instruments' readout toggle carries a caret rather
 * than a state, and that is the sort of thing a word says better than a picture.
 */
@Composable
fun TextToolButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) scheme.primary else scheme.surfaceContainer)
            .clickable(onClick = onClick),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = if (selected) scheme.onPrimary else scheme.onSurface,
        )
    }
}

/** Fixed places, no locale, no allocation beyond the string. */
private fun format(value: Float, places: Int): String = when (places) {
    0 -> value.toInt().toString()
    else -> {
        val scale = when (places) {
            1 -> 10f
            2 -> 100f
            else -> 1000f
        }
        val rounded = Math.round(value * scale) / scale
        rounded.toString().let { s ->
            val dot = s.indexOf('.')
            if (dot < 0) s else s.substring(0, minOf(s.length, dot + 1 + places))
        }
    }
}

/** A hairline used where a run of controls needs a visual break. */
@Composable
fun SlotDivider(axis: Axis) {
    val colour = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    if (axis == Axis.HORIZONTAL) {
        Box(Modifier.width(1.dp).height(24.dp).background(colour))
    } else {
        Box(Modifier.height(1.dp).width(24.dp).background(colour))
    }
}
