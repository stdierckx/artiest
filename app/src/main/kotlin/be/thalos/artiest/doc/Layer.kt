package be.thalos.artiest.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Looper

/**
 * The document's pixels: one ARGB_8888 `Bitmap`, one `Canvas` over it, and the
 * lock that is the whole of Phase 1's threading contract.
 *
 * This is why `Document` and `Layer` are in `:app` while `Stroke` and `Bounds`
 * are in `:engine`. `android.graphics.Bitmap` does not resolve in a
 * `kotlin("jvm")` module, so this type cannot move down; `:engine` cannot depend
 * on `:app` — a project cycle, since app/build.gradle.kts already depends on
 * `:engine`, and a variant mismatch on top of it — so W7's `StrokeBuilder`
 * could never see a `Bounds` that lived up here. The work plan's row for this
 * item says `:app` for all three types; the module tree says otherwise, and the
 * tree is the one that compiles.
 *
 * **Alpha-carrying, and that is the invariant this class exists to hold.**
 * Paper white is drawn by the renderer, at draw time, into the frame — it is
 * never painted into these pixels. Bake it in and the layer stops being a layer:
 * Phase 3's stack composites onto nothing, and Phase 4's `.ora` writes an opaque
 * sheet where a transparent one belongs. Nothing about the app looks wrong while
 * that is true, which is why [blank] exists rather than leaving the clear path
 * to write its own `drawColor` — `drawColor(WHITE)` at the clear site is the
 * exact shape of the mistake, and it renders identically until two phases later.
 *
 * **The threading contract.** The pixels are written on the render thread only,
 * in the commit step at pen-up, under this lock; `PngExporter` takes the same
 * lock to read; the UI thread never touches them. The lock is a plain monitor,
 * not a `ReentrantReadWriteLock` and not a `ReentrantLock`. There is one writer
 * thread, which never contends with its own reads, and one cross-thread pair —
 * export reading against commit writing — so a read/write lock buys nothing and
 * costs more uncontended, and the only `ReentrantLock` feature reachable here is
 * `tryLock(timeout)`, which has no correct fallback: a failed commit silently
 * loses a finished stroke, and a failed blit draws paper with no ink, which is a
 * full-canvas flash rather than one late frame. Reentrancy is not used today:
 * the commit callback stamps under this lock and then blits under it again,
 * sequentially, inside one frame — two acquisitions, not a nesting. A plain
 * monitor is reentrant anyway, so nesting stays legal if W8 ever wants it, but
 * do not cite reentrancy as a requirement until something actually nests.
 *
 * **The lock is a leaf.** While holding it a thread may touch these pixels and
 * nothing else — no renderer entry point, no `Handler.post`-and-wait, no
 * `CountDownLatch.await`, no `Thread.join`, no second `Bitmap` allocation, no
 * suspension. Two deadlocks are reachable, not one. The UI thread holding this
 * lock while `surfaceRedrawNeeded` awaits the render thread on an *untimed*
 * `CountDownLatch` is the first, and every entry point below is shaped so the
 * UI thread has no way to hold the lock across a call. The mirror is [close] on
 * the UI thread parking on this monitor behind a render thread wedged inside
 * [read] — `LayerTest` pins that it parks rather than proceeding — which [close]
 * cannot bound from inside, so the bound lives at its call site. Both are
 * permanent ANRs with no crash and no stack, not glitches.
 *
 * One clause of that rule has teeth; the rest is prose. The compiler refuses a
 * suspension point inside a `synchronized` block, and the refusal propagates
 * through these inline accessors — a `suspend` function awaiting anything inside
 * [read] fails with "A suspension point at … is inside a critical section",
 * checked against this class rather than taken on trust. So `PngExporter`, which
 * is `suspend`, cannot *await* anything on-lock: a `withContext` or a channel
 * receive inside [read] does not compile. It can still call `Bitmap.compress`
 * there, because a blocking call is not a suspension point — one full-size PNG
 * encode of this layer measured 177 ms inside [read] on the host, and nothing
 * but review stops that happening under this monitor. The rule W14 owes is to
 * copy on-lock and compress off it. Even that one clause holds only for the
 * scoped forms: replace these with a manual `lock()`/`unlock()` pair and the
 * last compiler-checked thing here reverts to prose too.
 *
 * There is no public `Bitmap` and no getter for one, and [write], [read] and
 * [blank] are the only ways in *that Kotlin source can name*: they take the lock
 * before handing anything over and check [alive] inside it. That is the shape of
 * the contract, not an enforcement of it. `block` is not `crossinline`, so
 * `read { escaped = it }` stashes the `Bitmap` in one line and writes it
 * off-lock, which `LayerTest` does deliberately — the gap is pinned as an
 * invariant rather than left as a surprise. **A handed-out `Bitmap` or `Canvas`
 * is valid only for the dynamic extent of the block**, and not retaining it is
 * the one rule here the compiler cannot hold. What the escape cannot do is
 * corrupt freed memory: `Bitmap` refuses `getPixel`/`setPixel` on a recycled
 * instance itself, and throws. The lock buys the *live* pixels; the recycle
 * guard is Skia's. `LayerTest` asserts the absence of a named accessor by
 * reflection, against a deliberately leaky control, because it is a property no
 * other kind of test can state.
 */
class Layer(
    val widthPx: Int,
    val heightPx: Int,
    /**
     * Whether the accessors refuse the main thread.
     *
     * On by default and off in unit tests, because Robolectric runs test bodies
     * on the main looper: a test that forgets to turn it off fails everywhere at
     * once, which reads as a broken assertion and invites deleting the check.
     *
     * It detects the contract's breach, it does not prevent it. Java cannot name
     * the library's render thread ahead of time, and latching the first writer's
     * identity would be worse than useless — the render thread is recreated on
     * every attach, so a detach/attach cycle would start rejecting the real
     * writer. "Not the main thread" is the half that is both stable and exactly
     * the failure the contract names.
     */
    private val enforceOffMainThread: Boolean = true,
) {

    /**
     * What the lock protects: these pixels and their liveness.
     *
     * Not a mutable reference — the `Bitmap` is final and is never replaced.
     * Phase 1 has no resize and no reallocation: the document is fixed at
     * 2160x3300, a new document is a new [Layer], and rotation changes only the
     * matrix. That deletes a whole class of race by construction rather than by
     * synchronizing it.
     *
     * ARGB_8888 is the only config that works, and three of the four
     * alternatives are ruled out by the framework rather than by taste.
     * HARDWARE is immutable by definition, so `Canvas` refuses it as a target
     * and `getPixels` refuses to read it back; RGB_565 carries no alpha at all;
     * ARGB_4444 is deprecated and silently upgraded, and bands an antialiased
     * rim. RGBA_F16 works and costs 54.4 MiB a copy instead of 27.2 for
     * precision that a bilinear sampler at a 0.5 scale floor and an 8-bit panel
     * both discard.
     *
     * 2160 x 3300 x 4 = 28,512,000 bytes = 27.19 MiB, and real Skia agrees to
     * the byte (`LayerTest` asserts the `byteCount`). The peak Phase 1 graphics
     * footprint is roughly 121 MiB — this, plus a transient export copy, plus a
     * GPU-side copy, plus four view-sized swap-chain buffers — against 4.4 GiB
     * measured free on this device (W0). The 4.4 GiB is a device measurement;
     * the 121 MiB is not. That figure is arithmetic whose swap-chain count is a
     * bytecode read of graphics-core 1.0.4 and whose GPU-side copy is assumed to
     * be one. W9/W10's allocation trace is where it becomes a measurement.
     *
     * `createBitmap` is not null-checked, and that is deliberate: it does not
     * null-check `nativeCreate` either — the framework relies on the native
     * allocator throwing — so `?: fail` here would be dead code and only a
     * `try`/`catch (OutOfMemoryError)` could ever see a failure. There is no
     * catch, and no sealed result, because the failure is not reachable at this
     * size: this allocation is 0.6% of free memory and even the whole peak
     * leaves 37x headroom. If Phase 2 makes the document size configurable this
     * is the line that needs a result type. Until then, note what the failure
     * actually looks like, because it is worse than it sounds: the framework
     * logs `OOM allocating Bitmap with dimensions 2160 x 3300` to logcat and
     * then throws a bare `OutOfMemoryError` with a **null message** (measured,
     * not assumed), so the stack trace is the only thing pointing at this line
     * and the byte count is only in logcat. That is judged enough here, at an
     * unreachable failure; a result nobody branches on is a result nobody
     * surfaces.
     *
     * Premultiplied, which `createBitmap` gives and which nothing may change.
     * `setPremultiplied(false)` to "protect" the alpha invariant makes the very
     * next `drawBitmap(layer, …)` in the render body throw ("trying to use a
     * non-premultiplied bitmap"). It is lossless for Phase 1 anyway: black is
     * the fixed point of premultiplication, so an antialiased black dab round
     * trips exactly at all 256 alphas, and the export flattens onto opaque white
     * where straight and premultiplied alpha coincide. It stops being lossless
     * for coloured ink at low alpha — worst case 127/255, at c=128, a=1 — which
     * is Phase 2's brushes and Phase 4's `.ora` export of the layer itself.
     */
    private val bitmap: Bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)

    /**
     * One `Canvas` for the layer's life, held in a field. `:spike`'s
     * `DirectSurfaceInkView` does the same; a `Canvas` per commit would allocate
     * on the render thread inside the frame it is trying to land.
     */
    private val canvas: Canvas = Canvas(bitmap)

    private val lock = Any()

    /** Read and written only under [lock]. */
    private var alive = true

    /**
     * How many [write]/[read] blocks are running on this thread right now. Read
     * and written only under [lock], which is why it needs no thread-local: a
     * thread that is *not* holding the monitor cannot observe a non-zero value,
     * because it cannot get in to look. So `depth > 0` seen from inside the lock
     * means this same thread reentered — the only case [close] has to refuse.
     */
    private var depth = 0

    /**
     * Rasterize into the layer. Returns false, having never run [block], if the
     * layer is closed.
     *
     * `inline`, and it is not a micro-optimisation: the non-inline form
     * allocates a `Function1` at every call site, and this is the render
     * thread's commit path. Inlined, the call site compiles to a
     * monitorenter/monitorexit pair with the body spliced between them —
     * `javap` of `LayerTest`'s call sites shows the `drawCircle` between the two
     * monitor instructions, reaching the private fields through static synthetic
     * accessors, with no `new` and no `invokedynamic` on that path.
     *
     * **The `Canvas` is valid for the extent of [block] and no longer.** It is
     * an ordinary reference and nothing stops a caller stashing it and drawing
     * off-lock, into a bitmap another thread may be reading or [close] may have
     * recycled. That is the one rule on this class the compiler cannot hold.
     */
    internal inline fun write(block: (Canvas) -> Unit): Boolean {
        requireOffMainThread("write")
        synchronized(lock) {
            if (!alive) return false
            depth++
            try {
                block(canvas)
            } finally {
                depth--
            }
            return true
        }
    }

    /**
     * Read the layer: the per-frame blit, and `PngExporter`'s copy. Returns
     * false, having never run [block], if the layer is closed.
     *
     * Against the writer this is a different thread; against another reader it
     * needs nothing. The lock on this path is about *lifecycle*, not pixels — it
     * is what makes a late frame arriving during teardown find `alive == false`
     * and draw paper with no ink, instead of reading recycled memory and killing
     * the process from the render thread with no usable stack, which is the
     * failure `:spike` recorded and worked around by ordering alone.
     *
     * Hands over the `Bitmap` and not a `Canvas`, so W14 can shorten its
     * critical section to a single `Bitmap`-level copy: allocate the transient
     * bitmap before taking the lock, copy on-lock, and composite paper off-lock.
     * A `Canvas`-only accessor would foreclose that, and the stall it shortens
     * is real — a 27.2 MiB copy on-lock blocks a concurrent commit for low tens
     * of milliseconds.
     *
     * **The `Bitmap` is valid for the extent of [block] and no longer.** The
     * handout is deliberate and W14 needs it, but the reference is an ordinary
     * one: `read { escaped = it }` keeps it, and a retained reference outlives
     * [close] into a recycled bitmap. Nothing here prevents that — `Bitmap`'s
     * own recycle guard turns it into an `IllegalStateException` rather than a
     * corrupted read, which is the floor, not the contract. Copy inside the
     * block; carry the copy out.
     */
    internal inline fun read(block: (Bitmap) -> Unit): Boolean {
        requireOffMainThread("read")
        synchronized(lock) {
            if (!alive) return false
            depth++
            try {
                block(bitmap)
            } finally {
                depth--
            }
            return true
        }
    }

    /**
     * Back to fully transparent. Returns false if the layer is closed.
     *
     * `Color.TRANSPARENT` through `PorterDuff.Mode.CLEAR`, which is `:spike`'s
     * `OP_CLEAR` verbatim. A named method rather than leaving the clear path to
     * write its own `drawColor`, because the tempting wrong version —
     * `drawColor(paperWhite)` — produces an identical screen and destroys the
     * alpha invariant silently. `LayerTest` asserts the cleared pixels are
     * alpha 0, which is the assertion that fails on that mutation.
     *
     * Render thread, like every other write. The plan's clear button blanks the
     * layer from the UI thread, which contradicts the threading contract stated
     * four sections earlier in the same document; W8 resolves that in the
     * contract's favour, with a `pendingClear` flag consumed inside
     * `onDrawMultiBufferedLayer` exactly as `pendingStroke` is. The three-call
     * ordering the plan requires — blank, `renderer.clear()`,
     * `renderMultiBufferedLayer(emptyList())` — survives that unchanged.
     */
    fun blank(): Boolean = write { it.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR) }

    /**
     * Back to transparent, but only where [mask] covers.
     *
     * What Clear means with a selection on the page: rub out the stencil's
     * inside and leave the rest. `DST_OUT` subtracts the source's alpha from
     * the destination's, so an `ALPHA_8` mask takes away exactly the coverage
     * it carries — a soft selection edge leaves a soft edge on the ink rather
     * than a cut one.
     *
     * A separate method rather than a nullable parameter on [blank], because
     * the two are different operations and the null branch is the one that
     * would be got wrong: `blank(null)` reads as "blank nothing".
     */
    fun blank(mask: Bitmap): Boolean =
        write { it.drawBitmap(mask, 0f, 0f, subtractPaint) }

    /** Shared, for the reason [replacePaint] is. Never mutated after construction. */
    private val subtractPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        isAntiAlias = false
        isFilterBitmap = false
    }

    /**
     * **There is no `copyRegion` here, and that is deliberate.**
     *
     * A method returning a `Bitmap` — even a fresh copy that owes the layer
     * nothing — puts `Bitmap` on this class's public surface, and `LayerTest`'s
     * reflective check refuses that outright. The check is coarser than the
     * property it defends, but it is coarse in the safe direction: it cannot
     * tell a copy from the original, so it forbids both, and the alternative is
     * a rule that has to be re-argued at every call site. [read]'s own KDoc
     * says how to take pixels out — copy inside the block, carry the copy out —
     * and `PixelPatch.capture` is the one place that does it.
     */
    /**
     * Put a rectangle of pixels back, **alpha and all**.
     *
     * `Mode.SRC`, not the default source-over, and the distinction is the whole
     * method. This layer is alpha-carrying: unpainted pixels are transparent,
     * and that invariant is what lets `PngExporter` composite paper underneath.
     * Compositing a patch over the top would restore colour and leave every
     * pixel the undone stroke had made opaque still opaque — ink would vanish
     * and its shadow would stay. `SRC` replaces the destination outright, which
     * is what "put it back the way it was" means.
     */
    fun restoreRegion(x: Int, y: Int, src: Bitmap): Boolean =
        write { it.drawBitmap(src, x.toFloat(), y.toFloat(), replacePaint) }

    /**
     * Shared, because [restoreRegion] runs on the render thread and a `Paint`
     * per undo is an allocation on the one path with a measured budget. Never
     * mutated after construction.
     */
    private val replacePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        isAntiAlias = false
        isFilterBitmap = false
    }

    /**
     * Release the pixels. Idempotent, and the one operation the UI thread
     * performs.
     *
     * **Only after the render thread is provably stopped.** `:spike` paid for
     * that ordering and states it: drop the surface-valid flag first, quit, join
     * with a bound, and recycle only `if (stopped)` — a leaked bitmap on a
     * wedged teardown is a far better outcome than a use-after-recycle. The lock
     * here is the belt to that braces: it makes a late arrival return false
     * rather than draw into freed memory. Never hold this lock across the join
     * itself — a render thread blocked on it while the UI thread joins that
     * thread from inside it is the mirror-image deadlock.
     *
     * **Only half of the spike's precedent is in this method, and the other half
     * is the caller's.** This call parks on the monitor with no bound, so a
     * render thread wedged inside [read] turns `onDestroy` into an indefinite
     * main-thread block — an ANR with no crash and no stack. `close` is the
     * spike's `if (stopped) layer?.recycle()` line, not a replacement for the
     * guard around it: W8 owes the bounded join and must call this only on the
     * `stopped == true` branch, preferring a leaked 27.19 MiB to a wedged
     * teardown. If W8 ever cannot guarantee that, this is the one place a
     * `ReentrantLock.tryLock(timeout)` earns its keep, because here the fallback
     * — leak rather than park — is correct rather than lossy.
     *
     * Refuses to run from inside a [write] or [read] block. The monitor is
     * reentrant, so without the check a block could recycle the bitmap it is
     * holding a live `Canvas` over and keep drawing into freed pixels, with the
     * lock held throughout and [alive] already checked — a use-after-recycle
     * straight through the public API.
     *
     * Not called at view detach. The `Document` outlives the view: detach
     * releases the *renderer*, and the layer is untouched, so a rotation cannot
     * lose the drawing and the 27.19 MiB is allocated exactly once per document.
     */
    fun close() {
        synchronized(lock) {
            check(depth == 0) { "Layer.close() from inside write/read" }
            if (!alive) return
            alive = false
            bitmap.recycle()
        }
    }

    /** False once [close] has run. For tests and for assertions; nothing branches on it. */
    internal val isOpen: Boolean get() = synchronized(lock) { alive }

    // `write` and `read` are inline, so their bodies are compiled into their
    // callers and reach the private state above through compiler-generated
    // synthetic accessors rather than directly. `internal inline` is allowed to
    // do that — only a *public* inline function is refused — so the fields stay
    // genuinely private, nothing needs @PublishedApi, and there is no named
    // member handing out the Bitmap for `LayerTest`'s reflection check to find.
    // The synthetic bridges are why that check has to skip synthetic members,
    // which otherwise looks like the test excusing itself.
    private fun requireOffMainThread(what: String) {
        if (!enforceOffMainThread) return
        check(Looper.myLooper() !== Looper.getMainLooper()) {
            "Layer.$what on the main thread; the layer is the render thread's"
        }
    }
}
