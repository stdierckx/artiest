package be.thalos.artiest.ui

import android.content.Context
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushEntry
import be.thalos.artiest.engine.brush.BrushLibrary
import be.thalos.artiest.engine.brush.BrushPreset

/**
 * The brush the app had last time, and which shelf entry it came from.
 *
 * Two values and not one: the brush text is the truth about how it draws, and
 * the entry's id is the truth about which row is lit. Deriving the second from
 * the first would mean comparing a loaded brush against every entry and
 * guessing — and a user who has moved a slider is still holding the pencil.
 *
 * Same shape as [ToolbarStore] and for the same reasons: `SharedPreferences`
 * because this is a handful of bytes and neither a file nor a database is
 * warranted, and a load that cannot fail because a stored value that will not
 * parse is a first run, not an error.
 *
 * **The stored id is an id now, not an enum name.** See [loadId] for the one
 * migration that costs.
 */
class BrushStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The stored brush, or the [BrushPreset.PEN] default. */
    fun load(): Brush = BrushCodec.decode(prefs.getString(KEY_BRUSH, null))
        ?: BrushPreset.PEN.create()

    /**
     * The [BrushEntry.tuning] the stored brush was saved under, or 0.
     *
     * Read by the restore path to decide between "load what was saved" and
     * "the tool has been re-tuned since, take the new numbers". See
     * [BrushPreset.TUNING] for why that decision has to exist — and note that a
     * saved brush's tuning is 0 at both ends of the comparison, so the decision
     * never fires for one. That is the shelf's promise that a brush you saved
     * stays put.
     */
    fun storedTuning(): Int = prefs.getInt(KEY_TUNING, 0)

    /**
     * The id of the entry that was in the hand, migrated if it is an old one.
     *
     * The key held the enum's `name` — `"PENCIL"` — until the shelf arrived and
     * now holds [BrushEntry.id] — `"pencil"`. Both are read, by
     * [BrushLibrary.idOf], and the old spelling is recognised
     * case-insensitively. Written down because a silent fallback here means
     * *"the app forgot which brush I was holding"*, which is the complaint this
     * class exists to prevent.
     *
     * An id this build has never heard of is returned as it is rather than
     * corrected: it may be a file that has not been listed yet, and
     * [BrushLibrary.entryFor] is where an id with nothing behind it becomes the
     * pen.
     */
    fun loadId(): String = BrushLibrary.idOf(prefs.getString(KEY_PRESET, null))
        ?: BrushPreset.PEN.id

    /**
     * The id of the brush the eraser uses, or null to erase with the brush in
     * the hand.
     *
     * Null is a real answer and not an empty one — see `InkSurfaceView.rubber`,
     * where it means "the pencil rubs out with the pencil's tilt". So it is
     * stored as an absent key rather than as an empty string, and a brush that
     * has since been deleted comes back as null the same way.
     */
    fun loadEraserId(): String? = prefs.getString(KEY_ERASER, null)?.takeIf { it.isNotEmpty() }

    fun saveEraserId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ERASER) else putString(KEY_ERASER, id)
        }.apply()
    }

    fun save(brush: Brush, entry: BrushEntry) {
        prefs.edit()
            .putString(KEY_BRUSH, BrushCodec.encode(brush))
            .putString(KEY_PRESET, entry.id)
            .putInt(KEY_TUNING, entry.tuning)
            .apply()
    }

    private companion object {
        const val FILE = "chrome"
        const val KEY_BRUSH = "brush.current"
        const val KEY_PRESET = "brush.preset"
        const val KEY_TUNING = "brush.tuning"
        const val KEY_ERASER = "brush.eraser"
    }
}
