package be.thalos.artiest.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * A timed drawing session: a picture, a clock, and then the page turns.
 *
 * Lr9. Every gesture timer an artist uses is a website — Line of Action,
 * Quickposes, SketchDaily, DrawGestures — which means every beginner doing
 * gesture practice on a tablet is running a browser in one window and their
 * drawing app in another. On this tablet, in this program, there is no other
 * window.
 *
 * One of those sites says the useful thing out loud: *"the single biggest gain
 * isn't the reference library — it's the timer, as the pressure forces you to
 * commit to lines, prioritize the largest shapes first, and stop fussing with
 * details."* That is the whole feature. Everything below is a clock, a page
 * turn, and getting out of the way.
 *
 * ## No score, ever
 *
 * No streak, no badge, no number to chase. Drawabox's 50 % rule exists because
 * *the finished result already gets in the way of the learning*, and adding
 * something to beat makes that worse. The only thing this offers at the end is
 * **what you drew, beside what you drew it from**, which is a review and not a
 * mark.
 */
@Composable
fun PracticeBody(
    state: PracticeState,
    /**
     * How many pictures are in the library right now.
     *
     * Not `state.total`, which is how many are in the session **being run** and
     * is zero until one starts — a distinction that read on the tablet as
     * *"add some pictures first"* over a panel with a picture in it.
     */
    available: Int,
    /** Thumbnails from the library, by picture id. Shared with the pane. */
    thumbnails: Map<String, android.graphics.Bitmap>,
    onStart: (lengthMs: Long) -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    listMaxHeight: Dp = SHEET_MAX_HEIGHT,
    trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var chosen by remember { mutableStateOf(LENGTHS[1]) }

    Column(modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        ) {
            Text(
                when {
                    state.finished -> "What you drew"
                    state.running -> "${state.index + 1} of ${state.total}"
                    else -> "Practice"
                },
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }

        Spacer(Modifier.height(8.dp))

        when {
            state.finished -> {
                if (state.drawn.isEmpty()) {
                    Text(
                        "Nothing to show. The session was stopped before a page turned.",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    // What you drew beside what you drew it from, in order.
                    // The payoff, and nearly free: both pictures already exist.
                    Column(
                        Modifier.heightIn(max = listMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (pair in state.drawn) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Shot(pair.second)
                                Shot(thumbnails[pair.first])
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                WideButton("Done", Modifier.fillMaxWidth(), onClick = onDone)
            }

            state.running -> {
                // A bar draining rather than a number counting down. A number
                // asks to be read; a bar is seen without looking away from the
                // paper, which is where the eyes are supposed to be.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.surfaceContainerHighest),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(state.left.coerceIn(0f, 1f))
                            .fillMaxSize()
                            .background(scheme.primary),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WideButton("Next", Modifier.weight(1f), onClick = onSkip)
                    PracticeButton(ToolIcons.close, "Stop", onClick = onStop)
                }
            }

            else -> {
                Text(
                    when (available) {
                        0 -> "Add some pictures to the reference pane first."
                        1 -> "1 picture."
                        else -> "$available pictures, one after another."
                    },
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                ) {
                    for (ms in LENGTHS) {
                        LengthChip(label(ms), ms == chosen) { chosen = ms }
                    }
                }
                Spacer(Modifier.height(10.dp))
                WideButton(
                    "Start",
                    Modifier.fillMaxWidth(),
                    enabled = available > 0,
                    onClick = { onStart(chosen) },
                )
            }
        }
    }
}

/** One picture in the contact sheet. Empty draws as a frame, not as nothing. */
@Composable
private fun Shot(bitmap: android.graphics.Bitmap?) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(SHOT_W, SHOT_H)
            .clip(RoundedCornerShape(5.dp))
            .background(scheme.surfaceContainerHighest),
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LengthChip(label: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, color = if (on) scheme.onPrimary else scheme.onSurface)
    }
}

@Composable
private fun WideButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun PracticeButton(
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

/** The button, and the panel it opens. See `ReferenceButton`. */
@Composable
fun PracticeButtonAndPanel(
    state: PracticeState,
    available: Int,
    thumbnails: Map<String, android.graphics.Bitmap>,
    onStart: (Long) -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onFixate: (Cell) -> Unit,
) {
    var showing by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { here = it.positionInRoot() }) {
        IconToolButton(
            icon = ToolIcons.practice,
            label = "Practice",
            onClick = { showing = true },
            // Lit while a session is running, wherever the panel is. A clock
            // you cannot see is a clock that has stopped as far as the user
            // knows, and the panel closes the moment the pen is wanted.
            selected = showing || state.running,
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
                    PracticeBody(
                        state = state,
                        available = available,
                        thumbnails = thumbnails,
                        onStart = {
                            showing = false
                            onStart(it)
                        },
                        onSkip = onSkip,
                        onStop = onStop,
                        onDone = {
                            showing = false
                            onDone()
                        },
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

/** The panel, kept on a bar — which is how the clock stays visible. */
@Composable
fun PracticePanelCard(
    state: PracticeState,
    available: Int,
    thumbnails: Map<String, android.graphics.Bitmap>,
    onStart: (Long) -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        PracticeBody(
            state = state,
            available = available,
            thumbnails = thumbnails,
            onStart = onStart,
            onSkip = onSkip,
            onStop = onStop,
            onDone = onDone,
            modifier = Modifier.fillMaxSize(),
            listMaxHeight = (maxHeight - CARD_FURNITURE).coerceAtLeast(60.dp),
        )
    }
}

/**
 * A session, as the chrome sees it.
 *
 * A value and not a machine: the panel draws it and the screen owns it, which
 * is the arrangement every other panel in this app has. [drawn] holds small
 * pictures of the pages that turned, paired with the picture they were drawn
 * from — the contact sheet, and the only review this feature will ever offer.
 */
data class PracticeState(
    val running: Boolean = false,
    val finished: Boolean = false,
    /** Which picture, counting from zero. */
    val index: Int = 0,
    val total: Int = 0,
    /** How much of the clock is left, 1 down to 0. */
    val left: Float = 1f,
    val drawn: List<Pair<String, android.graphics.Bitmap?>> = emptyList(),
)

/** 30 s, 1 m, 2 m, 5 m: what every gesture site offers, because it is what works. */
val LENGTHS = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)

private fun label(ms: Long): String =
    if (ms < 60_000L) "${ms / 1000}s" else "${ms / 60_000}m"

private val PANEL_WIDTH = 300.dp
private val SHEET_MAX_HEIGHT = 260.dp
private val SHOT_W = 130.dp
private val SHOT_H = 86.dp
private val CARD_FURNITURE = 110.dp
