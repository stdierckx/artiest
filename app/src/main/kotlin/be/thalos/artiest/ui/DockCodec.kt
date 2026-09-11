package be.thalos.artiest.ui

/**
 * Every surface as one line of text, for `SharedPreferences`.
 *
 * Format: `v5|<surface>|<surface>|…`, where a surface is
 *
 * ```
 * s1:R0,4,1,7:down_right:0,4=pen,0,5=pencil,0,8=colour
 * ```
 *
 * Four fields: the id, the **shape** as an `R` and a list of `x,y,w,h`
 * rectangles joined by `+`, the fill order, and what is on it as `x,y=id`.
 *
 * **Every number is a screen cell.** That is the whole of what changed in `v5`
 * and it is why the version moved: in `v4` a region started at `0,0` and a dock
 * decided where that corner went, so the same four numbers meant different
 * places on different edges. Now `R0,4,1,7` is column nought, rows four to ten,
 * and nothing else has an opinion. See `docs/ui-grid-plan.md`.
 *
 * A surface that has never been on a screen carries an anchor after its id —
 * `s1^left`, or `s1^@0.32,0.45` — and loses it the moment it has been on one.
 * See [Anchor].
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
 * keeps the rest. Unknown item ids, unreadable positions and rectangles off the
 * grid are all the same kind of event and all drop one thing rather than the
 * line.
 *
 * ## The four older formats
 *
 * All of them had docks, and all of them decode to an **anchored** surface: the
 * edge is remembered, resolved to real cells the first time the layout meets a
 * screen, and then forgotten. That is the only thing in the app that knows a
 * dock ever existed.
 *
 * `v1` was one bar, before there were docks, and it sat across the top. `v2`
 * had five fixed docks, one of them a single floating bar. `v3` was the same
 * five-plus-N model with a slot count instead of a shape. `v4` had shapes, and
 * an edge or a fraction to hang each one on.
 *
 * Reading any of them as a failure would have been the easy thing and would
 * have silently emptied the toolbar of everyone who had already arranged one.
 *
 * ## Which way this format travels
 *
 * Forwards only. A `v5` string handed to a build that only knows `v4` is
 * unreadable and falls back to the default, which is why this is a
 * *preference* and never a sharing format. Sharing a workspace is a JSON file
 * that names its own version and drops what it cannot do — see
 * `docs/workspace-format.md`.
 */
object DockCodec {

    private const val VERSION = "v5"

    /** Where a pre-docking bar lands. See the class KDoc. */
    internal val V1_SIDE = Side.TOP

    fun encode(layout: DockLayout): String = buildString {
        append(VERSION)
        for (surface in layout.surfaces) {
            append('|').append(surface.id)
            when (val a = surface.anchor) {
                is Anchor.Edge -> append('^').append(a.side.id)
                is Anchor.Spot -> append("^@").append(fmt(a.x)).append(',').append(fmt(a.y))
                null -> Unit
            }
            append(':').append(encodeRegion(surface.region))
            append(':').append(surface.flow.id)
            append(':').append(
                surface.slots.placements.joinToString(",") { p ->
                    // A panel carries the size the user gave it; nothing else
                    // does, because nothing else can be resized and a number
                    // that is always derivable is a number that can go stale.
                    if (p.hangs) {
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
        if (text.startsWith("v4|")) return decodeV4(text)

        val parts = text.split('|')
        if (parts[0] != VERSION) return null
        // A layout with no surfaces is a choice somebody made — every
        // toolbar rubbed out — and not a corrupt string. Reading it as a
        // failure would hand the starter layout back to the one user who
        // had most deliberately got rid of it.
        if (parts.size == 1) return DockLayout.EMPTY

        val surfaces = ArrayList<Surface>()
        for (segment in parts.drop(1)) {
            surfaces += parseSurface(segment) ?: continue
        }
        // Not one readable surface in a string that claimed to be a layout:
        // that is a corrupt preference and not an empty toolbar, and the
        // difference matters because the caller honours one and replaces the
        // other.
        if (surfaces.isEmpty()) return null
        return DockLayout.of(surfaces)
    }

    // -----------------------------------------------------------------------
    // v5
    // -----------------------------------------------------------------------

    /** `s1:R0,4,1,7:down_right:…`, or null when it is not a surface. */
    private fun parseSurface(segment: String): Surface? {
        val head = segment.substringBefore(':', missingDelimiterValue = "")
        if (head.isEmpty()) return null
        val rest = segment.substring(head.length + 1).split(':')
        if (rest.size != 3) return null

        val caret = head.indexOf('^')
        val id = (if (caret < 0) head else head.substring(0, caret)).trim()
        if (id.isEmpty()) return null
        // An anchor that will not parse drops the anchor, not the surface: the
        // surface is what somebody built, and losing a hint is cheaper than
        // losing the contents.
        val anchor = if (caret < 0) null else parseAnchor(head.substring(caret + 1))

        val region = parseRegion(rest[0]) ?: return null
        if (region.isEmpty) return null
        val flow = FlowOrder.byId(rest[1]) ?: FlowOrder.along(region.stripAxis ?: Axis.HORIZONTAL)
        return Surface(
            id = id,
            flow = flow,
            slots = SurfaceLayout.of(region, parseCells(rest[2], region)),
            anchor = anchor,
        )
    }

    /** `left`, or `@0.32,0.45`. Null when it is neither. */
    private fun parseAnchor(text: String): Anchor? {
        if (!text.startsWith('@')) return Anchor.edge(text)
        val comma = text.indexOf(',')
        if (comma <= 1) return null
        val x = text.substring(1, comma).toFloatOrNull() ?: return null
        val y = text.substring(comma + 1).toFloatOrNull() ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        return Anchor.Spot(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
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
    // the older formats: every one of them had docks, and every one of them
    // comes back anchored
    // -----------------------------------------------------------------------

    /** A dock id, as the four older formats wrote it. */
    private class Dock(val id: String, val side: Side?, val axis: Axis, val slots: Int)

    private val DOCKS = listOf(
        Dock("left", Side.LEFT, Axis.VERTICAL, 12),
        Dock("top", Side.TOP, Axis.HORIZONTAL, 24),
        Dock("right", Side.RIGHT, Axis.VERTICAL, 12),
        Dock("bottom", Side.BOTTOM, Axis.HORIZONTAL, 24),
        Dock(LEGACY_FLOAT, null, Axis.HORIZONTAL, 8),
    )

    private fun dockById(id: String): Dock? = DOCKS.firstOrNull { it.id == id }

    /** `left`, or `f1@0.3,0.4`. Null when it names no dock. */
    private class Legacy(val id: String, val dock: Dock, val anchor: Anchor?)

    private fun parseLegacyHead(head: String): Legacy? {
        val at = head.indexOf('@')
        val id = if (at < 0) head else head.substring(0, at)
        val named = dockById(id)
        val dock = named
            ?: (if (id.startsWith(FLOAT_PREFIX)) dockById(LEGACY_FLOAT) else null)
            ?: return null
        if (dock.side != null && id != dock.id) return null
        // A floating surface's fraction becomes an Anchor.Spot; an edge becomes
        // an Anchor.Edge, and a position written on one is ignored rather than
        // refused.
        val side = dock.side
        if (side != null) return Legacy(id, dock, Anchor.Edge(side))
        val spot = if (at < 0) null else {
            val comma = head.indexOf(',', at)
            if (comma <= at) null else {
                val x = head.substring(at + 1, comma).toFloatOrNull()
                val y = head.substring(comma + 1).toFloatOrNull()
                if (x == null || y == null || !x.isFinite() || !y.isFinite()) null
                else Anchor.Spot(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            }
        }
        return Legacy(id, dock, spot ?: Anchor.Spot(DEFAULT_X, DEFAULT_Y))
    }

    /** The shaped format. Regions were relative to the surface's own corner. */
    private fun decodeV4(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val surfaces = ArrayList<Surface>()
        for (segment in parts.drop(1)) {
            val head = segment.substringBefore(':', missingDelimiterValue = "")
            if (head.isEmpty()) continue
            val rest = segment.substring(head.length + 1).split(':')
            if (rest.size != 3) continue
            val legacy = parseLegacyHead(head) ?: continue
            val region = parseRegion(rest[0]) ?: continue
            if (region.isEmpty) continue
            val flow = FlowOrder.byId(rest[1]) ?: FlowOrder.along(legacy.dock.axis)
            surfaces += Surface(
                id = legacy.id,
                flow = flow,
                slots = SurfaceLayout.of(region, parseCells(rest[2], region)),
                anchor = legacy.anchor,
            )
        }
        if (surfaces.isEmpty()) return null
        return DockLayout.of(surfaces)
    }

    /**
     * The slot-count format. A bar of `n` slots is a strip `n` cells long along
     * its dock's own axis, which is the shape it always drew.
     */
    private fun decodeV3(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val surfaces = ArrayList<Surface>()
        for (segment in parts.drop(1)) {
            val head = segment.substringBefore(':', missingDelimiterValue = "")
            if (head.isEmpty()) continue
            val rest = segment.substring(head.length + 1).split(':')
            if (rest.size != 2) continue
            val slots = rest[0].toIntOrNull() ?: continue
            if (slots < 1 || slots > MAX_SLOTS) continue
            val legacy = parseLegacyHead(head) ?: continue
            val region = CellRegion.strip(slots, legacy.dock.axis)
            surfaces += Surface(
                id = legacy.id,
                flow = FlowOrder.along(legacy.dock.axis),
                slots = SurfaceLayout.of(region, parseSlots(rest[1], region, legacy.dock.axis)),
                anchor = legacy.anchor,
            )
        }
        if (surfaces.isEmpty()) return null
        return DockLayout.of(surfaces)
    }

    /** The five-dock format. Its `float` segment becomes the first drawn surface. */
    private fun decodeV2(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size < 2) return null
        val surfaces = ArrayList<Surface>()
        for (segment in parts.drop(1)) {
            val fields = segment.split(':')
            if (fields.size != 3) continue
            val dock = dockById(fields[0]) ?: continue
            val slots = fields[1].toIntOrNull() ?: continue
            if (slots < 1 || slots > MAX_SLOTS) continue
            val id = if (dock.side != null) dock.id else "${FLOAT_PREFIX}1"
            if (surfaces.any { it.id == id }) continue
            val region = CellRegion.strip(slots, dock.axis)
            val layout = SurfaceLayout.of(region, parseSlots(fields[2], region, dock.axis))
            // An empty floating bar in a v2 string is the dock that always
            // existed rather than one somebody made, so it is not carried over.
            if (dock.side == null && layout.isEmpty) continue
            surfaces += Surface(
                id = id,
                flow = FlowOrder.along(dock.axis),
                slots = layout,
                anchor = dock.side?.let { Anchor.Edge(it) } ?: Anchor.Spot(DEFAULT_X, DEFAULT_Y),
            )
        }
        if (surfaces.isEmpty()) return null
        return DockLayout.of(surfaces)
    }

    /** A single pre-docking bar, put where that bar used to be. */
    private fun decodeV1(text: String): DockLayout? {
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != "v1") return null
        val slots = parts[1].toIntOrNull() ?: return null
        if (slots < 1 || slots > MAX_SLOTS) return null
        val dock = dockById(V1_SIDE.id) ?: return null
        val region = CellRegion.strip(slots, dock.axis)
        val layout = SurfaceLayout.of(region, parseSlots(parts[2], region, dock.axis))
        return DockLayout.of(
            listOf(
                Surface(dock.id, FlowOrder.along(dock.axis), layout, Anchor.Edge(V1_SIDE))
            )
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

    /** What a floating surface was called in every format that had one. */
    private const val FLOAT_PREFIX = "f"

    /** What every older format called the dock that was not on an edge. */
    private const val LEGACY_FLOAT = "float"

    /** Clear of the left tools and above the bottom sliders. */
    private const val DEFAULT_X = 0.32f
    private const val DEFAULT_Y = 0.42f

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
