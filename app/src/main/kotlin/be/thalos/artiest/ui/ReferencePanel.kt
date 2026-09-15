package be.thalos.artiest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import be.thalos.artiest.model.ModelStage
import be.thalos.artiest.ref.RefKind
import be.thalos.artiest.ref.RefPicture
import kotlin.math.roundToInt

/**
 * How the picture sits in the pane: the zoom, where it has been dragged to, how
 * far it has been turned, and the two view switches.
 *
 * ## Why this is not five `remember`s inside the pane
 *
 * It was, and the tablet found the hole in one move: **go into arrange mode and
 * come back, and the zoom is gone.** Arranging rebuilds the bars, so the panel
 * leaves the composition and comes back a different instance — and everything
 * it remembered went with it. The same thing happens, less visibly, when the
 * pane is fixated to an edge, when it is moved from one bar to another, and
 * when its popup is closed and reopened.
 *
 * None of those are things the user did to the picture. A hand that has spent
 * ten seconds framing a nose at four times life size and then straightens a
 * toolbar has not asked for the nose back at arm's length.
 *
 * So the state is hoisted to `LearnerState`, which lives for as long as the
 * screen does, and the pane is handed it. There is exactly one pane, so there
 * is exactly one of these.
 *
 * ## What still resets, and when
 *
 * Picking a **different picture** resets all of it — see [reset], called from
 * `rememberLearner`. A zoom that belonged to the last photograph is a zoom
 * nobody asked for, and that rule is the reason the state was keyed on the
 * selection in the first place. Hoisting keeps the rule and drops the accident.
 */
@Stable
class PaneView {

    /** Multiplied onto the fit, so 1 is the whole picture in the pane. */
    var scale by mutableFloatStateOf(1f)

    /** Where it has been dragged, in pane pixels, from the middle. */
    var offset by mutableStateOf(Offset.Zero)

    /** Degrees, clockwise. */
    var turn by mutableFloatStateOf(0f)

    var flipped by mutableStateOf(false)

    var grey by mutableStateOf(false)

    // ---- and the same pane, when what is in it is a model --------------------

    /**
     * Lr12. Degrees around the model, degrees above it, and how close.
     *
     * Three numbers and not a camera, for exactly the reason the five above are
     * three numbers and not a matrix: this has to survive the panel being
     * rearranged, and what survives is what lives out here. A camera belongs to
     * a renderer and the renderer belongs to a surface that comes and goes.
     *
     * [dolly] multiplies the framing distance, so 1 is the whole model in the
     * pane and bigger is closer in. It is the same idea [scale] is for a
     * picture and it is clamped in the same spirit.
     */
    var spin by mutableFloatStateOf(24f)
    var tilt by mutableFloatStateOf(8f)
    var dolly by mutableFloatStateOf(1f)

    /** Where the key light stands, in the same two angles. */
    var lightAzimuth by mutableFloatStateOf(-35f)
    var lightElevation by mutableFloatStateOf(34f)

    /**
     * Whether a drag moves the light instead of the model.
     *
     * A switch and not a modifier key, because there is no keyboard, and not a
     * second finger, because the second finger already means *closer*. It is
     * the pane's own button and it lights up while it is on, the way Flip and
     * Grey do.
     */
    var lighting by mutableStateOf(false)

    /**
     * Grey stone instead of whatever the model was scanned wearing.
     *
     * A plaster cast, in other words, and for the reason a life room owns a
     * shelf of them: the scan's own colour is information about marble and dust
     * and the light it was photographed in, and none of that is the form. Grey
     * takes it away and leaves the planes.
     *
     * It starts **on** for a model that brought no surface of its own — see
     * `wearsItsOwnSurface`. Those arrive white, and white is the one value form
     * does not show in.
     */
    var stone by mutableStateOf(false)

    /**
     * Cross-contour lines over the form: about twenty rings up it and twenty
     * across it.
     *
     * The thing an artist means by *the wireframe*, and not the mesh's own
     * edges — a scan has a hundred thousand triangles arranged by a camera
     * rather than by a draughtsman, and one edge in twenty of that is noise.
     * These are slices, and they are the lines a hatch should follow: hatching
     * along them describes a volume, hatching across them describes a stain.
     *
     * Drawn on plain grey, always, because that is the picture that teaches —
     * which is why turning this on turns [stone] on with it.
     */
    var contour by mutableStateOf(false)

    /**
     * How many rings up the form, and as many across it.
     *
     * Twenty is where it started, because that is the number the request came
     * with and it is a good default — few enough to count and copy onto paper,
     * many enough to describe the turn of a cheek rather than the turn of a
     * head. But the right number is not a property of the app, it is a property
     * of what is being studied and how closely: a whole figure wants fewer, an
     * ear wants more. So it is a slider, and it lives here with the rest of the
     * pose so that it survives the panel being rearranged.
     */
    var slices by mutableFloatStateOf(DEFAULT_SLICES)

    /**
     * Back to the whole picture, square, in the middle.
     *
     * What the Fit button does — and it deliberately leaves [flipped] and
     * [grey] alone, because those two are not a *view of* the picture, they are
     * how the artist has asked to see it. Someone drawing from a mirrored
     * reference wants it mirrored after they have framed a different part of
     * it.
     */
    fun fit() {
        scale = 1f
        offset = Offset.Zero
        turn = 0f
        spin = 24f
        tilt = 8f
        dolly = 1f
    }

    /** [fit], and the two switches off as well. What a new picture gets. */
    fun reset() {
        fit()
        flipped = false
        grey = false
        lighting = false
        stone = false
        contour = false
        slices = DEFAULT_SLICES
        lightAzimuth = -35f
        lightElevation = 34f
    }

    /**
     * A drag of [dx], [dy] pane pixels, into whichever of the two it is moving.
     *
     * The model and the light are turned by different arithmetic on purpose.
     * The model is turned the way a model on a turntable turns — a drag across
     * spins it about its own upright axis and a drag down tips it — because
     * that is what the hand expects of an object standing on a table, and it
     * keeps the model the right way up without anybody having to work at it.
     *
     * The light has no up. It is somewhere on a sphere around the model and it
     * has to be able to get anywhere on that sphere, including over the top and
     * round the back, so it is turned by an arcball instead: see [turnedLight],
     * and see the tablet report that is written down there.
     */
    fun dragged(dx: Float, dy: Float) {
        if (lighting) {
            val (azimuth, elevation) =
                turnedLight(lightAzimuth, lightElevation, dx, dy, spin, tilt)
            lightAzimuth = azimuth
            lightElevation = elevation
        } else {
            spin -= dx * SPIN_PER_PX
            tilt = (tilt + dy * SPIN_PER_PX).coerceIn(-85f, 85f)
        }
    }

    /** A pinch of [zoom], as a ratio. Clamped so a model cannot be lost. */
    fun pinched(zoom: Float) {
        dolly = (dolly * zoom).coerceIn(MIN_DOLLY, MAX_DOLLY)
    }

    companion object {
        const val DEFAULT_SLICES = 20f

        /**
         * Four lines is a box and a hundred and twenty is a mesh.
         *
         * The bottom is where the grid stops describing a surface and starts
         * being a shape of its own; the top is where the app stops drawing
         * anyway, because the shader fades the lines out once one pixel spans
         * half a slice rather than showing moire.
         */
        const val MIN_SLICES = 4f
        const val MAX_SLICES = 120f
    }
}

/**
 * The reference pane: a picture to draw from, beside the drawing.
 *
 * Lr2. A **panel** in the sense the layers panel and the brush shelf are
 * panels — it opens from a toolbar button, it can be dragged to an edge and
 * fixated there, and it can be resized. *Side by side* is therefore not a
 * special layout and not a new kind of window; it is this panel fixated to the
 * right edge, which the workspace system has done since U6.
 *
 * ## The pen picks, the fingers move
 *
 * The one thing every Clip Studio user names about its Sub View is that the pen
 * over the pane is an eyedropper, with no button and no mode. That is what this
 * does, and the split is by **pointer type**: a stylus picks a colour, fingers
 * pan, pinch and twist the picture. Nothing has to be switched, so nothing can
 * be left switched on.
 *
 * The gesture vocabulary is the canvas's, deliberately — one finger drags, two
 * fingers zoom and turn — because a hand should not have to learn a second one
 * for a panel six inches away from the first.
 *
 * ## What it does not do
 *
 * It never touches the drawing. There is no "send to canvas", no tracing
 * overlay and no transform that leaves this rectangle. A picture that should be
 * *under* the drawing is an import — `PictureImporter` puts it on a sheet of
 * its own — and that is a different feature with a different undo story.
 */
@Composable
fun ReferenceBody(
    pictures: List<RefPicture>,
    selected: String?,
    onSelect: (String) -> Unit,
    /** The pixels of [selected], or null while they are being read. */
    bitmap: android.graphics.Bitmap?,
    /** A small one per picture, for the strip. Missing ones draw as a frame. */
    thumbnails: Map<String, android.graphics.Bitmap>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    /** A colour the pen took off the picture. */
    onInk: (Int) -> Unit,
    /** How the picture sits in the pane. Held outside; see [PaneView]. */
    pane: PaneView,
    /** Lr12. The renderer, which outlives this panel. See [ModelStage]. */
    stage: ModelStage,
    /** The bytes of [selected] when it is a model, and null when it is not. */
    glb: ByteArray?,
    /** Whether [selected] still needs a face for the strip. */
    wantPoster: Boolean,
    onPoster: (String, android.graphics.Bitmap) -> Unit,
    onAddModel: () -> Unit,
    modifier: Modifier = Modifier,
    pictureHeight: Dp = PICTURE_HEIGHT,
    trailing: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme

    // What is in the pane decides which half of it draws and which buttons sit
    // under it. Nothing else in this panel branches on the kind: the strip, the
    // selecting, the delete and the tags are the same for a mesh as for a
    // photograph, which is the point of there being one library.
    val current = pictures.firstOrNull { it.id == selected }
    val isModel = current?.kind == RefKind.MODEL

    // Named locals over the holder's fields, so the gesture arithmetic below
    // reads as it did when these were five `remember`s -- `scale` and not
    // `pane.scale` in an expression that already has six terms in it.
    var scale by pane::scale
    var offset by pane::offset
    var turn by pane::turn
    var flipped by pane::flipped
    var grey by pane::grey

    // The colour under the nib while a pick is in the hand, and where. Zero is
    // "no pick", for `PickRing`'s reason: this is read every frame of a drag
    // and a boxed Int per frame is a boxed Int per frame.
    var picked by remember { mutableStateOf(0) }
    var pickAt by remember { mutableStateOf(Offset.Unspecified) }

    Column(modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        ) {
            Text(
                "Reference",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing()
        }

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(pictureHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(scheme.surfaceContainerHighest),
        ) {
            if (isModel) {
                ModelPane(
                    stage = stage,
                    glb = glb,
                    modelId = selected,
                    pane = pane,
                    onPoster = { shot -> selected?.let { onPoster(it, shot) } },
                    wantPoster = wantPoster,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (bitmap == null) {
                Text(
                    if (pictures.isEmpty()) "Nothing here yet" else "…",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val boxW = with(LocalDensity.current) { maxWidth.toPx() }
                    val boxH = with(LocalDensity.current) { maxHeight.toPx() }
                    // The fit is recomputed rather than stored, because the box
                    // changes size whenever the card is resized and a stored
                    // one would leave the picture at the last card's scale.
                    val fit = minOf(boxW / bitmap.width, boxH / bitmap.height)
                    val filter = remember(grey) {
                        if (grey) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                        else null
                    }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        colorFilter = filter,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // Scale, then rotate, then translate, about the
                                // middle of the pane. `paneToPicture` undoes
                                // exactly this and in exactly this order; the
                                // two are one mapping written twice and the
                                // only thing holding them together is that
                                // sentence.
                                scaleX = scale * if (flipped) -1f else 1f
                                scaleY = scale
                                rotationZ = turn
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                    // Over the picture and under nothing: the ring is the answer
                    // to a question being asked right now.
                    PaneRing(pickAt, picked)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(bitmap, fit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (down.type == PointerType.Stylus) {
                                        pick(
                                            down, bitmap, fit, scale, offset, turn, flipped,
                                            size.width.toFloat(), size.height.toFloat(),
                                            onMove = { at, argb ->
                                                pickAt = at
                                                picked = argb
                                            },
                                            onDone = { argb ->
                                                pickAt = Offset.Unspecified
                                                picked = 0
                                                if (argb != 0) onInk(argb)
                                            },
                                        )
                                    } else {
                                        move(down) { centroid, pan, zoom, rotate ->
                                            // Anchored on the fingers, not on
                                            // the middle of the pane. See
                                            // `anchored` for the arithmetic and
                                            // for why the obvious version feels
                                            // broken.
                                            val next =
                                                (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                            offset = anchored(
                                                offset = offset,
                                                centroid = centroid,
                                                centre = Offset(boxW / 2f, boxH / 2f),
                                                pan = pan,
                                                zoom = next / scale,
                                                turn = rotate,
                                            )
                                            scale = next
                                            turn += rotate
                                        }
                                    }
                                }
                            },
                    )
                }
            }
        }

        if (isModel && pane.contour) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 2.dp),
            ) {
                Icon(
                    ToolIcons.contour,
                    "How many lines",
                    Modifier.size(15.dp),
                    scheme.onSurfaceVariant,
                )
                Slider(
                    value = pane.slices,
                    onValueChange = { pane.slices = it },
                    valueRange = PaneView.MIN_SLICES..PaneView.MAX_SLICES,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    pane.slices.roundToInt().toString(),
                    fontSize = 11.sp,
                    color = scheme.onSurface,
                    modifier = Modifier.width(24.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Six buttons is what fits across a 320dp panel: 40dp each and 6dp
        // between them is 270, and a seventh would be 316 in a 300dp row. So a
        // picture and a model get different middles. Greyscale is the one that
        // does not survive the split, and it is the right one to lose — stone
        // and contour have already taken the colour out, and what it was for
        // was judging the values in a photograph.
        val showing = isModel || bitmap != null
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            AddAction(onAdd = onAdd, onAddModel = onAddModel)
            PaneAction(
                ToolIcons.fit, "Fit", showing,
                onClick = { pane.fit() },
            )
            if (isModel) {
                PaneAction(
                    ToolIcons.light, "Move the light", true, lit = pane.lighting,
                    onClick = { pane.lighting = !pane.lighting },
                )
                PaneAction(
                    ToolIcons.stone, "Grey stone", true, lit = pane.stone,
                    // Stone off takes the lines with it: the lines are drawn on
                    // the grey and there is nothing for them to be drawn on
                    // once the scan's own surface is back.
                    onClick = {
                        pane.stone = !pane.stone
                        if (!pane.stone) pane.contour = false
                    },
                )
                PaneAction(
                    ToolIcons.contour, "Contour lines", true, lit = pane.contour,
                    onClick = {
                        pane.contour = !pane.contour
                        if (pane.contour) pane.stone = true
                    },
                )
            } else {
                PaneAction(
                    ToolIcons.flipAcross, "Flip", bitmap != null, lit = flipped,
                    onClick = { flipped = !flipped },
                )
                PaneAction(
                    ToolIcons.greyscale, "Take the colour out", showing, lit = grey,
                    onClick = { grey = !grey },
                )
            }
            Spacer(Modifier.weight(1f))
            PaneAction(
                ToolIcons.trash, "Remove", selected != null,
                onClick = { selected?.let(onRemove) },
            )
        }

        if (pictures.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = STRIP_HEIGHT)
                    .horizontalScroll(rememberScrollState()),
            ) {
                for (p in pictures) {
                    val thumb = thumbnails[p.id]
                    Box(
                        Modifier
                            .size(STRIP_THUMB_W, STRIP_HEIGHT)
                            .clip(RoundedCornerShape(6.dp))
                            .background(scheme.surfaceContainerHighest)
                            .border(
                                if (p.id == selected) 2.dp else 1.dp,
                                if (p.id == selected) scheme.primary else scheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable { onSelect(p.id) },
                    ) {
                        if (thumb != null && !thumb.isRecycled) {
                            Image(
                                bitmap = remember(thumb) { thumb.asImageBitmap() },
                                contentDescription = p.label,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else if (p.kind == RefKind.MODEL) {
                            // A model that has never been looked at has no
                            // poster yet, and an empty frame in the strip reads
                            // as a picture that failed rather than as a thing
                            // waiting to be opened.
                            Icon(
                                ToolIcons.cube,
                                p.label,
                                Modifier.size(22.dp).align(Alignment.Center),
                                scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The toolbar button, and the pane it opens.
 *
 * `ColourButton`'s shape exactly: the button keeps the open flag and the place
 * it was opened from, so fixating can put the kept card *beside it* rather than
 * somewhere clever. See `DropMath.cellBeside`.
 */
@Composable
fun ReferenceButton(
    pictures: List<RefPicture>,
    selected: String?,
    onSelect: (String) -> Unit,
    bitmap: android.graphics.Bitmap?,
    thumbnails: Map<String, android.graphics.Bitmap>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onInk: (Int) -> Unit,
    pane: PaneView,
    stage: ModelStage,
    glb: ByteArray?,
    wantPoster: Boolean,
    onPoster: (String, android.graphics.Bitmap) -> Unit,
    onAddModel: () -> Unit,
    onFixate: (Cell) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(Offset.Zero) }
    val view = androidx.compose.ui.platform.LocalView.current
    val slotPx = with(LocalDensity.current) { Chrome.SLOT.toPx() }

    Box(
        Modifier.fillMaxSize().onGloballyPositioned { here = it.positionInRoot() },
    ) {
        IconToolButton(
            icon = ToolIcons.references,
            label = "Reference",
            onClick = { open = true },
            selected = open,
        )
        if (open) {
            ReferencePanelPopup(
                pictures = pictures,
                selected = selected,
                onSelect = onSelect,
                bitmap = bitmap,
                thumbnails = thumbnails,
                onAdd = onAdd,
                onRemove = onRemove,
                onInk = onInk,
                pane = pane,
                stage = stage,
                glb = glb,
                wantPoster = wantPoster,
                onPoster = onPoster,
                onAddModel = onAddModel,
                onDismiss = { open = false },
                onFixate = {
                    open = false
                    onFixate(DropMath.cellBeside(here, view.width, view.height, slotPx))
                },
            )
        }
    }
}

/**
 * The same pane, in a popup, opened from a toolbar button.
 *
 * Focusable, or an outside tap is a stray touch on the canvas and the panel
 * stays open under the mark it just made. See `ColourPanel`.
 */
@Composable
fun ReferencePanelPopup(
    pictures: List<RefPicture>,
    selected: String?,
    onSelect: (String) -> Unit,
    bitmap: android.graphics.Bitmap?,
    thumbnails: Map<String, android.graphics.Bitmap>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onInk: (Int) -> Unit,
    pane: PaneView,
    stage: ModelStage,
    glb: ByteArray?,
    wantPoster: Boolean,
    onPoster: (String, android.graphics.Bitmap) -> Unit,
    onAddModel: () -> Unit,
    onDismiss: () -> Unit,
    onFixate: () -> Unit,
) {
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
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
            ReferenceBody(
                pictures = pictures,
                selected = selected,
                onSelect = onSelect,
                bitmap = bitmap,
                thumbnails = thumbnails,
                onAdd = onAdd,
                onRemove = onRemove,
                onInk = onInk,
                pane = pane,
                stage = stage,
                glb = glb,
                wantPoster = wantPoster,
                onPoster = onPoster,
                onAddModel = onAddModel,
            ) {
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
        }
    }
}

/**
 * The same pane, as a control on a bar — which is what *side by side* is.
 *
 * The picture takes whatever height is left after the header, the buttons and
 * the strip, so a taller bar is a bigger picture rather than more empty card.
 */
@Composable
fun ReferencePanelCard(
    pictures: List<RefPicture>,
    selected: String?,
    onSelect: (String) -> Unit,
    bitmap: android.graphics.Bitmap?,
    thumbnails: Map<String, android.graphics.Bitmap>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onInk: (Int) -> Unit,
    pane: PaneView,
    stage: ModelStage,
    glb: ByteArray?,
    wantPoster: Boolean,
    onPoster: (String, android.graphics.Bitmap) -> Unit,
    onAddModel: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val extra = if (pane.contour) SLICE_ROW else 0.dp
        val forPicture = (maxHeight - CARD_FURNITURE - extra).coerceAtLeast(MIN_PICTURE)
        ReferenceBody(
            pictures = pictures,
            selected = selected,
            onSelect = onSelect,
            bitmap = bitmap,
            thumbnails = thumbnails,
            onAdd = onAdd,
            onRemove = onRemove,
            onInk = onInk,
            pane = pane,
            stage = stage,
            glb = glb,
            wantPoster = wantPoster,
            onPoster = onPoster,
            onAddModel = onAddModel,
            modifier = Modifier.fillMaxSize(),
            pictureHeight = forPicture,
        )
    }
}

/**
 * The Add button, and the two things it can add.
 *
 * A menu and not a long press. A long press is the repository's idiom for a
 * *variation* on what the tap does — the colour picker's hold-to-stay — and
 * adding a 3D model is not a variation on adding a photograph; it is the other
 * half of what this library holds. A button whose second half nobody can find
 * is a feature nobody has.
 */
@Composable
private fun AddAction(onAdd: () -> Unit, onAddModel: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PaneAction(ToolIcons.add, "Add", true, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Picture…", fontSize = 13.sp) },
                leadingIcon = { Icon(ToolIcons.references, null, Modifier.size(17.dp)) },
                onClick = { open = false; onAdd() },
            )
            DropdownMenuItem(
                text = { Text("3D model…", fontSize = 13.sp) },
                leadingIcon = { Icon(ToolIcons.cube, null, Modifier.size(17.dp)) },
                onClick = { open = false; onAddModel() },
            )
        }
    }
}

/** A 36dp button for the pane's own row, lit when it names a state. */
@Composable
private fun PaneAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    lit: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 40.dp, height = 34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (lit) scheme.primary else scheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            icon,
            label,
            Modifier.size(18.dp),
            tint = when {
                lit -> scheme.onPrimary
                enabled -> scheme.onSurface
                else -> scheme.onSurface.copy(alpha = 0.25f)
            },
        )
    }
}

/** The split ring, inside the pane. See `PickRing` for why it is two halves. */
@Composable
private fun PaneRing(at: Offset, colour: Int) {
    if (at == Offset.Unspecified || colour == 0) return
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val cx = at.x
            val cy = at.y - RING_LIFT
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                style = android.graphics.Paint.Style.FILL
                this.color = colour
            }
            native.drawCircle(cx, cy, RING_RADIUS, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = 0xCCFFFFFF.toInt()
            native.drawCircle(cx, cy, RING_RADIUS + 1.5f, paint)
            paint.strokeWidth = 1.4f
            paint.color = 0xCC000000.toInt()
            native.drawCircle(cx, cy, RING_RADIUS, paint)
            native.drawCircle(at.x, at.y, 3f, paint)
        }
    }
}

/**
 * Where a point in the pane lands on the picture, in picture pixels.
 *
 * The inverse of the two transforms the picture is drawn through: `ContentScale.Fit`,
 * which scales it by [fit] and centres it, and the `graphicsLayer` above, which
 * scales, rotates and translates about the middle of the pane. **They are one
 * mapping written twice**, which is the shape of defect `Matrices.kt` has a page
 * of prose about — a `pre` where a `post` belongs still compiles, still runs,
 * and is exactly right at scale 1 with no rotation, which is where anybody
 * looks first.
 *
 * Null when the point is off the picture. That is a real answer and not an
 * error: the pane is bigger than the photograph in one direction whenever their
 * shapes differ, and a pick on the grey beside it is a pick on nothing.
 */
private fun paneToPicture(
    at: Offset,
    pictureW: Int,
    pictureH: Int,
    fit: Float,
    scale: Float,
    offset: Offset,
    turn: Float,
    flipped: Boolean,
    boxW: Float,
    boxH: Float,
): Pair<Int, Int>? {
    if (fit <= 0f || scale <= 0f) return null
    val cx = boxW * 0.5f
    val cy = boxH * 0.5f
    // Undo the translate, then the rotation, then the scale -- the reverse of
    // the order they are applied in.
    var x = at.x - offset.x - cx
    var y = at.y - offset.y - cy
    val rad = (-turn) * (Math.PI / 180.0)
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    val rx = x * cos - y * sin
    val ry = x * sin + y * cos
    x = rx / (scale * if (flipped) -1f else 1f)
    y = ry / scale
    // Back into the pane's own pixels, then undo the fit and the centring.
    val paneX = x + cx
    val paneY = y + cy
    val drawnW = pictureW * fit
    val drawnH = pictureH * fit
    val left = (boxW - drawnW) * 0.5f
    val top = (boxH - drawnH) * 0.5f
    val px = ((paneX - left) / fit).toInt()
    val py = ((paneY - top) / fit).toInt()
    if (px < 0 || py < 0 || px >= pictureW || py >= pictureH) return null
    return px to py
}

/**
 * A pick, for as long as the nib is down. **The colour lands on lift**, which
 * is what lets the nib be slid onto the right pixel while the ring updates —
 * `PickRing`'s rule, and the picker on the canvas follows it too.
 *
 * Reading the pixel is a `getPixel` on a bitmap this composition owns, so
 * unlike the canvas's picker there is no thread to cross and no frame to wait
 * for: the ring is exact from the first sample.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.pick(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    bitmap: android.graphics.Bitmap,
    fit: Float,
    scale: Float,
    offset: Offset,
    turn: Float,
    flipped: Boolean,
    boxW: Float,
    boxH: Float,
    onMove: (Offset, Int) -> Unit,
    onDone: (Int) -> Unit,
) {
    var argb = 0
    fun read(at: Offset) {
        val p = paneToPicture(
            at, bitmap.width, bitmap.height, fit, scale, offset, turn, flipped, boxW, boxH,
        ) ?: return
        // An alpha of zero is not a colour -- a PNG with a hole in it -- and
        // handing it over would put transparent black in the user's hand.
        val read = bitmap.getPixel(p.first, p.second)
        if (read ushr 24 == 0) return
        argb = read or (0xFF shl 24)
    }
    read(down.position)
    onMove(down.position, argb)
    down.consume()
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
            change.consume()
            break
        }
        read(change.position)
        onMove(change.position, argb)
        change.consume()
    }
    onDone(argb)
}

/**
 * Pan, pinch and twist, for as long as any finger is down.
 *
 * `awaitEachGesture` by hand rather than `detectTransformGestures`, for one
 * reason: the gesture has already been claimed by pointer *type* one level up,
 * and the detector would start its own gesture loop that does not know that.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.move(
    down: androidx.compose.ui.input.pointer.PointerInputChange,
    onChange: (centroid: Offset, pan: Offset, zoom: Float, rotate: Float) -> Unit,
) {
    down.consume()
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break
        val zoom = event.calculateZoom()
        val rotate = event.calculateRotation()
        val pan = event.calculatePan()
        if (zoom != 1f || rotate != 0f || pan != Offset.Zero) {
            onChange(event.calculateCentroid(), pan, zoom, rotate)
        }
        for (change in event.changes) if (change.pressed) change.consume()
    }
}

/**
 * Where the picture has to move to so that the point under the fingers stays
 * under the fingers.
 *
 * **The obvious version is to multiply the scale and leave the offset alone**,
 * and it is what this did first. It zooms about the middle of the pane, so the
 * part of the photograph you were pinching slides away from your fingers while
 * you pinch — which was reported from the tablet as *"when pinching to zoom,
 * the movement is very janky"*. It is not a dropped-frame problem and the frame
 * numbers said so: forty frames, none janky, ten milliseconds at every
 * percentile. It is the picture not going where the hand put it.
 *
 * The layer draws content point `p` at
 *
 * ```
 * screen = centre + offset + R(turn) · S(scale) · (p - centre)
 * ```
 *
 * Write `v` for the vector from the transformed origin to the centroid —
 * `centroid - centre - offset`. A gesture of `zoom` and `turn` about that
 * centroid multiplies `v` by `zoom` and rotates it, so keeping the same content
 * under the fingers means moving the offset by the difference:
 *
 * ```
 * offset' = offset + pan + v - zoom · R(turn) · v
 * ```
 *
 * Which is the line below. `zoom` is the *achieved* ratio rather than the
 * gesture's, because the scale is clamped and an offset computed from a zoom
 * that did not happen walks the picture sideways at the limits.
 */
private fun anchored(
    offset: Offset,
    centroid: Offset,
    centre: Offset,
    pan: Offset,
    zoom: Float,
    turn: Float,
): Offset {
    val v = centroid - centre - offset
    val rad = turn * (Math.PI / 180.0)
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    val spun = Offset(v.x * cos - v.y * sin, v.x * sin + v.y * cos)
    return offset + pan + v - spun * zoom
}

private val PANEL_WIDTH = 320.dp
private val PICTURE_HEIGHT = 200.dp
private val STRIP_HEIGHT = 46.dp
private val STRIP_THUMB_W = 62.dp

/** Header, buttons, strip and the gaps between them. Counted, not guessed. */
private val CARD_FURNITURE = 130.dp

/** The lines slider, when it is there. Counted the same way as the furniture. */
private val SLICE_ROW = 34.dp

/** Below this the pane is not a picture any more, so the card stops shrinking it. */
private val MIN_PICTURE = 90.dp

private const val MIN_ZOOM = 0.2f
private const val MAX_ZOOM = 12f

/**
 * Degrees of turn per pane pixel dragged.
 *
 * A third of a degree, so a drag across a three-hundred-pixel pane is a
 * hundred degrees: rather more than a quarter turn in one comfortable sweep,
 * which is what makes spinning a model feel like handling it. The light has its
 * own rate, in `LightBall`, because it is turned by different arithmetic.
 */
private const val SPIN_PER_PX = 0.34f

/** Whole model in the pane, up to an eyelash. */
private const val MIN_DOLLY = 0.4f
private const val MAX_DOLLY = 14f
private const val RING_RADIUS = 26f
private const val RING_LIFT = 52f
