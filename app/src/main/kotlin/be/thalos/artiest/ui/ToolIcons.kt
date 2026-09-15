package be.thalos.artiest.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp

/**
 * The app's glyphs, drawn here rather than fetched.
 *
 * ## Why these are not Lucide's, or Phosphor's, or Material's
 *
 * The UI plan named the third-party sets and it was right about all of them:
 * Material Symbols has no graphite pencil, and Lucide (ISC) and Phosphor (MIT)
 * both do. What it also recorded is the price of taking one — `NOTICE` says
 * *"No third-party source, assets or brush data have been copied into this
 * repository"*, both licences require the copyright notice to travel with the
 * copy, and the plan set the deadline at *"the same commit as the first
 * `.svg`"*.
 *
 * These are twenty-two glyphs of a dozen line segments each. Authoring them
 * costs less than the paragraph that would have to be true forever afterwards,
 * and it buys the thing the plan's first open question was actually about —
 * *"an app built on it looks like a Google app"*. A pen that is a real nib pen
 * and a pencil that is a barrel, a ferrule and a cone of graphite are this
 * app's, and they say what this app is for. So `NOTICE` is unchanged, and it is
 * still true.
 *
 * ## How they are built
 *
 * A 24×24 viewport, stroked at 1.9 with round caps and joins, in black —
 * `Icon` tints whatever it is given, so the colour here is only a placeholder
 * for the content colour of wherever the glyph lands. Fills are used only where
 * a stroke would not read at 20dp: the size ramp's dots, the grain's scatter,
 * and the filled half of the opacity circle.
 *
 * Each is built once, on first use, and held. An `ImageVector` is immutable and
 * its construction is a list of `PathNode`s — cheap, but not free, and building
 * one per recomposition of a toolbar that sits over the ink path is exactly the
 * sort of thing this project measures rather than assumes.
 */
object ToolIcons {

    /** The glyph for [item], or null when the item draws its own face. */
    fun of(item: ToolItem): ImageVector? = when (item) {
        ToolItem.PEN -> pen
        ToolItem.PENCIL -> pencil
        ToolItem.MARKER -> marker
        ToolItem.HARD_ERASER -> eraser
        ToolItem.SOFT_ERASER -> softEraser
        ToolItem.PICK_COLOUR -> pickColour
        ToolItem.PICK_LAYER_ONLY -> pickLayerOnly
        ToolItem.REFERENCES -> references
        ToolItem.REFERENCE_PANEL -> references
        ToolItem.DECK -> deck
        ToolItem.DECK_PANEL -> deck
        ToolItem.KEEP_CARD -> keepCard
        ToolItem.PRACTICE -> practice
        ToolItem.PRACTICE_PANEL -> practice
        ToolItem.FLIP_VIEW -> flipView
        ToolItem.GREY_VIEW -> greyView
        ToolItem.LAYERS -> layers
        ToolItem.MARQUEE -> marquee
        ToolItem.SELECTION -> selectionPanel
        ToolItem.SELECTION_PANEL -> selectionPanel
        ToolItem.LAYERS_PANEL -> layers

        // The panel, taken apart. Every one of these is the picture the panel
        // draws for the same button: a control pulled out onto a bar has to be
        // recognisable as the one it was pulled from.
        ToolItem.PICK_STROKES -> addVector
        ToolItem.MARQUEE_RECT -> marquee
        ToolItem.MARQUEE_OVAL -> marqueeOval
        ToolItem.MARQUEE_LASSO -> marqueeLasso
        ToolItem.SELECT_NEW -> selectNew
        ToolItem.SELECT_ADD -> selectAdd
        ToolItem.SELECT_SUBTRACT -> selectSubtract
        ToolItem.SELECT_OVERLAP -> selectIntersect
        ToolItem.SELECT_ALL -> selectAll
        ToolItem.SELECT_NONE -> selectNone
        ToolItem.SELECT_INVERT -> selectInvert
        ToolItem.FLOAT_MOVE -> moveFloat
        ToolItem.FLOAT_COPY -> copyFloat
        ToolItem.FLIP_ACROSS -> flipAcross
        ToolItem.FLIP_DOWN -> flipDown
        ToolItem.FLOAT_SHEET -> moveSheet
        ToolItem.FLOAT_PASTE -> dropFloat
        ToolItem.FLOAT_CANCEL -> close
        ToolItem.ERASE_WHOLE -> eraser
        ToolItem.ERASE_JUNCTION -> selectIntersect
        ToolItem.ERASE_PART -> softEraser
        ToolItem.GUIDES -> guides
        ToolItem.GUIDES_PANEL -> guides
        // The swatch shows the ink. A palette symbol beside it would be a label
        // for something already visible — but the chooser lists items before
        // they are placed, where there is no ink to show, so the glyph exists.
        ToolItem.COLOUR -> palette
        // The same glyph as the swatch, because it is the same idea at a
        // different size, and the chooser lists them next to each other.
        ToolItem.COLOUR_PANEL -> palette
        ToolItem.SIZE -> size
        ToolItem.SMOOTHING -> smoothing
        ToolItem.OPACITY -> opacity
        ToolItem.FLOW -> flow
        ToolItem.GRAIN -> grain
        ToolItem.UNDO -> undo
        ToolItem.REDO -> redo
        ToolItem.ZOOM_IN -> zoomIn
        ToolItem.ZOOM_OUT -> zoomOut
        ToolItem.FIT -> fit
        ToolItem.CLEAR -> trash
        ToolItem.EXPORT -> export
        ToolItem.PROJECTS -> gallery
        // A brush button's face is its own swatch; the glyph is only for the
        // chooser, which lists one row per brush and has no swatch to show.
        ToolItem.BRUSH -> brushes
        ToolItem.BRUSHES -> brushes
        ToolItem.BRUSH_SHELF -> brushes
        ToolItem.IMPORT -> import_
        ToolItem.STATS -> stats
    }

    /**
     * **The nib**, drawn big, with its slit and its breather hole.
     *
     * It was a whole pen seen at an angle — a barrel, a collar and a small
     * filled tip — and the user's words are the correction: *"the pen symbol of
     * the pen button is not a good one. Most pen symbols show the pen nib in
     * large. This is what sets a pen apart."*
     *
     * They are right about the object and right about the reason. A pen at an
     * angle is a *pen-shaped thing on a diagonal*, which at 21dp on a toolbar is
     * also what a pencil and a marker are; the nib is the part no other tool
     * has. Drawing the part that identifies the tool rather than the whole tool
     * is the rule the rest of this set already follows — [eraser] is a rubber's
     * wedge, not a hand holding one.
     *
     * **Raked, and it was upright.** This file argued that standing the nib up
     * was a second difference the eye could make without looking, because the
     * diagonal is the axis every other drawing tool in the set is on. The
     * tablet disagreed with exactly that sentence:
     *
     * > *"While the pen icon is better as before, all the other ones are at 45
     * > degrees, and the pen is at 90 degrees... can you put it also at 45
     * > degrees angle?"*
     *
     * Which is right, and it is right about a thing the argument had counted as
     * a gain. A row of tools all on one axis with one of them square to it does
     * not read as *that one is different*; it reads as *that one is wrong*, and
     * an odd man out costs more than the difference it buys. The nib is what
     * tells the pen from the pencil, and it goes on doing that at any angle —
     * so the angle is free to be the one the set is already on.
     *
     * It is [pencil]'s angle exactly: point at the bottom left, held end at the
     * top right, and the same 2.7-21.3 box the pencil fills. The shape is the
     * upright one turned 45 degrees about the middle and scaled to fill the box
     * again, so nothing about the nib itself changed.
     */
    val pen: ImageVector by lazy {
        icon("pen") {
            // The barrel, filled and small: it is here to say which end is
            // held, and any more of it takes room from the nib.
            fill {
                moveTo(17.0f, 2.7f)
                lineTo(21.3f, 7.0f)
                lineTo(18.0f, 12.5f)
                lineTo(11.5f, 6.0f)
                close()
            }
            stroke {
                // The nib: shoulders, two flanks that curve in, and a point.
                moveTo(11.4f, 6.2f)
                curveTo(6.6f, 9.6f, 3.9f, 15.7f, 2.7f, 21.3f)
                curveTo(8.3f, 20.1f, 14.4f, 17.4f, 17.8f, 12.6f)
                close()
                // The slit, which runs from the hole to the point.
                moveTo(9.0f, 15.0f)
                lineTo(2.7f, 21.3f)
            }
            // The breather hole. Filled, because the nib is an outline and a
            // filled dot inside an outline is what an eye reads as a hole.
            fill { circle(10.7f, 13.3f, 1.8f) }
        }
    }

    /**
     * A marker, **standing up**: a fat square barrel over a solid nib with one
     * corner cut off.
     *
     * Upright and not diagonal, which is the only thing that made it work. The
     * pen, the pencil and the eraser are all diagonal objects tapering to a
     * point, and two attempts at a diagonal marker produced first a fatter pen
     * and then something indistinguishable from the eraser — at 21dp the eye
     * reads the silhouette and nothing else. A vertical rectangle is a
     * silhouette none of the others has.
     *
     * The cut corner is the chisel, and it is the same shape as the mark this
     * tool actually makes.
     */
    val marker: ImageVector by lazy {
        icon("marker") {
            stroke {
                rect(7.6f, 2.8f, 16.4f, 14.4f)
            }
            fill {
                moveTo(7.6f, 15.6f)
                lineTo(16.4f, 15.6f)
                lineTo(16.4f, 18.2f)
                lineTo(11.4f, 21.4f)
                lineTo(7.6f, 21.4f)
                close()
            }
        }
    }

    /**
     * A pencil: a cone of graphite, a hexagonal barrel, a ferrule.
     *
     * Three separate shapes rather than one outline, and all three in outline
     * rather than filled: this is the light one of the pair. See [pen] for the
     * other half of that contrast, and for why it needed one.
     */
    val pencil: ImageVector by lazy {
        icon("pencil") {
            stroke {
                moveTo(2.8f, 21.2f)
                lineTo(4.9f, 15.2f)
                lineTo(8.8f, 19.1f)
                close()
                moveTo(4.9f, 15.2f)
                lineTo(15.6f, 4.5f)
                lineTo(19.5f, 8.4f)
                lineTo(8.8f, 19.1f)
                close()
                moveTo(17.2f, 2.9f)
                lineTo(21.1f, 6.8f)
                lineTo(19.5f, 8.4f)
                lineTo(15.6f, 4.5f)
                close()
            }
        }
    }

    /** A block of rubber on its edge, with the line it is taking off. */
    val eraser: ImageVector by lazy {
        icon("eraser") {
            stroke {
                moveTo(12.3f, 2.6f)
                lineTo(21.0f, 11.3f)
                lineTo(13.2f, 19.1f)
                lineTo(4.5f, 10.4f)
                close()
                moveTo(8.4f, 6.5f)
                lineTo(17.1f, 15.2f)
                moveTo(9.6f, 21.4f)
                lineTo(20.8f, 21.4f)
            }
        }
    }

    /**
     * The same block of rubber, with the mark it leaves fading out under it.
     *
     * The two erasers sit next to each other on a bar, so they have to be told
     * apart at 21dp without reading a label. The difference is the line at the
     * bottom: the hard one takes it off in a single piece, this one leaves it
     * broken — three dashes where [eraser] has one stroke — which is what
     * "fades rather than cuts" looks like drawn small.
     */
    val softEraser: ImageVector by lazy {
        icon("soft_eraser") {
            stroke {
                moveTo(12.3f, 2.6f)
                lineTo(21.0f, 11.3f)
                lineTo(13.2f, 19.1f)
                lineTo(4.5f, 10.4f)
                close()
                moveTo(8.4f, 6.5f)
                lineTo(17.1f, 15.2f)
                moveTo(9.6f, 21.4f)
                lineTo(12.4f, 21.4f)
                moveTo(14.8f, 21.4f)
                lineTo(17.0f, 21.4f)
                moveTo(19.2f, 21.4f)
                lineTo(20.8f, 21.4f)
            }
        }
    }

    /**
     * A pipette: pointed barrel, collar, bulb.
     *
     * The one glyph in this set that is a real object nobody has held. A
     * dropper is what every program in this trade has drawn for forty years, it
     * is what a search for "colour picker" returns, and a cleverer symbol here
     * would be a private joke — this is the one place to be conventional.
     */
    val pickColour: ImageVector by lazy {
        icon("pick_colour") {
            stroke {
                moveTo(3.2f, 20.8f)
                lineTo(4.8f, 15.8f)
                lineTo(14.2f, 6.4f)
                lineTo(17.6f, 9.8f)
                lineTo(8.2f, 19.2f)
                close()
                moveTo(12.4f, 8.2f)
                lineTo(15.8f, 11.6f)
            }
            fill { circle(18.6f, 5.4f, 2.8f) }
        }
    }

    /**
     * Two sheets with the top one solid: *this one, not the picture*.
     *
     * Deliberately the stack glyph's shape rather than a second pipette. The
     * switch is not another picker, it is which sheet the one picker reads, and
     * a pair of near-identical droppers on a bar would be two buttons nobody
     * could tell apart at 20dp.
     */
    val pickLayerOnly: ImageVector by lazy {
        icon("pick_layer_only") {
            stroke {
                moveTo(3.6f, 15.4f)
                lineTo(12.0f, 19.8f)
                lineTo(20.4f, 15.4f)
            }
            fill {
                moveTo(12.0f, 4.2f)
                lineTo(20.4f, 8.8f)
                lineTo(12.0f, 13.4f)
                lineTo(3.6f, 8.8f)
                close()
            }
        }
    }

    /**
     * A padlock, shackle closed. Lr4.
     *
     * The one place in this set where the obvious symbol is the right one for
     * the obvious reason: a locked sheet is a locked thing, and there is no
     * second meaning anybody could read into it.
     */
    val lock: ImageVector by lazy {
        icon("lock") {
            stroke {
                moveTo(7.6f, 10.4f)
                lineTo(7.6f, 7.4f)
                arcTo(4.4f, 4.4f, 0f, true, true, 16.4f, 7.4f)
                lineTo(16.4f, 10.4f)
            }
            fill {
                moveTo(4.6f, 10.4f)
                lineTo(19.4f, 10.4f)
                lineTo(19.4f, 21.0f)
                lineTo(4.6f, 21.0f)
                close()
            }
        }
    }

    /**
     * A picture with a corner turned up: a sheet that is something to look at.
     *
     * Not a camera and not an eye. A camera says *where it came from*, which is
     * not the point — a reference sheet may be a scan, a screenshot or a
     * drawing of your own — and an eye already means visibility one button
     * along.
     */
    val referenceLayer: ImageVector by lazy {
        icon("reference_layer") {
            stroke {
                moveTo(3.4f, 5.0f)
                lineTo(20.6f, 5.0f)
                lineTo(20.6f, 19.0f)
                lineTo(3.4f, 19.0f)
                close()
                moveTo(3.4f, 15.2f)
                lineTo(9.0f, 10.0f)
                lineTo(14.0f, 14.6f)
                lineTo(16.6f, 12.4f)
                lineTo(20.6f, 15.8f)
            }
            fill { circle(7.6f, 8.6f, 1.6f) }
        }
    }

    /**
     * A circle half filled: the colour taken out of one side. Lr4.
     *
     * The same shape [opacity] uses and deliberately so — both are "how much of
     * something" — but split the other way round, vertically against that one's
     * horizontal, so the pair cannot be confused at 20dp on a bar.
     */
    val greyscale: ImageVector by lazy {
        icon("greyscale") {
            stroke { circle(12f, 12f, 8.4f) }
            fill {
                moveTo(12f, 3.6f)
                arcTo(8.4f, 8.4f, 0f, false, true, 12f, 20.4f)
                close()
            }
        }
    }

    /**
     * A page and a picture beside it: the reference pane. Lr2.
     *
     * Side by side is what the feature *is* — the panel fixated to an edge — so
     * the glyph is the arrangement rather than an object. A single framed
     * picture would be [referenceLayer], which is a different thing one button
     * away: a sheet of the drawing rather than a pane beside it.
     */
    val references: ImageVector by lazy {
        icon("references") {
            stroke {
                moveTo(2.8f, 4.4f)
                lineTo(11.0f, 4.4f)
                lineTo(11.0f, 19.6f)
                lineTo(2.8f, 19.6f)
                close()
                moveTo(13.0f, 4.4f)
                lineTo(21.2f, 4.4f)
                lineTo(21.2f, 19.6f)
                lineTo(13.0f, 19.6f)
                close()
                moveTo(13.0f, 15.4f)
                lineTo(16.2f, 11.6f)
                lineTo(18.4f, 14.2f)
                lineTo(21.2f, 11.0f)
            }
            fill { circle(16.0f, 8.2f, 1.4f) }
        }
    }

    /**
     * Three cards, fanned. Lr7.
     *
     * A deck and not a book, and the difference is the feature: a book is read
     * in order and a deck is something you pull one thing out of. Nothing in it
     * is a page number.
     */
    val deck: ImageVector by lazy {
        icon("deck") {
            stroke {
                moveTo(6.6f, 7.6f)
                lineTo(3.0f, 9.0f)
                lineTo(7.4f, 20.2f)
                lineTo(11.0f, 18.8f)
                moveTo(17.4f, 7.6f)
                lineTo(21.0f, 9.0f)
                lineTo(16.6f, 20.2f)
                lineTo(13.0f, 18.8f)
                moveTo(8.6f, 3.4f)
                lineTo(15.4f, 3.4f)
                lineTo(15.4f, 20.6f)
                lineTo(8.6f, 20.6f)
                close()
            }
        }
    }

    /**
     * A card being kept: the deck's outline with a plus on it. Lr7.
     *
     * The one button in this set whose glyph is deliberately the *other* one
     * plus a mark, because the act is "put this in there" and the thing it goes
     * into has to be recognisable in it.
     */
    val keepCard: ImageVector by lazy {
        icon("keep_card") {
            stroke {
                moveTo(4.6f, 3.4f)
                lineTo(14.0f, 3.4f)
                lineTo(14.0f, 20.6f)
                lineTo(4.6f, 20.6f)
                close()
                moveTo(18.4f, 12.6f)
                lineTo(18.4f, 20.0f)
                moveTo(14.7f, 16.3f)
                lineTo(22.1f, 16.3f)
            }
        }
    }

    /**
     * A clock with a quarter gone. Lr9.
     *
     * A clock and not a stopwatch or an hourglass, because what this is about
     * is *how long you get*, not how long you took. The missing quarter is the
     * whole idea: time running out is the feature.
     */
    val practice: ImageVector by lazy {
        icon("practice") {
            stroke {
                circle(12f, 12.6f, 8.4f)
                moveTo(12f, 7.4f)
                lineTo(12f, 12.6f)
                lineTo(16.2f, 12.6f)
                moveTo(9.0f, 2.4f)
                lineTo(15.0f, 2.4f)
            }
        }
    }

    /**
     * A page with a mirror line down it. Lr5.
     *
     * Not [flipAcross], which is the same word about a different thing: that
     * one turns the pixels you selected over, and this one turns the *view*
     * over and touches nothing. Two buttons that did the same picture would be
     * the pair a hand reaches for wrongly under pressure, which is exactly when
     * both get used.
     */
    val flipView: ImageVector by lazy {
        icon("flip_view") {
            stroke {
                moveTo(12f, 2.6f)
                lineTo(12f, 21.4f)
                moveTo(9.4f, 5.2f)
                lineTo(3.2f, 5.2f)
                lineTo(3.2f, 18.8f)
                lineTo(9.4f, 18.8f)
                moveTo(14.6f, 5.2f)
                lineTo(20.8f, 5.2f)
                lineTo(20.8f, 18.8f)
                lineTo(14.6f, 18.8f)
            }
        }
    }

    /**
     * A page, half of it solid: the squint. Lr5.
     *
     * A page rather than [greyscale]'s circle, because this is about the whole
     * drawing and that one is about a sheet of it. The shapes are deliberately
     * cousins — both are "half the colour" — and deliberately not twins.
     */
    val greyView: ImageVector by lazy {
        icon("grey_view") {
            stroke {
                moveTo(3.4f, 4.2f)
                lineTo(20.6f, 4.2f)
                lineTo(20.6f, 19.8f)
                lineTo(3.4f, 19.8f)
                close()
            }
            fill {
                moveTo(12f, 4.2f)
                lineTo(20.6f, 4.2f)
                lineTo(20.6f, 19.8f)
                lineTo(12f, 19.8f)
                close()
            }
        }
    }

    /** The hue wheel with its puck — the same two shapes [ColorWheel] draws. */
    val palette: ImageVector by lazy {
        icon("palette") {
            stroke { circle(12f, 12f, 8.6f) }
            fill { circle(15.2f, 8.8f, 2.4f) }
        }
    }

    /** Three dots getting bigger. Filled, because a 1.5 radius ring is a smudge. */
    val size: ImageVector by lazy {
        icon("size") {
            fill {
                circle(4.6f, 12f, 1.5f)
                circle(11.6f, 12f, 3.0f)
                circle(19.0f, 12f, 4.4f)
            }
        }
    }

    /** A wobble settling into a line, which is what stabilisation does. */
    val smoothing: ImageVector by lazy {
        icon("smoothing") {
            stroke {
                moveTo(2.6f, 12f)
                curveTo(4.2f, 3.8f, 6.4f, 20.4f, 8.4f, 12f)
                curveTo(10.2f, 5.6f, 12.4f, 18.6f, 14.4f, 12f)
                curveTo(16.0f, 8.4f, 18.4f, 14.2f, 21.4f, 12f)
            }
        }
    }

    /** A disc half solid and half not: the ceiling a whole stroke composites at. */
    val opacity: ImageVector by lazy {
        icon("opacity") {
            stroke { circle(12f, 12f, 8.6f) }
            fill {
                moveTo(12f, 3.4f)
                arcTo(8.6f, 8.6f, 0f, false, false, 12f, 20.6f)
                close()
            }
        }
    }

    /** A drop. Paint per dab, which is the thing that accumulates. */
    val flow: ImageVector by lazy {
        icon("flow") {
            stroke {
                moveTo(12f, 2.6f)
                curveTo(15.6f, 7.0f, 19.0f, 11.2f, 19.0f, 14.6f)
                arcTo(7.0f, 7.0f, 0f, false, true, 5.0f, 14.6f)
                curveTo(5.0f, 11.2f, 8.4f, 7.0f, 12f, 2.6f)
                close()
            }
        }
    }

    /** The paper's tooth, as the scatter of pits a pencil skips over. */
    val grain: ImageVector by lazy {
        icon("grain") {
            fill {
                circle(6.0f, 6.4f, 1.35f)
                circle(12.4f, 4.7f, 1.05f)
                circle(18.3f, 7.3f, 1.35f)
                circle(4.8f, 13.0f, 1.05f)
                circle(10.7f, 11.6f, 1.5f)
                circle(17.0f, 13.6f, 1.15f)
                circle(7.2f, 19.0f, 1.3f)
                circle(13.6f, 18.0f, 1.05f)
                circle(19.3f, 19.2f, 1.35f)
            }
        }
    }

    val undo: ImageVector by lazy {
        icon("undo") {
            stroke {
                moveTo(9.6f, 6.8f)
                lineTo(4.2f, 12.2f)
                lineTo(9.6f, 17.6f)
                moveTo(4.2f, 12.2f)
                lineTo(14.0f, 12.2f)
                // An exact semicircle: the radius is half the chord, so there is
                // no arc-fitting slack for a renderer to resolve differently.
                arcTo(4.6f, 4.6f, 0f, false, true, 14.0f, 21.4f)
                lineTo(9.6f, 21.4f)
            }
        }
    }

    /** [undo] mirrored in x, which also flips the arc's direction. */
    val redo: ImageVector by lazy {
        icon("redo") {
            stroke {
                moveTo(14.4f, 6.8f)
                lineTo(19.8f, 12.2f)
                lineTo(14.4f, 17.6f)
                moveTo(19.8f, 12.2f)
                lineTo(10.0f, 12.2f)
                arcTo(4.6f, 4.6f, 0f, false, false, 10.0f, 21.4f)
                lineTo(14.4f, 21.4f)
            }
        }
    }

    val zoomIn: ImageVector by lazy {
        icon("zoom_in") {
            stroke {
                circle(10.6f, 10.6f, 6.6f)
                moveTo(15.4f, 15.4f)
                lineTo(20.8f, 20.8f)
                moveTo(7.4f, 10.6f)
                lineTo(13.8f, 10.6f)
                moveTo(10.6f, 7.4f)
                lineTo(10.6f, 13.8f)
            }
        }
    }

    val zoomOut: ImageVector by lazy {
        icon("zoom_out") {
            stroke {
                circle(10.6f, 10.6f, 6.6f)
                moveTo(15.4f, 15.4f)
                lineTo(20.8f, 20.8f)
                moveTo(7.4f, 10.6f)
                lineTo(13.8f, 10.6f)
            }
        }
    }

    /** Four corner brackets: the drawing, brought back inside the frame. */
    val fit: ImageVector by lazy {
        icon("fit") {
            stroke {
                moveTo(3.4f, 9.2f)
                lineTo(3.4f, 3.4f)
                lineTo(9.2f, 3.4f)
                moveTo(14.8f, 3.4f)
                lineTo(20.6f, 3.4f)
                lineTo(20.6f, 9.2f)
                moveTo(20.6f, 14.8f)
                lineTo(20.6f, 20.6f)
                lineTo(14.8f, 20.6f)
                moveTo(9.2f, 20.6f)
                lineTo(3.4f, 20.6f)
                lineTo(3.4f, 14.8f)
            }
        }
    }

    val trash: ImageVector by lazy {
        icon("trash") {
            stroke {
                moveTo(3.6f, 6.4f)
                lineTo(20.4f, 6.4f)
                moveTo(9.4f, 6.4f)
                lineTo(9.4f, 3.6f)
                lineTo(14.6f, 3.6f)
                lineTo(14.6f, 6.4f)
                moveTo(5.8f, 6.4f)
                lineTo(6.9f, 20.4f)
                lineTo(17.1f, 20.4f)
                lineTo(18.2f, 6.4f)
                moveTo(10.2f, 10.4f)
                lineTo(10.2f, 16.6f)
                moveTo(13.8f, 10.4f)
                lineTo(13.8f, 16.6f)
            }
        }
    }

    /** Down into a tray. The file leaves the app; it does not go up anywhere. */
    val export: ImageVector by lazy {
        icon("export") {
            stroke {
                moveTo(12f, 3.2f)
                lineTo(12f, 15.2f)
                moveTo(7.4f, 10.6f)
                lineTo(12f, 15.2f)
                lineTo(16.6f, 10.6f)
                moveTo(3.8f, 17.4f)
                lineTo(3.8f, 20.6f)
                lineTo(20.2f, 20.6f)
                lineTo(20.2f, 17.4f)
            }
        }
    }

    val stats: ImageVector by lazy {
        icon("stats") {
            stroke {
                moveTo(4.4f, 20.2f)
                lineTo(4.4f, 13.2f)
                moveTo(9.6f, 20.2f)
                lineTo(9.6f, 8.4f)
                moveTo(14.8f, 20.2f)
                lineTo(14.8f, 15.4f)
                moveTo(20.0f, 20.2f)
                lineTo(20.0f, 4.6f)
            }
        }
    }

    /**
     * A screen divided into panes: a bar down one side, a bar across the top,
     * and the paper in the corner they leave.
     *
     * It was four arrows from a point, which is the universal *move* glyph and
     * was the user's complaint: *"the button to go in arrange mode is a 'move'
     * icon. That is not a good match."* It was not — the mode is about where
     * the toolbars are, and moving one control is the smallest thing you can do
     * in it. This says layout.
     */
    val arrange: ImageVector by lazy {
        icon("arrange") {
            stroke(1.5f) { rect(2.8f, 3.4f, 21.2f, 20.6f) }
            fill { rect(5.0f, 5.6f, 8.6f, 18.4f) }
            fill { rect(10.6f, 5.6f, 19.0f, 9.2f) }
        }
    }

    /**
     * Six dots in two columns. The one part of a toolbar that is only a handle.
     *
     * It is the conventional grip on purpose, for the reason [search] is the
     * conventional lens: a handle that has to be learned is a handle nobody
     * grabs, and this is the only way to move a toolbar without redrawing it.
     */
    val grip: ImageVector by lazy {
        icon("grip") {
            fill {
                circle(9.2f, 6.4f, 1.7f)
                circle(14.8f, 6.4f, 1.7f)
                circle(9.2f, 12.0f, 1.7f)
                circle(14.8f, 12.0f, 1.7f)
                circle(9.2f, 17.6f, 1.7f)
                circle(14.8f, 17.6f, 1.7f)
            }
        }
    }

    /**
     * Four squares in a grid: the drawings you have, seen as pictures rather
     * than as names.
     *
     * Deliberately not a folder. A folder is a place files are kept and this is
     * a wall they are hung on — and the app has no folders, no paths and no
     * file names anybody types, so a folder would be promising a thing that
     * does not exist.
     */
    val gallery: ImageVector by lazy {
        icon("gallery") {
            fill {
                rect(3.2f, 3.2f, 11.0f, 11.0f)
                rect(13.0f, 3.2f, 20.8f, 11.0f)
                rect(3.2f, 13.0f, 11.0f, 20.8f)
            }
            stroke(1.7f) { rect(13.0f, 13.0f, 20.8f, 20.8f) }
        }
    }

    /**
     * Three marks of different weights: a shelf of brushes.
     *
     * Not a brush. Every other glyph in the DRAW group is already a tool —
     * [pen] is a nib, [pencil] is a barrel and a cone, [marker] is a wedge —
     * so a fourth picture of a brush would be a fourth tool rather than the
     * list of them. What a shelf is, is *marks you can choose between*, and
     * three strokes at three weights say that in a way no single object does.
     */
    val brushes: ImageVector by lazy {
        icon("brushes") {
            stroke(1.4f) {
                moveTo(4.4f, 6.4f)
                lineTo(19.6f, 6.4f)
            }
            stroke(2.7f) {
                moveTo(4.4f, 12.0f)
                lineTo(19.6f, 12.0f)
            }
            stroke(4.4f) {
                moveTo(4.6f, 18.2f)
                lineTo(19.4f, 18.2f)
            }
        }
    }

    /**
     * A diagonal arrow with a head at each end: the corner you pull to make a
     * panel bigger.
     *
     * The conventional one, for the reason [grip] is the conventional grip. It
     * points down-right and up-left because that is the direction the handle
     * moves in — a panel is anchored by its top-left cell and grows towards the
     * bottom-right, so the glyph is also a statement about which corner stays
     * put.
     */
    val scale: ImageVector by lazy {
        icon("scale") {
            stroke(1.8f) {
                moveTo(8.2f, 8.2f)
                lineTo(18.4f, 18.4f)
                moveTo(8.2f, 13.6f)
                lineTo(8.2f, 8.2f)
                lineTo(13.6f, 8.2f)
                moveTo(18.4f, 13.0f)
                lineTo(18.4f, 18.4f)
                lineTo(13.0f, 18.4f)
            }
        }
    }

    /**
     * A screen with a bar down one side: a workspace, seen from far enough away
     * to be a shape rather than a set of buttons.
     *
     * The same frame the shape presets and the dock targets use, because it is
     * the same idea at a different scale — this is which *whole arrangement* you
     * are in, and the arrangement is what the frame shows.
     */
    val workspace: ImageVector by lazy {
        icon("workspace") {
            stroke(1.5f) { rect(2.8f, 3.6f, 21.2f, 20.4f) }
            fill { rect(5.0f, 5.8f, 8.2f, 18.2f) }
            fill { rect(10.2f, 5.8f, 19.0f, 8.0f) }
        }
    }

    /**
     * A lens with a handle. What finds a control the workspace does not offer.
     *
     * It is the one glyph in this file that is deliberately the conventional
     * one: the search box is an escape hatch, and an escape hatch has to be
     * recognised without being learned.
     */
    val search: ImageVector by lazy {
        icon("search") {
            stroke {
                circle(10.4f, 10.4f, 6.4f)
                moveTo(15.1f, 15.1f)
                lineTo(20.6f, 20.6f)
            }
        }
    }

    /** Three lines pushed up against a rule: everything, closed up. */
    val tidy: ImageVector by lazy {
        icon("tidy") {
            stroke {
                moveTo(3.6f, 4.2f)
                lineTo(20.4f, 4.2f)
                moveTo(6.0f, 9.0f)
                lineTo(18.0f, 9.0f)
                moveTo(6.0f, 13.2f)
                lineTo(15.0f, 13.2f)
                moveTo(6.0f, 17.4f)
                lineTo(11.4f, 17.4f)
                moveTo(12f, 21.6f)
                lineTo(12f, 20.0f)
            }
        }
    }

    /** A pushpin. What keeps a popup on the screen. */
    val pin: ImageVector by lazy {
        icon("pin") {
            stroke {
                moveTo(9.0f, 3.0f)
                lineTo(15.0f, 3.0f)
                moveTo(10.3f, 3.0f)
                lineTo(9.7f, 9.5f)
                lineTo(6.2f, 12.3f)
                lineTo(6.2f, 13.9f)
                lineTo(17.8f, 13.9f)
                lineTo(17.8f, 12.3f)
                lineTo(14.3f, 9.5f)
                lineTo(13.7f, 3.0f)
                moveTo(12f, 13.9f)
                lineTo(12f, 21.2f)
            }
        }
    }

    val check: ImageVector by lazy {
        icon("check") {
            stroke {
                moveTo(4.4f, 12.8f)
                lineTo(9.8f, 18.2f)
                lineTo(19.6f, 6.2f)
            }
        }
    }

    val plus: ImageVector by lazy {
        icon("plus") {
            stroke {
                moveTo(12f, 5.6f)
                lineTo(12f, 18.4f)
                moveTo(5.6f, 12f)
                lineTo(18.4f, 12f)
            }
        }
    }

    /**
     * Three offset sheets: the stack, seen from a corner.
     *
     * Not the usual pile of parallelograms. At 21dp an isometric stack is three
     * grey slivers; three rectangles offset by two units each keep their edges
     * where the eye can find them, and the front one is filled so the glyph has
     * a subject rather than being an outline of an outline.
     */
    val layers: ImageVector by lazy {
        icon("layers") {
            stroke {
                rect(3.4f, 3.4f, 16.6f, 14.6f)
                rect(6.0f, 6.0f, 19.2f, 17.2f)
            }
            fill { rect(8.6f, 8.6f, 21.8f, 19.8f) }
        }
    }

    /**
     * A dashed rectangle with a solid corner: the marquee.
     *
     * Dashes rather than a solid outline because that is what the thing itself
     * looks like on the page — a selection is drawn as marching ants, and an
     * icon that showed a plain box would be the crop tool. Drawn as eight short
     * segments rather than with a dash effect, because `ImageVector` has no
     * dash and the segments are the same eight lines either way.
     */
    val marquee: ImageVector by lazy {
        icon("marquee") {
            stroke(1.7f) {
                // top
                moveTo(3.5f, 3.5f); lineTo(8.2f, 3.5f)
                moveTo(11.4f, 3.5f); lineTo(15.8f, 3.5f)
                moveTo(19.0f, 3.5f); lineTo(20.5f, 3.5f)
                // right
                moveTo(20.5f, 3.5f); lineTo(20.5f, 5.0f)
                moveTo(20.5f, 8.2f); lineTo(20.5f, 12.6f)
                moveTo(20.5f, 15.8f); lineTo(20.5f, 20.5f)
                // bottom
                moveTo(20.5f, 20.5f); lineTo(15.8f, 20.5f)
                moveTo(12.6f, 20.5f); lineTo(8.2f, 20.5f)
                moveTo(5.0f, 20.5f); lineTo(3.5f, 20.5f)
                // left
                moveTo(3.5f, 20.5f); lineTo(3.5f, 15.8f)
                moveTo(3.5f, 12.6f); lineTo(3.5f, 8.2f)
                moveTo(3.5f, 5.0f); lineTo(3.5f, 3.5f)
            }
        }
    }

    /** An ellipse, for the round marquee. */
    val marqueeOval: ImageVector by lazy {
        icon("marquee-oval") {
            stroke { circle(12f, 12f, 8.6f) }
        }
    }

    /**
     * A lasso: an open loop with a tail, which is the free-hand marquee.
     *
     * The loop is deliberately not closed at the top — a closed loop with a
     * tail is a balloon, and an open one reads as something drawn by hand.
     */
    val marqueeLasso: ImageVector by lazy {
        icon("marquee-lasso") {
            stroke {
                moveTo(13.4f, 4.6f)
                curveTo(18.6f, 5.4f, 21.0f, 9.4f, 19.2f, 12.4f)
                curveTo(17.4f, 15.4f, 11.0f, 16.4f, 6.8f, 14.4f)
                curveTo(2.6f, 12.4f, 3.0f, 7.4f, 7.4f, 5.2f)
                curveTo(9.0f, 4.4f, 11.0f, 4.3f, 12.4f, 4.5f)
                moveTo(7.0f, 14.8f)
                lineTo(6.2f, 20.4f)
            }
        }
    }

    /**
     * The marquee's dashed box with two sliders under it: the selection
     * *panel*, as opposed to the selection *tool*.
     *
     * A separate glyph and not the same one, which is a correction. The bar
     * carried [marquee] twice — once on the toggle that makes the pen select,
     * once on the button that opens this panel — and two buttons with the same
     * face doing different things is the same defect as two buttons lit at
     * once: there is nothing to read. The box says what it is about and the
     * sliders say it is a way in rather than a tool.
     */
    val selectionPanel: ImageVector by lazy {
        icon("selection-panel") {
            // Smaller than [marquee]'s box and pushed up and left, to leave
            // the bottom two rows for the sliders.
            dashedBox(2.6f, 2.6f, 15.4f, 13.2f)
            stroke {
                moveTo(8.4f, 17.4f); lineTo(21.4f, 17.4f)
                moveTo(8.4f, 21.0f); lineTo(21.4f, 21.0f)
            }
            fill {
                circle(12.4f, 17.4f, 1.9f)
                circle(17.4f, 21.0f, 1.9f)
            }
        }
    }

    /**
     * The four combine modes, as one picture with four endings.
     *
     * Every one of them is the marquee's dashed box, so the row reads as four
     * versions of the same thing rather than four unrelated symbols, and the
     * badge in the corner is the whole of the difference. That is the shape
     * these icons have in every editor that has them, and it is the reason a
     * user who has met one before does not have to learn this one.
     *
     * They replace four words. The words were defensible — "the difference
     * between add and intersect is a sentence" — and they were also *New /
     * Add / Take / Both*, which is three sentences none of which was in the
     * user's head. The caption under each glyph now says the word an editor
     * would use, so the picture is the recognition and the word is the
     * confirmation.
     */
    val selectNew: ImageVector by lazy { combineIcon("select-new") { } }

    val selectAdd: ImageVector by lazy {
        combineIcon("select-add") {
            stroke {
                moveTo(18.2f, 14.6f); lineTo(18.2f, 21.8f)
                moveTo(14.6f, 18.2f); lineTo(21.8f, 18.2f)
            }
        }
    }

    val selectSubtract: ImageVector by lazy {
        combineIcon("select-subtract") {
            // Clear of the box on the left, where the plus's own crossbar does
            // not have to be: a bare dash that starts under the corner reads as
            // part of the frame rather than as a minus sign.
            stroke(2.2f) {
                moveTo(16.6f, 18.4f); lineTo(21.8f, 18.4f)
            }
        }
    }

    /**
     * Intersect, and the one of the four that is not a badge on a box: it is
     * the two boxes themselves with only what they share filled in, because
     * "what both cover" is a picture and no badge says it.
     */
    val selectIntersect: ImageVector by lazy {
        icon("select-intersect") {
            stroke(1.7f) {
                rect(2.8f, 2.8f, 14.4f, 14.4f)
                rect(9.6f, 9.6f, 21.2f, 21.2f)
            }
            fill { rect(9.6f, 9.6f, 14.4f, 14.4f) }
        }
    }

    /** Everything: the dashed box with the page inside it filled. */
    val selectAll: ImageVector by lazy {
        icon("select-all") {
            dashedBox(2.6f, 2.6f, 21.4f, 21.4f)
            fill { rect(6.6f, 6.6f, 17.4f, 17.4f) }
        }
    }

    /** Nothing: the same box, struck through. */
    val selectNone: ImageVector by lazy {
        icon("select-none") {
            dashedBox(2.6f, 2.6f, 21.4f, 21.4f)
            stroke {
                moveTo(6.2f, 17.8f)
                lineTo(17.8f, 6.2f)
            }
        }
    }

    /**
     * Invert: the page filled with a hole where the selection was.
     *
     * An even-odd fill and not two paths, because the hole has to be a hole —
     * a second shape painted in the background colour would be a white square
     * on a toolbar whose background the icon does not know.
     */
    val selectInvert: ImageVector by lazy {
        icon("select-invert") {
            fill(evenOdd = true) {
                rect(2.6f, 2.6f, 21.4f, 21.4f)
                rect(8.4f, 8.4f, 15.6f, 15.6f)
            }
        }
    }

    /**
     * Four arrows from a centre: pick these pixels up and move them.
     *
     * It was the word "Lift", which is what the code calls the operation and
     * not what a person calls it. What the button does to the drawing is move
     * something, so the glyph is the move glyph every application has.
     */
    val moveFloat: ImageVector by lazy {
        icon("move-float") {
            stroke {
                moveTo(12f, 3.2f); lineTo(12f, 20.8f)
                moveTo(3.2f, 12f); lineTo(20.8f, 12f)
                moveTo(8.8f, 6.4f); lineTo(12f, 3.2f); lineTo(15.2f, 6.4f)
                moveTo(8.8f, 17.6f); lineTo(12f, 20.8f); lineTo(15.2f, 17.6f)
                moveTo(6.4f, 8.8f); lineTo(3.2f, 12f); lineTo(6.4f, 15.2f)
                moveTo(17.6f, 8.8f); lineTo(20.8f, 12f); lineTo(17.6f, 15.2f)
            }
        }
    }

    /** The same act on the whole sheet: a stack of pages with the arrows on it. */
    val moveSheet: ImageVector by lazy {
        icon("move-sheet") {
            stroke {
                rect(3.0f, 3.0f, 14.2f, 14.2f)
                moveTo(17.6f, 6.6f); lineTo(21.0f, 6.6f); lineTo(21.0f, 21.0f)
                lineTo(6.6f, 21.0f); lineTo(6.6f, 17.6f)
            }
            fill { circle(8.6f, 8.6f, 1.8f) }
        }
    }

    /**
     * Down into a tray: put the pixels back on the sheet.
     *
     * It was "Drop", and the user's own word for it is paste. The arrow going
     * into a container is what paste looks like everywhere, and it is also
     * literally what the operation does — the floating pixels stop floating
     * and land on the layer.
     */
    val dropFloat: ImageVector by lazy {
        icon("drop-float") {
            stroke {
                moveTo(12f, 2.8f); lineTo(12f, 14.4f)
                moveTo(7.6f, 10.0f); lineTo(12f, 14.4f); lineTo(16.4f, 10.0f)
                moveTo(3.4f, 14.6f); lineTo(3.4f, 20.6f); lineTo(20.6f, 20.6f); lineTo(20.6f, 14.6f)
            }
        }
    }

    /**
     * The selection staying put, and a copy of it lifted off.
     *
     * Dashed for the thing left behind and solid for the thing in your hand,
     * which is the same vocabulary the marquee and the transform box already
     * use: ants mean a region, a solid frame means pixels that are moving.
     */
    val copyFloat: ImageVector by lazy {
        icon("copy-float") {
            dashedBox(2.6f, 2.6f, 13.6f, 13.6f)
            stroke { rect(9.6f, 9.6f, 21.4f, 21.4f) }
        }
    }

    /**
     * Mirror left to right: an axis with a solid arrow one side and a hollow
     * one the other.
     *
     * Solid and hollow rather than two identical shapes, because two identical
     * shapes about a line is a picture of *symmetry* and this is a picture of
     * one thing **becoming** its reflection. [flipDown] is the same glyph
     * turned a quarter, which is the point: they are one idea on two axes.
     */
    val flipAcross: ImageVector by lazy {
        icon("flip-across") {
            stroke { moveTo(12f, 2.4f); lineTo(12f, 21.6f) }
            fill {
                moveTo(9.4f, 5.6f); lineTo(9.4f, 18.4f); lineTo(2.8f, 12f); close()
            }
            stroke {
                moveTo(14.6f, 5.6f); lineTo(14.6f, 18.4f); lineTo(21.2f, 12f); close()
            }
        }
    }

    /** Mirror top to bottom. [flipAcross]'s glyph, turned a quarter. */
    val flipDown: ImageVector by lazy {
        icon("flip-down") {
            stroke { moveTo(2.4f, 12f); lineTo(21.6f, 12f) }
            fill {
                moveTo(5.6f, 9.4f); lineTo(18.4f, 9.4f); lineTo(12f, 2.8f); close()
            }
            stroke {
                moveTo(5.6f, 14.6f); lineTo(18.4f, 14.6f); lineTo(12f, 21.2f); close()
            }
        }
    }

    /** An eye. Shown on a sheet that is visible; [hidden] is its other face. */
    val visible: ImageVector by lazy {
        icon("visible") {
            stroke {
                moveTo(2.6f, 12f)
                curveTo(6.0f, 6.4f, 18.0f, 6.4f, 21.4f, 12f)
                curveTo(18.0f, 17.6f, 6.0f, 17.6f, 2.6f, 12f)
                close()
            }
            fill { circle(12f, 12f, 2.9f) }
        }
    }

    /**
     * The same eye with a line through it.
     *
     * A struck-through icon rather than a dimmed one: the row it sits on is
     * already dimmed when the sheet is hidden, and two ways of saying the same
     * thing leaves neither of them legible.
     */
    val hidden: ImageVector by lazy {
        icon("hidden") {
            stroke {
                moveTo(2.6f, 12f)
                curveTo(6.0f, 6.4f, 18.0f, 6.4f, 21.4f, 12f)
                curveTo(18.0f, 17.6f, 6.0f, 17.6f, 2.6f, 12f)
                close()
                moveTo(4.2f, 20.4f)
                lineTo(19.8f, 3.6f)
            }
        }
    }

    /** A plus. New sheet. */
    val add: ImageVector by lazy {
        icon("add") {
            stroke {
                moveTo(12f, 5.2f)
                lineTo(12f, 18.8f)
                moveTo(5.2f, 12f)
                lineTo(18.8f, 12f)
            }
        }
    }

    /**
     * A sheet with a stroke across it: a layer that keeps what was drawn on it.
     *
     * A plus in the corner rather than a second glyph, so that it reads as
     * "new, of this kind" beside [add] rather than as a different action.
     */
    val addVector: ImageVector by lazy {
        icon("add-vector") {
            stroke {
                rect(3.2f, 3.2f, 16.4f, 20.8f)
                moveTo(6.4f, 16.8f)
                curveTo(9.2f, 8.4f, 12.4f, 18.4f, 15.2f, 9.6f)
                moveTo(19.6f, 3.6f)
                lineTo(19.6f, 10.4f)
                moveTo(16.2f, 7f)
                lineTo(23f, 7f)
            }
        }
    }

    /**
     * A ruler lying across the page, with a tick on it. Ik13's guides.
     *
     * A *drawn* ruler and not a set-square or a grid, because the thing it
     * opens is a list of lines you lay on the page: the picture and the noun
     * are the same object, which is the one thing that stops an icon needing a
     * caption.
     */
    val ruler: ImageVector by lazy {
        icon("ruler") {
            stroke {
                moveTo(2.4f, 15.2f)
                lineTo(15.2f, 2.4f)
                lineTo(21.6f, 8.8f)
                lineTo(8.8f, 21.6f)
                close()
                moveTo(6.6f, 11f)
                lineTo(9.2f, 13.6f)
                moveTo(10.2f, 7.4f)
                lineTo(12.8f, 10f)
                moveTo(13.8f, 3.8f)
                lineTo(16.4f, 6.4f)
            }
        }
    }

    /**
     * The same ruler with a plus beside it. Making one.
     *
     * Shares [addVector]'s plus, deliberately: two "add a thing of this kind"
     * buttons in one app that draw their plus differently are two buttons
     * nobody reads as a pair.
     */
    val guides: ImageVector by lazy {
        icon("guides") {
            stroke {
                moveTo(2.4f, 21.6f)
                lineTo(21.6f, 2.4f)
                moveTo(2.4f, 13.6f)
                lineTo(13.6f, 2.4f)
                moveTo(10.4f, 21.6f)
                lineTo(21.6f, 10.4f)
            }
        }
    }

    /**
     * Rays converging on a point off to one side. Ik15's perspective.
     *
     * Converging and not a box in perspective, because what the button makes is
     * a *set of rays* and not a drawing: the picture is the tool, which is the
     * same rule [ruler] follows.
     */
    val perspective: ImageVector by lazy {
        icon("perspective") {
            stroke {
                moveTo(2.4f, 12f)
                lineTo(21.6f, 12f)
                moveTo(2.4f, 3.6f)
                lineTo(21.6f, 11.2f)
                moveTo(2.4f, 20.4f)
                lineTo(21.6f, 12.8f)
                moveTo(2.4f, 7.8f)
                lineTo(21.6f, 11.6f)
                moveTo(2.4f, 16.2f)
                lineTo(21.6f, 12.4f)
            }
        }
    }

    /**
     * Three lines at the isometric angles, meeting at a point. Item 19.
     *
     * The corner of a cube, which is the one thing everybody draws on an
     * isometric grid and the shape the three angles make on their own.
     */
    val isometric: ImageVector by lazy {
        icon("isometric") {
            stroke {
                moveTo(12f, 12f)
                lineTo(12f, 22f)
                moveTo(12f, 12f)
                lineTo(3.4f, 7f)
                moveTo(12f, 12f)
                lineTo(20.6f, 7f)
                moveTo(3.4f, 7f)
                lineTo(12f, 2f)
                lineTo(20.6f, 7f)
            }
        }
    }

    /**
     * A circle with a bowed cross in it. Five-point curvilinear perspective.
     *
     * The field of view and one arc of each family, which is the smallest
     * picture that says "the straight lines bend" — and bending is the whole of
     * what tells a fisheye apart from the other perspective.
     */
    val fisheye: ImageVector by lazy {
        icon("fisheye") {
            stroke {
                circle(12f, 12f, 9.6f)
                moveTo(2.4f, 12f)
                curveTo(8f, 7.2f, 16f, 7.2f, 21.6f, 12f)
                moveTo(12f, 2.4f)
                curveTo(7.2f, 8f, 7.2f, 16f, 12f, 21.6f)
            }
        }
    }

    /** Two sheets, one behind the other. Duplicate. */
    val duplicate: ImageVector by lazy {
        icon("duplicate") {
            stroke {
                rect(3.4f, 3.4f, 15.0f, 15.0f)
                rect(9.0f, 9.0f, 20.6f, 20.6f)
            }
        }
    }

    /** A chevron up, for moving a sheet one place toward the top. */
    val up: ImageVector by lazy {
        icon("up") {
            stroke {
                moveTo(5.6f, 14.8f)
                lineTo(12f, 8.4f)
                lineTo(18.4f, 14.8f)
            }
        }
    }

    /** See [up]. */
    val down: ImageVector by lazy {
        icon("down") {
            stroke {
                moveTo(5.6f, 9.2f)
                lineTo(12f, 15.6f)
                lineTo(18.4f, 9.2f)
            }
        }
    }

    /**
     * A picture frame with a horizon and a sun, and an arrow going **into** it.
     *
     * The mirror of [export], which is the same arrow the other way out of the
     * same shape. Two buttons that differ only in the direction of an arrow are
     * a pair the eye learns once; a photograph glyph beside a download glyph is
     * two unrelated ideas.
     */
    val import_: ImageVector by lazy {
        icon("import") {
            stroke {
                rect(3.4f, 5.6f, 20.6f, 18.4f)
                moveTo(3.4f, 15.0f)
                lineTo(9.0f, 10.2f)
                lineTo(14.4f, 15.0f)
                moveTo(12.0f, 1.2f)
                lineTo(12.0f, 8.4f)
                moveTo(9.2f, 5.8f)
                lineTo(12.0f, 8.6f)
                lineTo(14.8f, 5.8f)
            }
            fill { circle(16.2f, 9.6f, 1.7f) }
        }
    }

    val close: ImageVector by lazy {
        icon("close") {
            stroke {
                moveTo(6.2f, 6.2f)
                lineTo(17.8f, 17.8f)
                moveTo(17.8f, 6.2f)
                lineTo(6.2f, 17.8f)
            }
        }
    }

    // ---- the builder ------------------------------------------------------

    private fun icon(name: String, block: IconScope.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).also { IconScope(it).block() }.build()

    /**
     * One of the four combine modes: the marquee's dashed box with [badge]
     * drawn into the corner it leaves free. See [selectNew].
     */
    private fun combineIcon(name: String, badge: IconScope.() -> Unit): ImageVector =
        icon(name) {
            dashedBox(2.6f, 2.6f, 15.8f, 15.8f)
            badge()
        }

    private class IconScope(private val builder: ImageVector.Builder) {
        fun stroke(width: Float = STROKE, path: PathBuilder.() -> Unit) {
            builder.addPath(
                pathData = PathData(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        /**
         * [evenOdd] is what makes a hole a hole. A second shape painted in the
         * background colour would be a white square on a toolbar whose colour
         * the icon does not know — see [selectInvert].
         */
        fun fill(evenOdd: Boolean = false, path: PathBuilder.() -> Unit) {
            builder.addPath(
                pathData = PathData(path),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = SolidColor(Color.Black),
            )
        }

        /**
         * A box drawn as marching ants: three segments a side, thinner than
         * [STROKE] so that twelve short lines do not read as a solid frame.
         *
         * The same eight-lines-and-no-dash-effect trick [marquee] uses, made
         * shared once a second icon wanted it — `ImageVector` has no dash, and
         * a box is a box wherever it is drawn.
         */
        fun dashedBox(l: Float, t: Float, r: Float, b: Float) {
            val w = (r - l) / 6f
            val h = (b - t) / 6f
            stroke(1.7f) {
                moveTo(l, t); lineTo(l + 2 * w, t)
                moveTo(l + 3 * w, t); lineTo(l + 5 * w, t)
                moveTo(l + 5.6f * w, t); lineTo(r, t)
                moveTo(r, t); lineTo(r, t + 2 * h)
                moveTo(r, t + 3 * h); lineTo(r, t + 5 * h)
                moveTo(r, t + 5.6f * h); lineTo(r, b)
                moveTo(r, b); lineTo(l + 4 * w, b)
                moveTo(l + 3 * w, b); lineTo(l + w, b)
                moveTo(l + 0.4f * w, b); lineTo(l, b)
                moveTo(l, b); lineTo(l, t + 4 * h)
                moveTo(l, t + 3 * h); lineTo(l, t + h)
                moveTo(l, t + 0.4f * h); lineTo(l, t)
            }
        }
    }

    /**
     * A full circle as two semicircular arcs.
     *
     * Two and not one because an arc whose start and end are the same point has
     * no defined sweep — it is either nothing or everything, and which one you
     * get is the renderer's opinion rather than the path's.
     */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        close()
    }

    private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
        moveTo(l, t)
        lineTo(r, t)
        lineTo(r, b)
        lineTo(l, b)
        close()
    }

    /**
     * 1.9 at a 24 viewport is a little under 2dp at 24dp, which is the weight
     * that survives being drawn at 20dp on a translucent bar. Thinner reads as
     * grey; thicker closes the gap inside the pencil's ferrule.
     */
    private const val STROKE = 1.9f
}
