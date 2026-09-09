package be.thalos.artiest.ui

import android.content.Context

/**
 * Where the chrome's state lives between runs: the docks, the floating panel's
 * position, and the colours the user has mixed.
 *
 * A toolbar you have to rebuild every launch is not a customisable toolbar, it
 * is a puzzle. This is the whole persistence story: a handful of strings in
 * `SharedPreferences`, written on every change because the changes are rare and
 * a user who arranges their bars and then force-quits should not lose them.
 *
 * [load] cannot fail. Everything that could go wrong with the stored string is
 * handled in [DockCodec], and what arrives here is either a layout or null;
 * null becomes the default. It reads the same preference key the single bar
 * used, which is what makes the migration in [DockCodec] reachable at all — a
 * new key would have been simpler here and would have quietly discarded every
 * bar anybody had already arranged.
 */
class DockStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The stored layout, widened to the current default lengths.
     *
     * Never shortened, because that would drop whatever sits past the new end.
     * Widened, because a release that adds a control must not strand someone
     * whose bars are already full — the day Undo and Redo were added the saved
     * bar was sixteen slots with sixteen in use, and without this rule the new
     * buttons had nowhere to go and the feature was invisible. Slots are
     * absolute, so every existing item keeps the position the user's hand
     * already knows and the new room appears at the end.
     */
    fun load(): DockLayout {
        val stored = DockCodec.decode(prefs.getString(KEY_LAYOUT, null))
            ?: return introduce(DockLayout.DEFAULT)
        val widened = stored.resized { dock -> maxOf(stored.bar(dock).slotCount, dock.defaultSlots) }
        return introduce(widened)
    }

    /**
     * Put newly shipped controls on the bars, once each.
     *
     * The same problem the widening above solves, one step further on. Widening
     * makes *room* for a new control; it does not put one anywhere, so a user
     * who arranged their bars before a release finds the new tool only if they
     * happen to open Arrange and read the chooser. That is how a feature ships
     * invisibly, and a layers panel is not a thing anyone would go looking for a
     * way to add.
     *
     * **Once each, recorded by id.** A control the user has deliberately
     * removed must stay removed, so what is recorded is what has been *offered*
     * rather than what is present: offered-then-deleted and offered-then-kept
     * look identical from here, and neither comes back.
     *
     * The preferred dock is a suggestion. If it is full the item goes wherever
     * it fits, and if nothing fits it is simply not placed — the chooser still
     * has it, and bars arranged to be full were meant to be full.
     */
    private fun introduce(layout: DockLayout): DockLayout {
        val offered = prefs.getString(KEY_OFFERED, null)
            ?.split(',')?.filter { it.isNotEmpty() }?.toMutableSet()
            ?: mutableSetOf()
        var out = layout
        var changed = false
        for ((item, preferred) in NEW_ITEMS) {
            if (!offered.add(item.id)) continue
            changed = true
            if (item in out) continue
            for (dock in listOf(preferred) + Dock.entries.filter { it != preferred }) {
                val slot = out.firstFit(dock, item) ?: continue
                out = out.place(dock, item, slot)
                break
            }
        }
        if (changed) {
            prefs.edit().putString(KEY_OFFERED, offered.joinToString(",")).apply()
            save(out)
        }
        return out
    }

    fun save(layout: DockLayout) {
        prefs.edit().putString(KEY_LAYOUT, DockCodec.encode(layout)).apply()
    }

    /**
     * Where the floating panel sits, as a fraction of the window in each axis.
     *
     * Fractions and not pixels, and that is the answer to the open question the
     * UI plan left for a human: *"does the floating dock need to survive
     * rotation, or reset?"* A fraction survives it — the panel keeps its
     * relative place on the screen instead of landing off the edge or snapping
     * to a dock it was moved away from — and it costs one line rather than a
     * second saved position per orientation. It is not perfect: a panel three
     * quarters of the way down a landscape window lands three quarters of the
     * way down a portrait one, which is further in absolute terms than the user
     * put it. It is honest, cheap, and never off-screen, and the drag to fix it
     * is one gesture.
     */
    fun loadFloatingAt(): Pair<Float, Float> =
        prefs.getFloat(KEY_FLOAT_X, DEFAULT_FLOAT_X) to prefs.getFloat(KEY_FLOAT_Y, DEFAULT_FLOAT_Y)

    fun saveFloatingAt(x: Float, y: Float) {
        prefs.edit()
            .putFloat(KEY_FLOAT_X, x.coerceIn(0f, 1f))
            .putFloat(KEY_FLOAT_Y, y.coerceIn(0f, 1f))
            .apply()
    }

    /**
     * The colours mixed on the wheel, most recent first.
     *
     * The wheel can reach sixteen million colours and the hand can reach about
     * six of them again. Without this the wheel is a control you use once per
     * colour and then re-aim by eye, which is the difference between a picker
     * and a palette.
     */
    fun loadRecentColours(): List<Int> =
        prefs.getString(KEY_RECENT, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.take(MAX_RECENT)
            ?: emptyList()

    /** Push [argb] to the front, dropping a duplicate and the oldest overflow. */
    fun pushRecentColour(argb: Int): List<Int> {
        val next = (listOf(argb) + loadRecentColours().filter { it != argb }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, next.joinToString(",")).apply()
        return next
    }

    companion object {
        /** How many mixed colours are kept. One row of swatches, and no more. */
        const val MAX_RECENT = 8

        private const val PREFS = "chrome"
        private const val KEY_LAYOUT = "toolbar.layout"
        private const val KEY_FLOAT_X = "dock.float.x"
        private const val KEY_FLOAT_Y = "dock.float.y"
        private const val KEY_RECENT = "colour.recent"
        private const val KEY_OFFERED = "toolbar.offered"

        /**
         * Controls that did not exist when someone's bars were arranged, and
         * where each would like to go. Append to this list when a tool ships;
         * never remove from it, because an id that leaves the list is an id
         * that gets offered a second time.
         */
        private val NEW_ITEMS: List<Pair<ToolItem, Dock>> = listOf(
            ToolItem.LAYERS to Dock.RIGHT,
            ToolItem.MARKER to Dock.LEFT,
            ToolItem.ERASER_SIZE to Dock.BOTTOM,
        )

        /** Clear of the left tools and above the bottom sliders, on a first run. */
        private const val DEFAULT_FLOAT_X = 0.32f
        private const val DEFAULT_FLOAT_Y = 0.62f
    }
}
