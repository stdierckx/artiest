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
    private var skyTexture: Texture? = null
    private var contour: Material? = null
    private var contourInstance: MaterialInstance? = null
    private var cast: Material? = null
    private var castInstance: MaterialInstance? = null
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
                // The crevices, and the reason the reference photograph reads
                // the way it does. A key light and a shadow map give the big
                // forms; what tells the eye about a fold of drapery, a curl of
                // hair or the corner of an eye socket is the darkening where
                // two surfaces come close, and no directional light can produce
                // it. This is the cheapest thing in the frame that makes a
                // model look carved rather than moulded -- and it costs nothing
                // at all when nothing is moving, because nothing is drawn.
                v.setAmbientOcclusionOptions(
                    View.AmbientOcclusionOptions().apply {
                        enabled = true
                        intensity = AO_STRENGTH
                        power = AO_FALLOFF
                        // Half resolution and medium quality. It is a wash of
                        // darkening in the crevices of a model a few hundred
                        // pixels across, not an edge anyone will look at, and
                        // full resolution costs four times as much for a
                        // difference that does not survive the upsample.
                        quality = View.QualityLevel.MEDIUM
                        resolution = 0.5f
                        // Set again for each model, in `measure`: a radius is a
                        // distance, and these models are anything from a skull
                        // normalised to one unit to a bust exported in metres.
                        radius = AO_REACH
                    },
                )
            }

            loadOurMaterials(engine)
            plainLook = ColorGrading.Builder().contrast(CONTRAST).build(engine)
            greyLook = ColorGrading.Builder().contrast(CONTRAST).saturation(0f).build(engine)
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
     * The two materials of our own, compiled at build time from `.mat` sources.
     *
     * A failure here is not fatal and deliberately so: the model still opens and
     * still turns, and the one thing that stops working is the button that wanted
     * the material. A reference pane that refuses to show a bust because a shader
     * did not load would be the worse failure by far — so each is loaded on its
     * own, and [applyCast] and [applyContour] both have a way to carry on
     * without theirs.
     */
    private fun loadOurMaterials(engine: Engine) {
        contour = build(engine, CONTOUR_ASSET)
        contourInstance = contour?.createInstance()
        cast = build(engine, CAST_ASSET)
        castInstance = cast?.createInstance()
    }

    /** One compiled material out of the assets, or null and a line in the log. */
    private fun build(engine: Engine, asset: String): Material? = runCatching {
        val bytes = assets.open(asset).use { it.readBytes() }
        val payload = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
        Material.Builder().payload(payload, bytes.size).build(engine)
    }.onFailure { Log.w(TAG, "no $asset", it) }.getOrNull()

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
            .color(0.95f, 0.95f, 1.0f)
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
        //
        // The reflections are a different matter and are the reason [sky]
        // exists. A metal has no diffuse colour at all — everything a bronze
        // shows is the room reflected in it — so with nothing to reflect a
        // bronze renders as a black shape with three bright scratches on it
        // where the lamps are. That is what the first one looked like.
        skyTexture = sky(engine)
        ambient = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(AMBIENT_R, AMBIENT_G, AMBIENT_B))
            .apply { skyTexture?.let { reflections(it) } }
            .build(engine)

        scene?.let { s ->
            s.addEntity(keyLight)
            s.addEntity(fillLight)
            s.addEntity(rimLight)
            s.indirectLight = ambient
        }
    }

    /**
     * A tiny cubemap of the room, for the things that reflect it.
     *
     * Not a photograph and not an HDR file: a gradient. Bright and slightly
     * cool overhead, mid at the horizon, dark and slightly warm below, which is
     * what any room with a window and a floor does and is enough for a metal to
     * have something to be. Thirty-two pixels a side, which is more than a
     * gradient needs and is 100 kB of texture.
     *
     * Every mip level is generated rather than filtered down, and the two come
     * to the same thing here: prefiltering a reflection map is blurring it by
     * roughness, and a gradient this smooth is already its own blur. That is
     * the whole reason the sky is a gradient — it is the one environment that
     * needs no `filament-utils`, no IBL prefilter and no 1.5 MB `.hdr` in a
     * repository that does not take data.
     */
    private fun sky(engine: Engine): Texture? = runCatching {
        val levels = Integer.numberOfTrailingZeros(SKY_SIDE) + 1
        val texture = Texture.Builder()
            .width(SKY_SIDE)
            .height(SKY_SIDE)
            .levels(levels)
            .format(Texture.InternalFormat.R11F_G11F_B10F)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .build(engine)
        var side = SKY_SIDE
        for (level in 0 until levels) {
            val face = side * side * 3
            val pixels = ByteBuffer
                .allocateDirect(face * 6 * 4)
                .order(ByteOrder.nativeOrder())
            val floats = pixels.asFloatBuffer()
            for (f in 0 until 6) {
                for (y in 0 until side) {
                    for (x in 0 until side) {
                        val u = (x + 0.5f) / side * 2f - 1f
                        val v = (y + 0.5f) / side * 2f - 1f
                        val up = upward(f, u, v)
                        // Up is sky, down is floor, and the horizon is the
                        // blend. Squared, so the bright half is the top half
                        // and not merely the upper hemisphere.
                        val t = (up * 0.5f + 0.5f)
                        val high = t * t
                        floats.put(SKY_LOW_R + (SKY_HIGH_R - SKY_LOW_R) * high)
                        floats.put(SKY_LOW_G + (SKY_HIGH_G - SKY_LOW_G) * high)
                        floats.put(SKY_LOW_B + (SKY_HIGH_B - SKY_LOW_B) * high)
                    }
                }
            }
            pixels.rewind()
            val offsets = IntArray(6) { it * face * 4 }
            texture.setImage(
                engine,
                level,
                Texture.PixelBufferDescriptor(pixels, Texture.Format.RGB, Texture.Type.FLOAT),
                offsets,
            )
            side /= 2
        }
        texture
    }.onFailure { Log.w(TAG, "no sky", it) }.getOrNull()

    /**
     * How far up the direction through [face] at [u], [v] points: +1 straight
     * up, -1 straight down.
     *
     * The cubemap face order is the OpenGL one — +X, -X, +Y, -Y, +Z, -Z — and
     * only the vertical component of the direction is wanted, so the other two
     * axes are never built.
     */
    private fun upward(face: Int, u: Float, v: Float): Float {
        val y = when (face) {
            2 -> 1f      // +Y, the ceiling
            3 -> -1f     // -Y, the floor
            else -> -v   // the four walls
        }
        // Whatever the face, the three components of the direction are u, v and
        // a ±1 in some order, so the length is the same expression for all six.
        return y / sqrt(u * u + v * v + 1f)
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
            when {
                look.isCast -> applyCast(look)
                look == Look.CONTOUR -> {
                    applyContour()
                    density(slices)
                }
                else -> Unit
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
        // The occlusion radius is a distance in the model's own world, so it
        // has to be a share of the model rather than a number: at a fixed
        // radius a skull normalised to one unit and a bust exported in metres
        // get occlusion at two completely different scales, and one of them is
        // a grey wash and the other is nothing at all.
        view?.let { v ->
            val options = v.ambientOcclusionOptions
            options.radius = reach * AO_REACH
            v.setAmbientOcclusionOptions(options)
        }
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
     * Dress the model in marble, bronze or terracotta.
     *
     * Every primitive gets the same instance, for [applyContour]'s reason: one
     * model is one block of stone, and a bust whose base and head were separate
     * primitives with separate grain would have the veins step at the join.
     *
     * The scale is the model's own half-size, so the grain is the same size
     * relative to the form whatever units the file was exported in — the same
     * rule the contour spacing follows, and for the same reason.
     *
     * If the material did not compile in, this falls back to [applyClay]: flat
     * grey with nothing in it, which is worse to draw from than any of the
     * three and far better than white.
     */
    private fun applyCast(look: Look) {
        val made = asset ?: return
        val instance = castInstance ?: run { applyClay(); return }
        val rm = engine?.renderableManager ?: return
        val recipe = when (look) {
            Look.BRONZE -> BRONZE
            Look.TERRACOTTA -> TERRACOTTA
            else -> MARBLE
        }
        instance.setParameter("pale", recipe.paleR, recipe.paleG, recipe.paleB)
        instance.setParameter("vein", recipe.veinR, recipe.veinG, recipe.veinB)
        instance.setParameter("grit", recipe.gritR, recipe.gritG, recipe.gritB)
        instance.setParameter("roughness", recipe.roughness)
        instance.setParameter("metallic", recipe.metallic)
        instance.setParameter("veining", recipe.veining)
        instance.setParameter("speckling", recipe.speckling)
        instance.setParameter("mottling", recipe.mottling)
        instance.setParameter("settling", recipe.settling)
        instance.setParameter("perUnit", 1f / reach.coerceAtLeast(1e-6f))
        for (entity in made.renderableEntities) {
            val renderable = rm.getInstance(entity)
            if (renderable == 0) continue
            for (i in 0 until rm.getPrimitiveCount(renderable)) {
                runCatching { rm.setMaterialInstanceAt(renderable, i, instance) }
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
        // Not clamped. The lamp is on a ball and the ball has a top and a
        // bottom, so a light directly overhead and a light directly underneath
        // are both things the artist can ask for -- and underlighting is a
        // study in its own right, not an accident to be guarded against.
        val e = if (elevation.isFinite()) elevation else 0f
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
                skyTexture?.let { engine.destroyTexture(it) }
                contourInstance?.let { engine.destroyMaterialInstance(it) }
                contour?.let { engine.destroyMaterial(it) }
                castInstance?.let { engine.destroyMaterialInstance(it) }
                cast?.let { engine.destroyMaterial(it) }
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
        castInstance = null
        cast = null
        loader = null
        materials = null
        ambient = null
        skyTexture = null
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

    /**
     * What the model is dressed in. See [open].
     *
     * The three materials are one shader and three sets of numbers — see
     * `cast.mat` — so they are one branch here and not three.
     */
    enum class Look {
        SCANNED,
        MARBLE,
        BRONZE,
        TERRACOTTA,
        CONTOUR,
        ;

        /** Whether this look is one of the cast materials. */
        val isCast: Boolean get() = this == MARBLE || this == BRONZE || this == TERRACOTTA
    }

    private companion object {
        const val TAG = "artiest-3d"

        const val CONTOUR_ASSET = "materials/contour.filamat"
        const val CAST_ASSET = "materials/cast.filamat"

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
         * fill a sixth of it and the rim an eighth — the ratios a studio uses,
         * not numbers picked until the first model looked right.
         *
         * They were all much higher, and that was the real reason a bare model
         * was unreadable: at ninety thousand lux against this exposure the
         * whole model sat in the shoulder of the tone curve, and a light plane
         * and the plane next to it came out four values apart. Measured on the
         * tablet, over the pixels of one skull: the model used to run from 98
         * to 185 with half of it inside 161–185, and now runs 83 to 151 with
         * the middle at 133. The same mesh, the same lights, three times less
         * of them, and the form is back.
         */
        const val KEY_LUX = 62_000f
        const val FILL_LUX = 9_000f
        const val RIM_LUX = 6_000f

        /**
         * The wall, not a fourth lamp.
         *
         * A share of Filament's own environment intensity and **not** lux — the
         * two are three orders of magnitude apart, so this reads as a harmless
         * number and behaves as one of the bright ones. Three thousand here is
         * a white screen.
         */
        const val AMBIENT_R = 0.16f
        const val AMBIENT_G = 0.16f
        const val AMBIENT_B = 0.17f

        /**
         * The room the metal reflects: a cool light above, a dark warm floor
         * below. Linear, and in the same units as [AMBIENT_R] — a share of
         * Filament's environment intensity, not lux.
         */
        const val SKY_SIDE = 32
        const val SKY_HIGH_R = 0.62f
        const val SKY_HIGH_G = 0.66f
        const val SKY_HIGH_B = 0.74f
        const val SKY_LOW_R = 0.09f
        const val SKY_LOW_G = 0.08f
        const val SKY_LOW_B = 0.07f

        /** Unbleached plasticine. Warm enough not to read as a screenshot. */
        const val CLAY_R = 0.58f
        const val CLAY_G = 0.55f
        const val CLAY_B = 0.52f

        /**
         * One of the three materials, as the numbers `cast.mat` asks for.
         *
         * A data class and not thirty loose constants, because the three of
         * them are the same ten questions answered differently and the only way
         * to see that is to have them written out side by side.
         */
        data class Recipe(
            val paleR: Float, val paleG: Float, val paleB: Float,
            val veinR: Float, val veinG: Float, val veinB: Float,
            val gritR: Float, val gritG: Float, val gritB: Float,
            val roughness: Float,
            val metallic: Float,
            val veining: Float,
            val speckling: Float,
            val mottling: Float,
            val settling: Float,
        )

        /**
         * Alabaster, measured off the reference the artist put in the pane — a
         * photograph of a fourteenth-century Virgin — rather than chosen: a lit
         * plane in it is (214, 204, 190) and a shadowed one (126, 114, 98),
         * which is ivory, warm, and a long way from a neutral grey. The veins
         * are a grey-brown two stops under it and never black; the specks are
         * nearly black and are the only thing here that is.
         *
         * Faintly glossy — *"somewhat glossy but not really"*. Polished marble
         * is nowhere near a mirror and nowhere near chalk.
         */
        val MARBLE = Recipe(
            paleR = 0.80f, paleG = 0.77f, paleB = 0.72f,
            veinR = 0.36f, veinG = 0.34f, veinB = 0.31f,
            gritR = 0.13f, gritG = 0.11f, gritB = 0.10f,
            roughness = 0.42f,
            metallic = 0f,
            veining = 0.78f,
            speckling = 1f,
            mottling = 0.13f,
            settling = 0f,
        )

        /**
         * Bronze with a century on it.
         *
         * The metal is the warm copper-gold every founder's alloy is under the
         * weather; what the eye actually reads at ten paces is the patina over
         * it, which is why the veining is nearly at its maximum and carries the
         * green. The specks are the casting pits.
         *
         * Metal, so there is no diffuse colour at all: everything a bronze
         * shows is the room reflected in it, which is exactly why it teaches a
         * different lesson from marble and is worth having. Smoother than the
         * stone, because a bronze is polished and a marble is honed.
         */
        val BRONZE = Recipe(
            paleR = 0.60f, paleG = 0.42f, paleB = 0.21f,
            veinR = 0.15f, veinG = 0.21f, veinB = 0.17f,
            gritR = 0.05f, gritG = 0.06f, gritB = 0.05f,
            roughness = 0.46f,
            metallic = 1f,
            veining = 0.78f,
            speckling = 0.7f,
            mottling = 0.20f,
            settling = 0.80f,
        )

        /**
         * Fired clay: the warm orange of a maquette, and the one of the three
         * that is *not* uniform.
         *
         * Terracotta has no veins to speak of — a trace, and only because a
         * complete absence of them reads as plastic — and a great deal of body
         * mottling and pore. Matte, because it is fired and not glazed.
         */
        val TERRACOTTA = Recipe(
            paleR = 0.58f, paleG = 0.29f, paleB = 0.19f,
            veinR = 0.44f, veinG = 0.21f, veinB = 0.13f,
            gritR = 0.24f, gritG = 0.13f, gritB = 0.09f,
            roughness = 0.86f,
            metallic = 0f,
            veining = 0.22f,
            speckling = 1.5f,
            mottling = 0.42f,
            settling = 0f,
        )

        /**
         * Screen-space occlusion: how strong, how fast it falls off, and how
         * far it reaches as a share of the model's own half-size.
         */
        const val AO_STRENGTH = 1.6f
        const val AO_FALLOFF = 0.8f
        const val AO_REACH = 0.075f

        /**
         * A long lens, and for the reason a portrait is shot on one: at 35
         * degrees a head turns without its nose swelling, which is the whole
         * thing a reference is being looked at for.
         */
        const val FOV_DEGREES = 35f
        const val NEAR = 0.05f
        const val FAR = 1000f

        /**
         * The S-curve on the way out, about mid grey.
         *
         * The tone mapper's job is to fit a lit scene into a screen and it does
         * that by compressing both ends, which is the right trade for a
         * photograph and the wrong one for something being copied by eye: the
         * lit planes come back within a few values of each other. One and a
         * quarter pulls them apart again around the middle, where the drawing
         * is.
         */
        const val CONTRAST = 1.35f

        /** See [readBudget]. Two thirds of a second at sixty, and never reached. */
        const val READ_FRAMES = 40

        const val DEG = (Math.PI / 180.0).toFloat()
    }
}
