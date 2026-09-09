package be.thalos.artiest.ink

import android.graphics.Bitmap
import be.thalos.artiest.engine.brush.AlphaMask
import be.thalos.artiest.engine.brush.MaskCache
import be.thalos.artiest.engine.brush.MaskSpec
import java.nio.ByteBuffer

/**
 * The app-side half of the mask cache: engine alpha bytes uploaded into
 * `ALPHA_8` bitmaps a `Canvas` can blit.
 *
 * **`ALPHA_8` and not `ARGB_8888`, which is the whole point.** An `ALPHA_8`
 * bitmap drawn through `drawBitmap` is treated as a coverage mask and coloured
 * by the paint, so one mask serves every colour and the cache survives a
 * palette change. It is also a quarter of the memory: a 128 px dab is 16 KiB
 * rather than 64.
 *
 * The bitmap is hung on [AlphaMask.attachment] rather than kept in a second map
 * keyed the same way, so eviction cannot get out of step — when [MaskCache]
 * drops a mask the bitmap is dropped with it, and there is no second policy to
 * maintain. Nothing recycles the bitmap explicitly: a `Bitmap` whose last
 * reference goes away is collected with its native pixels, and recycling one
 * that a queued `RecordingCanvas` still refers to is a crash rather than a
 * saving.
 *
 * Not thread-safe, and owned by the render thread, for the same reason
 * [MaskCache] is.
 */
class StampCache(
    val masks: MaskCache = MaskCache(),
) {

    /** Bitmaps uploaded since construction. Equal to the mask cache's misses. */
    var uploads: Long = 0L
        private set

    /**
     * The bitmap for [spec], generating and uploading if needed.
     *
     * Returns the mask too, because the caller needs its extent and hotspot to
     * place the blit, and asking the cache twice would count two lookups.
     */
    fun stampFor(spec: MaskSpec): AlphaMask {
        val mask = masks.get(spec)
        if (mask.attachment == null) {
            mask.attachment = upload(mask)
            uploads++
        }
        return mask
    }

    /** The uploaded bitmap for a mask [stampFor] has already returned. */
    fun bitmapOf(mask: AlphaMask): Bitmap = mask.attachment as Bitmap

    fun clear() {
        masks.clear()
        uploads = 0
    }

    private fun upload(mask: AlphaMask): Bitmap {
        val bmp = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        // copyPixelsFromBuffer wants exactly the bitmap's byte count and reads
        // from the buffer's current position, so the wrap is used once and
        // discarded rather than kept and rewound.
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(mask.alpha))
        return bmp
    }

    override fun toString(): String = "StampCache($uploads uploaded, $masks)"
}
