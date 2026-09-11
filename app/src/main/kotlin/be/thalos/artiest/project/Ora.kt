package be.thalos.artiest.project

import be.thalos.artiest.doc.LayerBlend

/**
 * OpenRaster: the layered format Krita, GIMP and MyPaint all open.
 *
 * ## Why this is the export and not the working format
 *
 * It is a zip, and a zip is one file. The app saves *while you draw*, and one
 * file cannot be updated in part — re-encoding eight sheets after every stroke
 * is seconds of work for one stroke's worth of change. `docs/projects-plan.md`
 * has the argument in full.
 *
 * What makes the pairing cheap is that a project directory is already the
 * inside of an `.ora`: `layers/0.png` is `data/layer0.png`, and the manifest is
 * `stack.xml` with different punctuation. Writing one is a few `store`-mode zip
 * entries, an XML file, and the one thing that has to be computed — the merged
 * image the format requires. **No layer is re-encoded.**
 *
 * ## What this file is, and what it is careful about
 *
 * The spec in the two places it bites:
 *
 * - **`stack.xml` lists the topmost layer first.** Ours are bottom-first,
 *   because that is composite order and every other part of this app agrees on
 *   it. The reversal happens here, in one place, in both directions, and
 *   [stackXml] and [readStack] are tested against each other precisely because
 *   getting it wrong produces a file that opens upside down rather than one
 *   that fails.
 * - **A layer may be smaller than the image and sit at an offset.** We never
 *   write one — every sheet is the page — but Krita writes them constantly,
 *   because it trims a layer to what is drawn on it. An importer that ignored
 *   `x` and `y` would put every layer in the corner, which looks like a bug in
 *   the drawing rather than in the reader.
 *
 * ## Reading a file from a stranger
 *
 * The XML is scanned rather than parsed by a library, for `Json`'s reason: a
 * parser that only exists on a device is a parser nobody tests against a
 * hostile file, and `XmlPullParser` is a stub on a plain JVM. The scanner reads
 * attributes off `<image>` and `<layer>` tags and ignores everything else —
 * there are no entities to expand, no external references to resolve and no
 * nesting to recurse into, which is most of what makes XML dangerous.
 *
 * Nested `<stack>` elements — Krita's layer groups — are **flattened**, and the
 * import says so. The alternative is refusing a file we can mostly read.
 */
internal object Ora {

    const val MIMETYPE = "image/openraster"
    const val MIME_BYTES = MIMETYPE
    const val STACK = "stack.xml"
    const val MERGED = "mergedimage.png"
    const val THUMBNAIL = "Thumbnails/thumbnail.png"

    /** The spec's own suggestion, and what every reader expects to find. */
    const val THUMB_MAX = 256

    /** `data/layer<n>.png`, numbered from the bottom as ours are. */
    fun dataFor(index: Int): String = "data/layer$index.png"

    /** One layer as the file describes it. Bottom-first once [readStack] is done. */
    data class Sheet(
        val src: String,
        val name: String,
        val x: Int = 0,
        val y: Int = 0,
        val opacity: Float = 1f,
        val visible: Boolean = true,
        val blend: LayerBlend = LayerBlend.NORMAL,
    )

    data class Stack(val widthPx: Int, val heightPx: Int, val sheets: List<Sheet>, val nested: Boolean)

    // -----------------------------------------------------------------------
    // writing
    // -----------------------------------------------------------------------

    fun stackXml(project: Project): String = buildString {
        append("<?xml version='1.0' encoding='UTF-8'?>\n")
        append("<image version=\"0.0.3\" w=\"").append(project.widthPx)
        append("\" h=\"").append(project.heightPx).append("\">\n")
        append("  <stack>\n")
        // Topmost first. See the file header.
        for (i in project.sheets.indices.reversed()) {
            val sheet = project.sheets[i]
            append("    <layer name=\"").append(escape(sheet.name))
            append("\" src=\"").append(dataFor(i))
            append("\" x=\"0\" y=\"0\"")
            append(" opacity=\"").append(round(sheet.opacity))
            append("\" visibility=\"").append(if (sheet.visible) "visible" else "hidden")
            append("\" composite-op=\"").append(compositeOf(sheet.blend))
            append("\"/>\n")
        }
        append("  </stack>\n")
        append("</image>\n")
    }

    /**
     * Our blend, as the SVG compositing operator the format names.
     *
     * Every one of ours has an exact equivalent, which is not luck: `LayerBlend`
     * was chosen from the modes Skia and SVG agree on, and its KDoc says the
     * `.ora` export is the reason its ids are stable.
     */
    fun compositeOf(blend: LayerBlend): String = when (blend) {
        LayerBlend.NORMAL -> "svg:src-over"
        LayerBlend.MULTIPLY -> "svg:multiply"
        LayerBlend.SCREEN -> "svg:screen"
        LayerBlend.OVERLAY -> "svg:overlay"
        LayerBlend.DARKEN -> "svg:darken"
        LayerBlend.LIGHTEN -> "svg:lighten"
        LayerBlend.DIFFERENCE -> "svg:difference"
    }

    /** Anything this build cannot do is normal, which is the safe way to be wrong. */
    fun blendOf(op: String?): LayerBlend = when (op?.trim()) {
        null, "", "svg:src-over" -> LayerBlend.NORMAL
        "svg:multiply" -> LayerBlend.MULTIPLY
        "svg:screen" -> LayerBlend.SCREEN
        "svg:overlay" -> LayerBlend.OVERLAY
        "svg:darken" -> LayerBlend.DARKEN
        "svg:lighten" -> LayerBlend.LIGHTEN
        "svg:difference" -> LayerBlend.DIFFERENCE
        else -> LayerBlend.NORMAL
    }

    // -----------------------------------------------------------------------
    // reading
    // -----------------------------------------------------------------------

    /**
     * The layers a `stack.xml` describes, bottom-first, or null if it is not
     * one.
     *
     * Bounded everywhere a number sizes something: the text, the tag count and
     * the sheets kept. See the file header for why this is a scanner.
     */
    fun readStack(xml: String): Stack? {
        if (xml.length > MAX_XML) return null
        val image = tagAfter(xml, "<image", 0) ?: return null
        val w = attr(image.text, "w")?.toIntOrNull()?.takeIf { it in 1..MAX_SIDE } ?: return null
        val h = attr(image.text, "h")?.toIntOrNull()?.takeIf { it in 1..MAX_SIDE } ?: return null

        val sheets = ArrayList<Sheet>()
        var at = image.end
        var seen = 0
        // One <stack> is the image's own; a second one is a layer group, and
        // this reader has no such thing. Counted over the whole document rather
        // than as the layers are walked, because a group's opening tag comes
        // before the layers inside it and after the ones above it.
        var stacks = 0
        var scan = xml.indexOf("<stack")
        while (scan >= 0 && stacks < MAX_TAGS) {
            stacks++
            scan = xml.indexOf("<stack", scan + 6)
        }
        while (seen < MAX_TAGS) {
            val layer = tagAfter(xml, "<layer", at) ?: break
            seen++
            at = layer.end
            val src = attr(layer.text, "src") ?: continue
            if (src.length > MAX_PATH || ".." in src || src.startsWith("/")) continue
            if (sheets.size >= Project.MAX_SHEETS) continue
            sheets += Sheet(
                src = src,
                name = attr(layer.text, "name")?.let(::unescape)?.take(Project.MAX_NAME)
                    ?: "Layer ${sheets.size + 1}",
                x = attr(layer.text, "x")?.toIntOrNull()?.coerceIn(-MAX_SIDE, MAX_SIDE) ?: 0,
                y = attr(layer.text, "y")?.toIntOrNull()?.coerceIn(-MAX_SIDE, MAX_SIDE) ?: 0,
                opacity = attr(layer.text, "opacity")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f,
                visible = attr(layer.text, "visibility")?.trim() != "hidden",
                blend = blendOf(attr(layer.text, "composite-op")),
            )
        }

        // Back to bottom-first, which is what everything else in this app means
        // by a list of layers.
        return Stack(w, h, sheets.reversed(), nested = stacks > 1)
    }

    private class Tag(val text: String, val end: Int)

    /** The next `<name ...>` after [from], as its attribute text. */
    private fun tagAfter(xml: String, name: String, from: Int): Tag? {
        val start = xml.indexOf(name, from)
        if (start < 0) return null
        val close = xml.indexOf('>', start)
        if (close < 0) return null
        return Tag(xml.substring(start + name.length, close), close + 1)
    }

    /** `name="value"`, with single quotes accepted because writers differ. */
    private fun attr(text: String, name: String): String? {
        var at = 0
        while (true) {
            val found = text.indexOf(name, at)
            if (found < 0) return null
            at = found + name.length
            // A whole attribute name, not the tail of another: `opacity` must
            // not be found inside `composite-opacity`, and `x` is in almost
            // everything.
            val before = if (found == 0) ' ' else text[found - 1]
            if (!before.isWhitespace()) continue
            var i = at
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '=') continue
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) return null
            val quote = text[i]
            if (quote != '"' && quote != '\'') return null
            val end = text.indexOf(quote, i + 1)
            if (end < 0) return null
            return text.substring(i + 1, end)
        }
    }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c >= ' ') append(c)
            }
        }
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun round(v: Float): String = ((v * 1000f).toInt() / 1000f).toString()

    /** A stack description is a few kilobytes. Anything larger is not one. */
    private const val MAX_XML = 1 shl 20
    private const val MAX_TAGS = 4096
    private const val MAX_PATH = 255
    private const val MAX_SIDE = 20_000
}
