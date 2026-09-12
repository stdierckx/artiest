package be.thalos.artiest.ui

import android.content.Context
import be.thalos.artiest.engine.brush.BrushEntry
import be.thalos.artiest.engine.brush.BrushLibrary
import be.thalos.artiest.engine.brush.BrushPreset
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the app remembers about brushes between launches.
 *
 * Robolectric for `SharedPreferences` and nothing else — no graphics, so the
 * default shadow is fine here where `BrushSwatchTest` needs NATIVE.
 *
 * The tests that matter are the two about *absence*: an eraser that was never
 * chosen and an eraser whose brush has since been deleted have to come back the
 * same way, because "erase with the brush in my hand" is the default and a user
 * who has never opened the menu is in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrushStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        context.getSharedPreferences("chrome", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a fresh install holds the pen and erases with it`() {
        val store = BrushStore(context)
        assertEquals("pen", store.loadId())
        assertNull(store.loadEraserId(), "no eraser brush is the default, not a missing value")
        assertEquals(0, store.storedTuning())
    }

    @Test
    fun `the brush in the hand survives a restart`() {
        val entry = assertNotNull(BrushLibrary.DEFAULT.find("marker"))
        val brush = entry.create().also { it.sizeMax = 61f }
        BrushStore(context).save(brush, entry)

        val back = BrushStore(context)
        assertEquals("marker", back.loadId())
        assertEquals(BrushPreset.TUNING, back.storedTuning())
        assertEquals(61f, assertNotNull(back.load()).sizeMax, "including the slider that was moved")
    }

    @Test
    fun `a saved brush is stored under its own tuning, which never moves`() {
        val mine = BrushEntry.fromText("mine", "Mine", be.thalos.artiest.engine.brush.BrushCodec.encode(
            BrushPreset.PENCIL.create(),
        ))
        BrushStore(context).save(mine.create(), mine)
        assertEquals(0, BrushStore(context).storedTuning(), "so no re-tune can reach it")
    }

    @Test
    fun `the eraser's brush survives a restart and can be taken back off`() {
        val store = BrushStore(context)
        store.saveEraserId("pen")
        assertEquals("pen", BrushStore(context).loadEraserId())

        store.saveEraserId(null)
        assertNull(BrushStore(context).loadEraserId(), "and back to the brush in the hand")
    }

    @Test
    fun `an eraser brush that has gone away is nobody's problem`() {
        BrushStore(context).saveEraserId("a-brush-that-was-deleted")
        val id = BrushStore(context).loadEraserId()
        assertEquals("a-brush-that-was-deleted", id, "the store does not second-guess it")
        assertNull(BrushLibrary.DEFAULT.find(id), "and the library answers with nothing")
    }

    @Test
    fun `the old enum spelling still names the same brush`() {
        context.getSharedPreferences("chrome", Context.MODE_PRIVATE)
            .edit().putString("brush.preset", "PENCIL").commit()
        assertEquals("pencil", BrushStore(context).loadId())
    }
}
