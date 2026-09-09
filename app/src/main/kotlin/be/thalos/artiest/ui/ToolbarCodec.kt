package be.thalos.artiest.ui

/**
 * The toolbar layout as one line of text, for `SharedPreferences`.
 *
 * Format: `v1|<slots>|<slot>=<id>,<slot>=<id>,...`, for example
 * `v1|12|0=colour,3=size,11=stats`. An empty bar is `v1|12|`.
 *
 * **Decoding never throws and never returns a partly-broken layout.** That is
 * the only interesting property here, and it is worth stating why it is a
 * requirement rather than politeness. This string is written by one version of
 * the app and read by the next. A build that adds a control writes an id the
 * previous build has never heard of; a build that removes one reads an id that
 * no longer exists; a slot count can change with the screen. Every one of those
 * is a normal Tuesday, and none of them may be a crash on launch into a blank
 * screen — the failure mode of a drawing app that cannot start is that the
 * drawing is inaccessible.
 *
 * So: an unreadable string decodes to null and the caller falls back to a
 * default; a readable string with unusable *entries* drops those entries and
 * keeps the rest, through [ToolbarLayout.of]. The line between the two is the
 * version tag and the slot count — if those do not parse there is no bar to
 * build; anything after them is best-effort.
 */
object ToolbarCodec {

    private const val VERSION = "v1"

    fun encode(layout: ToolbarLayout): String = buildString {
        append(VERSION).append('|')
        append(layout.slotCount).append('|')
        append(layout.placements.joinToString(",") { "${it.slot}=${it.item.id}" })
    }

    /** Null if [text] is not a layout at all. See the class KDoc for the distinction. */
    fun decode(text: String?): ToolbarLayout? {
        if (text == null) return null
        val parts = text.split('|')
        if (parts.size != 3) return null
        if (parts[0] != VERSION) return null
        val slots = parts[1].toIntOrNull() ?: return null
        if (slots < 1 || slots > MAX_SLOTS) return null

        val placements = parts[2]
            .split(',')
            .mapNotNull { entry ->
                if (entry.isBlank()) return@mapNotNull null
                val eq = entry.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val slot = entry.substring(0, eq).toIntOrNull() ?: return@mapNotNull null
                val item = ToolItem.byId(entry.substring(eq + 1)) ?: return@mapNotNull null
                Placement(item, slot)
            }
        return ToolbarLayout.of(slots, placements)
    }

    /**
     * A ceiling on the decoded slot count, because the number comes off disk
     * and is used to size a row of composables. Not a design limit — a bar this
     * long is already unusable — but the difference between a corrupt
     * preference and an allocation the size of the integer someone typed.
     */
    private const val MAX_SLOTS = 64
}
