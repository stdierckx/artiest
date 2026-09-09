package be.thalos.artiest.ui

/**
 * Every dock's bar as one line of text, for `SharedPreferences`.
 *
 * Format: `v2|<dock>:<slots>:<entries>|<dock>:<slots>:<entries>|...`, where
 * entries are the same `<slot>=<id>` pairs [ToolbarCodec] writes. For example
 *
 * ```
 * v2|left:12:0=pen,1=pencil,4=colour|top:24:0=undo,1=redo|bottom:24:0=size
 * ```
 *
 * A dock with nothing in it may be written or left out; both decode the same
 * way, because [DockLayout.of] fills in what is missing.
 *
 * ## Decoding never throws, and now it also never loses a bar somebody built
 *
 * [ToolbarCodec]'s rule is inherited whole: an unreadable string decodes to
 * null and the caller falls back to a default, and a readable string with
 * unusable *entries* drops those entries and keeps the rest. Unknown item ids
 * and unknown dock names are the same kind of event — a build that has moved on
 * — and both drop one entry rather than the line.
 *
 * The rule gains a clause here. **A `v1` string is not unreadable; it is a
 * top bar.** Before docking there was one toolbar and it sat across the top,
 * so that is where its contents go, at the slot numbers they were saved with.
 * Reading a v1 string as a failure would have been the easy thing and it would
 * have silently emptied the bar of everyone who had already arranged one — the
 * exact failure [ToolbarCodec]'s KDoc exists to rule out, arriving through the
 * door marked *new feature* instead of the one marked *renamed constant*.
 */
object DockCodec {

    private const val VERSION = "v2"

    /** Where a pre-docking bar lands. See the class KDoc. */
    internal val V1_DOCK = Dock.TOP

    fun encode(layout: DockLayout): String = buildString {
        append(VERSION)
        for (dock in Dock.entries) {
            val bar = layout.bar(dock)
            append('|').append(dock.id)
            append(':').append(bar.slotCount)
            append(':').append(bar.placements.joinToString(",") { "${it.slot}=${it.item.id}" })
        }
    }

    /** Null if [text] is not a layout at all. See the class KDoc for the line. */
    fun decode(text: String?): DockLayout? {
        if (text == null) return null
        if (text.startsWith("v1|")) return ToolbarCodec.decode(text)?.let { fromV1(it) }

        val parts = text.split('|')
        if (parts.size < 2) return null
        if (parts[0] != VERSION) return null

        val bars = LinkedHashMap<Dock, ToolbarLayout>()
        for (segment in parts.drop(1)) {
            val fields = segment.split(':')
            if (fields.size != 3) continue
            val dock = Dock.byId(fields[0]) ?: continue
            val slots = fields[1].toIntOrNull() ?: continue
            if (slots < 1 || slots > MAX_SLOTS) continue
            // Later segments naming the same dock lose, so a duplicated dock is
            // the same non-event as a duplicated slot: first one wins.
            if (dock in bars) continue
            bars[dock] = ToolbarLayout.of(slots, parseEntries(fields[2]))
        }
        // Not one readable dock in a string that claimed to be a layout: that is
        // a corrupt preference and not an empty toolbar, and the difference
        // matters because the caller falls back to a working default for one
        // and honours the other.
        if (bars.isEmpty()) return null
        return DockLayout.of(bars)
    }

    private fun parseEntries(text: String): List<Placement> =
        text.split(',').mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val eq = entry.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val slot = entry.substring(0, eq).toIntOrNull() ?: return@mapNotNull null
            val item = ToolItem.byId(entry.substring(eq + 1)) ?: return@mapNotNull null
            Placement(item, slot)
        }

    /**
     * A single pre-docking bar, put where that bar used to be.
     *
     * Widened to the top dock's own default length rather than kept at the
     * saved one, for the reason `ToolbarStore` widens and never shortens: the
     * saved bar may be exactly full, and a migration that preserves *full* is a
     * migration after which no new control can ever appear.
     */
    private fun fromV1(bar: ToolbarLayout): DockLayout =
        DockLayout.of(mapOf(V1_DOCK to bar.resized(maxOf(bar.slotCount, V1_DOCK.defaultSlots))))

    /**
     * A ceiling on a decoded slot count, for the reason [ToolbarCodec] has one:
     * the number comes off disk and is used to size a row of composables.
     */
    private const val MAX_SLOTS = 64
}
