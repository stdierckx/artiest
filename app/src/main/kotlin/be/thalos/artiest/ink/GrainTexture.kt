package be.thalos.artiest.ink

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import be.thalos.artiest.engine.brush.GrainField
import be.thalos.artiest.engine.brush.GrainSpec
import java.nio.ByteBuffer

/**
 * The grain, uploaded once and anchored to the document.
 *
 * **Applied to the finished stroke, not to each dab, and that is the design
 * decision that makes texture affordable at all.** Multiplying grain into every
 * dab would mean a shader per dab at several hundred dabs a stroke. The scratch
 * buffer already holds the whole stroke's accumulated alpha, so one
 * `DST_IN` pass over it multiplies the grain in exactly once — and because the
 * shader is anchored to document coordinates rather than to the dab, the result
 * is texture that belongs to the paper and stays put when the stroke moves
 * across it.
 *
 * `DST_IN` keeps the destination's colour and multiplies its alpha by the
 * source's. The tile is an `ALPHA_8` bitmap whose alpha *is* the grain, so the
 * pass reads "keep this stroke where the paper caught it".
 *
 * Not thread-safe; render thread only, like everything else here.
 */
class GrainTexture {

    private var spec: GrainSpec? = null
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val localMatrix = Matrix()

    /** Tiles generated since construction, for the instruments. */
    var builds: Long = 0L
        private set

    /**
     * A shader whose alpha is the grain, anchored to the document's origin.
     *
     * **Not applied to the buffer, composed with it.** The wet pass composites
     * the scratch every frame, so a destructive `DST_IN` pass would multiply
     * the grain in again on each one and the stroke would fade to nothing while
     * being drawn. Composing the two shaders at composite time is the only
     * arrangement that is correct for both the wet pass and the commit, and it
     * is why the grain is a `Shader` here rather than a `Paint`.
     *
     * Returns null when [want] is inactive, so callers skip the whole thing
     * rather than multiplying by a field of ones.
     */
    fun shaderFor(want: GrainSpec): Shader? {
        if (!want.isActive) return null
        if (spec != want || shader == null) rebuild(want)
        val sh = shader ?: return null
        val bmp = bitmap ?: return null
        // The tile covers scaleDocPx of document, so the scale is that over the
        // tile's pixel size. The translate is negative because the buffer's
        // origin sits at (docX, docY) in document space while the grain is
        // measured from the document's origin -- which is exactly what makes
        // the texture belong to the paper rather than to the stroke.
        // Scale only. **No translate, and the translate is what was wrong.**
        // A paint's shader is evaluated in the canvas's own coordinate space,
        // which here is document space -- so the tile is already anchored to
        // the page and needs nothing further. The first version translated by
        // the scratch buffer's origin, which moves: as a stroke grew, and again
        // between the wet pass and the commit, the grain slid to a new place.
        // From the outside that is a stroke that visibly re-textures itself the
        // instant the pen lifts, which is distracting in the way only a
        // drawing tool can be -- the mark you made is not the mark you keep.
        val s = want.scaleDocPx / bmp.width
        localMatrix.reset()
        localMatrix.setScale(s, s)
        sh.setLocalMatrix(localMatrix)
        return sh
    }

    fun release() {
        spec = null
        bitmap = null
        shader = null
    }

    private fun rebuild(want: GrainSpec) {
        val bytes = GrainField.tile(want)
        val n = GrainField.DEFAULT_TILE
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        bitmap = bmp
        shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        spec = want
        builds++
    }
}
