package be.thalos.artiest.ui

import android.content.Context
import be.thalos.artiest.ink.Tips
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wb8: the sixteen brushes a fresh install has.
 *
 * Three claims, and each is a thing that has gone wrong in this repository
 * before: that the shipped files actually parse, that seeding happens **once**
 * so a brush somebody threw away stays thrown away, and that a brush that names
 * a picture finds it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StarterBrushesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        Tips.library.clear()
        File(context.filesDir, "brushes").deleteRecursively()
        Tips.directoryIn(context.filesDir).deleteRecursively()
        context.getSharedPreferences("artiest.brushes", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `a fresh install gets the whole shipped set`() {
        assertEquals(16, StarterBrushes.seed(context))
        val shelf = BrushFiles(File(context.filesDir, "brushes")).list()
        assertEquals(16, shelf.size, "one of the shipped files did not parse")
        assertTrue(shelf.any { it.label.startsWith("Pencil") })
        assertTrue(shelf.any { it.label.startsWith("Ink") })
    }

    /**
     * The reason [StarterBrushes.SEED] is a number and not "is the directory
     * empty": an empty directory is what you have after deleting all sixteen.
     */
    @Test
    fun `a brush thrown away stays thrown away`() {
        StarterBrushes.seed(context)
        val files = BrushFiles(File(context.filesDir, "brushes"))
        assertTrue(files.delete("ink-3-gpen"))
        assertEquals(0, StarterBrushes.seed(context))
        assertTrue(files.list().none { it.id == "ink-3-gpen" })
    }

    @Test
    fun `a shipped brush that names a picture finds it`() {
        StarterBrushes.seed(context)
        assertTrue(Tips.loadDirectory(Tips.directoryIn(context.filesDir)) >= 6)
        val shelf = BrushFiles(File(context.filesDir, "brushes")).list()
        val tipped = shelf.mapNotNull { it.create().tip }
        assertTrue(tipped.isNotEmpty(), "nothing in the shipped set stamps a picture")
        for (id in tipped) assertNotNull(Tips.find(id), "$id is named but not shipped")
    }

    /**
     * Sixteen brushes plus their pictures ride in the APK on every install, so
     * the size is a thing worth failing on rather than noticing later.
     */
    @Test
    fun `the shipped set is small enough to carry`() {
        var bytes = 0L
        for (folder in listOf("brushes", "tips")) {
            for (name in context.assets.list(folder).orEmpty()) {
                bytes += context.assets.open("$folder/$name").use { it.readBytes() }.size
            }
        }
        assertTrue(bytes < 512 * 1024, "the starter set is ${bytes / 1024} KiB")
    }
}
