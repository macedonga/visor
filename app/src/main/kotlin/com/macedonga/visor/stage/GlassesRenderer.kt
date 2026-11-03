package com.macedonga.visor.stage

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** What the stage asks of the glasses this frame. Written by Compose, read by the renderer, both on the main thread. */
class GlassesPose {
    /** Each temple, 0 open to 1 folded. */
    var foldLeft = 0f
    var foldRight = 0f
    /** How far the framing has followed the pair as it folds: 0 framed open, 1 framed folded. */
    var framing = 0f
    var yawDegrees = 0f
    /** A gesture of the pair's own: the dip of a nod, the turn of a shake. */
    var pitchDegrees = 0f
    var gestureYaw = 0f
    /** From the phone's tilt: a few degrees of turn and lean, and a few millimetres of drift. */
    var tiltYawDegrees = 0f
    var tiltPitchDegrees = 0f
    var shiftX = 0f
    var shiftY = 0f
    /** Sideways out of the light, in stage widths. */
    var slide = 0f
    /** Its own slow drift, so it never looks frozen. */
    var idleYaw = 0f
    var idlePitch = 0f
    var idleRoll = 0f
    var idleLift = 0f
    var led = 0f
    /** Where a screen that tells a story turns and leans the pair, on top of everything else. */
    var viewYaw = 0f
    var viewPitch = 0f
    /** The notification LED inside the frame, 0 off to 1 lit blue, and the capture button lit up, 0 to 1. */
    var innerLed = 0f
    var button = 0f
    /** The model's material variants for the pair's look, best first: the first the model has is drawn. */
    var variants = variantsOf(null)
}

/**
 * The glasses, drawn by Filament into a transparent TextureView. Lit by one key light that takes the
 * pool's colour and strength, and a studio of soft panels that never quite goes dark.
 * A frame is drawn only when the light, the pose or the surface changed.
 */
class GlassesRenderer(context: Context, private val light: LightFrame, private val pose: GlassesPose, model: String = FrameModel.WAYFARER.asset) {
    private val engine: Engine
    private val renderer: Renderer
    private val scene: Scene
    private val view: View
    private val camera: Camera
    private val cameraEntity: Int
    private val materials: UbershaderProvider
    private val loader: AssetLoader
    private val resources: ResourceLoader
    private val asset: FilamentAsset?
    private val key: Int
    private val fill: Int
    private val reflections: Texture
    private val ibl: IndirectLight
    private val led: MaterialInstance?
    private val innerLed: Mark?
    private val button: Mark?
    private val ledLight: Int
    private val buttonLight: Int
    /**
     * The views drawn into. The renderer outlives them; while one screen dissolves into another both hold a stage,
     * and both are drawn, so neither shows an empty frame while the other's surface comes up.
     */
    private val targets = mutableListOf<Target>()
    private var framedFor = 0L
    private val choreographer = Choreographer.getInstance()
    private var loaded = false
    private var pending = false
    private var destroyed = false
    private var appliedVariants = emptyList<String>()
    private var lastSignature = FloatArray(0)

    init {
        Filament.init()
        Gltfio.init()
        engine = Engine.create()
        renderer = engine.createRenderer().apply {
            clearOptions = clearOptions.apply {
                clear = true
                clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            }
        }
        scene = engine.createScene()
        cameraEntity = EntityManager.get().create()
        // Exposure 1.3: f/1, 1.56 s, ISO 100.
        camera = engine.createCamera(cameraEntity).apply { setExposure(1f, 1.56f, 100f) }
        view = engine.createView().apply {
            scene = this@GlassesRenderer.scene
            camera = this@GlassesRenderer.camera
            blendMode = View.BlendMode.TRANSLUCENT
            antiAliasing = View.AntiAliasing.FXAA
            // Filament's dither lifts every transparent pixel a hair above black, and on OLED the view's edge shows.
            dithering = View.Dithering.NONE
            multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
                enabled = true
                sampleCount = 4
            }
            colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutralToneMapper()).build(engine)
        }

        reflections = Studio.reflections(engine)
        ibl = IndirectLight.Builder().reflections(reflections).irradiance(2, Studio.irradiance()).intensity(1f).build(engine)
        scene.indirectLight = ibl

        // A sun with a wide disc: its highlights spread like a big diffuser's instead of a point's.
        key = light(LightManager.Type.SUN) {
            direction(-0.25f, -1f, -0.45f)
            sunAngularRadius(14f)
            sunHaloSize(0f)
        }
        // A wide, softer light from just above and left of the camera: the front of the frame faces it, and its black
        // acetate shows its shape instead of reading as a hole.
        fill = light(LightManager.Type.SUN) {
            direction(0.22f, -0.32f, -1f)
            sunAngularRadius(24f)
            sunHaloSize(0f)
        }

        materials = UbershaderProvider(engine)
        loader = AssetLoader(engine, materials, EntityManager.get())
        resources = ResourceLoader(engine)
        asset = loader.createAsset(readModel(context, model))
        if (asset == null) Log.w(TAG, "the glasses model did not load")
        asset?.let {
            resources.asyncBeginLoad(it)
            scene.addEntities(it.entities)
        }
        led = asset?.getFirstEntityByName("led")?.let { e ->
            val rm = engine.renderableManager
            rm.getInstance(e).takeIf { it != 0 }?.let { rm.getMaterialInstanceAt(it, 0) }
        }
        innerLed = mark("inner_led")
        button = mark("button")
        // Small point lights at the marks, so the frame around them catches their colour.
        ledLight = light(LightManager.Type.POINT) {
            color(INNER_LED_COLOR[0], INNER_LED_COLOR[1], INNER_LED_COLOR[2])
            intensity(0f)
            falloff(MARK_FALLOFF)
        }
        buttonLight = light(LightManager.Type.POINT) {
            color(BUTTON_COLOR[0], BUTTON_COLOR[1], BUTTON_COLOR[2])
            intensity(0f)
            falloff(MARK_FALLOFF)
        }
    }

    /** A part of the model drawn only while it is lit. */
    private inner class Mark(val entity: Int, val material: MaterialInstance) {
        var shown = true

        fun show(on: Boolean) {
            if (on == shown) return
            shown = on
            if (on) scene.addEntity(entity) else scene.removeEntity(entity)
        }
    }

    private fun mark(name: String): Mark? {
        val e = asset?.getFirstEntityByName(name)?.takeIf { it != 0 } ?: return null
        val rm = engine.renderableManager
        val mi = rm.getInstance(e).takeIf { it != 0 }?.let { rm.getMaterialInstanceAt(it, 0) } ?: return null
        return Mark(e, mi).also { it.show(false) }
    }

    fun attach(textureView: TextureView) {
        if (targets.any { it.view === textureView }) return
        textureView.isOpaque = false
        targets += Target(textureView)
    }

    /** Stops drawing into [textureView]: its surface goes, the scene and the model stay loaded for the next one. */
    fun detach(textureView: TextureView) {
        targets.firstOrNull { it.view === textureView }?.let {
            it.release()
            targets.remove(it)
        }
    }

    private fun detachAll() {
        targets.forEach { it.release() }
        targets.clear()
    }

    /** Nothing on screen shows the pair: no frames are drawn until it is wanted again. */
    var paused = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                lastSignature = FloatArray(0)
                invalidate()
            }
        }

    /** Ask for a frame; cheap to call every vsync, it draws only if something changed. */
    fun invalidate() {
        if (pending || destroyed) return
        pending = true
        choreographer.postFrameCallback(onFrame)
    }

    private val onFrame = Choreographer.FrameCallback { now ->
        pending = false
        draw(now)
    }

    private fun draw(frameTimeNanos: Long) {
        if (paused || targets.none { it.chain != null }) return
        val asset = asset ?: return
        if (!loaded) {
            resources.asyncUpdateLoad()
            if (resources.asyncGetLoadProgress() >= 1f) {
                loaded = true
                asset.releaseSourceData()
            } else {
                invalidate()
            }
        }
        val signature = floatArrayOf(
            light.level, light.flash, light.core[0], light.core[1], light.core[2],
            pose.foldLeft, pose.foldRight, pose.framing, pose.yawDegrees, pose.pitchDegrees, pose.tiltYawDegrees, pose.tiltPitchDegrees, pose.shiftX, pose.shiftY, pose.slide,
            pose.idleYaw, pose.idlePitch, pose.idleRoll, pose.idleLift, pose.gestureYaw,
            pose.led, pose.viewYaw, pose.viewPitch, pose.innerLed, pose.button, if (loaded) 1f else 0f,
        )
        if (loaded && signature.contentEqualsApprox(lastSignature)) return
        lastSignature = signature

        if (pose.variants != appliedVariants) {
            val names = asset.instance.materialVariantNames
            pose.variants.firstNotNullOfOrNull { v -> names.indexOf(v).takeIf { it >= 0 } }?.let(asset.instance::applyMaterialVariant)
            appliedVariants = pose.variants
            polishLenses(asset)
        }
        val animator = asset.instance.animator
        for (i in 0 until animator.animationCount) {
            val fold = when (animator.getAnimationName(i)) {
                "fold_l" -> pose.foldLeft
                "fold_r" -> pose.foldRight
                else -> continue
            }
            // The animator wraps time at the clip's end, so exactly 1 would spring the temple open again.
            animator.applyAnimation(i, fold.coerceIn(0f, 0.999f))
        }
        place(asset)
        shine()
        for (t in targets) {
            val chain = t.chain ?: continue
            if (!t.helper.isReadyToRender || t.width == 0) continue
            val size = t.width.toLong() shl 32 or t.height.toLong()
            if (size != framedFor) frame(t.width, t.height)
            if (renderer.beginFrame(chain, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
            }
        }
    }

    /**
     * The lenses polished but not mirrors: at the model's near-zero roughness they reflected the key light's wide disc
     * with a hard edge, a line that swept across the lens as the pair drifted.
     */
    private fun polishLenses(asset: FilamentAsset) {
        val rm = engine.renderableManager
        for (e in asset.entities) {
            val r = rm.getInstance(e).takeIf { it != 0 } ?: continue
            for (p in 0 until rm.getPrimitiveCount(r)) {
                val mi = rm.getMaterialInstanceAt(r, p)
                if (mi.name.startsWith("lens") && mi.material.hasParameter("roughnessFactor")) mi.setParameter("roughnessFactor", LENS_ROUGHNESS)
            }
        }
    }

    private fun place(asset: FilamentAsset) {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        // Folded they are flatter, so they settle a little lower into the light.
        // A stage width is twice the radius the camera fits.
        Matrix.translateM(m, 0, pose.shiftX + pose.slide * 2 * FIT_RADIUS.toFloat(), pose.shiftY + pose.idleLift - CLOSED_DROP * pose.framing, 0f)
        Matrix.rotateM(m, 0, pose.idleRoll, 0f, 0f, 1f)
        Matrix.rotateM(m, 0, pose.pitchDegrees + pose.tiltPitchDegrees + pose.idlePitch + pose.viewPitch, 1f, 0f, 0f)
        Matrix.rotateM(m, 0, REST_YAW + pose.yawDegrees + pose.tiltYawDegrees + pose.idleYaw + pose.gestureYaw + pose.viewYaw, 0f, 1f, 0f)
        // The model is centred open; folded, its middle moves forward, so it slides back to stay on the spot.
        Matrix.translateM(m, 0, 0f, 0f, -CLOSED_CENTRE_Z * pose.framing)
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(asset.root), m)
    }

    private fun shine() {
        val lm = engine.lightManager
        // The square root lifts the dim moods most, so the pair reads in every state; zero stays dark for the way in.
        val lit = sqrt(max(light.level, 0f))
        val level = lit + light.flash
        val c = light.core
        lm.getInstance(key).let {
            lm.setColor(it, mixWhite(c[0], light.flash), mixWhite(c[1], light.flash), mixWhite(c[2], light.flash))
            lm.setIntensity(it, KEY_LUX * level)
        }
        lm.getInstance(fill).let {
            lm.setColor(it, mixWhite(c[0], light.flash), mixWhite(c[1], light.flash), mixWhite(c[2], light.flash))
            lm.setIntensity(it, FILL_LUX * level)
        }
        // The studio never goes fully dark, so a pair out of reach still shows its outline.
        ibl.intensity = IBL * (0.18f + 0.82f * lit.coerceAtMost(1.1f))
        led?.let { mi ->
            val e = LED_STRENGTH * pose.led
            if (mi.material.hasParameter("emissiveStrength")) mi.setParameter("emissiveStrength", 1f)
            if (mi.material.hasParameter("emissiveFactor")) mi.setParameter("emissiveFactor", e, e, e)
        }
        glow(innerLed, ledLight, pose.innerLed, INNER_LED_COLOR, INNER_LED_STRENGTH, INNER_LED_LUMENS)
        glow(button, buttonLight, pose.button, BUTTON_COLOR, BUTTON_STRENGTH, BUTTON_LUMENS)
    }

    private fun glow(mark: Mark?, lightEntity: Int, level: Float, colour: FloatArray, strength: Float, lumens: Float) {
        mark ?: return
        mark.show(level > 0f)
        val mi = mark.material
        val e = strength * level
        if (mi.material.hasParameter("emissiveStrength")) mi.setParameter("emissiveStrength", 1f)
        if (mi.material.hasParameter("emissiveFactor")) mi.setParameter("emissiveFactor", colour[0] * e, colour[1] * e, colour[2] * e)
        val lm = engine.lightManager
        val li = lm.getInstance(lightEntity)
        lm.setIntensity(li, lumens * level)
        worldCentre(mark.entity)?.let { (x, y, z) -> lm.setPosition(li, x, y, z) }
    }

    /** The centre of a part's box, in the world, as the pose last placed it. */
    private fun worldCentre(entity: Int): FloatArray? {
        val rm = engine.renderableManager
        val instance = rm.getInstance(entity).takeIf { it != 0 } ?: return null
        val box = com.google.android.filament.Box()
        rm.getAxisAlignedBoundingBox(instance, box)
        val c = box.center
        val tm = engine.transformManager
        val world = FloatArray(16)
        tm.getWorldTransform(tm.getInstance(entity), world)
        val p = FloatArray(4)
        Matrix.multiplyMV(p, 0, world, 0, floatArrayOf(c[0], c[1], c[2], 1f), 0)
        return floatArrayOf(p[0], p[1], p[2])
    }

    /** Where the named part's centre is in the view, as fractions of its width and height, from the pose last drawn. */
    fun partOnScreen(name: String): Pair<Float, Float>? {
        val entity = asset?.getFirstEntityByName(name)?.takeIf { it != 0 } ?: return null
        val p = worldCentre(entity) ?: return null
        return project(p[0], p[1], p[2])
    }

    private fun project(x: Float, y: Float, z: Float): Pair<Float, Float>? {
        val view = DoubleArray(16).also { camera.getViewMatrix(it) }
        val projection = DoubleArray(16).also { camera.getProjectionMatrix(it) }
        val clip = mul(projection, mul(view, doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble(), 1.0)))
        if (clip[3] == 0.0) return null
        return ((clip[0] / clip[3] + 1) / 2).toFloat() to ((1 - clip[1] / clip[3]) / 2).toFloat()
    }

    private fun mixWhite(v: Float, w: Float) = v + (1 - v) * w

    /**
     * Where the camera's glass is in the view, as fractions of its width and height, from the pose last drawn. The
     * model has the camera on the wearer's left and the capture LED mirrored on the right, so the LED's box, mirrored,
     * is the camera's.
     */
    fun cameraOnScreen(): Pair<Float, Float>? {
        val asset = asset ?: return null
        val entity = asset.getFirstEntityByName("led").takeIf { it != 0 } ?: return null
        val rm = engine.renderableManager
        val instance = rm.getInstance(entity).takeIf { it != 0 } ?: return null
        val box = com.google.android.filament.Box()
        rm.getAxisAlignedBoundingBox(instance, box)
        val c = box.center
        val tm = engine.transformManager
        val world = FloatArray(16)
        tm.getWorldTransform(tm.getInstance(entity), world)
        val local = floatArrayOf(-c[0], c[1], c[2], 1f)
        val p = FloatArray(4)
        Matrix.multiplyMV(p, 0, world, 0, local, 0)
        return project(p[0], p[1], p[2])
    }

    private fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(4) { r -> (0 until 4).sumOf { k -> m[k * 4 + r] * v[k] } }

    private fun light(type: LightManager.Type, setup: LightManager.Builder.() -> Unit): Int {
        val e = EntityManager.get().create()
        LightManager.Builder(type).castShadows(false).color(1f, 1f, 1f).apply(setup).build(engine, e)
        scene.addEntity(e)
        return e
    }

    private fun frame(width: Int, height: Int) {
        framedFor = width.toLong() shl 32 or height.toLong()
        view.viewport = Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height
        camera.setProjection(FOV_DEGREES, aspect, 0.05, 5.0, Camera.Fov.VERTICAL)
        // The pair fills about nine tenths of the width; on a tall stage that is the limit, not the height.
        val halfWidth = Math.tan(Math.toRadians(FOV_DEGREES / 2)) * aspect
        val distance = FIT_RADIUS / halfWidth
        val elevation = Math.toRadians(ELEVATION_DEGREES)
        camera.lookAt(
            0.0, distance * Math.sin(elevation) + LOOK_Y, distance * Math.cos(elevation),
            0.0, LOOK_Y, 0.0,
            0.0, 1.0, 0.0,
        )
        lastSignature = FloatArray(0)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        choreographer.removeFrameCallback(onFrame)
        detachAll()
        asset?.let { loader.destroyAsset(it) }
        resources.destroy()
        loader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        for (e in intArrayOf(key, fill, ledLight, buttonLight)) {
            engine.destroyEntity(e)
            EntityManager.get().destroy(e)
        }
        engine.destroyIndirectLight(ibl)
        engine.destroyTexture(reflections)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroy()
    }

    /** One view drawn into, with its own surface and size. */
    private inner class Target(val view: TextureView) : UiHelper.RendererCallback {
        val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        var chain: SwapChain? = null
        var width = 0
        var height = 0

        init {
            helper.isOpaque = false
            helper.renderCallback = this
            helper.attachTo(view)
        }

        fun release() {
            helper.detach()
            dropChain()
        }

        override fun onNativeWindowChanged(surface: Surface) {
            chain?.let { engine.destroySwapChain(it) }
            chain = engine.createSwapChain(surface, helper.swapChainFlags)
            lastSignature = FloatArray(0)
            invalidate()
        }

        override fun onDetachedFromSurface() = dropChain()

        override fun onResized(width: Int, height: Int) {
            this.width = width
            this.height = height
            lastSignature = FloatArray(0)
            invalidate()
        }

        private fun dropChain() {
            chain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
            }
            chain = null
        }
    }

    private fun readModel(context: Context, model: String): ByteBuffer {
        val bytes = context.assets.open(model).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
    }

    private companion object {
        const val TAG = "Visor"
        const val FOV_DEGREES = 24.0
        const val ELEVATION_DEGREES = 15.0
        const val FIT_RADIUS = 0.128
        const val LOOK_Y = -0.004
        const val REST_YAW = -24f
        /** How far forward the folded pair's centre is from the open pair's, from tools/glasses_glb.py's geometry. */
        const val CLOSED_CENTRE_Z = 0.0569f
        const val CLOSED_DROP = 0.007f
        const val LENS_ROUGHNESS = 0.22f
        const val KEY_LUX = 6f
        const val FILL_LUX = 2.7f
        const val IBL = 2.6f
        const val LED_STRENGTH = 40f
        val INNER_LED_COLOR = floatArrayOf(0.12f, 0.42f, 1f)
        val BUTTON_COLOR = floatArrayOf(0.82f, 0.88f, 1f)
        const val INNER_LED_STRENGTH = 60f
        const val BUTTON_STRENGTH = 14f
        /** In lumens: the camera's long exposure makes a hundredth of a lumen read at a centimetre. */
        const val INNER_LED_LUMENS = 0.02f
        const val BUTTON_LUMENS = 0.012f
        const val MARK_FALLOFF = 0.035f
    }
}

private fun FloatArray.contentEqualsApprox(other: FloatArray) = size == other.size && indices.all { abs(this[it] - other[it]) < 1e-5f }
