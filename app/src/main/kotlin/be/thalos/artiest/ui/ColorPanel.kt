package be.thalos.artiest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
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
    recent: List<Int>,
    onCommit: (Int) -> Unit,
    onFixate: (Cell) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    // The grid the fixated card lands on: a cell is Chrome.SLOT, everywhere.
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(38.dp)
            .onGloballyPositioned { here = it.positionInRoot() },
    ) {
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
                recent = recent,
                onDismiss = { open = false; onCommit(ink) },
                onFixate = {
                    open = false
                    onCommit(ink)
                    // "In the neighbourhood of the place it was opened", which
                    // is this button: the new bar appears beside it and the
                    // drag that moves it somewhere better is the one arrange
                    // mode has just been turned on for.
                    onFixate(DropMath.cellBeside(here, view.width, view.height, slotPx))
                },
            )
        }
    }
}

/**
 * The wheel, with three sliders and the colours you already have beneath it.
 *
 * Four ways to the same setting, in the order you reach for them: the hex field
 * for a colour somebody wrote down, the wheel for one you are looking for, the
 * sliders for a small change to the one you have, and the recents for the ones
 * this drawing is already made of.
 *
 * The **fixed palette is gone** — eight standard colours in a 45dp row, showing
 * eight of sixteen million — and the user named it as the thing to spend on
 * sliders. Nothing is lost by it: `DockStore.STARTING_COLOURS` seeds the
 * recents row with five of them on a fresh install, in a row that exists
 * anyway, and the first colour the artist mixes pushes one off the end.
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
    recent: List<Int>,
    onDismiss: () -> Unit,
    onFixate: () -> Unit,
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
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    HexField(ink, onInk, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    // The whole of "fixate", and it is one tap: the panel
                    // becomes a control on a toolbar of its own. See
                    // docs/panels-plan.md for why that is placing something
                    // rather than a new kind of thing.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onFixate),
                    ) {
                        Icon(
                            ToolIcons.pin,
                            "Keep this panel on screen",
                            Modifier.size(15.dp),
                            MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                ColourBody(ink, onInk, recent, Modifier.fillMaxWidth(), discSize = POPUP_DISC)
            }
        }
    }
}

/**
 * Below the button if there is room, and always on the screen.
 *
 * `internal` rather than file-private because the layers panel wants exactly
 * the same rule and for exactly the same reason: it opens from a button that
 * can be docked to any of five edges.
 *
 * The button that opens this can be docked to any of five places, so there is
 * no fixed side that is right — a panel that always opens to the right is off
 * the screen from the right edge. Below-and-centred is the guess, and the clamp
 * is what makes the guess safe: a panel that would hang off any edge is slid
 * back in rather than being placed somewhere clever.
 */
internal class PanelPosition(private val gap: Int) : PopupPositionProvider {
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

/**
 * The wheel and the swatch rows, with nothing around them.
 *
 * Shared by the popup and by [ColourPanelCard], which is the point: fixating
 * changes where the panel is drawn and nothing about what it is. A second copy
 * would be two colour pickers that drift apart, and the one you were not
 * looking at would be the one that was wrong.
 */
@Composable
private fun ColourBody(
    ink: Int,
    onInk: (Int) -> Unit,
    recent: List<Int>,
    modifier: Modifier = Modifier,
    discSize: Dp,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Given a width in both cases now, where the popup used to hand the
        // wheel the whole panel. See [POPUP_DISC]: the disc was the single
        // biggest thing on the screen that was not the drawing.
        ColorWheel(argb = ink, onColorChange = onInk, discSize = discSize)
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // No heading. A row of round swatches under a colour picker is not
            // a control anybody needs told the name of, and the word was a
            // 14dp line of its own — the same trade the layers panel's opacity
            // row makes. The palette row it sits where is gone entirely: eight
            // fixed colours were 45dp of the panel showing eight of sixteen
            // million, and the user named it as the thing to spend on sliders.
            SwatchRow(recent, ink, onInk)
        }
    }
}

/**
 * A `#RRGGBB` you can read **and type into**.
 *
 * The other half of the user's first item: *"You cant input a color code."* It
 * was a `Text`, which is the whole of what was wrong with it — the value was
 * already shown, and the only thing missing was being allowed to put the caret
 * in it.
 *
 * ## How it commits
 *
 * On every keystroke that leaves six usable hex digits, and never otherwise.
 * The alternative — commit on done, or on focus loss — means a user who types
 * a code and then reaches for the pen has set nothing, and a picker that
 * quietly discards what you typed is worse than one with no field at all.
 *
 * **Six digits only, and the three-digit shorthand is refused on purpose.**
 * `#fff` is a thing people type and accepting it here would break the rule
 * above: `#D32` is a valid three-digit code *and* a prefix of `#D32F2F`, so a
 * parser that took both would set the ink to `#DD3322` halfway through
 * somebody typing a red and then correct itself. Committing on every keystroke
 * and accepting prefixes are the two halves of one bad idea, and this is the
 * half worth keeping. A leading `#` stays optional, since no colour is a prefix
 * of another that way.
 *
 * ## Why the text is local state
 *
 * Because `#0F0` and `#00FF00` are the same colour and not the same string, and
 * deriving the field's text from [ink] would rewrite what the user is typing
 * under their caret. It is re-seeded from [ink] only when the colour arrives
 * disagreeing with what this field last produced — a wheel drag, a swatch tap —
 * which is exactly the rule `ColorWheel`'s ARGB overload uses for its puck, and
 * for the same reason.
 */
@Composable
private fun HexField(ink: Int, onInk: (Int) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(hexOf(ink)) }
    LaunchedEffect(ink) {
        if (parseHex(text) != ink) text = hexOf(ink)
    }
    BasicTextField(
        value = text,
        onValueChange = { typed ->
            // Eight is `#` plus six, which is the longest thing worth holding;
            // the filter keeps paste from putting a paragraph in here.
            text = typed.filter { it == '#' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                .take(7)
            parseHex(text)?.let(onInk)
        },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 9.dp),
            ) {
                field()
            }
        },
    )
}

/**
 * `#RRGGBB`, or the same without the hash. Null for anything else, including
 * every prefix of a valid code — see [HexField] for why that matters.
 *
 * Opaque always: the app's ink has no alpha, so an eight-digit code would be
 * offering something the rest of the program cannot hold. See `ColorWheel`.
 */
internal fun parseHex(text: String): Int? {
    val body = text.trim().removePrefix("#")
    if (body.length != 6) return null
    val value = body.toLongOrNull(16) ?: return null
    return (0xFF000000L or value).toInt()
}

/**
 * The same panel, as a control on a bar.
 *
 * It fills the cell the layout gave it — six cells by ten — rather than sizing
 * itself, because a control that disagrees with its slot is a control that
 * overlaps its neighbour. There is no close button on it: the bar it is on has
 * one, and a panel that could be closed two ways would leave an empty bar
 * behind one of them.
 */
@Composable
fun ColourPanelCard(ink: Int, onInk: (Int) -> Unit, recent: List<Int>) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The disc is square, so it is the *smaller* of the two budgets that
        // decides how big it can be. It used to be told only the width, which
        // is why dragging the resize handle upwards did nothing visible: the
        // wheel kept its size and the swatch rows went off the bottom of the
        // card. A control that ignores half of the space it was given is a
        // resize handle that half works.
        val side = minOf(maxWidth - SIDE_PADDING * 2, maxHeight - CARD_FURNITURE)
            .coerceAtLeast(MIN_WHEEL)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SIDE_PADDING, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The word "Colour" went with the palette. A card carrying a hue
            // wheel is not one anybody has to be told the subject of, and a
            // heading in a panel whose own bar can be labelled is a line spent
            // twice.
            HexField(ink, onInk, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ColourBody(ink, onInk, recent, Modifier.fillMaxWidth(), discSize = side)
        }
    }
}

/**
 * How much of a docked colour card is not the disc: the two paddings, the hex
 * field, the three sliders, the recents row and the gaps between them.
 *
 * A constant because those parts are constant, and it is counted rather than
 * guessed: 16 of padding, 26 of field, 8 of gap, 10 more, 78 for the three
 * slider rows, 8, and 26 for the recents. It is still an estimate, and the
 * scroll above is what makes an estimate safe — if the swatches wrap onto a
 * second line on a very narrow card, the card scrolls instead of hiding them.
 *
 * It was 200, for a card carrying a heading, a value bar and *two* swatch rows.
 * The 28 that went back are 28 more disc on every docked card that was already
 * height-limited.
 */
private val CARD_FURNITURE = 172.dp

/** Below this the disc is not something you can aim at, so the card scrolls instead. */
private val MIN_WHEEL = 96.dp

private val SIDE_PADDING = 12.dp



/**
 * The recents, and it **scrolls sideways** rather than fitting exactly.
 *
 * Eight 24dp swatches with 6dp between them is 234dp against the popup's 240dp
 * of content, which fits — and fitting by six device-independent pixels is not
 * a design, it is a coincidence waiting for a ninth colour or a wider glyph. A
 * docked card can be narrower than the popup as well, since its width is
 * whatever the user dragged it to. So the row scrolls, and the arithmetic stops
 * mattering.
 */
@Composable
private fun SwatchRow(colours: List<Int>, ink: Int, onInk: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (colour in colours) {
            Swatch(colour, selected = colour == ink) { onInk(colour) }
        }
    }
}

@Composable
private fun Swatch(colour: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
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
 * Wide enough for a wheel you can aim with a fingertip, and for a slider whose
 * ends are worth the travel.
 */
private val PANEL_WIDTH = 268.dp

/**
 * How big the disc is in the popup.
 *
 * **It was 236dp**, taking the panel's whole width, on the argument that a full
 * hue sweep needed 740dp of rim travel before adjacent degrees were further
 * apart than a fingertip. That argument survives the change and stops being
 * decisive, because there is a hue *slider* underneath it now: the disc is the
 * fast instrument and the slider is the precise one, so the disc no longer has
 * to be both.
 *
 * 128dp puts the sweep at about 400dp of rim, or 1.1dp per degree. A fingertip
 * is about 7dp of contact, so a touch still resolves six degrees of hue — where
 * it resolved three and a half before, and where the slider underneath resolves
 * one and a half. Krita's own advanced selector, measured off the screenshot the
 * user left on the tablet, is 160 device pixels: at this panel's density that is
 * 98dp, smaller again than what is being shipped here.
 *
 * What it buys, measured in the same units as everything else in
 * `docs/ui-space-plan.md`: the popup is 302dp tall where it was 440.
 */
private val POPUP_DISC = 128.dp
