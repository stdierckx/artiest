package be.thalos.artiest

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import be.thalos.artiest.canvas.GestureStress
import be.thalos.artiest.canvas.InkSurfaceView
import be.thalos.artiest.canvas.InputStats
import be.thalos.artiest.canvas.RejectionStress
import be.thalos.artiest.canvas.StrokeStress
import be.thalos.artiest.doc.Document
import be.thalos.artiest.doc.LayerInfo
import be.thalos.artiest.doc.LayerOp
import be.thalos.artiest.doc.LayerStack
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushPreset
import be.thalos.artiest.ui.ArtiestTheme
import be.thalos.artiest.ui.Axis
import be.thalos.artiest.canvas.MarqueeShape
import be.thalos.artiest.canvas.setDocToView
import be.thalos.artiest.doc.FloatOp
import be.thalos.artiest.doc.SelectMode
import be.thalos.artiest.doc.SelectOp
import be.thalos.artiest.ui.BarSpot
import be.thalos.artiest.ui.BrushCursor
import be.thalos.artiest.ui.SelectionButton
import be.thalos.artiest.ui.SelectionPanelCard
import be.thalos.artiest.ui.SelectionOverlay
import be.thalos.artiest.ui.TransformBox
import be.thalos.artiest.ui.LayersButton
import be.thalos.artiest.ui.LayersPanelCard
import be.thalos.artiest.ui.BrushStore
import be.thalos.artiest.ui.ColourButton
import be.thalos.artiest.ui.ColourPanelCard
import be.thalos.artiest.ui.DockHost
import be.thalos.artiest.ui.DockLayout
import be.thalos.artiest.ui.ChromeCounters
import be.thalos.artiest.ui.DockStore
import be.thalos.artiest.ui.Workspace
import be.thalos.artiest.ui.WorkspaceMenu
import be.thalos.artiest.ui.WorkspaceStore
import be.thalos.artiest.ui.IconToolButton
import be.thalos.artiest.ui.ToolIcons
import be.thalos.artiest.ui.ToolSlider
import be.thalos.artiest.ui.warmColourWheel
import be.thalos.artiest.doc.UndoHistory
import be.thalos.artiest.engine.input.CancelCause
import be.thalos.artiest.input.clockSkewNanos
import be.thalos.artiest.io.ExportResult
import be.thalos.artiest.io.ImportResult
import be.thalos.artiest.io.PictureImporter
import be.thalos.artiest.io.PngExporter
import be.thalos.artiest.ui.ToolItem
import kotlinx.coroutines.launch

/**
 * The app's one screen: a canvas, a toolbar over it, and the instruments W16
 * needs one tap away.
 *
 * Three things happen here that are not chrome, and each is the reason the item
 * lists them:
 *
 * - **The document is sized from a probe, not from a constant.** [DeviceProbe]
 *   runs before the `Document` is built and `documentSizeFor` clamps it to the
 *   measured `GL_MAX_TEXTURE_SIZE`. On this device the cap is 16383 against a
 *   3300 requirement, so it never fires; on a device where it would, the
 *   failure it prevents is not a slow canvas but a blank one.
 * - **The refresh rate is a three-way choice.** See [RefreshPolicy]. `:spike`
 *   asks for the highest mode unconditionally in `onCreate`, which is why every
 *   Phase 0 number is "the app asked for 90 and the vendor cap decided" and why
 *   W16 could not otherwise build a 60 Hz control.
 * - **Edge-to-edge with transient bars**, ported verbatim, because edge swipes
 *   for system navigation land on the canvas as stray touches and cancels.
 *
 * The diagnostic readout from W9 through W14 is not deleted. It is folded
 * behind a toggle and defaults **off**, because W16 is a feel pass and eleven
 * lines of monospace over the paper is not a drawing app — but every number is
 * still there, live, one tap away, and W16 is the item that needs them.
 */
class MainActivity : ComponentActivity() {

    private var document: Document? = null

    private var view: InkSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepNavGesturesOutOfTheWay()

        // Before the Document, because it is what decides how big the Document
        // is allowed to be.
        val report = DeviceProbe.run(this, activityDisplay())
        val (w, h) = report.documentSizeFor(Document.DEFAULT_WIDTH_PX, Document.DEFAULT_HEIGHT_PX)
        val doc = Document(w, h)
        document = doc

        applyRefreshPolicy(RefreshPolicy.HIGHEST)

        // A megabyte of atan2 that the colour panel would otherwise build
        // while the user is waiting for it to appear. Off the main thread and
        // fire-and-forget: if it has not finished by the first press, the panel
        // builds its own and this one is discarded.
        warmColourWheel()

        setContent {
            // The chrome's own scheme, not the platform's. See ArtiestTheme for
            // why there is no light variant: the paper is white in both, and a
            // toolbar in the paper's own whites competes with the drawing.
            ArtiestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CanvasScreen(
                        document = doc,
                        report = report,
                        refreshHzNow = { panelHz() },
                        onRefreshPolicy = ::applyRefreshPolicy,
                        onForceNinety = { done -> holdAndCheck(90f, done) },
                        onView = { view = it },
                    )
                }
            }
        }
    }

    /**
     * The framework does not reliably send an ACTION_CANCEL when the activity
     * leaves the foreground — a dialog taking focus can leave ACTION_MOVE as
     * the last event ever delivered — so the open stroke is abandoned here.
     * `InkSurfaceView.clear` is not what is wanted: the drawing stays.
     */
    override fun onPause() {
        view?.abandonStroke()
        super.onPause()
    }

    /**
     * Released last, and only here. The layer's pixels must outlive the render
     * thread that writes them, and the view's detach is what joins that thread.
     */
    override fun onDestroy() {
        view = null
        document?.close()
        document = null
        super.onDestroy()
    }

    /**
     * Put [policy] into the window, or take the request out again.
     *
     * `preferredDisplayModeId = 0` is the framework's "no preference", so
     * [RefreshPolicy.UNSPECIFIED] is expressible rather than being "the request
     * we happen not to have made yet". The assignment goes through
     * `window.attributes` as a whole because `LayoutParams` is read back,
     * mutated and re-set — mutating the live object in place does not trigger a
     * re-layout and the request is silently ignored.
     */
    private fun applyRefreshPolicy(policy: RefreshPolicy) {
        val display = activityDisplay() ?: return
        val modes = display.supportedModes.map {
            ModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
        }
        val chosen = RefreshPolicy.chooseModeId(modes, display.mode.modeId, policy)
        window.attributes = window.attributes.apply { preferredDisplayModeId = chosen }
    }

    /**
     * The rate the panel is physically holding.
     *
     * Deliberately `mode.refreshRate` and not `display.refreshRate`, which is
     * the *render* rate and carries any frame-rate override the system has put
     * on this app. Those differ: with the panel at 90 and the app idle, the
     * override reads 45, and a readout saying 45 while photons arrive every
     * 11.1 ms would send someone off to fix a frame drop that is not happening.
     * The film needs to know when photons land, so the mode is the honest
     * number here.
     */
    private fun panelHz(): Float = activityDisplay()?.mode?.refreshRate ?: 0f

    /**
     * Hold the panel where it is and say what rate that actually is.
     *
     * This deliberately does **not** set the rate, because an app on this
     * device cannot — [PanelRefresh] carries the evidence, including the two
     * permissions that were granted and still refused. Pretending otherwise
     * would be the same failure as the old `max` button, which reported nothing
     * and left everyone believing 90 Hz for a day.
     *
     * What it does instead is the two things that are the app's to do. It pins
     * the screen awake, because the system cap is recomputed as 60 on every
     * screen-on, so a rate set from a PC lasts exactly until the tablet next
     * blanks — the failure that is invisible precisely while it matters. And it
     * reports the rate the panel is holding, which is the check worth running
     * immediately before a take.
     */
    private fun holdAndCheck(targetHz: Float, done: (String) -> Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyRefreshPolicy(RefreshPolicy.HIGHEST)

        // The compositor may change mode a few frames from now, so the answer
        // is not available on this call stack.
        val handler = Handler(Looper.getMainLooper())
        var tries = 0
        lateinit var check: Runnable
        check = Runnable {
            val now = panelHz()
            when {
                PanelRefresh.settled(now, targetHz) ->
                    done("${r(now, 1)} Hz, screen held on - ok to film")
                ++tries < VERIFY_TRIES -> handler.postDelayed(check, VERIFY_STEP_MS)
                else ->
                    done("${r(now, 1)} Hz - run tools/panel-90hz.sh from the PC")
            }
        }
        handler.postDelayed(check, VERIFY_STEP_MS)
    }

    /**
     * Edge swipes for system navigation land on the canvas as stray touches and
     * ACTION_CANCELs. Transient bars keep them out of the way while drawing.
     *
     * Ported verbatim from `:spike`, and it is the one piece of window setup
     * that is load-bearing for input rather than for looks.
     */
    private fun keepNavGesturesOutOfTheWay() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @Suppress("DEPRECATION")
    private fun activityDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
}

/**
 * The ink colours, as a palette rather than a wheel.
 *
 * Five, and no white: white ink on white paper is a hole in the drawing that
 * looks exactly like an eraser, and Phase 1 has no eraser — the pen's back
 * reports `TOOL_TYPE_FINGER`, so flip-to-erase is impossible on this hardware
 * rather than merely deferred. Offering an invisible colour would be offering
 * the feature by accident, without the undo model or the blend mode it needs.
 * A colour wheel is Phase 4 and the doc agrees.
 */
private val PALETTE = listOf(
    AndroidColor.BLACK,
    AndroidColor.rgb(0x55, 0x55, 0x55),
    AndroidColor.rgb(0xD3, 0x2F, 0x2F),
    AndroidColor.rgb(0x19, 0x76, 0xD2),
    AndroidColor.rgb(0x2E, 0x7D, 0x32),
)

/** The two stress shapes the readout is meant to be compared across. */
private val STRESS_MODES = listOf(
    Triple("Sweep", null, StrokeStress.Path.SPIRAL),
    Triple("Firm", 1f, StrokeStress.Path.SPIRAL),
    Triple("Zigzag", 1f, StrokeStress.Path.ZIGZAG),
    // Nine separate strokes with the pen lifted between them. The only mode
    // that puts the pen down more than once, and the only one that could have
    // caught the scratch buffer ratcheting between strokes.
    Triple("Figure", null, StrokeStress.Path.FIGURE),
    // The reference sheet, redrawn: four upright strokes at rising pressure
    // and five laid further and further over. Not a timing run -- it is there
    // so a screenshot can be measured against the page the user drew in
    // another app, with the same script, instead of judged by eye.
    Triple("Sheet", null, StrokeStress.Path.SHEET),
)

@Composable
private fun CanvasScreen(
    document: Document,
    report: DeviceReport,
    refreshHzNow: () -> Float,
    onRefreshPolicy: (RefreshPolicy) -> Unit,
    onForceNinety: ((String) -> Unit) -> Unit,
    onView: (InkSurfaceView) -> Unit,
) {
    var generation by remember { mutableIntStateOf(0) }
    var surface by remember { mutableStateOf<InkSurfaceView?>(null) }
    var stress by remember { mutableStateOf<StrokeStress?>(null) }
    var pinch by remember { mutableStateOf<GestureStress?>(null) }
    var reject by remember { mutableStateOf<RejectionStress?>(null) }
    var polling by remember { mutableStateOf(true) }
    var export by remember { mutableStateOf<ExportResult?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    /** The last import's outcome, as one line, or empty when there has been none. */
    var importNote by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf(RefreshPolicy.HIGHEST) }
    var panelStatus by remember { mutableStateOf("") }

    // The brush settings live here as Compose state and are pushed into the
    // pen, not read back out of it. `Brush`'s fields are plain vars on the
    // render path — deliberately, they are read once per stroke — so making
    // them the source of truth for a slider would mean a recomposition could
    // not see a change and a stroke could see half of one.
    var ink by remember { mutableIntStateOf(PALETTE.first()) }
    var sizeMax by remember { mutableFloatStateOf(DEFAULT_SIZE_MAX) }

    /**
     * The eraser's width, in document pixels, held apart from [sizeMax].
     *
     * See `Brush.eraseSizeMax`: the rubber is a different width from the point,
     * and a user reaching for the eraser does not want the pencil resized on
     * the way back.
     */
    var eraserSize by remember { mutableFloatStateOf(DEFAULT_ERASER_SIZE) }
    var smoothing by remember { mutableFloatStateOf(DEFAULT_SMOOTHING) }

    // W7. Both default to 1, which is Phase 1's opaque nib exactly, so nothing
    // changes until a slider is moved -- and the indirect path stays off until
    // one is, because for an opaque nib it would be the same picture for more
    // work.
    var opacity by remember { mutableFloatStateOf(1f) }
    var flow by remember { mutableFloatStateOf(1f) }

    /** W8. Zero is the pen, untextured, which is what Phase 1 shipped. */
    var grain by remember { mutableFloatStateOf(0f) }

    val brushCtx = LocalContext.current
    val brushStore = remember(brushCtx) { BrushStore(brushCtx) }

    /** W10. Which of the two tools is in the hand, restored from last time. */
    var preset by remember { mutableStateOf(brushStore.loadPreset()) }

    /** W11. Whether that tool is currently taking ink out instead of putting it in. */
    var eraser by remember { mutableStateOf(false) }

    /**
     * Whether a barrel button is held down right now.
     *
     * The toolbar's copy of `InkSurfaceView.barrelHeld`, and unlike the hover
     * ring's state this one *does* recompose — that is the point of it. Holding
     * the barrel changes which tool the pen would use, and a bar that goes on
     * showing the pencil while the pen erases is a bar that is lying. It moves
     * twice per press, so the recomposition is free.
     */
    var barrel by remember { mutableStateOf(false) }

    /**
     * Where the pen is hovering, in view pixels, or `Unspecified` when it is
     * not over the glass.
     *
     * A `MutableState` held by hand rather than a `by remember` delegate,
     * because the hover callback below writes it from outside the composition
     * and the ring reads it inside a draw lambda. Written at whatever rate the
     * digitizer hovers — a few hundred a second — and read nowhere that
     * recomposes, so the cost is one draw-phase invalidation per sample. See
     * [BrushCursor].
     */
    val cursorAt = remember { mutableStateOf(Offset.Unspecified) }

    // The layer stack, as the UI sees it. `LayerStack.snapshot` is a volatile
    // field the render thread republishes -- a new immutable list per change --
    // so this is a reference comparison and a recomposition only when something
    // actually moved. Polled rather than pushed for the reason `canUndo` is:
    // the render thread has no way to reach into a composition, and a callback
    // it could call would be a callback running on the wrong thread.
    var layerRows by remember { mutableStateOf(document.layers.snapshot) }
    var activeLayer by remember { mutableIntStateOf(document.layers.activeId) }
    var layersOpen by remember { mutableStateOf(false) }

    // The stencil, and the marquee being dragged over it.
    //
    // Three pieces of state and not one, because they change at three
    // different rates and only one of them recomposes anything. `selecting`
    // and `marqueeShape` are pressed by hand; `selectionShape` changes when the
    // render thread republishes; `outlineTick` moves at the rate of a pan or a
    // pen sample and is read *inside a draw lambda*, so it invalidates the
    // draw phase and nothing else. That is the same arrangement `cursorAt`
    // uses, and it is what makes an animated outline affordable.
    var selecting by remember { mutableStateOf(false) }
    var marqueeShape by remember { mutableStateOf(MarqueeShape.RECTANGLE) }
    var marqueeMode by remember { mutableStateOf(SelectMode.NEW) }
    var selectionShape by remember { mutableStateOf(document.selection.snapshot) }
    val outlineTick = remember { mutableIntStateOf(0) }

    // The floating pixels, as the chrome sees them: the rectangle they were
    // lifted from, and a token that changes when a different float is lifted so
    // the transform box starts over rather than inheriting the last one's
    // matrix.
    var floatingBox by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var floatToken by remember { mutableIntStateOf(0) }

    // Document-to-view, rebuilt only when the canvas actually moves. A `Matrix`
    // is mutable native state and this one is written on the UI thread and read
    // on the UI thread, in a draw lambda, so one instance is enough -- but it
    // must not be the renderer's, which the render thread concatenates.
    val outlineMatrix = remember { android.graphics.Matrix() }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The bars the user built. Loaded once and written on every change: the
    // changes are rare and a layout that does not survive a force-quit is not a
    // layout anyone will invest in arranging.
    val store = remember { DockStore(context) }

    // Which program you are in. Two stores on purpose, and the split is the one
    // WorkspaceStore's KDoc argues: the *arrangement* goes to preferences on
    // every drag, because a drag is frequent and a JSON write per drop is a
    // dropped frame six months from now; the *workspace* goes to its own file
    // when you switch, duplicate or leave. A crash after a drag loses nothing --
    // the arrangement is in preferences and the file catches up.
    val workspaces = remember { WorkspaceStore(context) }
    var workspace by remember { mutableStateOf(workspaces.current()) }
    var entries by remember { mutableStateOf(workspaces.list()) }

    var docks by remember { mutableStateOf(store.load(workspace.filter)) }
    var arranging by remember { mutableStateOf(false) }

    /** Save the arrangement to both stores. The fast one always, the file too. */
    fun keep(next: DockLayout) {
        docks = next
        store.save(next)
        workspace = workspace.copy(layout = next)
        workspaces.save(workspace)
    }

    /**
     * Switch program: load the file, take its arrangement, take its filter.
     *
     * No animation, no dialog, no reload of the document -- the workspace plan
     * made that a stop condition and it is one line of code away from being
     * broken. What is on the paper does not change; what is around it does.
     */
    fun switchTo(id: String) {
        val next = workspaces.load(id) ?: return
        workspaces.switchTo(id)
        workspace = next
        docks = next.layout
        store.save(next.layout)
        entries = workspaces.list()
    }

    // The colours mixed on the wheel. Pushed when the panel closes rather than
    // on every sample of a drag -- see ColourButton for why.
    var recentInks by remember { mutableStateOf(store.loadRecentColours()) }

    // The undo buttons' enabled state. `Document.canUndo` is written by the
    // render thread, so it cannot be Compose state directly; it is sampled
    // here and assigned only when it changes, so a bar with nothing to undo
    // recomposes zero times a second rather than four.
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }

    // Applied on every change and once when the view arrives, because the view
    // is built by the AndroidView factory after the first composition.
    // W12. The stored brush is applied once, when the view arrives, and the
    // slider states are pulled back from it — the same order the preset button
    // uses, and for the same reason: pushing the sliders first would write
    // their defaults over the brush that was just restored.
    LaunchedEffect(surface) {
        val v = surface ?: return@LaunchedEffect
        val stored = brushStore.load()
        // A brush saved against an older tuning of the preset is deliberately
        // not restored. See BrushPreset.TUNING: the saved scalars would win
        // over every retuned number and the tool would read exactly as it did
        // before the retune, which is what happened when the pencil was
        // re-solved against the reference sheet and the tablet showed nothing.
        // Not even the size slider survives it, and that is specific to this
        // bump rather than a rule: the slider used to set the width of the
        // pencil's *point* and now sets the width of the mark it makes laid
        // over, which is four to five times bigger. Carrying the old number
        // across would hand the user a pencil a quarter of the size they had.
        if (brushStore.storedTuning() != BrushPreset.TUNING) {
            preset.applyTo(v.pen)
            v.pen.erase = false
            brushStore.save(v.pen, preset)
            sizeMax = v.pen.sizeMax
            eraserSize = v.pen.eraseSizeMax
            smoothing = v.pen.stabilization
            opacity = v.pen.opacity
            flow = v.pen.flow
            grain = v.pen.grain.strength
            return@LaunchedEffect
        }
        BrushCodec.decode(BrushCodec.encode(stored))?.let { b ->
            // Qualified, every one of them: `sizeMax`, `opacity`, `flow` and
            // `grain` are all names of Compose state in this scope, and a local
            // variable shadows an implicit receiver's member. Unqualified, this
            // block would assign the sliders to themselves and leave the brush
            // untouched -- and only `grain` would fail to compile.
            val pen = v.pen
            pen.sizeMin = b.sizeMin
            pen.sizeMax = b.sizeMax
            pen.sizeCurve = b.sizeCurve
            pen.spacing = b.spacing
            pen.isotropicSpacing = b.isotropicSpacing
            pen.hardness = b.hardness
            pen.opacity = b.opacity
            pen.flow = b.flow
            pen.stabilization = b.stabilization
            pen.antiAlias = b.antiAlias
            pen.onsetMillis = b.onsetMillis
            pen.onsetPressure = b.onsetPressure
            pen.grain = b.grain
            // Restored like every other scalar, and it was missed. `burnish` is
            // written by the codec and was simply not read back here, so the
            // pencil lost its tooth-crushing the first time the app was
            // restarted -- a change that had been measured, shipped and then
            // silently undone by the next launch. Checked on the tablet: the
            // stored brush read `burnish 0.0` under a preset that sets 0.75.
            pen.burnish = b.burnish
            pen.erase = b.erase
            pen.eraseSizeMax = b.eraseSizeMax
            preset.applyToShapeOnly(v.pen)
        }
        sizeMax = v.pen.sizeMax
        eraserSize = v.pen.eraseSizeMax
        smoothing = v.pen.stabilization
        opacity = v.pen.opacity
        flow = v.pen.flow
        grain = v.pen.grain.strength
    }

    LaunchedEffect(surface, ink, sizeMax, eraserSize, smoothing, opacity, flow, grain) {
        val v = surface ?: return@LaunchedEffect
        v.inkColorArgb = ink
        v.pen.sizeMax = sizeMax
        v.pen.eraseSizeMax = eraserSize
        v.pen.stabilization = smoothing
        // These two are what turn the indirect path on. Both at 1 is Phase 1's
        // opaque nib and takes the direct path; anything less routes the stroke
        // through the scratch buffer, which is the whole of W6.
        v.pen.opacity = opacity
        v.pen.flow = flow
        v.pen.grain = v.pen.grain.copy(strength = grain)
        brushStore.save(v.pen, preset)
    }

    // Polled twice a second rather than pushed. The counters this reads live on
    // the input and render paths, and making them Compose state would put a
    // recomposition on a path that runs at 321 Hz.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            if (polling && stats) generation++
        }
    }

    // Separate from the readout's poll above and faster, because this one
    // drives a control rather than a diagnostic: a Undo button that stays grey
    // for half a second after the first stroke reads as a broken button. Four
    // volatile reads a second, and a recomposition only when an answer moves.
    LaunchedEffect(document) {
        while (true) {
            kotlinx.coroutines.delay(250)
            if (canUndo != document.canUndo) canUndo = document.canUndo
            if (canRedo != document.canRedo) canRedo = document.canRedo
        }
    }

    // The stencil, polled for the same reason `canUndo` is: the render thread
    // is what applies a selection -- it arrives through the commit queue,
    // behind whatever was drawn before it -- and it has no way to reach into a
    // composition. Four times a second is fast enough for an outline that
    // appears after a gesture the user has already finished.
    //
    // The tick is bumped as well as the snapshot swapped, because the outline
    // is drawn from a lambda that reads the tick; swapping the snapshot alone
    // would recompose and leave the draw phase believing nothing had moved.
    LaunchedEffect(document) {
        while (true) {
            kotlinx.coroutines.delay(250)
            val snap = document.selection.snapshot
            if (snap !== selectionShape) {
                selectionShape = snap
                outlineTick.intValue++
            }
            // The float is picked up on the same poll. Its bounds never change
            // once lifted -- the matrix moves, not the source -- so a reference
            // comparison is enough and a new box means a new gesture.
            val box = document.floating?.sourceBounds
            if (box !== floatingBox) {
                floatingBox = box
                floatToken++
                outlineTick.intValue++
            }
        }
    }

    // Only while the panel is open, and faster than the undo poll, because
    // this one is watching the result of a press the user just made: a row that
    // takes half a second to light up reads as a button that did not work.
    // Closed, nothing here runs at all -- the button itself shows no state.
    LaunchedEffect(document, layersOpen) {
        if (!layersOpen) return@LaunchedEffect
        while (true) {
            val snap = document.layers.snapshot
            if (snap !== layerRows) layerRows = snap
            if (activeLayer != document.layers.activeId) activeLayer = document.layers.activeId
            kotlinx.coroutines.delay(100)
        }
    }

    /**
     * Queue a change to the stack and ask for a frame.
     *
     * The second half is not optional. A stroke asks for its own render when it
     * commits; a layer operation has nothing that would, so without this the
     * queue would sit there until the user drew something and the panel would
     * show a press that had apparently done nothing.
     */
    val onLayerOp: (LayerOp) -> Unit = { op ->
        document.requestLayers(op)
        surface?.redrawDry()
    }

    /**
     * The system photo picker.
     *
     * `PickVisualMedia` and not `GetContent`, and not a `READ_MEDIA_IMAGES`
     * permission either: the picker runs in another process, hands back a URI
     * for the one file the user chose, and needs no permission at all. An app
     * that asks for the whole gallery in order to open one reference photo is
     * an app that has asked for more than it needs.
     */
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) {
            // The user backed out. Not a failure and not worth a line of
            // chrome; the note is cleared so a stale one from a previous
            // attempt does not read as this attempt's answer.
            importNote = ""
            return@rememberLauncherForActivityResult
        }
        importing = true
        importNote = "opening the picture..."
        scope.launch {
            val name = document.suggestLayerName("Picture")
            importNote = when (val r = PictureImporter.importInto(context, document, uri, name)) {
                is ImportResult.Imported -> "imported ${r.width}x${r.height} as \"${r.name}\""
                is ImportResult.Failed -> "could not import: ${r.reason}"
            }
            importing = false
            // The stack changed, and nothing else is going to ask for the frame
            // that draws it -- the same reason `onLayerOp` asks for one.
            surface?.redrawDry()
            generation++
        }
    }

    // Hoisted out of the toolbar because the toolbar is now generic: it is
    // handed a renderer and does not know what an export is.
    val doExport: () -> Unit = {
        // A redraw first, on this thread, because the export cannot ask for
        // one - it waits for the commit queue to drain and only the render
        // thread drains it.
        //
        // Stated at its real size: every path that queues a commit already
        // asks for a render of its own (`commitStroke` calls `commit()`,
        // `clear` calls `redrawDry`), so on a live surface this is a second
        // chance and not the first, and measured on the tablet the export's
        // wait was 0 ms every time. What it covers is a render that was
        // scheduled and then deferred, and it is a no-op when there is no
        // surface - which is the only state in which the queue is reliably
        // non-empty, and also the one in which this button cannot be pressed.
        // Pixels still in the air are put down first. The export composites
        // sheets and knows nothing about a float, and the alternatives are
        // both worse: exporting without it saves a drawing with a hole in it,
        // and teaching the exporter to read a bitmap the render thread may
        // recycle at any moment is a race for a case that has an obvious
        // answer. Pressing Save while transforming commits the transform.
        if (document.floating != null) surface?.float(FloatOp.Drop)
        surface?.redrawDry()
        exporting = true
        export = null
        scope.launch {
            export = PngExporter.export(context, document)
            exporting = false
            generation++
        }
    }

    // Turning the marquee on or off, in one place because three controls do it:
    // the Select toggle, picking a shape in the panel, and picking a brush.
    //
    // Through the view rather than straight onto the field, because an open
    // stroke has to be abandoned -- a mode switch with the pen already down
    // would turn half a lasso into half a pencil line.
    val setSelecting: (Boolean) -> Unit = { on ->
        if (selecting != on) {
            selecting = on
            surface?.let {
                it.selecting = on
                it.abandonStroke()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                // No transform is set here. The view fits the document to its
                // own surface in surfaceChanged, which is the only place the
                // real size is known — displayMetrics is the display, not the
                // window, and the two differ by the system bars at minimum.
                InkSurfaceView(ctx, document).also {
                    surface = it
                    it.onHover = { inRange, x, y ->
                        cursorAt.value = if (inRange) Offset(x, y) else Offset.Unspecified
                    }
                    it.onBarrel = { held -> barrel = held }
                    // Both bump a counter read inside a draw lambda rather than
                    // setting a value: a marquee at 200 Hz and a pan at 90 Hz
                    // must not recompose the chrome.
                    it.onMarqueeChanged = { outlineTick.intValue++ }
                    it.onTransformChanged = { outlineTick.intValue++ }
                    onView(it)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Above the canvas and below the chrome, for the reason the ring is
        // there: the outline belongs over the paper.
        SelectionOverlay(
            selection = {
                outlineTick.intValue
                // Hidden while pixels are in the air: the transform box is the
                // outline then, and two rectangles -- one around the hole, one
                // around what came out of it -- is a picture nobody can read.
                if (floatingBox != null) null else selectionShape.path
            },
            marquee = {
                outlineTick.intValue
                surface?.liveMarquee?.takeIf { it.isOpen }?.path
            },
            docToView = {
                outlineTick.intValue
                surface?.let { outlineMatrix.setDocToView(it.transform) }
                outlineMatrix
            },
            // Recomposes when the answer moves, which is once per selection
            // rather than once per frame -- and it is what stops the ants
            // ticking over a page with nothing on it.
            showing = selectionShape.active || selecting,
            modifier = Modifier.fillMaxSize(),
        )

        // Over the outline, because it is the thing being dragged, and it
        // takes the pen while it is there. Absent -- and consuming nothing --
        // whenever there is no float.
        TransformBox(
            sourceBounds = floatingBox,
            token = floatToken,
            docToView = {
                surface?.let { outlineMatrix.setDocToView(it.transform) }
                outlineMatrix
            },
            onMatrix = { m -> surface?.float(FloatOp.Move(m)) },
            modifier = Modifier.fillMaxSize(),
        )

        // Above the canvas and below the chrome, which is the order it has to
        // be in: the ring belongs over the paper, and a toolbar with a hover
        // ring drawn on top of its buttons is a toolbar that looks broken.
        BrushCursor(
            at = { cursorAt.value },
            diameterPx = {
                val v = surface
                if (v == null) 0f else v.cursorDiameterDocPx * v.transform.scale
            },
            // `erasingNow` and not `pen.erase`: that field is the *last*
            // stroke's decision once the pen has lifted, so a single barrel
            // stroke left the ring on for the rest of the session.
            //
            // `barrel` is read as well, and it is not redundant: it is the only
            // one of the two that is snapshot state, so it is what makes the
            // ring appear the moment the side button goes down with the pen
            // held still. `erasingNow` is a plain field and changing it
            // invalidates nothing on its own.
            erasing = { barrel || surface?.erasingNow == true },
            modifier = Modifier.fillMaxSize(),
        )

        // The instruments sit under the top dock and inside the side ones, so
        // they are readable without moving a bar out of the way. They are drawn
        // before the chrome so that a bar wins the overlap, which is the right
        // way round: a toolbar half covered by a diagnostic is a toolbar with a
        // button you cannot press — but a readout whose first three lines are
        // under the top bar is also a readout that cannot be read, so the
        // padding here clears the bar rather than relying on the draw order.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .systemBarsPadding()
                .padding(start = READOUT_INSET, top = READOUT_TOP, end = READOUT_INSET),
        ) {
            ExportStatus(export, exporting)
            if (importNote.isNotEmpty()) {
                Text(
                    text = importNote,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (stats) {
                Text(
                    text = readout(
                        surface, document, report, refreshHzNow(), policy,
                        reject, export, exporting, generation,
                        stress?.strokeTimes()?.joinToString(" ") { r(it, 0) + "ms" } ?: "",
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    // A ground of its own, like the bars. Without it this is
                    // grey monospace over a dark desk on one side and white
                    // paper on the other, and the half over the desk is
                    // unreadable — which is a readout that exists and cannot be
                    // read, the worst of both.
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                DebugRow(
                    surface = surface,
                    policy = policy,
                    onPolicy = { policy = it; onRefreshPolicy(it); generation++ },
                    panelStatus = panelStatus,
                    onForceNinety = {
                        panelStatus = "checking..."
                        onForceNinety { msg -> panelStatus = msg; generation++ }
                    },
                    pinchRunning = pinch?.running == true,
                    rejectRunning = reject?.running == true,
                    stressRunning = stress?.running == true,
                    onPinch = {
                        val v = surface ?: return@DebugRow
                        val g = pinch ?: GestureStress(v).also { pinch = it }
                        polling = false
                        g.start { polling = true; generation++ }
                    },
                    onDoubleTap = {
                        val v = surface ?: return@DebugRow
                        val g = pinch ?: GestureStress(v).also { pinch = it }
                        polling = false
                        g.doubleTap { polling = true; generation++ }
                    },
                    onReject = {
                        val v = surface ?: return@DebugRow
                        val g = reject ?: RejectionStress(v).also { reject = it }
                        // Cleared first: three of the eight cases commit ink on
                        // purpose, and the seven that must not are counted
                        // against the document's stroke count.
                        v.clear()
                        polling = false
                        g.start { polling = true; generation++ }
                    },
                    onPredict = {
                        val v = surface ?: return@DebugRow
                        v.predictionEnabled = !v.predictionEnabled
                        v.predictor?.enabled = v.predictionEnabled
                        generation++
                    },
                    onStamp = {
                        val v = surface ?: return@DebugRow
                        v.stampMode = !v.stampMode
                        generation++
                    },
                    onF16 = {
                        val v = surface ?: return@DebugRow
                        v.scratchF16 = !v.scratchF16
                        generation++
                    },
                    onWet = {
                        val v = surface ?: return@DebugRow
                        val on = v.pen.opacity < 1f
                        val p = if (on) BrushPreset.PEN else BrushPreset.PENCIL
                        p.applyTo(v.pen)
                        preset = p
                        sizeMax = v.pen.sizeMax
                        eraserSize = v.pen.eraseSizeMax
                        smoothing = v.pen.stabilization
                        opacity = v.pen.opacity
                        flow = v.pen.flow
                        grain = v.pen.grain.strength
                        generation++
                    },
                    onStress = { pressure, path ->
                        val v = surface ?: return@DebugRow
                        val s = stress ?: StrokeStress(v).also { stress = it }
                        v.clear()
                        // The readout is paused for the duration: recomposing
                        // it allocates, and this run is measuring allocation.
                        polling = false
                        s.start(pressure = pressure, path = path) {
                            polling = true
                            generation++
                        }
                    },
                )
            }
        }

        DockHost(
            layout = docks,
            onLayout = { keep(it) },
            arranging = arranging,
            onArranging = { arranging = it },
            filter = workspace.filter,
            onFilter = {
                workspace = workspace.copy(filter = it)
                workspaces.save(workspace)
            },
            arrangeExtras = {
                WorkspaceMenu(
                    current = workspace,
                    entries = entries,
                    isShipped = workspaces::isShipped,
                    onSwitch = { switchTo(it) },
                    onDuplicate = {
                        val copy = workspaces.duplicate(
                            workspace.id,
                            Workspace.copyName(workspace.name, entries.map { it.name }),
                        )
                        entries = workspaces.list()
                        copy?.let { switchTo(it.id) }
                    },
                    onReset = {
                        workspaces.reset(workspace.id)
                        switchTo(workspace.id)
                    },
                )
            },
        ) { item, axis ->
                ToolSlot(
                    item = item,
                    axis = axis,
                    ink = ink,
                    recentInks = recentInks,
                    onInkCommitted = { recentInks = store.pushRecentColour(it) },
                    onFixate = { panel, spot ->
                        // Three ordinary operations and no new idea: make a
                        // floating bar, put the panel on it, and turn on
                        // arrange mode so the next thing the hand does is move
                        // it somewhere better. Every panel is fixated through
                        // this one path, which is what makes adding the next
                        // one a catalogue entry rather than a feature.
                        val (next, _) = docks.addFloating(panel, spot)
                        keep(next)
                        arranging = true
                    },
                    onInk = { ink = it },
                    sizeMax = sizeMax,
                    onSizeMax = { sizeMax = it },
                    eraserSize = eraserSize,
                    onEraserSize = { eraserSize = it },
                    layerRows = layerRows,
                    activeLayer = activeLayer,
                    onLayerOp = onLayerOp,
                    onLayerAdd = {
                        // The empty sheet is allocated here, on the UI thread,
                        // and handed over. See `LayerStack`: 27.19 MiB inside a
                        // render callback is a hitch in the frame the user is
                        // drawing into.
                        onLayerOp(LayerOp.Add(document.newLayer(), document.suggestLayerName()))
                    },
                    onLayerDuplicate = {
                        onLayerOp(
                            LayerOp.Duplicate(
                                activeLayer,
                                document.newLayer(),
                                document.suggestLayerName(),
                            ),
                        )
                    },
                    onLayersOpen = { open ->
                        layersOpen = open
                        document.layers.wantThumbnails = open
                        if (open) {
                            layerRows = document.layers.snapshot
                            activeLayer = document.layers.activeId
                            // The thumbnails are built one per frame by the
                            // render callback, so opening the panel has to ask
                            // for the first of those frames.
                            surface?.redrawDry()
                        }
                    },
                    selecting = selecting,
                    onSelecting = { on ->
                        setSelecting(on)
                        generation++
                    },
                    marqueeShape = marqueeShape,
                    onMarqueeShape = {
                        marqueeShape = it
                        surface?.marqueeShape = it
                        generation++
                    },
                    marqueeMode = marqueeMode,
                    onMarqueeMode = {
                        marqueeMode = it
                        surface?.marqueeMode = it
                        generation++
                    },
                    hasSelection = selectionShape.active,
                    floating = floatingBox != null,
                    onFloatOp = { op ->
                        surface?.float(op)
                        generation++
                    },
                    onSelectOp = { op ->
                        surface?.select(op)
                        // Not waited for: the op is queued and the render
                        // thread applies it behind whatever was drawn first.
                        // The poll above is what notices.
                        generation++
                    },
                    smoothing = smoothing,
                    onSmoothing = { smoothing = it },
                    opacity = opacity,
                    onOpacity = { opacity = it },
                    flow = flow,
                    onFlow = { flow = it },
                    grain = grain,
                    onGrain = { grain = it },
                    eraser = eraser,
                    barrel = barrel,
                    onEraser = {
                        eraser = !eraser
                        surface?.eraserTool = eraser
                        // Reaching for the eraser is a statement that the pen
                        // is drawing. See `onPreset`.
                        setSelecting(false)
                        generation++
                    },
                    preset = preset,
                    onPreset = { p ->
                        // The preset writes the whole brush, then the sliders
                        // are pulled back from it. Without that second half the
                        // LaunchedEffect above would push the *old* slider
                        // values straight back over the preset it just set,
                        // and switching tools would half work.
                        surface?.let { v ->
                            p.applyTo(v.pen)
                            preset = p
                            // Picking a brush turns the marquee off, the mirror
                            // of picking a shape turning it on. Without it the
                            // pen button and the Select button are lit at the
                            // same time and the pen still selects -- two
                            // mutually exclusive states both showing as
                            // current, which is worse than either being wrong.
                            // Found on the tablet, not in a test.
                            setSelecting(false)
                            sizeMax = v.pen.sizeMax
                            eraserSize = v.pen.eraseSizeMax
                            smoothing = v.pen.stabilization
                            opacity = v.pen.opacity
                            flow = v.pen.flow
                            grain = v.pen.grain.strength
                            generation++
                        }
                    },
                    exporting = exporting,
                    importing = importing,
                    onImport = { picker.launch(imageRequest) },
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = { surface?.undo(); generation++ },
                    onRedo = { surface?.redo(); generation++ },
                    stats = stats,
                    onStats = { stats = !stats; generation++ },
                    onClear = { surface?.clear(); generation++ },
                    onFit = { surface?.fitToView(); generation++ },
                    onZoom = { factor ->
                        val v = surface
                        if (v != null && v.width > 0) {
                            // About the middle of the view, not the origin: a
                            // zoom button that walks the drawing off the screen
                            // is a zoom button nobody presses twice.
                            v.applyTransform(
                                v.transform.zoomedAbout(v.width / 2f, v.height / 2f, factor),
                            )
                            generation++
                        }
                    },
                    onExport = doExport,
                )
        }
    }
}

/**
 * One filled slot, drawn.
 *
 * This `when` is the app's half of the toolbar contract: [DockHost] decides
 * *where* things go and knows nothing about what they are; this decides what a
 * [ToolItem] looks like and knows nothing about slots or docks. Adding a Phase 2
 * control is an entry in [ToolItem] and a branch here, and the compiler names
 * the branch you forgot because the `when` is exhaustive.
 *
 * [axis] is the one thing the dock has to pass through, and only the sliders
 * read it: a slider docked to the left edge has to be a vertical slider, and
 * nothing else in the catalogue changes shape when it is turned on its side.
 */
@Composable
private fun ToolSlot(
    item: ToolItem,
    axis: Axis,
    ink: Int,
    onInk: (Int) -> Unit,
    recentInks: List<Int>,
    onInkCommitted: (Int) -> Unit,
    onFixate: (ToolItem, BarSpot) -> Unit,
    sizeMax: Float,
    onSizeMax: (Float) -> Unit,
    eraserSize: Float,
    onEraserSize: (Float) -> Unit,
    layerRows: List<LayerInfo>,
    activeLayer: Int,
    onLayerOp: (LayerOp) -> Unit,
    onLayerAdd: () -> Unit,
    onLayerDuplicate: () -> Unit,
    onLayersOpen: (Boolean) -> Unit,
    selecting: Boolean,
    onSelecting: (Boolean) -> Unit,
    marqueeShape: MarqueeShape,
    onMarqueeShape: (MarqueeShape) -> Unit,
    marqueeMode: SelectMode,
    onMarqueeMode: (SelectMode) -> Unit,
    hasSelection: Boolean,
    floating: Boolean,
    onFloatOp: (FloatOp) -> Unit,
    onSelectOp: (SelectOp) -> Unit,
    smoothing: Float,
    onSmoothing: (Float) -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    flow: Float,
    onFlow: (Float) -> Unit,
    grain: Float,
    onGrain: (Float) -> Unit,
    preset: BrushPreset,
    onPreset: (BrushPreset) -> Unit,
    eraser: Boolean,
    barrel: Boolean,
    onEraser: () -> Unit,
    exporting: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    stats: Boolean,
    onStats: () -> Unit,
    onClear: () -> Unit,
    onFit: () -> Unit,
    onZoom: (Float) -> Unit,
    onExport: () -> Unit,
) {
    when (item) {
        // The one control whose face is its own value. See ColourButton.
        ToolItem.COLOUR -> ColourButton(
            ink = ink,
            onInk = onInk,
            palette = PALETTE,
            recent = recentInks,
            onCommit = onInkCommitted,
            onFixate = { onFixate(ToolItem.COLOUR_PANEL, it) },
        )

        /**
         * The same picker, kept. It is a separate catalogue entry rather than a
         * bigger [ToolItem.COLOUR] because fixate leaves the swatch where it
         * was — two things on screen at once cannot be one item under the rule
         * that an item lives in exactly one place.
         */
        ToolItem.COLOUR_PANEL -> ColourPanelCard(
            ink = ink,
            onInk = onInk,
            palette = PALETTE,
            recent = recentInks,
        )

        ToolItem.SIZE -> ToolSlider(
            ToolIcons.size, item.label, sizeMax, MIN_SIZE_MAX, MAX_SIZE_MAX, 0, axis, onSizeMax,
        )

        ToolItem.SMOOTHING -> ToolSlider(
            ToolIcons.smoothing, item.label, smoothing, 0f, 1f, 2, axis, onSmoothing,
        )

        ToolItem.OPACITY -> ToolSlider(
            ToolIcons.opacity, item.label, opacity, MIN_ALPHA, 1f, 2, axis, onOpacity,
        )

        ToolItem.FLOW -> ToolSlider(
            ToolIcons.flow, item.label, flow, MIN_ALPHA, 1f, 2, axis, onFlow,
        )

        ToolItem.GRAIN -> ToolSlider(
            ToolIcons.grain, item.label, grain, 0f, 1f, 2, axis, onGrain,
        )

        // Lit rather than disabled. The tool in the hand is a state worth
        // seeing from across the room, and a greyed-out Pen says "broken" at a
        // glance where a lit Pencil says "this one".
        //
        // `&& !selecting` on all four: while the marquee is in hand the pen
        // does not draw, and a bar that lights the pencil *and* the Select
        // button says two things are current when only one is. Found on the
        // tablet, where the screenshot showed both lit at once.
        //
        // `&& !barrel` on the three brushes, and `|| barrel` on the eraser, is
        // the same rule applied to the other momentary override: holding the
        // pen's side button erases, so while it is held the eraser is the tool
        // in the hand and the bar should say so. The toggle underneath does not
        // move — releasing the button gives the brush back, which is what a
        // pencil with a rubber on the end does — so this is the bar reporting
        // the pen rather than the bar changing state.
        ToolItem.PEN -> IconToolButton(
            icon = ToolIcons.pen,
            label = item.label,
            onClick = { onPreset(BrushPreset.PEN) },
            selected = preset == BrushPreset.PEN && !selecting && !barrel,
        )

        ToolItem.PENCIL -> IconToolButton(
            icon = ToolIcons.pencil,
            label = item.label,
            onClick = { onPreset(BrushPreset.PENCIL) },
            selected = preset == BrushPreset.PENCIL && !selecting && !barrel,
        )

        ToolItem.MARKER -> IconToolButton(
            icon = ToolIcons.marker,
            label = item.label,
            onClick = { onPreset(BrushPreset.MARKER) },
            selected = preset == BrushPreset.MARKER && !selecting && !barrel,
        )

        ToolItem.ERASER -> IconToolButton(
            icon = ToolIcons.eraser,
            label = item.label,
            onClick = onEraser,
            selected = (eraser || barrel) && !selecting,
        )

        ToolItem.ERASER_SIZE -> ToolSlider(
            ToolIcons.eraser, item.label, eraserSize,
            MIN_ERASER_SIZE, MAX_ERASER_SIZE, 0, axis, onEraserSize,
        )

        ToolItem.UNDO ->
            IconToolButton(ToolIcons.undo, item.label, onUndo, enabled = canUndo)
        ToolItem.REDO ->
            IconToolButton(ToolIcons.redo, item.label, onRedo, enabled = canRedo)

        ToolItem.MARQUEE -> IconToolButton(
            ToolIcons.marquee,
            item.label,
            { onSelecting(!selecting) },
            selected = selecting,
        )
        ToolItem.SELECTION -> SelectionButton(
            shape = marqueeShape,
            mode = marqueeMode,
            selecting = selecting,
            hasSelection = hasSelection,
            floating = floating,
            onShape = onMarqueeShape,
            onMode = onMarqueeMode,
            onOp = onSelectOp,
            onFloatOp = onFloatOp,
            onSelecting = onSelecting,
            onFixate = { onFixate(ToolItem.SELECTION_PANEL, it) },
        )

        /** The same panel, kept. See [ToolItem.SELECTION_PANEL]. */
        ToolItem.SELECTION_PANEL -> SelectionPanelCard(
            shape = marqueeShape,
            mode = marqueeMode,
            selecting = selecting,
            hasSelection = hasSelection,
            floating = floating,
            onShape = { onMarqueeShape(it); onSelecting(true) },
            onMode = onMarqueeMode,
            onOp = onSelectOp,
            onFloatOp = onFloatOp,
        )
        ToolItem.LAYERS -> LayersButton(
            layers = layerRows,
            activeId = activeLayer,
            maxLayers = LayerStack.MAX_LAYERS,
            onOp = onLayerOp,
            onAdd = onLayerAdd,
            onDuplicate = onLayerDuplicate,
            onOpenChange = onLayersOpen,
            onFixate = { onFixate(ToolItem.LAYERS_PANEL, it) },
        )

        /** The same list, kept. See [ToolItem.LAYERS_PANEL]. */
        ToolItem.LAYERS_PANEL -> LayersPanelCard(
            layers = layerRows,
            activeId = activeLayer,
            maxLayers = LayerStack.MAX_LAYERS,
            onOp = onLayerOp,
            onAdd = onLayerAdd,
            onDuplicate = onLayerDuplicate,
            onOpenChange = onLayersOpen,
        )

        ToolItem.ZOOM_IN ->
            IconToolButton(ToolIcons.zoomIn, item.label, { onZoom(ZOOM_STEP) })
        ToolItem.ZOOM_OUT ->
            IconToolButton(ToolIcons.zoomOut, item.label, { onZoom(1f / ZOOM_STEP) })
        ToolItem.FIT -> IconToolButton(ToolIcons.fit, item.label, onFit)
        ToolItem.CLEAR -> IconToolButton(ToolIcons.trash, item.label, onClear)
        ToolItem.EXPORT ->
            IconToolButton(ToolIcons.export, item.label, onExport, enabled = !exporting)
        ToolItem.IMPORT ->
            IconToolButton(ToolIcons.import_, item.label, onImport, enabled = !importing)
        ToolItem.STATS ->
            IconToolButton(ToolIcons.stats, item.label, onStats, selected = stats)
    }
}

/**
 * What the last export did, in one line that goes away.
 *
 * A `Toast` is what `:spike` used and it is the wrong instrument here: W16 films
 * the screen at 240 fps, and a floating black rectangle over the canvas for two
 * seconds after every save is in the frame. This is a line of the app's own
 * chrome, and a failure keeps it until the next attempt rather than fading —
 * the whole point of W14's sealed result is that a failed export is something
 * the user is told about.
 */
@Composable
private fun ExportStatus(export: ExportResult?, exporting: Boolean) {
    val text = when {
        exporting -> "saving…"
        export is ExportResult.Written -> "saved ${export.bytes} B to Pictures/Artiest" +
            if (export.notYetStamped > 0) "  (${export.notYetStamped} strokes not yet drawn)" else ""
        export is ExportResult.Failed -> "export failed at ${export.stage.name.lowercase()}: ${export.detail}"
        else -> null
    } ?: return
    Text(
        text = text,
        fontSize = 11.sp,
        color = if (export is ExportResult.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * The instruments, behind the Stats toggle.
 *
 * These are the synthetic drivers W9 through W13 were judged by, and they stay
 * in the shipping build on purpose: they are the only way to reproduce a
 * multi-touch gesture or an eight-case rejection sweep on a device where
 * `adb shell input` is single-touch. Hidden, not removed.
 */
@Composable
private fun DebugRow(
    surface: InkSurfaceView?,
    policy: RefreshPolicy,
    onPolicy: (RefreshPolicy) -> Unit,
    panelStatus: String,
    onForceNinety: () -> Unit,
    pinchRunning: Boolean,
    rejectRunning: Boolean,
    stressRunning: Boolean,
    onPinch: () -> Unit,
    onDoubleTap: () -> Unit,
    onReject: () -> Unit,
    onPredict: () -> Unit,
    onStamp: () -> Unit,
    onF16: () -> Unit,
    onWet: () -> Unit,
    onStress: (Float?, StrokeStress.Path) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            // Scrolls, because this row has grown a control per work item since
            // W9 and it is now wider than the tablet. A row that runs off the
            // screen hides whichever instrument was added last, which is
            // reliably the one being used.
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp),
    ) {
        Text("Hz", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        for (p in RefreshPolicy.entries) {
            TextButton(onClick = { onPolicy(p) }) {
                Text(
                    text = when (p) {
                        RefreshPolicy.HIGHEST -> "max"
                        RefreshPolicy.UNSPECIFIED -> "auto"
                        RefreshPolicy.SIXTY -> "60"
                    },
                    fontSize = 12.sp,
                    color = if (p == policy) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                )
            }
        }
        // Separate from the three above on purpose. Those three are the app's
        // *request*, which is all W16's 60-against-90 control needs and all an
        // app can normally do. This one also lifts the system cap that made
        // `max` look broken, and it is the only control here that reports
        // whether the panel actually moved.
        TextButton(onClick = onForceNinety) {
            Text("90 Hz", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (panelStatus.isNotEmpty()) {
            Text(panelStatus, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        TextButton(enabled = surface != null && !pinchRunning, onClick = onPinch) { Text("Pinch") }
        TextButton(enabled = surface != null && !pinchRunning, onClick = onDoubleTap) { Text("Tap2") }
        TextButton(enabled = surface != null && !rejectRunning, onClick = onReject) { Text("Reject") }
        TextButton(onClick = onPredict) {
            Text(if (surface?.predictionEnabled == true) "Predict ON" else "Predict off")
        }
        // W5's A/B, switchable on the device rather than across two builds:
        // the control and the candidate have to be compared at identical
        // settings or the comparison is of two sessions.
        TextButton(onClick = onStamp) {
            Text(if (surface?.stampMode == true) "Stamp ON" else "Stamp off")
        }
        // W6's format question, switchable on the device for the same reason
        // the stamp is: both arms have to run in one session.
        TextButton(onClick = onF16) {
            Text(if (surface?.scratchF16 == true) "F16" else "8888")
        }
        // The indirect path only engages for a translucent brush, so measuring
        // it needs one. This applies the real pencil preset rather than a
        // hand-set opacity, so one tap exercises the scratch buffer, the grain
        // and the tilt-driven dab together -- written straight onto the pen
        // because the point is a repeatable setting, and a slider drag is not.
        TextButton(onClick = onWet) {
            Text(if ((surface?.pen?.opacity ?: 1f) < 1f) "Pencil ON" else "Pencil off")
        }
        // Two runs, not one. See StrokeStress.start's pressure parameter: the
        // sweep is the worst case and the firm press is what most of a real
        // stroke looks like, and the pair is what shows the cost tracking dabs
        // rather than samples.
        for ((label, p, path) in STRESS_MODES) {
            TextButton(enabled = surface != null && !stressRunning, onClick = { onStress(p, path) }) {
                Text(label)
            }
        }
    }
}

/**
 * W9's numbers, and only the ones a decision hangs on.
 *
 * `spills` says whether the batch ring was ever exhausted while the render
 * thread was behind, and `peak` says how close it came — together they set
 * `DabBatchPool.DEFAULT_SLOTS`. `event` is the per-event cost against the
 * plan's 0.31 ms ceiling, in percentiles because the budget is missed by a
 * tail. `alloc` is bytes per digitizer sample during the drag, which the plan
 * predicts at about 56 — one `PenSample` and nothing else. It refuses to print
 * a figure that a collection ran through: heap-used is a level, so a GC inside
 * the window makes the delta meaningless rather than merely noisy.
 *
 * [generation] is a poll tick, read only so Compose recomputes the string. The
 * counters themselves are plain fields on the input and render paths and are
 * deliberately not Compose state — a recomposition at 321 Hz would be the most
 * expensive thing in the app, and on this screen it would also be the thing
 * being measured.
 */
private fun readout(
    surface: InkSurfaceView?,
    document: Document,
    report: DeviceReport,
    refreshHz: Float,
    policy: RefreshPolicy,
    reject: RejectionStress?,
    export: ExportResult?,
    exporting: Boolean,
    generation: Int,
    strokeTimes: String,
): String {
    if (surface == null) return "surface  -"
    val p = surface.batches
    val s = surface.stats
    val alloc = if (!s.allocationValid) {
        if (s.dragSamples == 0) "no stroke measured" else "invalidated by ${s.strokeGcs} GC"
    } else {
        "${r(s.bytesPerSample(), 1)} B/sample   " +
            "drag ${s.dragBytes} B over ${s.dragSamples}   commit ${s.commitBytes} B"
    }
    return deviceLines(report, refreshHz, policy) +
        "doc      ${document.widthPx}x${document.heightPx}   " +
        "strokes ${document.strokeCount}   t $generation\n" +
        // The undo budget, which is the number that decides whether a long
        // session quietly stops being undoable. Both caps are visible so it is
        // obvious which one bit.
        "undo     ${document.undoDepth} back   ${document.redoDepth} forward   " +
        "${r(document.historyBytes / (1024f * 1024f), 1)} MiB of " +
        "${UndoHistory.DEFAULT_MAX_BYTES / (1024L * 1024L)}   " +
        "depth cap ${UndoHistory.DEFAULT_MAX_DEPTH}\n" +
        "batches  ${p.slots} slots   peak ${p.peakInFlight}   spills ${p.spills}   " +
        // In flight *right now*, which at rest must be zero. Non-zero on an
        // idle canvas means batches were handed out and never reported drawn,
        // and the only path that does that is a cancel.
        "in flight ${p.inFlight}   " +
        "commits pending ${document.pendingCommits}\n" +
        "last     ${surface.lastStrokeSamples} samples -> ${surface.lastStrokeDabs} dabs   " +
        "${r(s.samplesPerEvent(), 2)} samples/event   ${r(s.dabsPerEvent(), 1)} dabs/event\n" +
        "event    p50 ${r(s.eventMs(0.5f), 3)}  " +
        "p95 ${r(s.eventMs(0.95f), 3)}  " +
        "p99 ${r(s.eventMs(0.99f), 3)}  " +
        "max ${r(s.eventMs(1f), 3)} ms   " +
        "over ${r(s.overBudgetRate() * 100f, 1)}% of ${s.events}\n" +
        "submit   p50 ${r(s.submitMs(0.5f), 3)}  " +
        "p95 ${r(s.submitMs(0.95f), 3)}  " +
        "p99 ${r(s.submitMs(0.99f), 3)} ms in renderFrontBufferedLayer\n" +
        latencyLine(s) +
        "sample   p50 ${r(s.msPerSample(0.5f) * 1000f, 1)}  " +
        "p99 ${r(s.msPerSample(0.99f) * 1000f, 1)} us/sample   " +
        "of a 3108 us interval\n" +
        "alloc    $alloc\n" +
        // The workspace system's own gate. `recompose N/s` must read 0 with the
        // pen on the glass -- see ChromeCounters for why zero and not "small".
        ChromeCounters.readout() + "\n" +
        "predict  ${if (surface.predictionEnabled) "ON" else "off"}   " +
        "${surface.predictor?.implementation ?: "-"}   " +
        "${surface.predictor?.availability ?: "-"}   " +
        "${surface.predictedDabs} dabs   " +
        "lead mean ${r(surface.predictLeadMeanDoc, 2)} max ${r(surface.predictLeadMaxDoc, 2)} doc px\n" +
        "gate     ${surface.gateAllowed} allowed   ${surface.gateSuppressed} suppressed   " +
        "${surface.predictSuppressedIndirect} indirect\n" +
        "press    ${r(surface.lastStrokePressureMin, 3)}..${r(surface.lastStrokePressureMax, 3)} " +
        "last stroke\n" +
        "tilt     ${r(surface.lastStrokeTiltDeg, 1)} deg max last stroke   " +
        "dab ${r(surface.lastStrokeWidthMin, 1)}..${r(surface.lastStrokeWidthMax, 1)} doc px\n" +
        "brush    ${if (surface.pen.erase) "ERASING" else "painting"}   " +
        "${surface.pen.opacity.let { if (it < 1f) "translucent" else "opaque" }}   " +
        "flow ${r(surface.pen.flow, 2)}   hard ${r(surface.pen.hardness, 2)}   " +
        "shaped ${surface.pen.hasShapeDynamics}\n" +
        "grain    ${r(surface.pen.grain.strength, 2)} strength   " +
        "${r(surface.pen.grain.scaleDocPx, 0)} doc px a tile   " +
        "${surface.grainBuilds} built\n" +
        "wetpass  ${r(surface.wetMeanMs, 3)} ms mean over ${surface.wetCalls} batches\n" +
        "dryframe ${r(surface.dryLastMs, 2)} last   ${r(surface.dryMeanMs, 2)} mean   " +
        "${r(surface.dryMaxMs, 2)} max ms   ${if (surface.dryHardware) "GPU" else "CPU"}   " +
        "over ${surface.dryCalls} frames\n" +
        "record   ${r(surface.compositeLastMs, 2)} last   ${r(surface.compositeMeanMs, 2)} mean   " +
        "${r(surface.compositeMaxMs, 2)} max ms   ${surface.drySheets} sheets recorded\n" +
        "gpuround ${r(surface.roundTripLastMs, 2)} last   ${r(surface.roundTripMeanMs, 2)} mean   " +
        "${r(surface.roundTripMaxMs, 2)} max ms   over ${surface.roundTripCalls} round trips\n" +
        "figure   ${strokeTimes.ifEmpty { "not run" }}\n" +
        "scratch  ${if (surface.scratchF16) "RGBA_F16" else "ARGB_8888"}   " +
        "${surface.scratchExtent}   ${surface.scratchAllocations} alloc   " +
        "${surface.scratchGrowths} grown\n" +
        "stamp    ${if (surface.stampMode) "ON" else "off"}   " +
        "${surface.stampCount} masks   ${surface.stampBytes / 1024} KiB   " +
        "${surface.stampUploads} uploaded   " +
        "hit ${if (surface.stampHitRate.isNaN()) "-" else r((surface.stampHitRate * 100).toFloat(), 1) + "%"}\n" +
        "xform    scale ${r(surface.transform.scale, 3)}   " +
        "rot ${r(surface.transform.rotationRad, 3)} rad   " +
        "t ${r(surface.transform.txDoc, 1)},${r(surface.transform.tyDoc, 1)}   " +
        "fitOnResize ${surface.fitOnResize}   " +
        "deferred ${surface.transformDeferrals}\n" +
        "gesture  ${surface.gestures.updates} updates   " +
        "${surface.gestures.framesDrawn} frames drawn   " +
        "${surface.gestures.framesSkipped} skipped\n" +
        rejectionLines(surface, reject) + "\n" +
        exportLine(export, exporting)
}

/**
 * W16's line: the part of the latency chain the app can see.
 *
 * Two terms, and they are wildly different sizes. `age` is how stale the newest
 * sample already was when `onTouchEvent` was handed it — the digitizer's own
 * sampling plus the framework's dispatch, which no other number in this readout
 * reaches. `app` is everything from there to `renderFrontBufferedLayer`
 * returning, which is a fifth of a millisecond. What is still missing at the end
 * of this line is the panel: SurfaceFlinger's composition and scanout, which
 * nothing inside the process can observe, and which is what the 240 fps film
 * measures. `age + app + film` is the whole chain.
 *
 * **The age is refused rather than approximated when the clocks disagree.**
 * `getEventTimeNanos` is documented in the `SystemClock.uptimeMillis` base and
 * `System.nanoTime` is `CLOCK_MONOTONIC`; on Android those are the same counter,
 * but nothing enforces it and a mismatch would produce a perfectly plausible
 * wrong number instead of a failure. So the skew is measured every time this
 * line is built, and a skew outside one millisecond replaces the figure with
 * the reason it is missing. `MotionEventsTest` explains why this check cannot
 * live in a unit test: Robolectric's `uptimeMillis` is a simulated clock and
 * answers a different question.
 */
private fun latencyLine(s: InputStats): String {
    val skew = clockSkewNanos()
    if (skew < 0L || skew >= MAX_CLOCK_SKEW_NANOS) {
        return "latency  UNMEASURABLE: nanoTime and the uptime base differ by " +
            "${r(skew / 1e6f, 1)} ms\n"
    }
    return "latency  age p50 ${r(s.inputAgeMs(0.5f), 2)}  " +
        "p95 ${r(s.inputAgeMs(0.95f), 2)}  " +
        "p99 ${r(s.inputAgeMs(0.99f), 2)} ms before onTouchEvent   " +
        "app p50 ${r(s.eventMs(0.5f), 3)} ms   " +
        "panel: film it\n"
}

/**
 * One millisecond, because `uptimeMillis` truncates to milliseconds and a
 * moment passes between the two reads. Anything larger is two clocks.
 */
private const val MAX_CLOCK_SKEW_NANOS = 1_000_000L

/** How long to wait for the compositor to actually change mode, and how often. */
private const val VERIFY_TRIES = 12
private const val VERIFY_STEP_MS = 250L

/**
 * W15's two lines: what the device is, and whether the refresh request took.
 *
 * `req` against `now` is the whole point of the toggle. On this tablet they
 * disagree by default — `mDefaultPeakRefreshRate` is 61 with
 * `mAlwaysRespectAppRequest=false`, so asking for 90 gets 60 until the system
 * peak vote is lifted with `tools/panel-90hz.sh`, which no value of
 * [RefreshPolicy] can do from inside the app — and a refresh toggle whose
 * effect cannot be read is one nobody can trust. Reading `req 90 / now 60` off
 * this very line is what eventually found that. The
 * digitizer rate tracks the panel (246.85 Hz at 60, 321.75 Hz at 90), so this
 * line is also the sample rate, indirectly.
 */
private fun deviceLines(report: DeviceReport, refreshHz: Float, policy: RefreshPolicy): String {
    val modes = report.modes.joinToString("  ") {
        "${it.widthPx}x${it.heightPx}@${r(it.refreshHz, 1)}"
    }
    val front = when (report.frontBufferSupported) {
        null -> "not asked (<API 33)"
        true -> "yes"
        false -> "NO (running in the fallback path)"
    }
    return "device   ${report.manufacturer} ${report.model}   ${report.soc}   " +
        "Android ${report.androidRelease} (API ${report.sdkInt})\n" +
        "gpu      ${report.glRenderer}   maxTexture ${report.glMaxTextureSize}   " +
        "fullCanvas ${report.supportsFullCanvas}   frontBuffer $front\n" +
        "refresh  req ${policy.name.lowercase()}   now ${r(refreshHz, 1)} Hz   modes $modes\n" +
        "mem      ${gib(report.availMemBytes)} free of ${gib(report.totalMemBytes)} GiB   " +
        "heap ${report.memoryClassMb}/${report.largeMemoryClassMb} MB\n"
}

/**
 * W13's three lines: what was refused, why strokes ended the way they did, and
 * whether the eight sequences still behave.
 *
 * `finger-begins` is the one number here that is an assertion rather than an
 * observation — "fingers and the pen's back never draw" — and its only correct
 * value is 0. `flagged` is the open question the plan could not close: whether
 * this digitizer's driver ever sets `FLAG_CANCELED`. A non-zero count while
 * drawing with a hand on the glass answers it yes; the honest reading of a zero
 * is "not seen yet", not "never".
 */
private fun rejectionLines(surface: InkSurfaceView, reject: RejectionStress?): String {
    val r = surface.rejections
    val causes = buildString {
        for (cause in CancelCause.entries) {
            val n = r.cancelsBy(cause)
            if (n > 0L) append("  ").append(cause.name.lowercase()).append(' ').append(n)
        }
    }
    val verdict = when {
        reject == null -> "not run"
        reject.running -> "running"
        else -> "${reject.passed}/${reject.passed + reject.failed} pass" +
            reject.results.filter { it.startsWith("FAIL") }.joinToString("") { "\n         $it" }
    }
    return "contacts ${r.contacts} (${r.penContacts} pen)   " +
        "dropped ${r.contactsDropped} (${r.penContactsDropped} pen)   " +
        "finger-begins ${r.fingerStrokeBegins}   flagged ${r.canceledFlagEvents}\n" +
        "strokes  ${r.strokesBegun} begun   ${r.strokesEnded} ended   " +
        // Both counts, on purpose. The router counts the decision and the view
        // counts the ink it threw away in response; they are the same number
        // unless a cancel stopped somewhere between them, which is precisely
        // the failure that would otherwise be silent.
        "${r.strokesCanceled} cancelled (${surface.strokesCanceled} dropped)" +
        "${if (causes.isEmpty()) "" else " ->$causes"}\n" +
        "reject   $verdict"
}

/**
 * W14's line. Every field on it is one the export could otherwise get wrong
 * silently.
 *
 * `notYetStamped` is the one to watch: non-zero means the file on the tablet is
 * missing strokes the document counts, and on a working render thread it never
 * is. `bytes` is there because a zero-byte PNG is precisely the artifact this
 * exporter was written to stop publishing — seeing the number is what makes
 * that checkable by looking rather than by opening the file.
 */
private fun exportLine(export: ExportResult?, exporting: Boolean): String = "export   " + when {
    exporting -> "running"
    export == null -> "not run"
    export is ExportResult.Failed -> "FAILED at ${export.stage.name.lowercase()}: ${export.detail}"
    export is ExportResult.Written ->
        "${export.bytes} B   ${export.strokes} strokes" +
            (if (export.notYetStamped > 0) " (${export.notYetStamped} NOT STAMPED)" else "") +
            "   wait ${export.waitMs} copy ${export.copyMs} encode ${export.encodeMs} " +
            "total ${export.totalMs} ms\n         ${export.uri}"
    else -> "?"
}

/**
 * Round to [places] decimals, without `String.format`.
 *
 * `String.format` and `DecimalFormat` follow the default locale, which on this
 * machine is nl-BE and prints a comma. That is banned outright in serialization
 * — an `:engine` test pins it — and while a debug readout is not serialization,
 * having exactly one rule about number formatting is cheaper than having two.
 * `Float.toString` is locale-independent by specification. `:spike`'s device
 * report used `"%.1f".format(…)` for exactly this and shipped a locale-
 * dependent probe; the port drops it.
 */
private fun r(v: Float, places: Int): String {
    var m = 1f
    repeat(places) { m *= 10f }
    return (kotlin.math.round(v * m) / m).toString()
}

private fun gib(bytes: Long): String = r(bytes / (1024f * 1024f * 1024f), 2)

/** `Brush.sizeMax`'s default, mirrored so the slider starts where the pen is. */
private const val DEFAULT_SIZE_MAX = 24f

/** `Brush.stabilization`'s default. The plan's number, on the plan's slider. */
private const val DEFAULT_SMOOTHING = 0.15f

/**
 * The floor on the opacity and flow sliders.
 *
 * Not zero. A brush at zero paints nothing, and a slider whose bottom end makes
 * the app look broken is a support question rather than a feature. 0.02 is
 * still far below the reference's median 0.27 alpha, so nothing expressive is
 * lost by refusing the one value that cannot draw.
 */
private const val MIN_ALPHA = 0.02f

/**
 * The opacity and flow the `Wet` instrument button sets.
 *
 * 0.3, near the reference's median 0.27 alpha, so the measurement is of the
 * brush the phase is actually trying to build rather than of an arbitrary
 * translucency.
 */
private const val WET_TEST_ALPHA = 0.3f

/**
 * One press of the zoom buttons. A quarter is large enough that a press is
 * visibly worth making and small enough that three of them land somewhere you
 * meant; `CanvasTransform.zoomedAbout` clamps the ends.
 */
private const val ZOOM_STEP = 1.25f

/**
 * What the photo picker is asked for: pictures, not video.
 *
 * A value rather than a call at the button, because building the request is
 * cheap but doing it inside a lambda that recomposes with the toolbar is a
 * needless allocation on a path that already has enough of them.
 */
private val imageRequest = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

private const val MIN_SIZE_MAX = 2f

/**
 * The top of the size slider, in document pixels.
 *
 * It was 48, and 48 is exactly where the retuned pencil's default sits: the
 * slider had a default pinned to its own ceiling and no way to make anything
 * wider. 120 doc px is about 8.8 mm at a fitted page, a fat carpenter's pencil,
 * which is a width worth being able to ask for.
 */
private const val MAX_SIZE_MAX = 120f

/** `Brush.eraseSizeMax`'s default, mirrored so the slider starts where the tool is. */
private const val DEFAULT_ERASER_SIZE = 96f

/**
 * The eraser slider's ends, in document pixels: about 1 mm to about 22 mm at a
 * fitted page.
 *
 * The floor is a nib-sized eraser for picking a stray line out of a hatch; the
 * ceiling is a block rubber for clearing a passage. Below the floor an eraser
 * is indistinguishable from a fingernail and above the ceiling the Clear button
 * is quicker.
 */
private const val MIN_ERASER_SIZE = 14f
private const val MAX_ERASER_SIZE = 300f

/**
 * Where the instruments start, measured off the docks rather than guessed.
 *
 * The side docks are one bar thick plus their inset, and the top dock is the
 * same again downward. Deriving it means a change to `Chrome.BAR_THICKNESS`
 * moves the readout with it instead of leaving it half underneath.
 */
private val READOUT_INSET = be.thalos.artiest.ui.Chrome.BAR_THICKNESS +
    be.thalos.artiest.ui.Chrome.EDGE_INSET * 2

private val READOUT_TOP = READOUT_INSET
