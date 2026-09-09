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

    fun load(): ToolbarLayout =
        ToolbarCodec.decode(prefs.getString(KEY_LAYOUT, null)) ?: ToolbarLayout.DEFAULT

    fun save(layout: ToolbarLayout) {
        prefs.edit().putString(KEY_LAYOUT, ToolbarCodec.encode(layout)).apply()
    }

    private companion object {
        const val PREFS = "chrome"
        const val KEY_LAYOUT = "toolbar.layout"
    }
}
