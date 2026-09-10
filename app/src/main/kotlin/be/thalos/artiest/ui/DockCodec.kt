package be.thalos.artiest.ui

/**
 * Every surface as one line of text, for `SharedPreferences`.
 *
 * Format: `v4|<surface>|<surface>|…`, where an edge is
 *
 * ```
 * left:R0,0,1,12:down_right:0,0=pen,0,1=pencil,0,4=colour
 * ```
 *
 * and a floating surface carries its position and its own name:
 *
 * ```
 * f1@0.32,0.45:R0,0,8,1:right_down:0,0=colour_panel@6x11
 * ```
 *
 * Four fields: the id, the **shape** as an `R` and a list of `x,y,w,h`
 * rectangles joined by `+`, the fill order, and what is on it as `x,y=id`.
 * A surface with nothing on it may be written or left out; both decode the same
 * way, because [DockLayout.of] fills in the four edges and drops nothing else.
 *
 * **Sizes are still not written**, with the one exception they always had. How
 * many cells a control takes follows from the item and from the shape it is
 * standing in, and both of those are known at decode time. Writing it down
 * would mean a release that resizes a panel leaves every saved layout laid out
 * to a number no code believes any more. A panel is the exception because a
 * panel can be resized by the user, and a size somebody chose is theirs.
 *
 * ## Decoding never throws, and never loses a surface somebody built
 *
 * That rule is a requirement rather than politeness. This string is written by
 * one version of the app and read by the next: a build that adds a control
 * writes an id the previous one has never heard of, a build that removes one
 * reads an id that no longer exists, and a screen can change size. Every one of
 * those is a normal Tuesday, and none of them may be a crash on launch into a
 * blank screen — the failure mode of a drawing app that cannot start is that
 * the drawing is inaccessible.
 *
 * So an unreadable string decodes to null and the caller falls back to a
 * default, and a readable string with unusable *entries* drops those entries and
 * keeps the rest. Unknown item ids, unknown dock names, unreadable positions and
 * rectangles off the grid are all the same kind of event and all drop one thing
 * rather than the line.
 *
 * ## The three older formats
 *
 * `v1` was one bar, before there were docks, and it sat across the top; it
 * decodes to the top edge at the slot numbers it was saved with. `v2` had five
 * fixed docks, one of them a single floating bar; its `float` segment becomes
 * the first floating surface. `v3` was the same five-plus-N model with a slot
 * count instead of a shape, and it becomes a strip of that length along the
 * dock's own axis — which is exactly the shape it always drew.
 *
 * Reading any of them as a failure would have been the easy thing and would
 * have silently emptied the toolbar of everyone who had already arranged one.
 *
 * ## Which way this format travels
 *
 * Forwards only. A `v4` string handed to a build that only knows `v3` is
 * unreadable and falls back to the default, which is why this is a
 * *preference* and never a sharing format. Sharing a workspace is a JSON file
 * that names its own version and drops what it cannot do — see
 * `docs/ui-expansion-plan.md`, U7.
 */
object DockCodec {

    private const val VERSION = "v4"

    /** Where a pre-docking bar lands. See the class KDoc. */
    internal val V1_DOCK = Dock.TOP

    fun encode(layout: DockLayout): String = buildString {
        append(VERSION)
        for (surface in layout.surfaces) {
            append('|').append(surface.id)
            surface.spot?.let { append('@').append(fmt(it.x)).append(',').append(fmt(it.y)) }
            append(':').append(encodeRegion(surface.region))
            append(':').append(surface.flow.id)
            append(':').append(
                surface.slots.placements.joinToString(",") { p ->
                    // A panel carries the size the user gave it; nothing else
                    // does, because nothing else can be resized and a number
                    // that is always derivable is a number that can go stale.
                    if (p.item.kind == ToolKind.PANEL) {
                        "${p.x},${p.y}=${p.item.id}@${p.w}x${p.h}"
                    } else {
                        "${p.x},${p.y}=${p.item.id}"
                    }
                }
            )
        }
    }

    /** Null if [text] is not a layout at all. See the class KDoc for the line. */
    fun decode(text: String?): DockLayout? {
        if (text == null) return null
        if (text.startsWith("v1|")) return decodeV1(text)
        if (text.startsWith("v2|")) return decodeV2(text)
        if (text.startsWith("v3|")) return decodeV3(text)

        val parts = text.split('|')
        if (parts.size < 2 || parts[0] != VERSION) return null

        val surfaces = ArrayList<Surface>()
        val seen = HashSet<String>()
        for (segment in parts.drop(1)) {
            val surface = parseSurface(segment) ?: continue
            // Later segments naming the same surface lose, so a duplicate is
            // the same non-event as a duplicated cell: first one wins.
            if (!seen.add(surface.id)) continue
            surfaces += surface
        }
        // Not one readable surface in a string that claimed to be a layout:
        // that is a corrupt preference and not an empty toolbar, and the
        // difference matters because the caller honours one and replaces the
        // other.
        if (surfaces.isEmpty()) return null
        return DockLayout.of(surfaces)
    }

    // -----------------------------------------------------------------------
    // v4
    // -----------------------------------------------------------------------

    /** `left:R0,0,1,12:down_right:…`, or null when it is not a surface. */
    private fun parseSurface(segment: String): Surface? {
        val head = segment.substringBefore(':', missingDelimiterValue = "")
        if (head.isEmpty()) return null
        val rest = segment.substring(head.length + 1).split(':')
        if (rest.size != 3) return null

        val anchor = parseAnchor(head) ?: return null
        val region = parseRegion(rest[0]) ?: return null
        if (region.isEmpty) return null
        val flow = FlowOrder.byId(rest[1]) ?: anchor.dock.defaultFlow()
        return Surface(
            id = anchor.id,
            dock = anchor.dock,
            spot = anchor.spot,
            flow = flow,
            slots = SurfaceLayout.of(region, parseCells(rest[2], region)),
        )
    }

    /** `R0,0,1,12+0,11,5,1`, or null. */
    private fun parseRegion(text: String): CellRegion? {
        if (!text.startsWith('R')) return null
        val rects = ArrayList<CellRect>()
        for (part in text.substring(1).split('+')) {
            val n = part.split(',')
            if (n.size != 4) continue
            val rect = CellRect.of(
                n[0].toIntOrNull() ?: continue,
                n[1].toIntOrNull() ?: continue,
                n[2].toIntOrNull() ?: continue,
                n[3].toIntOrNull() ?: continue,
            ) ?: continue
            rects += rect
            if (rects.size >= MAX_RECTS) break
        }
        if (rects.isEmpty()) return null
        return CellRegion.of(rects)
    }

    private fun encodeRegion(region: CellRegion): String =
        "R" + region.rects.joinToString("+") { "${it.x},${it.y},${it.w},${it.h}" }

    /** `0,0=pen,0,1=pencil,2,0=colour_panel@6x11`. */
    private fun parseCells(text: String, region: CellRegion): List<CellPlacement> {
        val out = ArrayList<CellPlacement>()
        // Split on the commas that separate entries, which are the ones after
        // an `=`. Two commas per entry live inside the cell, so a plain split
        // would cut every entry into three.
        for (raw in splitEntries(text)) {
            // A stray comma belongs to the junk before it, not to the entry
            // after it. Without this, one malformed entry takes its neighbour
            // down with it, which is exactly the "drops the line" behaviour the
            // class KDoc says must not happen.
            val entry = raw.trim().trimStart(',')
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val at = entry.substring(0, eq).split(',')
            if (at.size != 2) continue
            val x = at[0].toIntOrNull() ?: continue
            val y = at[1].toIntOrNull() ?: continue
            val body = entry.substring(eq + 1)
            val sizeAt = body.indexOf('@')
            val item = ToolItem.byId(if (sizeAt < 0) body else body.substring(0, sizeAt)) ?: continue
            // A size that will not parse falls back to what the shape says,
            // which is a control at the wrong size rather than a control that
            // is gone.
            val size = if (sizeAt < 0) null else sizeOf(body.substring(sizeAt + 1))
            val natural = RegionLayout.naturalSize(item, region, Cell(x, y))
            out += CellPlacement(
                item = item,
                x = x,
                y = y,
                w = size?.first ?: natural.first,
                h = size?.second ?: natural.second,
            )
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    /** Entries are `x,y=id`, so an entry ends at the comma after the next `=`. */
    private fun splitEntries(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var seenEquals = false
        for (i in text.indices) {
            when (text[i]) {
                '=' -> seenEquals = true
                ',' -> if (seenEquals) {
                    out += text.substring(start, i)
                    start = i + 1
                    seenEquals = false
                }
            }
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    // -----------------------------------------------------------------------
    // shared
    // -----------------------------------------------------------------------

    private class Anchor(val id: String, val dock: Dock, val spot: BarSpot?)

    /** `left`, or `f1@0.3,0.4`. Null when it names no dock. */
    private fun parseAnchor(head: String): Anchor? {
        val at = head.indexOf('@')
        val id = if (at < 0) head else head.substring(0, at)
        val dock = Dock.byId(id) ?: if (id.startsWith(DockLayout.FLOAT_PREFIX)) {
            Dock.FLOATING
        } else {
            return null
        }
        if (dock.isEdge && id != dock.id) return null
        // An edge writes no position and a floating surface always does. A
        // position on an edge is ignored rather than refused; a floating one
        // without a position is placed by DockLayout.of, which is the same
        // fallback a surface that was never given one gets.
        // A position that will not parse drops the position, not the surface:
        // the surface is what somebody built, and losing two numbers is cheaper
        // than losing the contents.
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
        return Anchor(id, dock, spot)
    }

    /** `6x11`, or null. */
    private fun sizeOf(text: String): Pair<Int, Int>? {
        val x = text.indexOf('x')
        if (x <= 0) return null
        val w = text.substring(0, x).toIntOrNull() ?: return null
        val h = text.substring(x + 1).toIntOrNull() ?: return null
        if (w !in 1..MAX_SLOTS || h !in 1..MAX_SLOTS) return null
        return w to h
    }

    // -----------------------------------------------------------------------
    // the older formats
    // -----------------------------------------------------------------------

    /**
     * The slot-count format. A bar of `n` slots is a strip `n` cells long along
     * its dock's own axis, which is the shape it always drew.
     */
    private fun decodeV3(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val surfaces = ArrayList<Surface>()
        val seen = HashSet<String>()
        for (segment in parts.drop(1)) {
            val head = segment.substringBefore(':', missingDelimiterValue = "")
            if (head.isEmpty()) continue
            val rest = segment.substring(head.length + 1).split(':')
            if (rest.size != 2) continue
            val slots = rest[0].toIntOrNull() ?: continue
            if (slots < 1 || slots > MAX_SLOTS) continue
            val anchor = parseAnchor(head) ?: continue
            if (!seen.add(anchor.id)) continue
            val region = CellRegion.strip(slots, anchor.dock.axis)
            surfaces += Surface(
                id = anchor.id,
                dock = anchor.dock,
                spot = anchor.spot,
                flow = anchor.dock.defaultFlow(),
                slots = SurfaceLayout.of(region, parseSlots(rest[1], region, anchor.dock.axis)),
            )
        }
        if (surfaces.isEmpty()) return null
        return DockLayout.of(surfaces)
    }

    /**
     * The five-dock format. Its `float` segment becomes the first floating
     * surface, positioned by the caller — `DockStore` still holds the two
     * preference keys that used to carry that position, precisely so this can
     * use them.
     */
    private fun decodeV2(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val surfaces = ArrayList<Surface>()
        for (segment in parts.drop(1)) {
            val fields = segment.split(':')
            if (fields.size != 3) continue
            val dock = Dock.byId(fields[0]) ?: continue
            val slots = fields[1].toIntOrNull() ?: continue
            if (slots < 1 || slots > MAX_SLOTS) continue
            val id = if (dock.isEdge) dock.id else "${DockLayout.FLOAT_PREFIX}1"
            if (surfaces.any { it.id == id }) continue
            val region = CellRegion.strip(slots, dock.axis)
            val layout = SurfaceLayout.of(region, parseSlots(fields[2], region, dock.axis))
            // An empty floating bar in a v2 string is the dock that always
            // existed rather than one somebody made, so it is not carried over.
            if (!dock.isEdge && layout.isEmpty) continue
            surfaces += Surface(id, dock, null, dock.defaultFlow(), layout)
        }
        if (surfaces.isEmpty()) return null
        return DockLayout.of(surfaces)
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
        val region = CellRegion.strip(maxOf(slots, dock.defaultSlots), dock.axis)
        val layout = SurfaceLayout.of(region, parseSlots(parts[2], region, dock.axis))
        return DockLayout.of(
            listOf(Surface(dock.id, dock, null, dock.defaultFlow(), layout))
        )
    }

    /** `0=pen,4=size` — the one-dimensional entry list every old format used. */
    private fun parseSlots(text: String, region: CellRegion, axis: Axis): List<CellPlacement> =
        text.split(',').mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            val eq = entry.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val slot = entry.substring(0, eq).toIntOrNull() ?: return@mapNotNull null
            val body = entry.substring(eq + 1)
            val at = body.indexOf('@')
            val item = ToolItem.byId(if (at < 0) body else body.substring(0, at))
                ?: return@mapNotNull null
            val cell = if (axis == Axis.HORIZONTAL) Cell(slot, 0) else Cell(0, slot)
            // The old size was along-by-across; the new one is width-by-height,
            // so on a vertical bar the two numbers swap. Getting this the wrong
            // way round is the whole of what the migration can break, and
            // `DockCodecTest` says so in as many words.
            val size = if (at < 0) null else sizeOf(body.substring(at + 1))
            val natural = RegionLayout.naturalSize(item, region, cell)
            val w = size?.let { if (axis == Axis.HORIZONTAL) it.first else it.second }
            val h = size?.let { if (axis == Axis.HORIZONTAL) it.second else it.first }
            CellPlacement(item, cell.x, cell.y, w ?: natural.first, h ?: natural.second)
        }

    /** Three places. A surface positioned to a thousandth of a screen is exact enough. */
    private fun fmt(v: Float): String = ((v * 1000f).toInt() / 1000f).toString()

    /**
     * Ceilings on what comes off disk, because these numbers size allocations.
     *
     * Not design limits — a bar this long is already unusable — but the
     * difference between a corrupt preference and an allocation the size of the
     * integer somebody typed.
     */
    private const val MAX_SLOTS = 64
    private const val MAX_RECTS = 32
    private const val MAX_ITEMS = 256
}
