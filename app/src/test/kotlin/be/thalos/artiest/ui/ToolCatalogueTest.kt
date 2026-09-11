package be.thalos.artiest.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The published vocabulary, and the one thing that keeps it honest.
 *
 * The last test is the load-bearing one: `docs/catalogue.json` is checked in so
 * that it can be read without building the app, and a checked-in generated file
 * is a file that goes stale. This is what stops it — add a control and the
 * build fails until `./gradlew :app:catalogueJson` has been run, which is one
 * command and a diff somebody can read.
 */
class ToolCatalogueTest {

    private val json = ToolCatalogue.json()

    @Test
    fun `every tool in the catalogue is in the file`() {
        for (item in ToolItem.entries) {
            assertTrue("\"id\": \"${item.id}\"" in json, "${item.id} is missing")
            assertTrue("\"label\": \"${item.label}\"" in json, "${item.label} is missing")
        }
    }

    @Test
    fun `every group, kind, flow and anchor is named`() {
        for (g in ToolGroup.entries) assertTrue("\"${g.name.lowercase()}\"" in json, g.name)
        for (k in ToolKind.entries) assertTrue("\"${k.name.lowercase()}\"" in json, k.name)
        for (f in FlowOrder.entries) assertTrue("\"${f.id}\"" in json, f.id)
        for (a in Side.entries) assertTrue("\"${a.id}\"" in json, a.id)
    }

    @Test
    fun `ids are unique and contain nothing the format would have to escape`() {
        val ids = ToolItem.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "two tools share an id")
        for (id in ids) {
            assertTrue(
                id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' },
                "$id is not a plain lower-case identifier",
            )
        }
        // The separators the compact layout string uses. An id carrying one of
        // these would cut a saved layout in half at the point it appeared.
        for (id in ids) {
            for (c in listOf('|', ':', ',', '=', '@', '+')) {
                assertTrue(c !in id, "$id contains the separator '$c'")
            }
        }
    }

    @Test
    fun `it is a whole, balanced JSON document`() {
        // Not a parser — there is none on this classpath — but enough that a
        // truncated or double-comma'd file cannot pass unnoticed.
        assertTrue(json.startsWith("{\n"))
        assertTrue(json.trimEnd().endsWith("}"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' }, "braces")
        assertEquals(json.count { it == '[' }, json.count { it == ']' }, "brackets")
        assertTrue(",," !in json && ",\n  ]" !in json, "a trailing comma")
        assertTrue("\"cell_dp\": 44" in json, "a rectangle means nothing without this")
    }

    @Test
    fun `the same catalogue always writes the same file`() {
        assertEquals(json, ToolCatalogue.json())
    }

    @Test
    fun `the checked-in file is not stale`() {
        val file = catalogueFile()
        assertTrue(
            file.exists(),
            "${file.path} is missing — run ./gradlew :app:catalogueJson",
        )
        assertEquals(
            json,
            file.readText(),
            "${file.path} is out of date — run ./gradlew :app:catalogueJson",
        )
    }

    /**
     * `docs/catalogue.json`, found from wherever the test happens to run.
     *
     * Gradle runs unit tests with the module directory as the working
     * directory, but that is a convention rather than a promise, so this walks
     * up until it finds the repository root instead of hard-coding `../`.
     */
    private fun catalogueFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/catalogue.json")
            if (candidate.exists() || File(dir, "settings.gradle.kts").exists()) return candidate
            dir = dir.parentFile
        }
        return File("docs/catalogue.json")
    }
}
