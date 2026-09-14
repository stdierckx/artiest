package be.thalos.artiest.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import be.thalos.artiest.card.Card

/**
 * Your deck: the drawings you kept, with what you said about them.
 *
 * Lr7, and it is the user's *"knowledge base ... with his own examples on how
 * to draw stuff"*. The one decision made for them is that **you author it by
 * drawing**: a beginner will not keep written notes, and they are already
 * drawing, so a card is a drawing plus one sentence.
 *
 * A **tutorial** is a card somebody else made. Same shelf, same buttons, same
 * *Practice this* — which is why there is no separate tutorials feature in this
 * program and no curriculum to maintain.
 *
 * ## Two states and no more
 *
 * The shelf, or one card open. Not a browser, not a folder tree, not a search:
 * `docs/learner-plan.md` trap 2 says what this must never become, and the
 * shortest way to hold that line is a panel that can only be in two states.
 */
@Composable
fun DeckBody(
    cards: List<Card>,
    thumbnails: Map<String, android.graphics.Bitmap>,
    tags: List<String>,
    tag: String?,
    onTag: (String?) -> Unit,
    /** Null while nothing is open. The picture of [open], full size. */
    open: Card?,
    openBitmap: android.graphics.Bitmap?,
    onOpen: (String?) -> Unit,
    /** Keep the drawing that is on the paper, with this title and note. */
    onKeep: (String, String, List<String>) -> Unit,
    onPractise: (Card) -> Unit,
    onDelete: (Card) -> Unit,
    modifier: Modifier = Modifier,
    listMaxHeight: Dp = LIST_MAX_HEIGHT,
    trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var keeping by remember { mutableStateOf(false) }

    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        ) {
            Text(
                when {
                    keeping -> "Keep this drawing"
                    open != null -> "Card"
                    else -> "Deck"
                },
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (open != null || keeping) {
                DeckButton(ToolIcons.close, "Back") {
                    keeping = false
                    onOpen(null)
                }
                Spacer(Modifier.width(6.dp))
            }
            trailing()
        }

        Spacer(Modifier.height(8.dp))

        when {
            keeping -> KeepForm(
                onCancel = { keeping = false },
                onKeep = { title, note, chosen ->
                    keeping = false
                    onKeep(title, note, chosen)
                },
                known = tags,
            )

            open != null -> Column(
                Modifier.verticalScroll(rememberScrollState()),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(CARD_PICTURE)
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainerHighest),
                ) {
                    val bmp = openBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = remember(bmp) { bmp.asImageBitmap() },
                            contentDescription = open.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (open.title.isNotEmpty()) {
                    Text(open.title, fontSize = 14.sp, color = scheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                }
                if (open.note.isNotEmpty()) {
                    Text(open.note, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The button that makes a card a lesson rather than a
                    // souvenir: a new page with this drawing under it as a
                    // ghost, so you draw it again over your own line.
                    DeckWideButton("Practice this", Modifier.weight(1f)) { onPractise(open) }
                    DeckButton(ToolIcons.trash, "Delete this card") { onDelete(open) }
                }
            }

            else -> {
                if (tags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        TagChip("All", tag == null) { onTag(null) }
                        for (t in tags) TagChip(t, tag == t) { onTag(if (tag == t) null else t) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val shown = if (tag == null) cards else cards.filter { tag in it.tags }
                if (shown.isEmpty()) {
                    Text(
                        if (cards.isEmpty()) {
                            "Nothing kept yet. Draw something and press Keep."
                        } else {
                            "Nothing with that word on it."
                        },
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    Column(
                        Modifier.heightIn(max = listMaxHeight).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (card in shown) {
                            CardRow(card, thumbnails[card.id]) { onOpen(card.id) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                DeckWideButton("Keep this drawing", Modifier.fillMaxWidth()) { keeping = true }
            }
        }
    }
}

/** One row: a small picture, a title, and the first words of the note. */
@Composable
private fun CardRow(card: Card, thumb: android.graphics.Bitmap?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .size(ROW_THUMB_W, ROW_THUMB_H)
                .clip(RoundedCornerShape(5.dp))
                .background(scheme.surfaceContainerHighest),
        ) {
            if (thumb != null && !thumb.isRecycled) {
                Image(
                    bitmap = remember(thumb) { thumb.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                card.title.ifEmpty { "Untitled" },
                fontSize = 13.sp,
                maxLines = 1,
                color = scheme.onSurface,
            )
            if (card.note.isNotEmpty()) {
                Text(
                    card.note,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Title, one note, and words to find it by.
 *
 * Three fields and a button, and the note is one line high on purpose: a box
 * that grows invites an essay, and an essay is the thing this feature must not
 * become. See `Card`.
 */
@Composable
private fun KeepForm(
    onCancel: () -> Unit,
    onKeep: (String, String, List<String>) -> Unit,
    known: List<String>,
) {
    val scheme = MaterialTheme.colorScheme
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var words by remember { mutableStateOf("") }

    Column {
        Field(title, "What is it?", scheme) { title = it }
        Spacer(Modifier.height(6.dp))
        Field(note, "How did you do it?", scheme) { note = it }
        Spacer(Modifier.height(6.dp))
        Field(words, "Words to find it by", scheme) { words = it }
        if (known.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                // The words already in use, as buttons. Typing a tag that
                // differs from one you used before by a capital letter is how a
                // deck ends up with "Hands" and "hands" in it.
                for (t in known) {
                    TagChip(t, false) {
                        words = (words.split(' ').filter { it.isNotBlank() } + t)
                            .distinct().joinToString(" ")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckWideButton("Keep", Modifier.weight(1f)) {
                onKeep(title.trim(), note.trim(), words.split(' ').filter { it.isNotBlank() })
            }
            DeckButton(ToolIcons.close, "Cancel", onClick = onCancel)
        }
    }
}

@Composable
private fun Field(
    value: String,
    hint: String,
    scheme: androidx.compose.material3.ColorScheme,
    onValue: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, fontSize = 12.sp, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = scheme.onSurface, fontSize = 12.sp),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TagChip(label: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (on) scheme.onPrimary else scheme.onSurface,
        )
    }
}

@Composable
private fun DeckButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 40.dp, height = 34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = scheme.onSurface)
    }
}

@Composable
private fun DeckWideButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(scheme.primary)
            .clickable(onClick = onClick),
    ) {
        Text(label, fontSize = 12.sp, color = scheme.onPrimary)
    }
}

/** The deck, in a popup, from a toolbar button. See `ReferenceButton`. */
@Composable
fun DeckButtonAndPanel(
    cards: List<Card>,
    thumbnails: Map<String, android.graphics.Bitmap>,
    tags: List<String>,
    tag: String?,
    onTag: (String?) -> Unit,
    open: Card?,
    openBitmap: android.graphics.Bitmap?,
    onOpen: (String?) -> Unit,
    onKeep: (String, String, List<String>) -> Unit,
    onPractise: (Card) -> Unit,
    onDelete: (Card) -> Unit,
    onFixate: (Cell) -> Unit,
) {
    var showing by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { here = it.positionInRoot() }) {
        IconToolButton(
            icon = ToolIcons.deck,
            label = "Deck",
            onClick = { showing = true },
            selected = showing,
        )
        if (showing) {
            Popup(
                popupPositionProvider = remember(gap) { PanelPosition(gap) },
                onDismissRequest = { showing = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = 14.dp,
                    modifier = Modifier.width(PANEL_WIDTH),
                ) {
                    DeckBody(
                        cards = cards,
                        thumbnails = thumbnails,
                        tags = tags,
                        tag = tag,
                        onTag = onTag,
                        open = open,
                        openBitmap = openBitmap,
                        onOpen = onOpen,
                        onKeep = onKeep,
                        onPractise = {
                            showing = false
                            onPractise(it)
                        },
                        onDelete = onDelete,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    showing = false
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
}

/** The deck, kept on a bar. */
@Composable
fun DeckPanelCard(
    cards: List<Card>,
    thumbnails: Map<String, android.graphics.Bitmap>,
    tags: List<String>,
    tag: String?,
    onTag: (String?) -> Unit,
    open: Card?,
    openBitmap: android.graphics.Bitmap?,
    onOpen: (String?) -> Unit,
    onKeep: (String, String, List<String>) -> Unit,
    onPractise: (Card) -> Unit,
    onDelete: (Card) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DeckBody(
            cards = cards,
            thumbnails = thumbnails,
            tags = tags,
            tag = tag,
            onTag = onTag,
            open = open,
            openBitmap = openBitmap,
            onOpen = onOpen,
            onKeep = onKeep,
            onPractise = onPractise,
            onDelete = onDelete,
            modifier = Modifier.fillMaxSize(),
            listMaxHeight = (maxHeight - CARD_FURNITURE).coerceAtLeast(80.dp),
        )
    }
}

private val PANEL_WIDTH = 300.dp
private val LIST_MAX_HEIGHT = 230.dp
private val CARD_PICTURE = 190.dp
private val ROW_THUMB_W = 54.dp
private val ROW_THUMB_H = 38.dp

/** Header, tag row, the Keep button and the gaps. Counted, not guessed. */
private val CARD_FURNITURE = 120.dp
