package be.thalos.artiest

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushPreset
import be.thalos.artiest.ui.ArtiestTheme
import be.thalos.artiest.ui.Axis
import be.thalos.artiest.ui.BrushStore
import be.thalos.artiest.ui.ColourButton
import be.thalos.artiest.ui.DockHost
import be.thalos.artiest.ui.DockLayout
import be.thalos.artiest.ui.DockStore
import be.thalos.artiest.ui.IconToolButton
import be.thalos.artiest.ui.ToolIcons
import be.thalos.artiest.ui.ToolSlider
import be.thalos.artiest.doc.UndoHistory
import be.thalos.artiest.engine.input.CancelCause
import be.thalos.artiest.input.clockSkewNanos
import be.thalos.artiest.io.ExportResult
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

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The bars the user built. Loaded once and written on every change: the
    // changes are rare and a layout that does not survive a force-quit is not a
    // layout anyone will invest in arranging.
    val store = remember { DockStore(context) }
    var docks by remember { mutableStateOf(store.load()) }
    var arranging by remember { mutableStateOf(false) }
    var floatingAt by remember {
        val (x, y) = store.loadFloatingAt()
        mutableStateOf(Offset(x, y))
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
            pen.erase = b.erase
            preset.applyToShapeOnly(v.pen)
        }
        sizeMax = v.pen.sizeMax
        smoothing = v.pen.stabilization
        opacity = v.pen.opacity
        flow = v.pen.flow
        grain = v.pen.grain.strength
    }

    LaunchedEffect(surface, ink, sizeMax, smoothing, opacity, flow, grain) {
        val v = surface ?: return@LaunchedEffect
        v.inkColorArgb = ink
        v.pen.sizeMax = sizeMax
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
        surface?.redrawDry()
        exporting = true
        export = null
        scope.launch {
            export = PngExporter.export(context, document)
            exporting = false
            generation++
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
                    onView(it)
                }
            },
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
            if (stats) {
                Text(
                    text = readout(
                        surface, document, report, refreshHzNow(), policy,
                        reject, export, exporting, generation,
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
            onLayout = { docks = it; store.save(it) },
            arranging = arranging,
            onArranging = { arranging = it },
            floatingAt = floatingAt,
            onFloatingAt = { floatingAt = it; store.saveFloatingAt(it.x, it.y) },
        ) { item, axis ->
                ToolSlot(
                    item = item,
                    axis = axis,
                    ink = ink,
                    recentInks = recentInks,
                    onInkCommitted = { recentInks = store.pushRecentColour(it) },
                    onInk = { ink = it },
                    sizeMax = sizeMax,
                    onSizeMax = { sizeMax = it },
                    smoothing = smoothing,
                    onSmoothing = { smoothing = it },
                    opacity = opacity,
                    onOpacity = { opacity = it },
                    flow = flow,
                    onFlow = { flow = it },
                    grain = grain,
                    onGrain = { grain = it },
                    eraser = eraser,
                    onEraser = {
                        eraser = !eraser
                        surface?.eraserTool = eraser
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
                            sizeMax = v.pen.sizeMax
                            smoothing = v.pen.stabilization
                            opacity = v.pen.opacity
                            flow = v.pen.flow
                            grain = v.pen.grain.strength
                            generation++
                        }
                    },
                    exporting = exporting,
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
    sizeMax: Float,
    onSizeMax: (Float) -> Unit,
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
    onEraser: () -> Unit,
    exporting: Boolean,
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
        ToolItem.PEN -> IconToolButton(
            icon = ToolIcons.pen,
            label = item.label,
            onClick = { onPreset(BrushPreset.PEN) },
            selected = preset == BrushPreset.PEN,
        )

        ToolItem.PENCIL -> IconToolButton(
            icon = ToolIcons.pencil,
            label = item.label,
            onClick = { onPreset(BrushPreset.PENCIL) },
            selected = preset == BrushPreset.PENCIL,
        )

        ToolItem.ERASER -> IconToolButton(
            icon = ToolIcons.eraser,
            label = item.label,
            onClick = onEraser,
            selected = eraser,
        )

        ToolItem.UNDO ->
            IconToolButton(ToolIcons.undo, item.label, onUndo, enabled = canUndo)
        ToolItem.REDO ->
            IconToolButton(ToolIcons.redo, item.label, onRedo, enabled = canRedo)

        ToolItem.ZOOM_IN ->
            IconToolButton(ToolIcons.zoomIn, item.label, { onZoom(ZOOM_STEP) })
        ToolItem.ZOOM_OUT ->
            IconToolButton(ToolIcons.zoomOut, item.label, { onZoom(1f / ZOOM_STEP) })
        ToolItem.FIT -> IconToolButton(ToolIcons.fit, item.label, onFit)
        ToolItem.CLEAR -> IconToolButton(ToolIcons.trash, item.label, onClear)
        ToolItem.EXPORT ->
            IconToolButton(ToolIcons.export, item.label, onExport, enabled = !exporting)
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
        "predict  ${if (surface.predictionEnabled) "ON" else "off"}   " +
        "${surface.predictor?.implementation ?: "-"}   " +
        "${surface.predictor?.availability ?: "-"}   " +
        "${surface.predictedDabs} dabs   " +
        "lead mean ${r(surface.predictLeadMeanDoc, 2)} max ${r(surface.predictLeadMaxDoc, 2)} doc px\n" +
        "gate     ${surface.gateAllowed} allowed   ${surface.gateSuppressed} suppressed   " +
        "${surface.predictSuppressedIndirect} indirect\n" +
        "brush    ${if (surface.pen.erase) "ERASING" else "painting"}   " +
        "${surface.pen.opacity.let { if (it < 1f) "translucent" else "opaque" }}   " +
        "flow ${r(surface.pen.flow, 2)}   hard ${r(surface.pen.hardness, 2)}   " +
        "shaped ${surface.pen.hasShapeDynamics}\n" +
        "grain    ${r(surface.pen.grain.strength, 2)} strength   " +
        "${r(surface.pen.grain.scaleDocPx, 0)} doc px a tile   " +
        "${surface.grainBuilds} built\n" +
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

private const val MIN_SIZE_MAX = 2f
private const val MAX_SIZE_MAX = 48f

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
