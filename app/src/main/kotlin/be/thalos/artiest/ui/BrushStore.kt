package be.thalos.artiest.ui

import android.content.Context
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushPreset

/**
 * The brush the app had last time, and which preset it came from.
 *
 * Two values and not one: the brush text is the truth about how it draws, and
 * the preset name is the truth about which button should look pressed. Deriving
 * the second from the first would mean comparing a loaded brush against every
 * preset and guessing — and a user who has moved a slider is still holding the
 * pencil.
 *
 * Same shape as [ToolbarStore] and for the same reasons: `SharedPreferences`
 * because this is a handful of bytes and neither a file nor a database is
 * warranted, and a load that cannot fail because a stored value that will not
 * parse is a first run, not an error.
 */
class BrushStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The stored brush, or the [BrushPreset.PEN] default. */
    fun load(): Brush = BrushCodec.decode(prefs.getString(KEY_BRUSH, null))
        ?: BrushPreset.PEN.create()

    /**
     * The [BrushPreset.TUNING] the stored brush was saved under, or 0 for a
     * brush from before this was recorded.
     *
     * Read by the restore path to decide between "load what was saved" and
     * "the tool has been re-tuned since, take the new numbers". See
     * [BrushPreset.TUNING] for why that decision has to exist.
     */
    fun storedTuning(): Int = prefs.getInt(KEY_TUNING, 0)

    /** The stored preset, or [BrushPreset.PEN]. */
    fun loadPreset(): BrushPreset {
        val name = prefs.getString(KEY_PRESET, null) ?: return BrushPreset.PEN
        return BrushPreset.entries.firstOrNull { it.name == name } ?: BrushPreset.PEN
    }

    fun save(brush: Brush, preset: BrushPreset) {
        prefs.edit()
            .putString(KEY_BRUSH, BrushCodec.encode(brush))
            .putString(KEY_PRESET, preset.name)
            .putInt(KEY_TUNING, BrushPreset.TUNING)
            .apply()
    }

    private companion object {
        const val FILE = "chrome"
        const val KEY_BRUSH = "brush.current"
        const val KEY_PRESET = "brush.preset"
        const val KEY_TUNING = "brush.tuning"
    }
}
