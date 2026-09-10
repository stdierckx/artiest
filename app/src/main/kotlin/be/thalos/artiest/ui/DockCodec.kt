package be.thalos.artiest.ui

/**
 * Every bar as one line of text, for `SharedPreferences`.
 *
 * Format: `v3|<bar>|<bar>|…`, where an edge bar is
 *
 * ```
 * left:12:0=pen,1=pencil,4=colour
 * ```
 *
 * and a floating bar carries its position and its own name:
 *
 * ```
 * f1@0.32,0.45:8:0=colour_panel
 * ```
 *
 * A bar with nothing on it may be written or left out; both decode the same way,
 * because [DockLayout.of] fills in the four edges and drops nothing else.
 *
 * **Spans are not written.** How many slots a control takes follows from the
 * item and from which way its bar runs, and both of those are known at decode
 * time. Writing the span down would mean a release that resizes a panel leaves
 * every saved layout laid out to a number no code believes any more.
 *
 * ## Decoding never throws, and never loses a bar somebody built
 *
 * That rule came from [ToolbarCodec] and it is a requirement rather than
 * politeness. This string is written by one version of the app and read by the
 * next: a build that adds a control writes an id the previous one has never
 * heard of, a build that removes one reads an id that no longer exists, and a
 * slot count can change with the screen. Every one of those is a normal Tuesday,
 * and none of them may be a crash on launch into a blank screen — the failure
 * mode of a drawing app that cannot start is that the drawing is inaccessible.
 *
 * So an unreadable string decodes to null and the caller falls back to a
 * default, and a readable string with unusable *entries* drops those entries and
 * keeps the rest. Unknown item ids, unknown dock names and unreadable positions
 * are all the same kind of event and all drop one thing rather than the line.
 *
 * ## The two older formats
 *
 * `v1` was one bar, before there were docks, and it sat across the top; it
 * decodes to the top edge at the slot numbers it was saved with. `v2` had five
 * fixed docks, one of them a single floating bar; its `float` segment becomes
 * the first floating bar. Reading either as a failure would have been the easy
 * thing and would have silently emptied the toolbar of everyone who had already
 * arranged one.
 */
object DockCodec {

    private const val VERSION = "v3"

    /** Where a pre-docking bar lands. See the class KDoc. */
    internal val V1_DOCK = Dock.TOP

    fun encode(layout: DockLayout): String = buildString {
        append(VERSION)
        for (bar in layout.bars) {
            append('|').append(bar.id)
            bar.spot?.let { append('@').append(fmt(it.x)).append(',').append(fmt(it.y)) }
            append(':').append(bar.slots.slotCount)
            append(':').append(bar.slots.placements.joinToString(",") { "${it.slot}=${it.item.id}" })
        }
    }

    /** Null if [text] is not a layout at all. See the class KDoc for the line. */
    fun decode(text: String?): DockLayout? {
        if (text == null) return null
        if (text.startsWith("v1|")) return decodeV1(text)
        if (text.startsWith("v2|")) return decodeV2(text)

        val parts = text.split('|')
        if (parts.size < 2 || parts[0] != VERSION) return null

        val bars = ArrayList<Bar>()
        val seen = HashSet<String>()
        for (segment in parts.drop(1)) {
            val bar = parseBar(segment) ?: continue
            // Later segments naming the same bar lose, so a duplicated bar is
            // the same non-event as a duplicated slot: first one wins.
            if (!seen.add(bar.id)) continue
            bars += bar
        }
        // Not one readable bar in a string that claimed to be a layout: that is
        // a corrupt preference and not an empty toolbar, and the difference
        // matters because the caller honours one and replaces the other.
        if (bars.isEmpty()) return null
        return DockLayout.of(bars)
    }

    /** `left:12:…` or `f1@0.3,0.4:8:…`, or null when it is neither. */
    private fun parseBar(segment: String): Bar? {
        val head = segment.substringBefore(':', missingDelimiterValue = "")
        if (head.isEmpty()) return null
        val rest = segment.substring(head.length + 1).split(':')
        if (rest.size != 2) return null
        val slots = rest[0].toIntOrNull() ?: return null
        if (slots < 1 || slots > MAX_SLOTS) return null

        val at = head.indexOf('@')
        val id = if (at < 0) head else head.substring(0, at)
        val dock = Dock.byId(id) ?: if (id.startsWith(DockLayout.FLOAT_PREFIX)) {
            Dock.FLOATING
        } else {
            return null
        }
        // An edge writes no position and a floating bar always does. A position
        // on an edge is ignored rather than refused; a floating bar without one
        // is placed by DockLayout.of, which is the same fallback a bar that was
        // never given one gets.
        // A position that will not parse drops the position, not the bar: the
        // bar is what somebody built and DockLayout.of has a fallback for where
        // it goes, so losing two numbers is cheaper than losing the contents.
        val spot = if (at < 0 || dock.isEdge) {
            null
        } else {
            val comma = head.indexOf(',', at)
            if (comma <= at) {
                null
            } else {
                val x = head.substring(at + 1, comma).toFloatOrNull()
                val y = head.substring(comma + 1).toFloatOrNull()
                if (x == null || y == null) null else BarSpot.of(x, y)
            }
        }
        if (dock.isEdge && id != dock.id) return null
        return Bar(id, dock, spot, ToolbarLayout.of(slots, parseEntries(rest[1], dock.axis)))
    }

    private fun parseEntries(text: String, axis: Axis): List<Placement> =
        text.split(',').mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val eq = entry.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val slot = entry.substring(0, eq).toIntOrNull() ?: return@mapNotNull null
            val item = ToolItem.byId(entry.substring(eq + 1)) ?: return@mapNotNull null
            Placement(item, slot, item.slotsIn(axis))
        }

    /**
     * The five-dock format. Its `float` segment becomes the first floating bar,
     * positioned by the caller — `DockStore` still holds the two preference keys
     * that used to carry that position, precisely so this can use them.
     */
    private fun decodeV2(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val bars = ArrayList<Bar>()
        for (segment in parts.drop(1)) {
            val fields = segment.split(':')
            if (fields.size != 3) continue
            val dock = Dock.byId(fields[0]) ?: continue
            val slots = fields[1].toIntOrNull() ?: continue
            if (slots < 1 || slots > MAX_SLOTS) continue
            val id = if (dock.isEdge) dock.id else "${DockLayout.FLOAT_PREFIX}1"
            if (bars.any { it.id == id }) continue
            val layout = ToolbarLayout.of(slots, parseEntries(fields[2], dock.axis))
            // An empty floating bar in a v2 string is the dock that always
            // existed rather than one somebody made, so it is not carried over.
            if (!dock.isEdge && layout.isEmpty) continue
            bars += Bar(id, dock, null, layout)
        }
        if (bars.isEmpty()) return null
        return DockLayout.of(bars)
    }

    /**
     * A single pre-docking bar, put where that bar used to be.
     *
     * Widened to the top edge's own default length rather than kept at the saved
     * one, for the reason `DockStore` widens and never shortens: the saved bar
     * may be exactly full, and a migration that preserves *full* is one after
     * which no new control can ever appear.
     */
    private fun decodeV1(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != "v1") return null
        val slots = parts[1].toIntOrNull() ?: return null
        if (slots < 1 || slots > MAX_SLOTS) return null
        val dock = V1_DOCK
        val layout = ToolbarLayout.of(slots, parseEntries(parts[2], dock.axis))
            .resized(maxOf(slots, dock.defaultSlots))
        return DockLayout.of(listOf(Bar(dock.id, dock, null, layout)))
    }

    /** Three places. A bar positioned to a thousandth of a screen is exact enough. */
    private fun fmt(v: Float): String = ((v * 1000f).toInt() / 1000f).toString()

    /**
     * A ceiling on a decoded slot count, because the number comes off disk and
     * is used to size a row of composables. Not a design limit — a bar this long
     * is already unusable — but the difference between a corrupt preference and
     * an allocation the size of the integer someone typed.
     */
    private const val MAX_SLOTS = 64
}
