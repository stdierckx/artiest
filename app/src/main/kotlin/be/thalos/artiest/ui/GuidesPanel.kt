package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import be.thalos.artiest.doc.GuideKind
import be.thalos.artiest.doc.Guideline

/**
 * What a guide panel is looking at, taken off the document once per
 * recomposition.
 *
 * A snapshot and not the `GuideSet` itself, for `LayerInfo`'s reason: the set
 * is a plain mutable object read by the render thread at pen-down, and a
 * composable that read it directly would be a composable that never recomposes
 * — nothing in it is Compose state. The panel is a set of buttons over somebody
 * else's model, and this is the picture of that model it draws.
 */
data class GuideInfo(
    val rows: List<Row> = emptyList(),
    val strength: Float = 1f,
    val reachDoc: Float = 0f,
    /**
     * How many strokes are picked, so the panel can offer to turn them into a
     * curve — and not offer it when there is nothing to turn.
     *
     * A control that is dimmed for most of a session teaches the eye to skip
     * the row it is in, which is `SelectionPanel`'s reason for hiding its
     * restyle buttons rather than disabling them.
     */
    val picked: Int = 0,
) {
    data class Row(
        val id: Long,
        val label: String,
        val on: Boolean,
        /**
         * What a perspective set's lock reads, or null for a kind that has no
         * rays. See `Guideline.lockedRay`.
         */
        val lock: String? = null,
        /**
         * How many vanishing points a perspective set has, or null for a kind
         * that has none. Tapping it cycles one, two, three.
         */
        val points: String? = null,
    )

    val isEmpty: Boolean get() = rows.isEmpty()
}

/** What the panel asks for. The document is the caller's to change. */
sealed interface GuideAct {
    /** A fresh guide of this kind, in the middle of the page. */
    class Add(val kind: GuideKind) : GuideAct

    /**
     * The picked strokes' centreline, as a curve to draw along.
     *
     * `docs/guides-plan.md` items 12 and 15, which are the same item: the hard
     * half of a French curve is getting the curve, and a stroke you have
     * already drawn is one.
     */
    object FromPicked : GuideAct

    /**
     * Three parallel sets at the isometric angles.
     *
     * `docs/guides-plan.md` item 19, and it needs no guide of its own: an
     * isometric grid is *"three fixed angles, no vanishing points"*, which is
     * three of Ik14's parallel rulers. Three guides and not one, so that any of
     * them can be switched off on its own — which is how it is used: two
     * angles for a wall, all three for a box.
     */
    object Isometric : GuideAct

    /** Cycle a perspective set's ray: choosing, then each point, then choosing. */
    class Lock(val id: Long) : GuideAct

    /** Cycle a perspective set between one, two and three vanishing points. */
    class Points(val id: Long) : GuideAct

    class SetOn(val id: Long, val on: Boolean) : GuideAct

    class Remove(val id: Long) : GuideAct

    object Clear : GuideAct

    class SetStrength(val value: Float) : GuideAct

    class SetReach(val value: Float) : GuideAct
}

/**
 * The guides panel, as a button that opens it.
 *
 * `docs/inker-plan.md`'s Ik13, and it is the smallest panel in the app on
 * purpose. A guide framework's interface wants to be a list of objects with
 * properties; what an inker actually does with it is *lay a ruler down, draw
 * along it, and turn it off* — so the panel is one button to make one, a row
 * per ruler with an eye and a bin, and the two numbers that say how hard they
 * pull.
 *
 * **It does not move anything.** Rulers are dragged on the canvas, in arrange
 * mode, and the panel says so rather than growing four number fields — see
 * [GuideHandles] for why that mode and not another. A panel with coordinates in
 * it would be a second way to place a ruler that disagrees with the first.
 *
 * It can be kept, like the other three, and for a weaker reason than the
 * selection panel's: setting up a perspective grid is a thing you do once. The
 * pin is there because every other panel has one and an inconsistent pin is
 * worse than a pin nobody uses.
 */
@Composable
fun GuidesButton(
    info: GuideInfo,
    arranging: Boolean,
    onAct: (GuideAct) -> Unit,
    onFixate: (Cell) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(38.dp).onGloballyPositioned { here = it.positionInRoot() },
    ) {
        IconToolButton(
            icon = ToolIcons.guides,
            label = "Guides",
            onClick = { open = true },
            // Lit while a ruler is live, not only while the panel is open: it
            // is the one control that says the pen is being helped, and a pen
            // that is being helped without anything saying so is the complaint
            // every drawing-assist feature gets.
            selected = open || info.rows.any { it.on },
        )
        if (open) {
            val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(gap) { PanelPosition(gap) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 14.dp,
                    modifier = Modifier.width(GUIDES_PANEL_WIDTH),
                ) {
                    GuidesBody(
                        info = info,
                        arranging = arranging,
                        onAct = onAct,
                        onFixate = {
                            open = false
                            onFixate(DropMath.cellBeside(here, view.width, view.height, slotPx))
                        },
                    )
                }
            }
        }
    }
}

/** The same panel, kept on a bar. See `SelectionPanelCard`. */
@Composable
fun GuidesPanelCard(info: GuideInfo, arranging: Boolean, onAct: (GuideAct) -> Unit) {
    GuidesBody(info, arranging, onAct, onFixate = null, modifier = Modifier.fillMaxSize())
}

@Composable
private fun GuidesBody(
    info: GuideInfo,
    arranging: Boolean,
    onAct: (GuideAct) -> Unit,
    onFixate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        ) {
            Text("Guides", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                if (info.isEmpty) "none" else "${info.rows.size}",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
            )
            if (onFixate != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHigh)
                        .clickable(onClick = onFixate),
                ) {
                    Icon(ToolIcons.pin, "Keep this panel on screen", Modifier.size(15.dp), colors.primary)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // One row of three rather than three rows of one: they are the same
        // act with a different shape, which is what a row of pictures says and
        // a stack of sentences does not.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Make(ToolIcons.ruler, "Ruler") { onAct(GuideAct.Add(GuideKind.RULER)) }
            Make(ToolIcons.guides, "Parallel") { onAct(GuideAct.Add(GuideKind.PARALLEL)) }
            Make(ToolIcons.marqueeOval, "Ellipse") { onAct(GuideAct.Add(GuideKind.ELLIPSE)) }
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Make(ToolIcons.perspective, "Perspective") {
                onAct(GuideAct.Add(GuideKind.PERSPECTIVE))
            }
            // Item 19, and it is three parallel sets rather than a guide of its
            // own -- which is why it sits beside Perspective as an equal and
            // not inside it as an option.
            Make(ToolIcons.isometric, "Isometric") { onAct(GuideAct.Isometric) }
            Make(ToolIcons.fisheye, "Fisheye") { onAct(GuideAct.Add(GuideKind.FISHEYE)) }
        }

        // Only when there is a stroke to trace. See GuideInfo.picked.
        if (info.picked > 0) {
            Spacer(Modifier.height(6.dp))
            WideAct(
                ToolIcons.marqueeLasso,
                if (info.picked == 1) "Trace that stroke" else "Trace the first of them",
            ) { onAct(GuideAct.FromPicked) }
        }

        // The one sentence the panel exists to say, and it is only said when it
        // is actionable: a line telling you to switch modes while you are
        // already in that mode is a line that teaches the eye to skip the
        // paragraph it is in.
        if (info.rows.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (arranging) {
                    "Drag a guide, or any of its handles."
                } else {
                    "Guides are moved in arrange mode."
                },
                fontSize = 10.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }

        for (row in info.rows) {
            Spacer(Modifier.height(6.dp))
            GuideRow(row, onAct)
        }

        if (info.rows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "How hard they pull",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            NumberRow(info.strength, 0f, 1f, percent(info.strength)) {
                onAct(GuideAct.SetStrength(it))
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "How far they reach",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            NumberRow(
                info.reachDoc,
                0f,
                MAX_REACH_DOC,
                // Zero reads as a word rather than as a number, because it does
                // not mean "no reach" — it means everywhere, which is what a
                // ruler wants and is the opposite of what 0 looks like.
                if (info.reachDoc <= 0f) "all" else "${info.reachDoc.toInt()}",
            ) { onAct(GuideAct.SetReach(it)) }

            Spacer(Modifier.height(10.dp))
            WideAct(ToolIcons.trash, "Clear them all") { onAct(GuideAct.Clear) }
        }
    }
}

@Composable
private fun GuideRow(row: GuideInfo.Row, onAct: (GuideAct) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (row.on) colors.primaryContainer else colors.surfaceContainerHighest)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            row.label,
            fontSize = 12.sp,
            color = if (row.on) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        // How many vanishing points, as a word you tap. One, two, three.
        if (row.points != null) {
            Pill(row.points) { onAct(GuideAct.Points(row.id)) }
            Spacer(Modifier.width(6.dp))
        }
        // `docs/guides-plan.md` item 18's manual override, as a word you tap.
        // A cycle rather than a menu: there are at most four states, and a menu
        // for four states is a menu nobody opens.
        if (row.lock != null) {
            Pill(row.lock) { onAct(GuideAct.Lock(row.id)) }
            Spacer(Modifier.width(6.dp))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { onAct(GuideAct.SetOn(row.id, !row.on)) },
        ) {
            Icon(
                if (row.on) ToolIcons.visible else ToolIcons.hidden,
                contentDescription = if (row.on) "Switch this ruler off" else "Switch this ruler on",
                tint = if (row.on) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { onAct(GuideAct.Remove(row.id)) },
        ) {
            Icon(
                ToolIcons.trash,
                contentDescription = "Take this ruler away",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * A word you tap to get the next one.
 *
 * Two of these sit on a perspective set's row — how many points, and which ray
 * — and they are words rather than icons because both are *states in a cycle*
 * and a picture of a state you have to learn is worse than the word for it.
 */
@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(text, fontSize = 11.sp, color = colors.onSurfaceVariant)
    }
}

/** A slider with its value written beside it. `LayersPanel`'s opacity row. */
@Composable
private fun NumberRow(
    value: Float,
    from: Float,
    to: Float,
    text: String,
    onChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 2.dp),
    ) {
        Slider(
            value = value.coerceIn(from, to),
            onValueChange = onChange,
            valueRange = from..to,
            modifier = Modifier.weight(1f),
        )
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(34.dp),
        )
    }
}

/** One of the three kinds, as a glyph with its word under it. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Make(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(top = 5.dp),
    ) {
        Icon(icon, contentDescription = text, tint = colors.onSurfaceVariant, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(2.dp))
        Text(text, fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, color = colors.onSurfaceVariant)
    }
}

/** A full-width button with a glyph and a phrase. */
@Composable
private fun WideAct(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = text, tint = colors.onSurfaceVariant, modifier = Modifier.size(19.dp))
        Text(text, fontSize = 12.sp, color = colors.onSurface)
    }
}

private fun percent(v: Float): String = "${(v * 100f).toInt()}%"

/**
 * The name a ruler is given in the list.
 *
 * Its kind and its position in the list, and not its id: an id is stable and
 * therefore leaves gaps, and a list reading "Ruler 1, Ruler 4, Ruler 9" would
 * be a list that looks like something has gone missing.
 */
fun labelFor(line: Guideline, at: Int): String = "${line.kind.label} ${at + 1}"

/**
 * Past this a reach is not a reach, it is the page.
 *
 * Four hundred document pixels is about a fifth of the long side of the
 * tablet's own 3300x2160 page, which is as far as a guide can pull before
 * "near it" stops being a description of anything.
 */
const val MAX_REACH_DOC = 400f

/** The same width as the selection panel, for its reason. */
private val GUIDES_PANEL_WIDTH = 268.dp
