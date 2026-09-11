package be.thalos.artiest.ui

/**
 * The catalogue, as a file somebody else can read.
 *
 * ## Why this exists at all
 *
 * A workspace file names tools by id and shapes by rectangles, and neither of
 * those means anything without the vocabulary behind them. This is that
 * vocabulary: every tool, every group, every kind, every fill order, every
 * anchor, and how big a cell is — enough that a person, or a model, can write
 * a workspace for this build without the source in front of them.
 *
 * It is the smaller half of what makes *"an AI can design a layout"* true. The
 * other half is `docs/workspace-format.md`, which says what the fields mean.
 * Together they are two files you can paste into a conversation.
 *
 * ## Why it is generated and checked in
 *
 * Generated, because a hand-written copy of an enum is a hand-written copy of
 * an enum: it is correct on the day it is written and wrong on the day someone
 * adds a control. Checked in, because the point of it is to be readable
 * *without* building the app.
 *
 * Both of those are only true if the checked-in file cannot go stale, so
 * `ToolCatalogueTest` fails when `docs/catalogue.json` does not match what this
 * produces, and `./gradlew :app:catalogueJson` writes it. The build is what
 * keeps the published vocabulary honest — it cannot drift, because the test
 * says so.
 *
 * ## No JSON library
 *
 * `org.json` is on the platform but not on a plain JVM, and this has to run in
 * a unit test — that is the whole point of the staleness check. It is a flat
 * object of flat objects, so it is written out by hand, with the one escape
 * that a label could ever need.
 */
object ToolCatalogue {

    /**
     * Bumped when an entry is added, removed or renamed.
     *
     * A reader that has cached a catalogue can tell whether it is looking at
     * the same vocabulary. It cannot be derived from a hash of the contents,
     * because a hash tells you *that* something changed and this is meant to
     * tell you **whether you need to care** — a new tool does not invalidate a
     * workspace, and a removed one does.
     *
     * 1: pen, pencil, marker, eraser and its size, colour and the wheel, the
     * five sliders, layers, selection and their panels, zoom, fit, clear,
     * export, import, instruments.
     *
     * 2: the same tools. The vocabulary around them changed — there are no
     * docks and no shape presets, and a surface names a side only as a
     * starting position. See `docs/ui-grid-plan.md`.
     *
     * 3: `projects`, which opens the gallery. The first entry that is about the
     * drawing rather than about the marks on it. See `docs/projects-plan.md`.
     */
    const val VERSION = 3

    /** What a reader checks before believing any of the rest. */
    const val FORMAT = 1

    fun json(): String = buildString {
        line(0, "{")
        field(1, "artiest_catalogue", FORMAT, comma = true)
        field(1, "version", VERSION, comma = true)
        // A reader needs this to make sense of a rectangle. Everything in a
        // workspace file is counted in cells and this is what a cell is.
        field(1, "cell_dp", 44, comma = true)

        list(1, "groups", comma = true) {
            for ((i, g) in ToolGroup.entries.withIndex()) {
                line(2, "{")
                field(3, "id", g.name.lowercase(), comma = true)
                field(3, "label", g.label, comma = false)
                line(2, "}" + if (i < ToolGroup.entries.lastIndex) "," else "")
            }
        }

        strings(1, "kinds", ToolKind.entries.map { it.name.lowercase() }, comma = true)
        strings(1, "flows", FlowOrder.entries.map { it.id }, comma = true)
        strings(1, "anchors", Side.entries.map { it.id }, comma = true)

        val tools = ToolItem.entries.sortedBy { it.id }
        list(1, "tools", comma = false) {
            for ((i, t) in tools.withIndex()) {
                line(2, "{")
                field(3, "id", t.id, comma = true)
                field(3, "label", t.label, comma = true)
                field(3, "short", t.short, comma = true)
                field(3, "group", t.group.name.lowercase(), comma = true)
                field(3, "kind", t.kind.name.lowercase(), comma = true)
                field(3, "cells_wide", t.cellsWide, comma = true)
                field(3, "cells_tall", t.cellsTall, comma = true)
                // Whether the two numbers above may be swapped to suit where it
                // lands. True for a slider, false for a panel.
                field(3, "turns", t.turns, comma = false)
                line(2, "}" + if (i < tools.lastIndex) "," else "")
            }
        }

        line(0, "}")
    }

    // ---- the writer --------------------------------------------------------

    private fun StringBuilder.line(depth: Int, text: String) {
        repeat(depth) { append("  ") }
        append(text)
        append('\n')
    }

    private fun StringBuilder.field(depth: Int, name: String, value: String, comma: Boolean) =
        line(depth, "\"$name\": ${quote(value)}" + if (comma) "," else "")

    private fun StringBuilder.field(depth: Int, name: String, value: Int, comma: Boolean) =
        line(depth, "\"$name\": $value" + if (comma) "," else "")

    private fun StringBuilder.field(depth: Int, name: String, value: Boolean, comma: Boolean) =
        line(depth, "\"$name\": $value" + if (comma) "," else "")

    private fun StringBuilder.list(
        depth: Int,
        name: String,
        comma: Boolean,
        body: StringBuilder.() -> Unit,
    ) {
        line(depth, "\"$name\": [")
        body()
        line(depth, "]" + if (comma) "," else "")
    }

    private fun StringBuilder.strings(
        depth: Int,
        name: String,
        values: List<String>,
        comma: Boolean,
    ) = line(
        depth,
        "\"$name\": [" + values.joinToString(", ") { quote(it) } + "]" + if (comma) "," else "",
    )

    /**
     * The escapes a catalogue could plausibly need, and the ones it must not
     * omit even though it will never need them.
     *
     * Nothing here is user input today — every string is a Kotlin constant in
     * this repository — but this file is the template a workspace file is
     * written against, and an escape that is missing here is an escape somebody
     * copies out.
     */
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
}
