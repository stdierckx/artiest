package be.thalos.artiest.project

import be.thalos.artiest.doc.LayerBlend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `stack.xml`, in both directions.
 *
 * The one that would otherwise be found by a user is the first: **the format
 * lists the topmost layer first and we list the bottom one first**, so a
 * reversal that is missing or doubled produces a file that opens upside down
 * rather than one that fails. Writing and reading are checked against each
 * other here for exactly that reason.
 *
 * The rest is a file from a stranger: a layer whose `src` tries to leave the
 * archive, a hundred layers, an attribute that is a prefix of another one.
 */
class OraTest {

    private fun project(vararg sheets: ProjectSheet) = Project(
        id = "sketch",
        name = "Sketch",
        widthPx = 3300,
        heightPx = 2160,
        sheets = sheets.toList(),
    )

    private fun sheet(i: Int, name: String) = ProjectSheet(Project.fileFor(i), name)

    @Test
    fun `the topmost layer is written first and read back last`() {
        val p = project(sheet(0, "Paper"), sheet(1, "Pencil"), sheet(2, "Ink"))
        val xml = Ora.stackXml(p)

        assertTrue(xml.indexOf("Ink") < xml.indexOf("Pencil"), "the file is top-first")
        assertTrue(xml.indexOf("Pencil") < xml.indexOf("Paper"))

        val back = assertNotNull(Ora.readStack(xml))
        assertEquals(listOf("Paper", "Pencil", "Ink"), back.sheets.map { it.name })
        assertEquals(3300, back.widthPx)
        assertEquals(2160, back.heightPx)
        assertEquals(listOf("data/layer0.png", "data/layer1.png", "data/layer2.png"), back.sheets.map { it.src })
    }

    @Test
    fun `everything about a sheet survives the trip`() {
        val p = project(
            ProjectSheet(Project.fileFor(0), "Paper"),
            ProjectSheet(Project.fileFor(1), "Ink", opacity = 0.4f, visible = false, blend = LayerBlend.MULTIPLY),
        )
        val back = assertNotNull(Ora.readStack(Ora.stackXml(p)))
        assertEquals("Ink", back.sheets[1].name)
        assertEquals(0.4f, back.sheets[1].opacity)
        assertEquals(false, back.sheets[1].visible)
        assertEquals(LayerBlend.MULTIPLY, back.sheets[1].blend)
        assertEquals(true, back.sheets[0].visible)
    }

    @Test
    fun `every blend this build has maps both ways`() {
        for (blend in LayerBlend.entries) {
            assertEquals(blend, Ora.blendOf(Ora.compositeOf(blend)), blend.id)
        }
        assertEquals(LayerBlend.NORMAL, Ora.blendOf("svg:colour-burn-ish"), "and the rest is normal")
        assertEquals(LayerBlend.NORMAL, Ora.blendOf(null))
    }

    @Test
    fun `a name with punctuation in it comes back whole`() {
        val p = project(sheet(0, "\"Anna\" & <the> 'rest'"))
        val back = assertNotNull(Ora.readStack(Ora.stackXml(p)))
        assertEquals("\"Anna\" & <the> 'rest'", back.sheets.first().name)
    }

    @Test
    fun `what a foreign file brings that ours never does`() {
        // Krita trims a layer to what is drawn on it, so this is the ordinary
        // shape of a file from anywhere else.
        val xml = """
            <?xml version='1.0' encoding='UTF-8'?>
            <image version="0.0.3" w="800" h="600">
              <stack>
                <layer name="Sketch" src="data/1.png" x="120" y="40" opacity="0.5"
                       visibility="hidden" composite-op="svg:screen"/>
              </stack>
            </image>
        """.trimIndent()
        val back = assertNotNull(Ora.readStack(xml))
        val sheet = back.sheets.single()
        assertEquals(120, sheet.x)
        assertEquals(40, sheet.y)
        assertEquals(0.5f, sheet.opacity)
        assertEquals(false, sheet.visible)
        assertEquals(LayerBlend.SCREEN, sheet.blend)
    }

    @Test
    fun `a layer group is flattened, and it says so`() {
        val xml = """
            <image version="0.0.3" w="10" h="10">
              <stack>
                <stack name="Group" opacity="1">
                  <layer name="Inside" src="data/1.png"/>
                </stack>
                <layer name="Outside" src="data/2.png"/>
              </stack>
            </image>
        """.trimIndent()
        val back = assertNotNull(Ora.readStack(xml))
        assertEquals(listOf("Outside", "Inside"), back.sheets.map { it.name })
        assertTrue(back.nested, "so the import can say what it did")
    }

    @Test
    fun `a src that tries to leave the archive is dropped`() {
        for (path in listOf("../../../etc/passwd", "/etc/passwd", "data/../../x.png")) {
            val xml = """<image w="10" h="10"><stack><layer name="X" src="$path"/></stack></image>"""
            assertEquals(0, Ora.readStack(xml)?.sheets?.size, "$path was accepted")
        }
    }

    @Test
    fun `something that is not a stack is nothing`() {
        assertNull(Ora.readStack("hello"))
        assertNull(Ora.readStack("<image><stack></stack></image>"), "no size")
        assertNull(Ora.readStack("<image w=\"0\" h=\"10\"><stack/></image>"), "no page")
        assertNull(Ora.readStack("<image w=\"1\" h=\"1\"/>".repeat(200_000)), "more than a stack")
    }

    @Test
    fun `more layers than the stack can hold are dropped rather than kept`() {
        val layers = (0 until 40).joinToString("") { """<layer name="L$it" src="data/$it.png"/>""" }
        val back = assertNotNull(Ora.readStack("""<image w="10" h="10"><stack>$layers</stack></image>"""))
        assertEquals(Project.MAX_SHEETS, back.sheets.size)
    }

    @Test
    fun `an attribute is not found inside another one`() {
        // `opacity` is a substring of nothing here, but `x` is inside almost
        // every attribute name there is, and a scanner that matched it would
        // put every layer somewhere random.
        val xml = """<image w="10" h="10"><stack>""" +
            """<layer name="max" src="data/0.png" composite-op="svg:src-over" x="3" y="4"/>""" +
            """</stack></image>"""
        val sheet = assertNotNull(Ora.readStack(xml)).sheets.single()
        assertEquals(3, sheet.x)
        assertEquals(4, sheet.y)
        assertEquals("max", sheet.name)
    }
}
