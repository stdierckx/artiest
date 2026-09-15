package be.thalos.artiest.ui

import android.content.Context
import be.thalos.artiest.engine.brush.Brush
import be.thalos.artiest.engine.brush.BrushCodec
import be.thalos.artiest.engine.brush.BrushEntry
import be.thalos.artiest.engine.brush.copyScalarsOnto

/**
 * What you last set each brush to, kept per brush.
 *
 * ## The complaint this exists for
 *
 * *"I take the eraser, it is set to 96. I change it to 30, because 96 is way
 * too big for what I am doing. I switch back to pencil, and then back to
 * eraser: 96 again."*
 *
 * That is not a bug in the eraser. It is what the app was built to do: picking
 * a row from the shelf called `BrushEntry.applyTo`, which means *be that
 * brush*, and the brush it means is the one that was authored — so every
 * setting the user had made was written over, every time, by design.
 *
 * The design was wrong, and it was wrong in a way that is easy to defend and
 * still loses. **A brush is a tool you own, not a preset you re-open.** Nobody
 * re-sharpens a pencil to the factory point every time they put it down. The
 * only thing the authored numbers are for is the first time you ever pick the
 * brush up, and after that the truth about how wide your eraser is, is
 * whatever you last set it to.
 *
 * ## What is stored, and what is not
 *
 * The whole brush as [BrushCodec] text, and only the **numbers** are read back
 * out of it — see [copyScalarsOnto]. The entry is applied whole first, so the
 * tip and the sensor wiring are already the entry's and re-copying them out of
 * a stored text could only make them worse: a text written by an older build is
 * the one place a *partial* brush comes from, which is the trap
 * `copyWiringOnto` was written for.
 *
 * ## Why the tuning number is stored alongside
 *
 * The same reason `BrushStore.storedTuning` exists, and it is not a detail.
 * `BrushPreset.TUNING` is bumped whenever a shipped brush is re-solved, and a
 * remembered size from before the bump would win over the new number *forever*
 * — the tool would read exactly as it did before the retune, on a device where
 * nobody could tell why. So a tweak is only honoured under the tuning it was
 * made against, and a bump forgets every tweak on a built-in in one stroke.
 *
 * A saved or imported brush has tuning 0 at both ends of that comparison, so a
 * retune never touches one. That is the same promise the shelf already makes
 * about a brush you saved: it stays put.
 *
 * ## Why this is not [BrushStore]
 *
 * [BrushStore] answers *"which brush was in the hand, and how was it set"* —
 * one brush, the one you are holding, restored at launch. This answers *"how
 * was each of the others set, the last time I put it down"*. They overlap for
 * exactly one brush and disagree about nothing, and keeping them apart means
 * this file can be cleared without the app forgetting what you were holding.
 *
 * Its own preferences file for the same reason, and one more: this one grows
 * with the number of brushes you have touched, and `chrome` is read on the way
 * to the first frame.
 */
class BrushTweaks(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Remember how [brush] is set, as [entry]'s settings.
     *
     * A brush that has *not* been moved is forgotten rather than stored, and
     * that is what keeps this file from filling with copies of brushes nobody
     * changed. It also makes Revert's job one call: put the entry back, forget
     * the tweak, and the next pick of that row is the authored brush again.
     */
    fun remember(entry: BrushEntry, brush: Brush) {
        if (entry.matches(brush)) {
            forget(entry.id)
            return
        }
        prefs.edit()
            .putString(KEY_BRUSH + entry.id, BrushCodec.encode(brush))
            .putInt(KEY_TUNING + entry.id, entry.tuning)
            .apply()
    }

    /**
     * Put [entry]'s remembered numbers onto [brush], and say whether there were
     * any.
     *
     * [brush] must already *be* [entry] — apply the entry first. This only ever
     * moves numbers, so calling it on some other brush would make a hybrid of
     * the two that matches nothing on the shelf.
     *
     * Returns false when there is nothing remembered, when what is remembered
     * was made against another tuning of the same built-in, or when the text
     * will not parse. All three mean the same thing to the caller — *the user
     * has never set this one, use what it was authored as* — and the last two
     * also clear the stored value, because a value that can never be honoured
     * is a value that should not be kept.
     */
    fun restore(entry: BrushEntry, brush: Brush): Boolean {
        val text = prefs.getString(KEY_BRUSH + entry.id, null) ?: return false
        if (prefs.getInt(KEY_TUNING + entry.id, 0) != entry.tuning) {
            forget(entry.id)
            return false
        }
        val stored = BrushCodec.decode(text)
        if (stored == null) {
            forget(entry.id)
            return false
        }
        copyScalarsOnto(stored, brush)
        return true
    }

    /** Whether anything is remembered for [id]. */
    fun has(id: String): Boolean = prefs.contains(KEY_BRUSH + id)

    fun forget(id: String) {
        prefs.edit().remove(KEY_BRUSH + id).remove(KEY_TUNING + id).apply()
    }

    /**
     * Drop what is remembered for every brush that is no longer on the shelf.
     *
     * Called once with the library's ids. Without it a brush that was deleted,
     * or one from a build that shipped one more than this one, leaves its
     * settings here for the life of the install — and worse, a *new* brush that
     * happened to be given the same id would silently inherit them.
     *
     * This is also the whole of the size answer. A tweak is a kilobyte of text
     * and one is written only for a brush the user has actually moved a slider
     * on, so the file is bounded by the shelf and the shelf is bounded by the
     * disk.
     */
    fun prune(ids: Set<String>) {
        val stale = prefs.all.keys
            .filter { it.startsWith(KEY_BRUSH) && it.removePrefix(KEY_BRUSH) !in ids }
            .map { it.removePrefix(KEY_BRUSH) }
        if (stale.isEmpty()) return
        val edit = prefs.edit()
        for (id in stale) edit.remove(KEY_BRUSH + id).remove(KEY_TUNING + id)
        edit.apply()
    }

    private companion object {
        const val FILE = "brush-tweaks"

        /**
         * The two prefixes, and why the brush one has to be the longer read.
         *
         * [prune] finds ids by stripping [KEY_BRUSH] off every key that starts
         * with it. If one prefix were a prefix of the other, a tuning key would
         * be read as a brush key with a mangled id and pruning would delete
         * live tweaks. They are the same length and differ at the first
         * character, so that cannot happen.
         */
        const val KEY_BRUSH = "b/"
        const val KEY_TUNING = "t/"
    }
}
