package be.thalos.artiest.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the `+` menu's tab strip has on it.
 *
 * The user asked for a fifth tab — *"It has 4 categories. Edit, draw, Canvas
 * and File. Make a new categorie: brushes."* — and the interesting part is not
 * that it exists but that it is **not a [ToolGroup]**. Every other tab is a
 * property of a catalogue entry; this one is a property of the shelf, which the
 * catalogue has never heard of. These tests are the seam between those two
 * ideas.
 *
 * No Compose: [chooserTabs] is a function of two values, which is the whole
 * reason it was pulled out of the composable.
 */
class ChooserTabsTest {

    private fun labels(filter: CatalogueFilter, hasBrushes: Boolean) =
        chooserTabs(filter, hasBrushes).map { it.label }

    @Test
    fun `everything offers the groups and then brushes`() {
        assertEquals(
            // Selection is the sixth, and it sits third because that is where
            // the hand reaches for it. See `ToolGroup.SELECT`.
            listOf("Edit", "Draw", "Selection", "Canvas", "Learn", "File", "Instruments", "Brushes"),
            labels(CatalogueFilter.EVERYTHING, hasBrushes = true),
        )
    }

    @Test
    fun `an empty shelf has no tab at all`() {
        // Not an empty tab. A Brushes tab with nothing in it is a drawer that
        // can never fill up, which reads as broken rather than as empty — and
        // the shelf always ships built-ins, so this state is only ever reached
        // by a caller that has no library, such as a test rendering a dock.
        assertTrue("Brushes" !in labels(CatalogueFilter.EVERYTHING, hasBrushes = false))
    }

    @Test
    fun `hiding the brush button hides the tab`() {
        // A workspace that does not offer `ToolItem.BRUSH` cannot place one, so
        // offering a list of brushes to place would be a menu of dead rows.
        val filter = CatalogueFilter.EVERYTHING.hiding(ToolItem.BRUSH)
        assertTrue("Brushes" !in labels(filter, hasBrushes = true))
    }

    @Test
    fun `a group with nothing left in it loses its tab`() {
        // File survives a Draw-only filter, and that is `ToolItem.essential`
        // doing its job rather than a slip: Drawings is the way back to your
        // work and no workspace may take it away, so the tab it lives on
        // cannot go either.
        val only = CatalogueFilter.of(ToolGroup.DRAW)
        assertEquals(listOf("Draw", "File", "Brushes"), labels(only, hasBrushes = true))
    }

    @Test
    fun `the group tabs still carry their group`() {
        val tabs = chooserTabs(CatalogueFilter.of(ToolGroup.FILE), hasBrushes = false)
        assertEquals(listOf<ChooserTab>(ChooserTab.Group(ToolGroup.FILE)), tabs)
    }
}
