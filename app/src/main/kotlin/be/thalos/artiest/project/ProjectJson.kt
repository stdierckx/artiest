package be.thalos.artiest.project

import be.thalos.artiest.doc.LayerBlend
import be.thalos.artiest.ui.Json
import be.thalos.artiest.ui.JsonValue
import be.thalos.artiest.ui.text

/**
 * `project.json`: what a directory of PNGs means.
 *
 * ## Why a project has a manifest at all
 *
 * Because a stack of PNGs is not a drawing. The order is the compositing order,
 * and a directory listing has no order worth trusting; the opacity, the
 * visibility and the blend of each sheet are not in the pixels; and which sheet
 * the pen was on is not in the pixels either. Every one of those is a thing the
 * user set and would have to set again.
 *
 * ## The rules are `WorkspaceJson`'s rules
 *
 * That decoder was written against files from strangers, and this one will have
 * to be the day an import lands. So, the same three:
 *
 * **A version number first**, and a file claiming a newer one is read anyway
 * with a complaint, because "this file mostly works and here is what is
 * missing" is the true answer and the useful one.
 *
 * **Unknown fields are reported, never fatal.**
 *
 * **Nothing is resolved, only read.** There is exactly one field in this format
 * that becomes a path — [ProjectSheet.file] — and it is checked against a
 * pattern rather than sanitised: `layers/<digits>.png` and nothing else. A
 * sanitiser is a guess about what somebody meant; a pattern is a decision about
 * what is allowed. See [sheetFile].
 *
 * ## What decoding gives back
 *
 * A project **and** a list of what could not be read, in words a person can
 * act on. The gallery shows the list beside the drawing rather than hiding it,
 * for the reason the workspace importer does: a file that half opened and said
 * nothing is the one failure a user cannot work around.
 */
object ProjectJson {

    /** The version of the *format*. A file that claims another is still read. */
    const val FORMAT = 1

    /** What was read, and what could not be. */
    data class Decoded(
        val project: Project?,
        val dropped: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = project != null
    }

    // -----------------------------------------------------------------------
    // writing
    // -----------------------------------------------------------------------

    fun encode(p: Project): String = buildString {
        append("{\n")
        append("  \"artiest_project\": ").append(FORMAT).append(",\n")
        append("  \"id\": ").append(Json.quote(p.id)).append(",\n")
        append("  \"name\": ").append(Json.quote(p.name)).append(",\n")
        append("  \"created\": ").append(p.created).append(",\n")
        append("  \"modified\": ").append(p.modified).append(",\n")
        append("  \"revision\": ").append(p.revision).append(",\n")
        append("  \"width\": ").append(p.widthPx).append(",\n")
        append("  \"height\": ").append(p.heightPx).append(",\n")
        append("  \"paper\": ").append(Json.quote(hex(p.paperColor))).append(",\n")
        append("  \"active\": ").append(p.active).append(",\n")
        append("  \"layers\": [\n")
        for ((i, sheet) in p.sheets.withIndex()) {
            append("    ").append(encodeSheet(sheet))
            append(if (i < p.sheets.lastIndex) ",\n" else "\n")
        }
        append("  ]\n")
        append("}\n")
    }

    private fun encodeSheet(s: ProjectSheet): String = buildString {
        append("{\"file\": ").append(Json.quote(s.file))
        append(", \"name\": ").append(Json.quote(s.name))
        append(", \"opacity\": ").append(round(s.opacity))
        append(", \"visible\": ").append(s.visible)
        append(", \"blend\": ").append(Json.quote(s.blend.id))
        append("}")
    }

    /**
     * `#rrggbb`, or `#aarrggbb` when the paper is not opaque.
     *
     * Written by hand rather than through `Color`, because every line of this
     * file has to run in a plain JVM test — the format is the thing a hostile
     * file is thrown at, and a parser that only exists on a device is a parser
     * nobody tests that way.
     */
    private fun hex(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = "%06x".format(argb and 0xFFFFFF)
        return if (a == 0xFF) "#$rgb" else "#" + "%02x".format(a) + rgb
    }

    /** Three places. A thousandth of an opacity is past what an eye or a byte can hold. */
    private fun round(v: Float): String = ((v * 1000f).toInt() / 1000f).toString()

    // -----------------------------------------------------------------------
    // reading
    // -----------------------------------------------------------------------

    fun decode(text: String): Decoded {
        val dropped = ArrayList<String>()
        val root = Json.parse(text) as? JsonValue.Obj
            ?: return Decoded(null, listOf("this is not a project file"))
        if (root["artiest_project"] == null) {
            return Decoded(null, listOf("this is not a project file: no \"artiest_project\""))
        }
        val format = root.int("artiest_project")
        if (format != null && format > FORMAT) {
            dropped += "written for a newer version of the format ($format) — " +
                "anything this build does not know is left out"
        }
        for (key in root.unknown(ROOT_KEYS)) dropped += "\"$key\" — this build does not use it"

        // A page with no size is not a page. Refused rather than guessed at:
        // every other field has a sensible default and this one does not, and a
        // drawing loaded into a page of the wrong size is a drawing that has
        // quietly lost its edges.
        val width = root.int("width")?.takeIf { it in 1..MAX_SIDE }
        val height = root.int("height")?.takeIf { it in 1..MAX_SIDE }
        if (width == null || height == null) {
            return Decoded(null, dropped + "it does not say how big the page is")
        }

        val name = root.text("name", Project.MAX_NAME) ?: "Drawing"
        val id = root.text("id", MAX_ID)?.let(Project::slug) ?: Project.slug(name)

        val sheets = decodeSheets(root.arr("layers"), dropped)
        val active = (root.int("active") ?: 0).coerceIn(0, maxOf(0, sheets.lastIndex))

        return Decoded(
            Project(
                id = id,
                name = name,
                created = root.long("created") ?: 0L,
                modified = root.long("modified") ?: 0L,
                revision = root.int("revision")?.coerceAtLeast(1) ?: 1,
                widthPx = width,
                heightPx = height,
                paperColor = paperOf(root.str("paper"), dropped),
                active = active,
                sheets = sheets,
            ),
            dropped,
        )
    }

    private fun decodeSheets(items: List<JsonValue>?, dropped: MutableList<String>): List<ProjectSheet> {
        if (items == null) return emptyList()
        val out = ArrayList<ProjectSheet>(items.size)
        for (value in items) {
            if (out.size >= Project.MAX_SHEETS) {
                dropped += "more sheets than this build can hold (${Project.MAX_SHEETS}) — " +
                    "the ones above the top are still in the file"
                break
            }
            val obj = value as? JsonValue.Obj ?: continue
            val file = sheetFile(obj.str("file"))
            if (file == null) {
                dropped += "a sheet names a file this build will not open: ${obj.str("file")}"
                continue
            }
            out += ProjectSheet(
                file = file,
                name = obj.text("name", Project.MAX_NAME) ?: "Layer ${out.size + 1}",
                opacity = obj.float("opacity")?.coerceIn(0f, 1f) ?: 1f,
                visible = (obj["visible"] as? JsonValue.Bool)?.value ?: true,
                blend = LayerBlend.byId(obj.str("blend")),
            )
        }
        return out
    }

    /**
     * The one field in this format that becomes a path.
     *
     * A pattern and not a sanitiser. `../../../shared_prefs/chrome.xml` with
     * the dots taken out is still a decision about what somebody meant; this is
     * a decision about what is allowed, and everything else is refused and
     * reported. The set of names this app ever writes is `layers/0.png`
     * upwards — see [Project.fileFor] — so the pattern is the whole truth
     * rather than a conservative subset of it.
     */
    private fun sheetFile(raw: String?): String? {
        val name = raw ?: return null
        return if (FILE.matches(name)) name else null
    }

    /** `#rgb`, `#rrggbb` or `#aarrggbb`. Anything else is white, and says so. */
    private fun paperOf(raw: String?, dropped: MutableList<String>): Int {
        val text = raw?.trim()?.removePrefix("#") ?: return WHITE
        val digits = when (text.length) {
            3 -> text.map { "$it$it" }.joinToString("")
            6 -> text
            8 -> text
            else -> null
        }
        val value = digits?.toLongOrNull(16)
        if (value == null) {
            dropped += "\"$raw\" is not a colour — the paper is white"
            return WHITE
        }
        return if (digits.length == 8) value.toInt() else (0xFF000000L or value).toInt()
    }

    private fun JsonValue.Obj.long(name: String): Long? =
        (this[name] as? JsonValue.Num)?.value?.takeIf { it.isFinite() && it >= 0 }?.toLong()

    /** Bigger than any page this app allocates, by a wide margin. */
    private const val MAX_SIDE = 20_000

    private const val MAX_ID = 64

    private const val WHITE = 0xFFFFFFFF.toInt()

    private val FILE = Regex("^${Project.LAYERS}/[0-9]{1,4}\\.png$")

    private val ROOT_KEYS = setOf(
        "artiest_project", "id", "name", "created", "modified", "revision",
        "width", "height", "paper", "active", "layers",
    )
}
