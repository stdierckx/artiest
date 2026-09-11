package be.thalos.artiest.project

import be.thalos.artiest.doc.LayerBlend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest, and every hostile thing a file can be.
 *
 * A project file is not yet something a stranger sends you — that is the `.ora`
 * import, Pj6 — but it will be, and the decoder that is written as if it were
 * is the only one that is ready for it. So the same tests `WorkspaceJsonTest`
 * has: a version from the future, a field nobody knows, a number that sizes an
 * allocation, and a path that tries to leave the directory.
 */
class ProjectJsonTest {

    private fun project(vararg sheets: ProjectSheet) = Project(
        id = "morning-sketch",
        name = "Morning sketch",
        created = 1_757_606_400_000L,
        modified = 1_757_610_000_000L,
        revision = 12,
        widthPx = 3300,
        heightPx = 2160,
        active = 0,
        sheets = sheets.toList(),
    )

    private fun sheet(i: Int, name: String = "Layer ${i + 1}") =
        ProjectSheet(Project.fileFor(i), name)

    @Test
    fun `a project comes back exactly as it went in`() {
        val p = project(
            sheet(0),
            ProjectSheet(Project.fileFor(1), "Ink", opacity = 0.8f, blend = LayerBlend.MULTIPLY),
            ProjectSheet(Project.fileFor(2), "Notes", visible = false),
        ).copy(active = 1)

        val back = assertNotNull(ProjectJson.decode(ProjectJson.encode(p)).project)
        assertEquals(p, back)
    }

    @Test
    fun `the text is the text, twice`() {
        val p = project(sheet(0))
        val once = ProjectJson.encode(p)
        val twice = ProjectJson.encode(assertNotNull(ProjectJson.decode(once).project))
        assertEquals(once, twice, "a save that changes the file for no reason is a save nobody trusts")
    }

    @Test
    fun `something that is not a project file says so and yields nothing`() {
        assertNull(ProjectJson.decode("not json at all").project)
        assertNull(ProjectJson.decode("{}").project)
        assertNull(ProjectJson.decode("""{"artiest_workspace": 2}""").project)
        assertTrue(ProjectJson.decode("{}").dropped.isNotEmpty())
    }

    @Test
    fun `a page with no size is refused rather than guessed at`() {
        val text = """{"artiest_project": 1, "name": "X", "layers": []}"""
        val decoded = ProjectJson.decode(text)
        assertNull(decoded.project)
        assertTrue(decoded.dropped.any { "how big" in it })
    }

    @Test
    fun `a file from a newer build is read, with a complaint`() {
        val text = """{"artiest_project": 99, "name": "X", "width": 100, "height": 50, "layers": []}"""
        val decoded = ProjectJson.decode(text)
        assertNotNull(decoded.project)
        assertTrue(decoded.dropped.any { "newer version" in it })
    }

    @Test
    fun `a field this build does not know is reported and not fatal`() {
        val text = """
            {"artiest_project": 1, "name": "X", "width": 100, "height": 50,
             "layers": [], "frames": 24}
        """.trimIndent()
        val decoded = ProjectJson.decode(text)
        assertEquals("X", decoded.project?.name)
        assertTrue(decoded.dropped.any { "frames" in it })
    }

    @Test
    fun `a sheet that names a file outside the project is dropped and said out loud`() {
        val hostile = listOf(
            "../../../shared_prefs/chrome.xml",
            "layers/../../secrets.png",
            "/etc/passwd",
            "layers/0.png.exe",
            "layers/abc.png",
            "Layers/0.png",
        )
        for (path in hostile) {
            val text = """
                {"artiest_project": 1, "name": "X", "width": 10, "height": 10,
                 "layers": [{"file": "$path", "name": "L"}]}
            """.trimIndent()
            val decoded = ProjectJson.decode(text)
            assertEquals(0, decoded.project?.sheets?.size, "$path was accepted")
            assertTrue(decoded.dropped.any { "will not open" in it }, "$path passed quietly")
        }
    }

    @Test
    fun `more sheets than the stack can hold loads what fits and says so`() {
        val sheets = (0..20).joinToString(", ") { """{"file": "layers/$it.png", "name": "L$it"}""" }
        val text = """
            {"artiest_project": 1, "name": "X", "width": 10, "height": 10, "layers": [$sheets]}
        """.trimIndent()
        val decoded = ProjectJson.decode(text)
        assertEquals(Project.MAX_SHEETS, decoded.project?.sheets?.size)
        assertTrue(decoded.dropped.any { "still in the file" in it })
    }

    @Test
    fun `the active sheet is always one that exists`() {
        val two = project(sheet(0), sheet(1))
        val far = ProjectJson.encode(two.copy(active = 9))
        assertEquals(1, ProjectJson.decode(far).project?.active)

        val none = """{"artiest_project": 1, "name": "X", "width": 9, "height": 9, "active": 4, "layers": []}"""
        assertEquals(0, ProjectJson.decode(none).project?.active)
    }

    @Test
    fun `paper takes three spellings and complains about the rest`() {
        fun paperOf(raw: String): Int? {
            val text = """{"artiest_project": 1, "name": "X", "width": 9, "height": 9, "paper": "$raw"}"""
            return ProjectJson.decode(text).project?.paperColor
        }
        assertEquals(0xFFFFFFFF.toInt(), paperOf("#ffffff"))
        assertEquals(0xFF1188CC.toInt(), paperOf("#18c"))
        assertEquals(0x80112233.toInt(), paperOf("#80112233"))
        assertEquals(0xFFFFFFFF.toInt(), paperOf("periwinkle"), "and the paper is white")

        val decoded = ProjectJson.decode(
            """{"artiest_project": 1, "name": "X", "width": 9, "height": 9, "paper": "periwinkle"}"""
        )
        assertTrue(decoded.dropped.any { "not a colour" in it })
    }

    @Test
    fun `an opacity outside nought to one is brought back in`() {
        val text = """
            {"artiest_project": 1, "name": "X", "width": 9, "height": 9,
             "layers": [{"file": "layers/0.png", "name": "L", "opacity": 4.5},
                        {"file": "layers/1.png", "name": "M", "opacity": -2}]}
        """.trimIndent()
        val sheets = assertNotNull(ProjectJson.decode(text).project).sheets
        assertEquals(1f, sheets[0].opacity)
        assertEquals(0f, sheets[1].opacity)
    }

    @Test
    fun `a blend this build has never heard of is normal`() {
        val text = """
            {"artiest_project": 1, "name": "X", "width": 9, "height": 9,
             "layers": [{"file": "layers/0.png", "name": "L", "blend": "hard-light-2"}]}
        """.trimIndent()
        assertEquals(LayerBlend.NORMAL, ProjectJson.decode(text).project?.sheets?.first()?.blend)
    }

    @Test
    fun `a name that is trying to be something else is cleaned and cut`() {
        val nasty = "Draw‮ing" + "x".repeat(200)
        val text = """{"artiest_project": 1, "name": "$nasty", "width": 9, "height": 9}"""
        val name = assertNotNull(ProjectJson.decode(text).project).name
        assertTrue('‮' !in name, "the override is gone")
        assertEquals(Project.MAX_NAME, name.length)
    }

    @Test
    fun `the id is a slug, and a missing one comes from the name`() {
        val text = """{"artiest_project": 1, "name": "Anna's Inker", "width": 9, "height": 9}"""
        assertEquals("annas-inker", ProjectJson.decode(text).project?.id)

        val nasty = """{"artiest_project": 1, "id": "../../x", "name": "N", "width": 9, "height": 9}"""
        val id = assertNotNull(ProjectJson.decode(nasty).project).id
        assertTrue("/" !in id && ".." !in id, "id was $id")
    }
}
