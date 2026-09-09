package be.thalos.artiest.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The chrome's colours, which are not the drawing's.
 *
 * ## Why the chrome is dark whatever the paper is
 *
 * The bars float over a white page and over the desk around it, and there is no
 * one translucent tint that is legible against both. Dark chrome is the tint
 * that is: over paper it reads as a grey pane and the ink shows through it; over
 * the dark desk it reads as itself, and the hairline outline is what keeps its
 * edge findable there. Light chrome over white paper is a bar you can only find
 * by its shadow.
 *
 * It is also the answer to a question the drawing asks. A toolbar in the same
 * whites as the page competes with the page — the eye keeps checking whether a
 * pale rectangle is part of the picture. A dark one never does.
 *
 * So this is not a dark *mode*: there is no light one, and the system setting is
 * deliberately not consulted. The paper is white in both.
 *
 * ## Why it is not Material's default scheme
 *
 * The UI plan's first open question was *"how modern is modern"*, and it noted
 * that an app built on Material's own palette looks like a Google app. The way
 * out taken here is the cheap half of it: Material's components, with a scheme
 * that is this app's. Graphite greys, a warm accent at the temperature of a
 * pencil rather than of a brand, and no purple.
 *
 * The accent is used sparingly on purpose — a lit tool, a slider's track, the
 * arrange affordance while it is on. Everything else is grey, because every
 * coloured pixel of chrome is a pixel competing with the colour the user just
 * mixed.
 */
private val ChromeScheme = darkColorScheme(
    // Warm graphite-yellow. Reads as "this one is on" against every grey here,
    // and is far enough from any ink anybody mixes to not be mistaken for it.
    primary = Color(0xFFE0B252),
    onPrimary = Color(0xFF2A1F06),
    primaryContainer = Color(0xFF574421),
    onPrimaryContainer = Color(0xFFFFE0A6),

    secondary = Color(0xFFA8AEB8),
    onSecondary = Color(0xFF1B1E23),

    // The bar's ground before it is made translucent. Slightly blue of neutral,
    // which is what stops a large translucent panel over white paper from
    // reading as a coffee stain.
    surface = Color(0xFF15181C),
    onSurface = Color(0xFFE4E7EC),
    surfaceVariant = Color(0xFF262A31),
    onSurfaceVariant = Color(0xFFB9BFC9),

    // The buttons. These are the opaque things sitting on the translucent bar,
    // so they are a step lighter than the bar and carry the whole visual weight.
    surfaceContainerLowest = Color(0xFF101317),
    surfaceContainerLow = Color(0xFF181C21),
    surfaceContainer = Color(0xFF1E232A),
    surfaceContainerHigh = Color(0xFF272D35),
    surfaceContainerHighest = Color(0xFF323942),

    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFF3A414A),

    error = Color(0xFFF2A08F),
    onError = Color(0xFF3A0B04),

    // The desk the page sits on, for the one place Compose paints behind the
    // canvas. InkSurfaceView punches through this, so it is visible only at the
    // very edges and for the frame before the surface exists.
    background = Color(0xFF0C0E11),
    onBackground = Color(0xFFE4E7EC),
)

/**
 * Rounder than Material's default at every size.
 *
 * The chrome is a set of floating panes rather than a page of cards, and a
 * floating pane with a 4dp corner looks like a piece of a page that came loose.
 */
private val ChromeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ArtiestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ChromeScheme, shapes = ChromeShapes, content = content)
}

/**
 * The numbers the chrome is built from, in one place because they have to agree
 * with each other and there is no way for the compiler to notice when they stop.
 */
object Chrome {

    /** One slot. [ToolItem.slots] is counted in these, along the dock's axis. */
    val SLOT = 44.dp

    /** A bar's thickness across its axis — one button plus its padding. */
    val BAR_THICKNESS = 52.dp

    /**
     * How much of the bar's ground is actually painted.
     *
     * This is the user's request, and it is worth writing down as a rule rather
     * than a number: **the bar is translucent and the buttons on it are not.**
     * The bar is a hint at where the controls live and the drawing must stay
     * visible through it; a button is a thing you press and it must look
     * pressable, which means it must look solid.
     *
     * 0.62 rather than something lower because the bar also has to be findable
     * against a busy drawing — below about a half it stops reading as a surface
     * and starts reading as a smudge on the page, which is worse than opaque.
     *
     * A blur behind it would let this go much lower, and cannot be had: the UI
     * plan explains why at length, and it comes down to `InkSurfaceView` being a
     * `SurfaceView` on its own composited layer, which Compose's graphics-layer
     * pipeline cannot capture. A blurred bar over the canvas would blur nothing.
     */
    const val BAR_ALPHA = 0.62f

    /** The hairline that keeps a translucent bar's edge findable over the desk. */
    const val BAR_OUTLINE_ALPHA = 0.30f

    /** Gap between a bar and the edge it is docked to. */
    val EDGE_INSET = 10.dp

    /** Inside the bar, around the run of buttons. */
    val BAR_PADDING = 5.dp

    /** A button's glyph. Smaller than the slot, so the slot has room to be a hit target. */
    val GLYPH = 21.dp
}
