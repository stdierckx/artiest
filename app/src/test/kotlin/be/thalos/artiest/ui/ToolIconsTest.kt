package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The glyphs, as a plain JVM test.
 *
 * `ImageVector` and everything under it — `Color`, `SolidColor`, `PathData`,
 * `Dp` — is Kotlin with no Android in it, so this runs without Robolectric.
 * That matters: the thing being checked is a catalogue, and a catalogue check
 * that needed a device would not be run.
 *
 * **What it is for.** A user reported that the Select toggle and the Selection
 * panel button had the same face. Two buttons that look identical and do
 * different things is the same defect as two buttons lit at once — there is
 * nothing to read — and it is exactly the sort of thing that arrives by
 * copy-paste and survives review, because both lines are individually correct.
 */
class ToolIconsTest {

    /**
     * Items that share a glyph on purpose, as unordered pairs of ids.
     *
     * Each of these is a control and the kept version of the same control, or
     * a control and its own size slider. They are listed rather than inferred,
     * so that adding a third one is a deliberate line in this file.
     */
    private val deliberate: Set<Set<String>> = setOf(
        setOf("colour", "colour_panel"),
        setOf("layers", "layers_panel"),
        setOf("selection", "selection_panel"),
        setOf("eraser", "eraser_size"),
    )

    @Test
    fun `the select toggle and the selection panel do not share a face`() {
        assertNotSame(
            ToolIcons.of(ToolItem.MARQUEE),
            ToolIcons.of(ToolItem.SELECTION),
            "the toggle that makes the pen select and the button that opens the " +
                "selection panel are different things and must look different",
        )
    }

    @Test
    fun `no two catalogue items share a glyph by accident`() {
        val byGlyph = ToolItem.entries
            .mapNotNull { item -> ToolIcons.of(item)?.let { it to item } }
            .groupBy({ it.first.name }, { it.second.id })

        for ((glyph, ids) in byGlyph) {
            if (ids.size == 1) continue
            for (a in ids) {
                for (b in ids) {
                    if (a == b) continue
                    assertTrue(
                        setOf(a, b) in deliberate,
                        "$a and $b both draw '$glyph'; add the pair to `deliberate` " +
                            "if that is intended, or give one of them a glyph of its own",
                    )
                }
            }
        }
    }

    @Test
    fun `every catalogue item has a glyph except the swatch`() {
        // The colour swatch draws the ink itself, and that is the only face in
        // the app that is its own value -- but the chooser lists items before
        // they are placed, where there is no ink to show, so even it has one.
        for (item in ToolItem.entries) {
            assertTrue(ToolIcons.of(item) != null, "${item.id} has no glyph")
        }
    }

    @Test
    fun `two different glyphs never carry the same name`() {
        // The name is what the test above reports and what a debugger shows.
        // A glyph copy-pasted from its neighbour keeps the neighbour's name,
        // and then a duplicate reads as the same icon twice in every diagnostic
        // that would have caught it.
        val byName = ToolItem.entries
            .mapNotNull { ToolIcons.of(it) }
            .distinct()
            .groupBy { it.name }
        for ((name, glyphs) in byName) {
            assertEquals(1, glyphs.size, "'$name' is the name of ${glyphs.size} different glyphs")
            assertTrue(name.isNotBlank())
        }
    }
}
