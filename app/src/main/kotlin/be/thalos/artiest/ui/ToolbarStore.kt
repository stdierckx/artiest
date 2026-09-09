package be.thalos.artiest.ui

import android.content.Context

/**
 * Where the toolbar layout lives between runs.
 *
 * A toolbar you have to rebuild every launch is not a customisable toolbar, it
 * is a puzzle. This is the whole persistence story: one string in
 * `SharedPreferences`, written on every change because the changes are rare and
 * a user who arranges their bar and then force-quits should not lose it.
 *
 * [load] cannot fail. Everything that could go wrong with the stored string is
 * handled in [ToolbarCodec], and what arrives here is either a layout or null;
 * null becomes the default. See that class for why "cannot fail" is a
 * requirement and not a nicety.
 */
class ToolbarStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The stored bar, widened to the current default length if it is shorter.
     *
     * Never shortened, because that would drop whatever sits past the new end.
     * Widened, because a release that adds a control must not strand someone
     * whose bar is already full — the day Undo and Redo were added, the saved
     * bar was sixteen slots with sixteen slots used, and without this rule the
     * new buttons had nowhere to go and the feature was invisible. Slots are
     * absolute, so every existing item keeps the position the user's hand
     * already knows and the new room appears at the end.
     */
    fun load(): ToolbarLayout {
        val stored = ToolbarCodec.decode(prefs.getString(KEY_LAYOUT, null))
            ?: return ToolbarLayout.DEFAULT
        return if (stored.slotCount < ToolbarLayout.DEFAULT_SLOTS) {
            stored.resized(ToolbarLayout.DEFAULT_SLOTS)
        } else {
            stored
        }
    }

    fun save(layout: ToolbarLayout) {
        prefs.edit().putString(KEY_LAYOUT, ToolbarCodec.encode(layout)).apply()
    }

    private companion object {
        const val PREFS = "chrome"
        const val KEY_LAYOUT = "toolbar.layout"
    }
}
