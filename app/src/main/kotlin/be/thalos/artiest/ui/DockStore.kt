package be.thalos.artiest.ui

import android.content.Context

/**
 * Where the chrome's state lives between runs: the surfaces, and the colours
 * the user has mixed.
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
     * The stored layout, or the default when there is none.
     *
     * It used to widen every bar to the current default length, so that a
     * release adding a control did not strand a user whose bars were full.
     * There is nothing left to widen to: since `docs/ui-grid-plan.md` a bar is a
     * shape somebody drew, and lengthening it would change their drawing. What
     * replaces it is [introduce] failing quietly — a new control goes wherever
     * there is room, and a workspace with no room was meant to have no room.
     */
    fun load(filter: CatalogueFilter = CatalogueFilter.EVERYTHING): DockLayout {
        val stored = DockCodec.decode(prefs.getString(KEY_LAYOUT, null))
            ?: return introduce(DockLayout.DEFAULT, filter)
        return introduce(stored, filter)
    }

    /**
     * Put newly shipped controls on the bars, once each.
     *
     * A user who arranged their surfaces before a release finds a new tool
     * only if they happen to open Arrange and read the chooser. That is how a
     * feature ships invisibly, and a layers panel is not a thing anyone would
     * go looking for a way to add.
     *
     * **Once each, recorded by id.** A control the user has deliberately
     * removed must stay removed, so what is recorded is what has been *offered*
     * rather than what is present: offered-then-deleted and offered-then-kept
     * look identical from here, and neither comes back.
     *
     * It goes wherever it fits, in the order the surfaces are in; if nothing
     * fits it is simply not placed — the chooser still has it, and a
     * workspace arranged to be full was meant to be full. There used to be a
     * preferred dock per control and there is nothing left for one to name.
     *
     * **The filter decides where a tool is put, not whether it is recorded as
     * offered.** A control the current workspace hides is marked offered and
     * simply not placed, and switching to *Everything* later does not resurrect
     * it. That looks harsh until you see what the alternative costs: the offered
     * set is what protects a deliberate removal, and it cannot tell
     * offered-then-deleted from offered-then-kept. If a hidden tool were left
     * unoffered, every workspace switch would rain new buttons onto bars the
     * user had already arranged — which is the bug this whole mechanism exists
     * to prevent, arriving through the door marked *helpful*.
     *
     * The tool is not lost either way: it is one search away in the chooser,
     * which is the escape hatch the filter is only safe because of.
     */
    private fun introduce(layout: DockLayout, filter: CatalogueFilter): DockLayout {
        val offered = prefs.getString(KEY_OFFERED, null)
            ?.split(',')?.filter { it.isNotEmpty() }?.toMutableSet()
            ?: mutableSetOf()
        var out = layout
        var changed = false
        for (item in NEW_ITEMS) {
            if (!offered.add(item.id)) continue
            changed = true
            if (item in out) continue
            if (item !in filter) continue
            val (surfaceId, cell) = out.anyFit(item) ?: continue
            out = out.place(surfaceId, item, cell)
        }
        if (changed) {
            prefs.edit().putString(KEY_OFFERED, offered.joinToString(",")).apply()
            save(out)
        }
        return out
    }

    fun save(layout: DockLayout) {
        // Tidied on the way out, not on the way in: a surface has to survive
        // being empty for as long as the drag emptying it might still be
        // undone, and for the whole of the time it is being drawn, but an
        // empty one has no business outliving the session.
        prefs.edit().putString(KEY_LAYOUT, DockCodec.encode(layout.tidied())).apply()
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
                private const val KEY_RECENT = "colour.recent"
        private const val KEY_OFFERED = "toolbar.offered"

        /**
         * Controls that did not exist when someone's bars were arranged, and
         * where each would like to go. Append to this list when a tool ships;
         * never remove from it, because an id that leaves the list is an id
         * that gets offered a second time.
         */
        private val NEW_ITEMS: List<ToolItem> = listOf(
            ToolItem.LAYERS,
            ToolItem.MARKER,
            ToolItem.IMPORT,
            ToolItem.ERASER_SIZE,
            ToolItem.MARQUEE,
            ToolItem.SELECTION,
        )

    }
}
