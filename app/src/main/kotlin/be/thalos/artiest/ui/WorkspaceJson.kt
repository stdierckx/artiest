package be.thalos.artiest.ui

/**
 * A workspace as a file somebody can send you.
 *
 * The reference, with a worked example and a field table, is
 * `docs/workspace-format.md`. This is the implementation of it, and the two are
 * meant to be handed to a model together with `docs/catalogue.json`.
 *
 * ## Three properties, and everything else follows from them
 *
 * **`at` is optional, everywhere.** A tool may say which cell it is in, or say
 * nothing and be packed in flow order; a surface may say where its corner is,
 * or name a side and be put there once. That is what makes a workspace something a person
 * can write by hand — *"pen, pencil, eraser, colour, down the left edge"* is a
 * list of four names — while still letting one that was arranged by hand keep
 * every position exactly.
 *
 * **Almost no sizes.** How many cells a slider takes follows from the catalogue
 * and from the shape it lands in, both known at decode time, so writing it down
 * would mean a release that resizes a control leaves every shared workspace laid
 * out to a number no code believes any more. The one exception is a **panel**,
 * which the user can resize, and a size somebody chose is theirs — the same
 * exception, for the same reason, that `DockCodec` has always made.
 *
 * **Unknown things drop.** A field this build does not know, a tool it has
 * never heard of, a rectangle off the screen: each one is dropped, each one is
 * *reported*, and none of them is fatal. That is what makes the format work in
 * both directions in time — a file from a newer build opens in an older one
 * with the parts it cannot do left out, which is the behaviour that lets people
 * share files at all.
 *
 * ## Decoding returns a report, not a workspace
 *
 * [decode] hands back what it could read **and a list of what it could not**,
 * in words a person can act on: *"perspective_ruler — this build has no such
 * tool"*. The importer shows that list. It does not hide it and it does not
 * fail on it, because "this file mostly works and here is what is missing" is
 * the true answer and the useful one.
 *
 * ## A file from a stranger
 *
 * Every ceiling below is an allocation sized by a number somebody typed. They
 * are enforced here and in [Json], which is the only place they *can* be
 * enforced — see that file for why the parser is written out rather than taken
 * off the shelf.
 *
 * The format has no field that can carry a URL, a path, a colour named after a
 * resource, or anything else that would be resolved rather than read. That is a
 * property of the schema and not of the decoder, and it is the reason this can
 * be a file people email each other.
 */
object WorkspaceJson {

    /** The version of the *format*. A file that claims another is still read. */
    const val FORMAT = 2

    /** More surfaces than a screen can hold. */
    const val MAX_SURFACES = 16

    /** More controls than the catalogue has, several times over. */
    const val MAX_ITEMS = 256

    /** A shape somebody drew, generously. */
    const val MAX_RECTS = 32

    /** A name, an author, a description. */
    const val MAX_NAME = 64
    const val MAX_TEXT = 512

    /**
     * What was read, and what could not be.
     *
     * [workspace] is null only when the text is not a workspace file at all —
     * not JSON, or JSON without the marker. Anything else yields a workspace
     * and a list of complaints.
     */
    data class Decoded(
        val workspace: Workspace?,
        val dropped: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = workspace != null
    }

    // -----------------------------------------------------------------------
    // writing
    // -----------------------------------------------------------------------

    fun encode(ws: Workspace): String = buildString {
        append("{\n")
        append("  \"artiest_workspace\": ").append(FORMAT).append(",\n")
        append("  \"catalogue\": ").append(ToolCatalogue.VERSION).append(",\n")
        append("  \"id\": ").append(quote(ws.id)).append(",\n")
        append("  \"name\": ").append(quote(ws.name)).append(",\n")
        append("  \"description\": ").append(quote(ws.description)).append(",\n")
        append("  \"author\": ").append(quote(ws.author)).append(",\n")
        append("  \"revision\": ").append(ws.revision).append(",\n")
        append("  \"filter\": ").append(encodeFilter(ws.filter)).append(",\n")
        append("  \"defaults\": ").append(encodeDefaults(ws.defaults)).append(",\n")
        append("  \"surfaces\": [\n")
        val surfaces = ws.layout.surfaces
        for ((i, s) in surfaces.withIndex()) {
            append(encodeSurface(s))
            append(if (i < surfaces.lastIndex) ",\n" else "\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private fun encodeFilter(f: CatalogueFilter): String = buildString {
        append("{")
        append("\"groups\": ")
        if (f.groups == null) {
            append("null")
        } else {
            append(ToolGroup.entries.filter { it in f.groups }.joinToString(", ", "[", "]") {
                quote(it.name.lowercase())
            })
        }
        append(", \"hide\": ").append(strings(f.hide.sorted()))
        append(", \"show\": ").append(strings(f.show.sorted()))
        append("}")
    }

    private fun encodeDefaults(d: WorkspaceDefaults): String = buildString {
        append("{")
        append("\"brush\": ").append(d.brush?.let { quote(it) } ?: "null")
        append(", \"shelf\": ").append(strings(d.shelf))
        append(", \"stabilisation\": ").append(d.stabilisation?.toString() ?: "null")
        append("}")
    }

    /**
     * One surface: where its corner is, the shape from that corner, and what is
     * on it.
     *
     * **Everything inside a surface is written relative to its own corner**, and
     * the corner is the one absolute thing. That is what makes a shape
     * readable — `[[0, 0, 1, 8], [0, 7, 4, 1]]` is an L wherever it is put, and
     * moving the toolbar changes two numbers instead of all of them.
     */
    private fun encodeSurface(s: Surface): String = buildString {
        val b = s.region.bounds
        append("    {\n")
        append("      \"id\": ").append(quote(s.id)).append(",\n")
        when (val a = s.anchor) {
            // A side by name, a corner as two fractions, or — for a surface
            // that has already been on a screen — the cells it is actually at.
            is Anchor.Edge -> append("      \"anchor\": ").append(quote(a.side.id)).append(",\n")
            is Anchor.Spot ->
                append("      \"anchor\": [").append(round(a.x)).append(", ")
                    .append(round(a.y)).append("],\n")
            null -> append("      \"at\": [").append(b.x).append(", ").append(b.y).append("],\n")
        }
        append("      \"rects\": [")
        append(s.region.rects.joinToString(", ") {
            "[${it.x - b.x}, ${it.y - b.y}, ${it.w}, ${it.h}]"
        })
        append("],\n")
        append("      \"flow\": ").append(quote(s.flow.id)).append(",\n")
        append("      \"tools\": [")
        if (s.slots.placements.isEmpty()) {
            append("]\n")
        } else {
            append("\n")
            for ((i, p) in s.slots.placements.withIndex()) {
                append("        {\"id\": ").append(quote(p.item.id))
                append(", \"at\": [").append(p.x - b.x).append(", ").append(p.y - b.y).append("]")
                if (p.hangs) {
                    append(", \"size\": [").append(p.w).append(", ").append(p.h).append("]")
                }
                append("}")
                append(if (i < s.slots.placements.lastIndex) ",\n" else "\n")
            }
            append("      ]\n")
        }
        append("    }")
    }

    // -----------------------------------------------------------------------
    // reading
    // -----------------------------------------------------------------------

    fun decode(text: String): Decoded {
        val dropped = ArrayList<String>()
        val root = Json.parse(text) as? JsonValue.Obj
            ?: return Decoded(null, listOf("this is not a workspace file"))
        if (root["artiest_workspace"] == null) {
            return Decoded(null, listOf("this is not a workspace file: no \"artiest_workspace\""))
        }
        val format = root.int("artiest_workspace")
        if (format != null && format > FORMAT) {
            // Read it anyway. A newer file whose extra fields we cannot use is
            // still mostly this file, and refusing it outright is how a format
            // stops being shareable.
            dropped += "written for a newer version of the format ($format) — " +
                "anything this build does not know is left out"
        }

        for (key in root.unknown(ROOT_KEYS)) dropped += "\"$key\" — this build does not use it"

        val name = root.text("name", MAX_NAME) ?: "Workspace"
        val id = root.text("id", Workspace.MAX_SLUG)?.let { Workspace.slug(it) }
            ?: Workspace.slug(name)

        val filter = decodeFilter(root.obj("filter"), dropped)
        val defaults = decodeDefaults(root.obj("defaults"), dropped)
        val layout = decodeSurfaces(root.arr("surfaces"), format ?: 1, dropped)

        return Decoded(
            Workspace(
                id = id,
                name = name,
                description = root.text("description", MAX_TEXT) ?: "",
                author = root.text("author", MAX_NAME) ?: "",
                revision = root.int("revision")?.coerceIn(1, Int.MAX_VALUE) ?: 1,
                layout = layout,
                filter = filter,
                defaults = defaults,
            ),
            dropped,
        )
    }

    private fun decodeFilter(obj: JsonValue.Obj?, dropped: MutableList<String>): CatalogueFilter {
        if (obj == null) return CatalogueFilter.EVERYTHING
        for (key in obj.unknown(FILTER_KEYS)) dropped += "filter: \"$key\" is not a field"

        val groups = obj.arr("groups")?.let { items ->
            val out = LinkedHashSet<ToolGroup>()
            for (v in items) {
                val text = (v as? JsonValue.Str)?.value
                val group = ToolGroup.entries.firstOrNull { it.name.equals(text, true) }
                if (group == null) dropped += "filter: there is no group \"$text\"" else out += group
            }
            out
        }
        return CatalogueFilter(
            groups = groups,
            hide = ids(obj.arr("hide"), "filter.hide", dropped),
            show = ids(obj.arr("show"), "filter.show", dropped),
        )
    }

    /**
     * Tool ids from an array, keeping only ones this build has.
     *
     * An unknown id in a *filter* is reported but is not otherwise interesting:
     * hiding a tool that does not exist hides nothing. It is reported anyway,
     * because it is usually the first sign that the file was written for a
     * newer build and the user is about to find something else missing too.
     */
    private fun ids(
        items: List<JsonValue>?,
        where: String,
        dropped: MutableList<String>,
    ): Set<String> {
        if (items == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (v in items.take(MAX_ITEMS)) {
            val text = (v as? JsonValue.Str)?.value ?: continue
            if (ToolItem.byId(text) == null) {
                dropped += "$where: \"$text\" — this build has no such tool"
                continue
            }
            out += text
        }
        return out
    }

    private fun decodeDefaults(
        obj: JsonValue.Obj?,
        dropped: MutableList<String>,
    ): WorkspaceDefaults {
        if (obj == null) return WorkspaceDefaults()
        for (key in obj.unknown(DEFAULTS_KEYS)) dropped += "defaults: \"$key\" is not a field"
        return WorkspaceDefaults(
            brush = obj.text("brush", MAX_NAME),
            shelf = obj.arr("shelf")
                ?.mapNotNull { (it as? JsonValue.Str)?.value?.let(::clean)?.take(MAX_NAME) }
                ?.take(MAX_ITEMS)
                ?: emptyList(),
            stabilisation = obj.float("stabilisation")?.coerceIn(0f, 1f),
        )
    }

    private fun decodeSurfaces(
        items: List<JsonValue>?,
        format: Int,
        dropped: MutableList<String>,
    ): DockLayout {
        if (items == null) return DockLayout.EMPTY
        if (items.size > MAX_SURFACES) {
            dropped += "${items.size} toolbars is more than a screen can hold — " +
                "only the first $MAX_SURFACES were read"
        }
        val out = ArrayList<Surface>()
        val seen = HashSet<String>()
        val placed = HashSet<ToolItem>()
        var made = 0
        for (value in items.take(MAX_SURFACES)) {
            val obj = value as? JsonValue.Obj ?: continue
            for (key in obj.unknown(SURFACE_KEYS)) dropped += "toolbar: \"$key\" is not a field"

            // A format 1 file named its surfaces after the dock they hung on
            // and usually carried no "id" at all, so the dock is the fallback
            // before a made-up name is: an id is what a later edit keys on, and
            // one that changes every time the file is read is not an id.
            val id = obj.str("id")?.let(::clean)?.take(Workspace.MAX_SLUG)?.takeIf { it.isNotEmpty() }
                ?: obj.str("dock")?.takeIf { Side.byId(it) != null }
                ?: "${DockLayout.ID_PREFIX}${++made}"
            if (!seen.add(id)) {
                dropped += "toolbar \"$id\" appears twice — the first one was kept"
                continue
            }

            val tools = obj.arr("tools")
            val anchor = decodeAnchor(obj, id, dropped)
            val region = decodeRegion(obj.arr("rects"), tools, anchor, id, dropped)
            val corner = decodeCorner(obj, format, anchor)
            val flow = obj.str("flow")?.let { FlowOrder.byId(it) }
                ?: FlowOrder.along(region.stripAxis ?: Axis.HORIZONTAL)

            val slots = decodeTools(tools, region, flow, id, placed, dropped)
            val surface = Surface(id, flow, slots, anchor).movedTo(corner)
            // A format 1 file's shapes were as long as the dock they hung on,
            // and the renderer of the day drew only as far as the last control.
            // Nothing hides a tail now, so they are cut back to what is on them
            // — see Surface.trimmedToContents.
            out += if (format >= 2) surface else surface.trimmedToContents() ?: continue
        }
        return DockLayout.of(out)
    }

    /**
     * `"anchor": "left"`, or nothing at all.
     *
     * An anchor is a starting position for a surface that has never been on a
     * screen — see [Anchor]. A file that says where its corner is in cells needs
     * none, and most files written by the app say exactly that.
     *
     * Format 1 called it `"dock"` and meant something stronger by it, and a
     * `"dock": "float"` carried its position as a **fraction** in `"at"`. Both
     * are read, and both come back as an anchor that is resolved once.
     */
    private fun decodeAnchor(
        obj: JsonValue.Obj,
        id: String,
        dropped: MutableList<String>,
    ): Anchor? {
        // Two fractions: a corner, which a side cannot name. Clean's L wants
        // the bottom-left and "left" would give it the middle of the edge.
        obj.arr("anchor")?.let { return spotOf(it) }

        val named = obj.str("anchor") ?: obj.str("dock") ?: return null
        Anchor.edge(named)?.let { return it }
        // Format 1 called a surface that was on no edge a float, and carried its
        // position as a fraction in "at".
        if (named == LEGACY_FLOAT) return obj.arr("at")?.let { spotOf(it) }
        dropped += "toolbar \"$id\": there is no side called \"$named\""
        return null
    }

    /** Two numbers between nought and one, or null. */
    private fun spotOf(at: List<JsonValue>): Anchor.Spot? {
        val x = (at.getOrNull(0) as? JsonValue.Num)?.value?.toFloat() ?: return null
        val y = (at.getOrNull(1) as? JsonValue.Num)?.value?.toFloat() ?: return null
        if (!x.isFinite() || !y.isFinite()) return null
        return Anchor.Spot(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    /**
     * Where the surface's corner goes, in cells.
     *
     * `"at": [4, 0]` and nothing else. An anchored surface is put at the origin
     * and moved by [DockLayout.settled] the first time it is on a screen, so the
     * two are never both honoured.
     *
     * A format 1 file's `"at"` was a fraction of the screen and belonged to a
     * float; that is read as an anchor instead, above, and never as cells.
     */
    private fun decodeCorner(obj: JsonValue.Obj, format: Int, anchor: Anchor?): Cell {
        if (anchor != null || format < 2) return Cell(0, 0)
        val at = obj.arr("at") ?: return Cell(0, 0)
        val x = (at.getOrNull(0) as? JsonValue.Num)?.value?.toInt() ?: 0
        val y = (at.getOrNull(1) as? JsonValue.Num)?.value?.toInt() ?: 0
        return Cell(x.coerceIn(0, CellRegion.MAX_COORD), y.coerceIn(0, CellRegion.MAX_COORD))
    }

    /**
     * The shape, from the surface's own corner.
     *
     * With no `"rects"` at all it is a plain bar long enough for what is on it,
     * running the way the anchor suggests — which is what makes
     * `{"anchor": "left", "tools": ["pen", "pencil", "eraser"]}` a whole
     * toolbar. That is the same promise `at` being optional makes, one level up.
     */
    private fun decodeRegion(
        items: List<JsonValue>?,
        tools: List<JsonValue>?,
        anchor: Anchor?,
        id: String,
        dropped: MutableList<String>,
    ): CellRegion {
        if (items == null) return defaultStrip(tools, anchor)
        if (items.size > MAX_RECTS) {
            dropped += "toolbar \"$id\": only the first $MAX_RECTS rectangles were read"
        }
        val rects = ArrayList<CellRect>()
        for (value in items.take(MAX_RECTS)) {
            val n = (value as? JsonValue.Arr)?.items
            val four = n?.mapNotNull { (it as? JsonValue.Num)?.value?.toInt() }
            if (four == null || four.size != 4) {
                dropped += "toolbar \"$id\": a rectangle is not four numbers"
                continue
            }
            val rect = CellRect.of(four[0], four[1], four[2], four[3])
            if (rect == null) {
                dropped += "toolbar \"$id\": the rectangle ${four.joinToString(",")} is off the screen"
                continue
            }
            rects += rect
        }
        if (rects.isEmpty()) {
            dropped += "toolbar \"$id\" has no shape — it was given a plain bar"
            return defaultStrip(tools, anchor)
        }
        return CellRegion.of(rects)
    }

    /** A bar long enough for [tools], upright against a side or flat otherwise. */
    private fun defaultStrip(tools: List<JsonValue>?, anchor: Anchor?): CellRegion {
        var length = 0
        for (value in tools.orEmpty().take(MAX_ITEMS)) {
            val name = (value as? JsonValue.Str)?.value
                ?: ((value as? JsonValue.Obj)?.str("id"))
                ?: continue
            val item = ToolItem.byId(name) ?: continue
            length += if (item.hangs) 1 else item.cellsWide
        }
        val side = (anchor as? Anchor.Edge)?.side
        val axis = if (side == Side.LEFT || side == Side.RIGHT) Axis.VERTICAL else Axis.HORIZONTAL
        return CellRegion.strip(length.coerceAtLeast(1), axis)
    }

    /**
     * The tools on one surface: the fixed ones exactly, the rest by flow.
     *
     * An item that is already on an earlier surface is dropped here rather than
     * by `DockLayout.of`, so that the user is *told*. One control in two places
     * is a file that was edited by hand, and silently keeping the first is the
     * right behaviour and the wrong silence.
     */
    private fun decodeTools(
        items: List<JsonValue>?,
        region: CellRegion,
        flow: FlowOrder,
        id: String,
        placed: MutableSet<ToolItem>,
        dropped: MutableList<String>,
    ): SurfaceLayout {
        if (items == null) return SurfaceLayout.empty(region)
        if (items.size > MAX_ITEMS) {
            dropped += "toolbar \"$id\": only the first $MAX_ITEMS controls were read"
        }
        val fixed = ArrayList<CellPlacement>()
        val flowing = ArrayList<RegionLayout.Footprint>()

        for (value in items.take(MAX_ITEMS)) {
            val obj = value as? JsonValue.Obj
                ?: (value as? JsonValue.Str)?.let { JsonValue.Obj(mapOf("id" to it)) }
                ?: continue
            for (key in obj.unknown(TOOL_KEYS)) dropped += "$id: \"$key\" is not a field"

            val itemId = obj.str("id")
            val item = ToolItem.byId(itemId ?: "")
            if (item == null) {
                dropped += "\"$itemId\" — this build has no such tool"
                continue
            }
            if (!placed.add(item)) {
                dropped += "${item.id} appears on more than one toolbar — the first one was kept"
                continue
            }

            val at = obj.arr("at")
            val x = (at?.getOrNull(0) as? JsonValue.Num)?.value?.toInt()
            val y = (at?.getOrNull(1) as? JsonValue.Num)?.value?.toInt()
            val natural = if (x != null && y != null) {
                RegionLayout.naturalSize(item, region, Cell(x, y))
            } else {
                null
            }
            // Only a panel may carry a size, and only because the user can
            // resize one. See the file KDoc.
            val size = obj.arr("size")
                ?.takeIf { item.hangs }
                ?.mapNotNull { (it as? JsonValue.Num)?.value?.toInt() }
                ?.takeIf { it.size == 2 && it.all { n -> n in 1..CellRegion.MAX_SPAN } }

            if (x == null || y == null) {
                flowing += RegionLayout.Footprint.of(item)
            } else {
                fixed += CellPlacement(
                    item = item,
                    x = x,
                    y = y,
                    w = size?.get(0) ?: natural!!.first,
                    h = size?.get(1) ?: natural!!.second,
                )
            }
        }

        val fill = RegionLayout.pack(region, flow, fixed, flowing)
        for (item in fill.overflow) {
            dropped += "${item.id} — there was no room for it on \"$id\""
            placed -= item
        }
        return SurfaceLayout.of(region, fill.placements)
    }

    // -----------------------------------------------------------------------
    // the small print
    // -----------------------------------------------------------------------

    private fun JsonValue.Obj.text(name: String, max: Int): String? =
        str(name)?.let(::clean)?.take(max)?.takeIf { it.isNotEmpty() }

    /**
     * A string on its way to a `Text`, with the characters that do not belong
     * on a label taken out.
     *
     * Control characters, and the bidirectional overrides — those are how a
     * name that reads *"Sketcher"* on screen is something else in the file, and
     * a workspace list is exactly the sort of place that trick is aimed at.
     */
    private fun clean(text: String): String = buildString {
        for (c in text) {
            if (c < ' ' || c == '\u007F') continue
            // The bidirectional overrides and isolates: how a name that
            // reads "Sketcher" on screen is something else in the file.
            if (c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069') continue
            append(c)
        }
    }.trim()

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun strings(values: Collection<String>): String =
        values.joinToString(", ", "[", "]") { quote(it) }

    /** Three places, the same as `DockCodec`. A thousandth of a screen is exact enough. */
    private fun round(v: Float): String = ((v * 1000f).toInt() / 1000f).toString()

    /** What format 1 called a surface that was not on an edge. */
    private const val LEGACY_FLOAT = "float"

    private val ROOT_KEYS = setOf(
        "artiest_workspace", "catalogue", "id", "name", "description", "author",
        "revision", "filter", "defaults", "surfaces",
    )
    private val FILTER_KEYS = setOf("groups", "hide", "show")
    private val DEFAULTS_KEYS = setOf("brush", "shelf", "stabilisation")
    private val SURFACE_KEYS = setOf("id", "anchor", "dock", "at", "rects", "flow", "tools")
    private val TOOL_KEYS = setOf("id", "at", "size")
}
