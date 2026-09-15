package be.thalos.artiest.model

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 3D reference, as a renderer that owns everything except the surface.
 *
 * Lr12. One of these exists for as long as the screen does. It is **not** tied
 * to the pane it draws into, and that split is the whole design:
 *
 * The reference pane is a panel, and a panel leaves the composition every time
 * the bars are rearranged, every time it is fixated to an edge, and every time
 * its popup is closed. Everything the panel holds goes with it. That is the
 * defect `PaneView` was written for — *"go into arrange mode and come back, and
 * the zoom is gone"* — and a 3D model has far more to lose than a zoom: the
 * mesh on the GPU, the materials compiled for it, and a Filament engine that
 * costs a tenth of a second to build.
 *
 * So the engine, the scene, the lights and the loaded model live here, and the
 * `SwapChain` — the only thing that genuinely belongs to a surface — is made
 * and destroyed by [ModelPane] as the `TextureView` comes and goes.
 *
 * ## It draws when asked and never otherwise
 *
 * There is no render loop in this class. [frame] draws exactly one frame, and
 * the pane calls it when something has changed: a new model, a drag, a light
 * moved, a resize. A reference is still until you move it, so an idle pane
 * costs nothing at all — which matters more here than in any normal 3D app,
 * because the thing sharing this GPU is a front-buffered canvas whose whole
 * purpose is for the ink to arrive under the nib.
 *
 * ## Why Filament
 *
 * Apache-2.0. That is not a footnote: this repository's rule for the drawing
 * engines it learns from is read-and-reimplement, because they are GPL, and
 * that rule would have applied to a 3D engine too. Filament's licence is the
 * project's own, so it can simply be used.
 *
 * ## No environment map, and why the light still looks like a studio
 *
 * The usual way to light a model well is an HDR photograph of a room, prefiltered
 * into a cubemap. It looks wonderful and it costs a megabyte and a half of
 * binary sitting in the repository for ever. Three directional lights and a flat
 * ambient cost nothing, are readable as code, and are what a life-drawing room
 * actually is: a key, a fill, something behind, and the grey of the walls.
 *
 * It also makes the feature the user asked for honest. *Moving the lighting*
 * means moving lights, and these are lights — two angles drive all three of
 * them, and the model does not move while they do.
 */
class ModelStage(context: Context) {

    private val assets: AssetManager = context.applicationContext.assets

    private var engine: Engine? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var renderer: Renderer? = null

    private var materials: UbershaderProvider? = null
    private var loader: AssetLoader? = null
    private var resources: ResourceLoader? = null
    private var asset: FilamentAsset? = null

    private var keyLight = 0
    private var fillLight = 0
    private var rimLight = 0
    private var ambient: IndirectLight? = null
    private var contour: Material? = null
    private var contourInstance: MaterialInstance? = null
    private var plainLook: ColorGrading? = null
    private var greyLook: ColorGrading? = null

    /** The middle of the model and how big it is, in the model's own units. */
    private var centreX = 0f
    private var centreY = 0f
    private var centreZ = 0f
    private var radius = 1f

    /** The longest half-side of the box. What the contour spacing is a share of. */
    private var reach = 1f

    /** Whether [start] has run and succeeded. Nothing else here works until it has. */
    var ready: Boolean = false
        private set

    /** Whether a model is loaded and has something to draw. */
    var loaded: Boolean = false
        private set

    /** Why the last [start] or [open] failed, in words. Empty when nothing has. */
    var trouble: String = ""
        private set

    /**
     * Build the engine. True if there is a renderer now.
     *
     * Costs about a tenth of a second the first time, most of it loading two
     * native libraries, so it is called when the first model is opened rather
     * than when the app starts: a learner who never touches 3D never pays it.
     */
    fun start(): Boolean {
        if (ready) return true
        return runCatching {
            Gltfio.init()
            val engine = Engine.create()
            this.engine = engine
            renderer = engine.createRenderer()
            scene = engine.createScene()
            camera = engine.createCamera(EntityManager.get().create())
            view = engine.createView()

            // The exposure of a camera in a lit room. Every light intensity
            // below is in lux and means what it says only against these three
            // numbers, so they are here and not spread through the rig.
            camera?.setExposure(APERTURE, SHUTTER, SENSITIVITY)

            // The pane's background is Compose's, not the renderer's. Drawing
            // it here instead was tried and is subtly wrong: a background
            // painted inside the scene goes through the tone mapper with
            // everything else, so the same grey the panel is drawn in comes out
            // at (36,45,57) where the card beside it is (50,57,66) -- close
            // enough to look like a mistake and not close enough to be one.
            // Rendering onto nothing and letting the card show through is exact.
            renderer?.clearOptions = Renderer.ClearOptions().apply {
                clear = true
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }

            view?.let { v ->
                v.scene = scene
                v.camera = camera
                v.blendMode = View.BlendMode.TRANSLUCENT
                // FXAA and not MSAA: this is a panel a few hundred pixels
                // across and the cost of four samples a pixel is four times the
                // fill rate of the thing that has to stay out of the canvas's
                // way. Multi-sampling is off by default and is left that way
                // rather than said out loud, because the setter that says it is
                // deprecated.
                v.antiAliasing = View.AntiAliasing.FXAA
                v.isPostProcessingEnabled = true
                v.dithering = View.Dithering.TEMPORAL
                v.setShadowingEnabled(true)
            }

            loadContourMaterial(engine)
            plainLook = ColorGrading.Builder().build(engine)
            greyLook = ColorGrading.Builder().saturation(0f).build(engine)
            view?.colorGrading = plainLook

            buildLights(engine)
            ready = true
            trouble = ""
            true
        }.getOrElse { e ->
            Log.w(TAG, "could not start", e)
            trouble = e.message ?: "3D could not start on this tablet"
            shutdown()
            false
        }
    }

    /**
     * The contour material, compiled at build time from `contour.mat`.
     *
     * A failure here is not fatal and deliberately so: the model still opens and
     * still turns, and the one thing that stops working is the contour button.
     * A reference pane that refuses to show a bust because a shader did not load
     * would be the worse failure by far.
     */
    private fun loadContourMaterial(engine: Engine) {
        runCatching {
            val bytes = assets.open(CONTOUR_ASSET).use { it.readBytes() }
            val payload = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
            val material = Material.Builder().payload(payload, bytes.size).build(engine)
            contour = material
            contourInstance = material.createInstance()
        }.onFailure { Log.w(TAG, "no contour material", it) }
    }

    /**
     * Key, fill, rim, and the grey of the room.
     *
     * The three-point rig every life-drawing room and every photographic studio
     * uses, because it is the one that reads form: a strong light from one side
     * above to carve the planes, a weak cool one from the other to keep the
     * shadow side from going to nothing, and a third behind to lift the edge
     * away from the background.
     *
     * Only the key casts a shadow. Three shadow maps for a single object is
     * three times the cost for two shadows nobody is studying.
     */
    private fun buildLights(engine: Engine) {
        val em = EntityManager.get()

        keyLight = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.97f, 0.92f)
            .intensity(KEY_LUX)
            .direction(0f, -1f, -1f)
            .castShadows(true)
            .build(engine, keyLight)

        fillLight = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.82f, 0.88f, 1.0f)
            .intensity(FILL_LUX)
            .direction(0f, -1f, -1f)
            .castShadows(false)
            .build(engine, fillLight)

        rimLight = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(RIM_LUX)
            .direction(0f, -1f, -1f)
            .castShadows(false)
            .build(engine, rimLight)

        // One band of spherical harmonics is a constant: light of the same
        // colour from every direction at once. It is the cheapest ambient there
        // is and it is exactly what the walls of a room do.
        ambient = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(AMBIENT_R, AMBIENT_G, AMBIENT_B))
            .build(engine)

        scene?.let { s ->
            s.addEntity(keyLight)
            s.addEntity(fillLight)
            s.addEntity(rimLight)
            s.indirectLight = ambient
        }
    }

    /**
     * Put a `.glb` on the stage, replacing whatever was there.
     *
     * [look] is passed in rather than switched afterwards on purpose. Turning a
     * scanned material into matte grey means writing over its colour, its
     * texture and its roughness, and there is no way to read back what those
     * were — so the way back is to load it again. A reload is the cost of one
     * frame for a model of the size this library holds, and it cannot be
     * subtly wrong, which the alternative can.
     */
    fun open(glb: ByteArray, look: Look, slices: Float): Boolean {
        if (!start()) return false
        val engine = engine ?: return false
        close()
        return runCatching {
            val materials = materials ?: UbershaderProvider(engine).also { this.materials = it }
            val loader = loader ?: AssetLoader(engine, materials, EntityManager.get())
                .also { this.loader = it }
            val resources = resources ?: ResourceLoader(engine).also { this.resources = it }

            // A direct buffer, because gltfio reads it from its own threads and
            // a heap array would have to be copied there anyway.
            val buffer = ByteBuffer.allocateDirect(glb.size).put(glb).apply { rewind() }
            val made = loader.createAsset(buffer)
                ?: throw IllegalStateException("the model would not open")
            resources.loadResources(made)
            // The source bytes are gltfio's copy, not ours, and they are no use
            // once the meshes are on the GPU. Dropping them here is the
            // difference between a sixty-megabyte model costing sixty megabytes
            // and a hundred and twenty.
            made.releaseSourceData()

            scene?.addEntities(made.entities)
            asset = made
            measure(made)
            when (look) {
                Look.SCANNED -> Unit
                Look.CLAY -> applyClay()
                Look.CONTOUR -> {
                    applyContour()
                    density(slices)
                }
            }
            loaded = true
            trouble = ""
            true
        }.getOrElse { e ->
            Log.w(TAG, "could not open model", e)
            trouble = e.message ?: "the model would not open"
            close()
            false
        }
    }

    /** Take the model off the stage. The engine and the lights stay. */
    fun close() {
        val made = asset ?: return
        scene?.removeEntities(made.entities)
        loader?.destroyAsset(made)
        asset = null
        loaded = false
    }

    /** Where the model is and how big, so the camera can be put outside it. */
    private fun measure(made: FilamentAsset) {
        val box = made.boundingBox
        val centre = box.center
        val half = box.halfExtent
        centreX = centre[0]
        centreY = centre[1]
        centreZ = centre[2]
        radius = sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2])
        if (!radius.isFinite() || radius <= 0f) radius = 1f
        reach = maxOf(half[0], half[1], half[2])
        if (!reach.isFinite() || reach <= 0f) reach = radius
    }

    /**
     * Matte grey, no texture, no shine.
     *
     * What an artist studying form actually wants: a maquette. A photographic
     * scan tells you about marble and dust; the same mesh in clay tells you
     * about the planes, because the only thing left in the picture is the light
     * falling on them.
     *
     * Each primitive separately and each one guarded, because a glTF may carry
     * materials whose parameters these are not — an unlit one has no roughness
     * — and one such primitive must not cost the other forty.
     */
    private fun applyClay() {
        val made = asset ?: return
        val rm = engine?.renderableManager ?: return
        for (entity in made.renderableEntities) {
            val renderable = rm.getInstance(entity)
            if (renderable == 0) continue
            for (i in 0 until rm.getPrimitiveCount(renderable)) {
                val material = rm.getMaterialInstanceAt(renderable, i) ?: continue
                runCatching { material.setParameter("baseColorIndex", -1) }
                runCatching { material.setParameter("normalIndex", -1) }
                runCatching { material.setParameter("emissiveIndex", -1) }
                runCatching {
                    material.setParameter("baseColorFactor", CLAY_R, CLAY_G, CLAY_B, 1f)
                }
                runCatching { material.setParameter("metallicFactor", 0f) }
                runCatching { material.setParameter("roughnessFactor", 0.88f) }
                runCatching { material.setParameter("emissiveFactor", 0f, 0f, 0f) }
            }
        }
    }

    /**
     * Draw the model as a clay form with cross-contour lines over it.
     *
     * Every primitive gets the *same* material instance. A model is one object
     * under one set of slices, so one instance is right as well as cheap: a
     * bust whose base and head were separate primitives with separate spacings
     * would have the lines step at the join.
     *
     * The spacing itself is [density]'s business, and it is a share of the
     * model's own size rather than a number in metres — so a bust exported in
     * millimetres and one exported in metres both get the same number of rings
     * up their height.
     */
    private fun applyContour() {
        val made = asset ?: return
        val instance = contourInstance ?: run { applyClay(); return }
        val rm = engine?.renderableManager ?: return
        instance.setParameter("clay", CLAY_R, CLAY_G, CLAY_B)
        instance.setParameter("ink", INK_R, INK_G, INK_B)
        instance.setParameter("roughness", 0.88f)
        instance.setParameter("weight", LINE_HALF_PX)
        for (entity in made.renderableEntities) {
            val renderable = rm.getInstance(entity)
            if (renderable == 0) continue
            for (i in 0 until rm.getPrimitiveCount(renderable)) {
                runCatching { rm.setMaterialInstanceAt(renderable, i, instance) }
            }
        }
    }

    /**
     * How many rings, and as many across.
     *
     * A parameter on a material instance, so moving the slider is a uniform
     * written and a frame drawn — not a reload. That is what makes it usable as
     * a slider rather than as a setting: the grid gets denser under the finger.
     */
    fun density(slices: Float) {
        val instance = contourInstance ?: return
        val count = if (slices.isFinite()) slices.coerceIn(2f, 400f) else 20f
        val spacing = (reach * 2f / count).coerceAtLeast(1e-6f)
        runCatching { instance.setParameter("perUnit", 1f / spacing) }
    }

    /**
     * Point the camera at the model.
     *
     * [spin] and [tilt] are degrees around it; [dolly] multiplies the distance,
     * so 1 is the whole model in the pane and 3 is close in on a nose. The
     * three of them are the pose, they live on `PaneView`, and they are what
     * survives the panel being rearranged.
     *
     * The distance comes from the bounding sphere and the field of view rather
     * than from a number, so a model that was exported in metres and one that
     * was exported in millimetres both arrive framed.
     */
    fun aim(spin: Float, tilt: Float, dolly: Float) {
        val camera = camera ?: return
        val safeDolly = if (dolly.isFinite() && dolly > 0.05f) dolly else 1f
        val fitting = radius / sin(FOV_DEGREES * 0.5f * DEG).coerceAtLeast(0.05f)
        val distance = (fitting / safeDolly).coerceAtLeast(radius * 0.05f)

        val a = spin * DEG
        val e = (tilt.coerceIn(-85f, 85f)) * DEG
        val x = centreX + distance * cos(e) * sin(a)
        val y = centreY + distance * sin(e)
        val z = centreZ + distance * cos(e) * cos(a)

        camera.lookAt(
            x.toDouble(), y.toDouble(), z.toDouble(),
            centreX.toDouble(), centreY.toDouble(), centreZ.toDouble(),
            0.0, 1.0, 0.0,
        )
    }

    /**
     * Move the light, leaving the model where it is.
     *
     * One pair of angles aims all three lamps: the key at [azimuth]/[elevation],
     * the fill a hundred and twenty degrees round the other way and flatter,
     * and the rim behind. Keeping the rig rigid is what makes dragging feel
     * like walking a lamp around a model rather than like turning three
     * separate knobs.
     */
    fun relight(azimuth: Float, elevation: Float) {
        val lm = engine?.lightManager ?: return
        val e = elevation.coerceIn(-80f, 80f)
        point(lm, keyLight, azimuth, e)
        point(lm, fillLight, azimuth + 130f, e * 0.3f - 8f)
        point(lm, rimLight, azimuth + 200f, e * 0.5f + 18f)
    }

    /**
     * A light at these angles shines *towards* the model, so the direction
     * handed to Filament is the negative of the one the lamp sits at.
     */
    private fun point(lm: LightManager, entity: Int, azimuth: Float, elevation: Float) {
        val instance = lm.getInstance(entity)
        if (instance == 0) return
        val a = azimuth * DEG
        val e = elevation * DEG
        val x = cos(e) * sin(a)
        val y = sin(e)
        val z = cos(e) * cos(a)
        lm.setDirection(instance, -x, -y, -z)
    }

    /** Take the colour out, or put it back. The pane's Grey button. */
    fun grey(on: Boolean) {
        view?.colorGrading = if (on) greyLook else plainLook
    }

    private var pendingShot: ((Bitmap) -> Unit)? = null
    private var shotBacking = 0
    private var reading = false

    /**
     * How many more frames to draw while a capture is outstanding.
     *
     * Filament answers a `readPixels` on its driver thread, and the driver
     * thread only runs while frames are being pumped. This pane does not pump
     * — it draws when something changes — so a read issued and then left alone
     * is a read that never completes. The budget is what pumps it, and it is a
     * budget rather than a loop so that a read which never lands costs forty
     * frames and not a spinning pane.
     */
    private var readBudget = 0

    /**
     * Keep the next frame as a picture, on [backdrop], and hand it over.
     *
     * The strip needs a face for a model and this is the only place in the app
     * where a mesh has pixels. It goes through Filament's own `readPixels`
     * rather than `TextureView.getBitmap`, and that is not a preference:
     * `endFrame` only *queues* the frame, so a `getBitmap` in the same
     * callback reads a surface nothing has been written to yet and comes back
     * a solid black rectangle — which is exactly what the strip filled up with
     * the first time this was tried.
     *
     * The read is asynchronous. [onShot] arrives a frame or two later, on the
     * main thread, or never if the pane went away first.
     */
    fun captureNext(backdrop: Int, onShot: (Bitmap) -> Unit) {
        shotBacking = backdrop
        pendingShot = onShot
        reading = false
        readBudget = READ_FRAMES
    }

    /** Whether a capture is still owed. The pane keeps asking for frames until it is not. */
    val capturing: Boolean get() = (pendingShot != null || reading) && readBudget > 0

    /**
     * Read the frame that was just rendered, flip it, and put it on a backing.
     *
     * Two things have to happen to those bytes and both are easy to leave out.
     * The render was onto nothing, so the colour is premultiplied by an alpha
     * that a JPEG cannot keep and the backing has to be composited in by hand.
     * And the buffer has to stay referenced until the callback runs, which it
     * does by being captured in it.
     *
     * The rows are **not** reversed, which is worth saying because the textbook
     * says they should be: `glReadPixels` is bottom-up, so the first version of
     * this flipped them and the first poster came out upside down. Rendering
     * into a `SurfaceTexture` has already turned the image over, and the read
     * lands the right way up. Measured on the tablet, not reasoned about.
     */
    private fun readShot(widthPx: Int, heightPx: Int) {
        val renderer = renderer ?: return
        val take = pendingShot ?: return
        val backing = shotBacking
        val bytes = ByteBuffer.allocateDirect(widthPx * heightPx * 4).order(ByteOrder.nativeOrder())
        val descriptor = Texture.PixelBufferDescriptor(
            bytes,
            Texture.Format.RGBA,
            Texture.Type.UBYTE,
        )
        descriptor.setCallback(Handler(Looper.getMainLooper()), Runnable {
            reading = false
            readBudget = 0
            val shot = runCatching { compose(bytes, widthPx, heightPx, backing) }.getOrNull()
            if (shot == null) Log.w(TAG, "the poster could not be made")
            if (shot != null) take(shot)
        })
        pendingShot = null
        reading = true
        runCatching { renderer.readPixels(0, 0, widthPx, heightPx, descriptor) }
            .onFailure {
                Log.w(TAG, "readPixels refused", it)
                pendingShot = take
                reading = false
            }
    }

    private fun compose(bytes: ByteBuffer, w: Int, h: Int, backing: Int): Bitmap {
        val backR = (backing shr 16) and 0xFF
        val backG = (backing shr 8) and 0xFF
        val backB = backing and 0xFF
        val pixels = IntArray(w * h)
        bytes.rewind()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val at = ((y * w) + x) * 4
                val r = bytes.get(at).toInt() and 0xFF
                val g = bytes.get(at + 1).toInt() and 0xFF
                val b = bytes.get(at + 2).toInt() and 0xFF
                val a = bytes.get(at + 3).toInt() and 0xFF
                val gap = 255 - a
                pixels[row + x] = (0xFF shl 24) or
                    (((r + backR * gap / 255).coerceAtMost(255)) shl 16) or
                    (((g + backG * gap / 255).coerceAtMost(255)) shl 8) or
                    ((b + backB * gap / 255).coerceAtMost(255))
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Draw one frame into [swapChain]. False when Filament chose to skip it.
     *
     * Filament's own frame pacing can decline a frame, which is not a failure
     * and not something to retry: the next change will ask for another.
     */
    fun frame(swapChain: SwapChain, widthPx: Int, heightPx: Int, atNanos: Long): Boolean {
        val renderer = renderer ?: return false
        val view = view ?: return false
        if (widthPx <= 0 || heightPx <= 0) return false
        view.viewport = Viewport(0, 0, widthPx, heightPx)
        camera?.setProjection(
            FOV_DEGREES.toDouble(),
            widthPx.toDouble() / heightPx.toDouble(),
            NEAR.toDouble(),
            FAR.toDouble(),
            Camera.Fov.VERTICAL,
        )
        if (!renderer.beginFrame(swapChain, atNanos)) return false
        renderer.render(view)
        // Between the render and the end of the frame, which is the window
        // Filament allows a read in.
        if (readBudget > 0) readBudget--
        if (pendingShot != null && loaded) readShot(widthPx, heightPx)
        renderer.endFrame()
        return true
    }

    /**
     * Give everything back.
     *
     * Order matters and it is the order Filament's own samples use: the asset
     * before the loaders that made it, the loaders before the engine, and
     * nothing at all after `engine.destroy()`.
     */
    fun shutdown() {
        close()
        runCatching {
            val engine = engine
            resources?.destroy()
            loader?.destroy()
            materials?.destroy()
            if (engine != null) {
                ambient?.let { engine.destroyIndirectLight(it) }
                contourInstance?.let { engine.destroyMaterialInstance(it) }
                contour?.let { engine.destroyMaterial(it) }
                plainLook?.let { engine.destroyColorGrading(it) }
                greyLook?.let { engine.destroyColorGrading(it) }
                val em = EntityManager.get()
                for (light in intArrayOf(keyLight, fillLight, rimLight)) {
                    if (light != 0) {
                        engine.lightManager.destroy(light)
                        em.destroy(light)
                    }
                }
                camera?.let { engine.destroyCameraComponent(it.entity) }
                view?.let { engine.destroyView(it) }
                scene?.let { engine.destroyScene(it) }
                renderer?.let { engine.destroyRenderer(it) }
                engine.destroy()
            }
        }
        resources = null
        contourInstance = null
        contour = null
        loader = null
        materials = null
        ambient = null
        plainLook = null
        greyLook = null
        keyLight = 0
        fillLight = 0
        rimLight = 0
        camera = null
        view = null
        scene = null
        renderer = null
        engine = null
        ready = false
        loaded = false
    }

    /** The engine, for the pane that has to make a swap chain against it. */
    fun engineOrNull(): Engine? = engine

    /** What the model is dressed in. See [open]. */
    enum class Look { SCANNED, CLAY, CONTOUR }

    private companion object {
        const val TAG = "artiest-3d"

        const val CONTOUR_ASSET = "materials/contour.filamat"

        /** Half the line width in pixels. Under one, so the line is hairline. */
        const val LINE_HALF_PX = 0.38f

        /** The lines. Dark and slightly blue, so they read as pencil, not as soot. */
        const val INK_R = 0.05f
        const val INK_G = 0.06f
        const val INK_B = 0.08f

        /** f/16 at 1/125 and ISO 100: a bright room, and the reference exposure. */
        const val APERTURE = 16f
        const val SHUTTER = 1f / 125f
        const val SENSITIVITY = 100f

        /**
         * Lux, against the exposure above. The key is an overcast window, the
         * fill is a quarter of it and the rim a fifth — the ratios a studio
         * uses, not numbers picked until the first model looked right.
         */
        const val KEY_LUX = 90_000f
        const val FILL_LUX = 22_000f
        const val RIM_LUX = 17_000f

        /** Linear, and deliberately dim: this is the wall, not a fourth lamp. */
        const val AMBIENT_R = 0.45f
        const val AMBIENT_G = 0.47f
        const val AMBIENT_B = 0.52f

        /** Unbleached plasticine. Warm enough not to read as a screenshot. */
        const val CLAY_R = 0.58f
        const val CLAY_G = 0.55f
        const val CLAY_B = 0.52f

        /**
         * A long lens, and for the reason a portrait is shot on one: at 35
         * degrees a head turns without its nose swelling, which is the whole
         * thing a reference is being looked at for.
         */
        const val FOV_DEGREES = 35f
        const val NEAR = 0.05f
        const val FAR = 1000f

        /** See [readBudget]. Two thirds of a second at sixty, and never reached. */
        const val READ_FRAMES = 40

        const val DEG = (Math.PI / 180.0).toFloat()
    }
}
