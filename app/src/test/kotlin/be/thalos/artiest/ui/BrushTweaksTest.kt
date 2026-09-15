package be.thalos.artiest.ui

import android.content.Context
import be.thalos.artiest.engine.brush.BrushCodec
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What each brush was last set to, across being put down and picked up.
 *
 * The test that names the complaint is [an eraser set to thirty is thirty the
 * next time it is picked up] — that is the tablet report, written as the two
 * calls the app makes.
 *
 * Robolectric for `SharedPreferences` and nothing else; no graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrushTweaksTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val eraser: BrushEntry get() = BrushLibrary.DEFAULT.entryFor(BrushPreset.HARD_ERASER.id)
    private val pencil: BrushEntry get() = BrushLibrary.DEFAULT.entryFor(BrushPreset.PENCIL.id)

    @Before
    fun clean() {
        context.getSharedPreferences("brush-tweaks", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun tweaks() = BrushTweaks(context)

    /** Pick [entry] up the way `CanvasScreen.pick` does. */
    private fun pickUp(t: BrushTweaks, entry: BrushEntry) = entry.create().also { t.restore(entry, it) }

    @Test
    fun `an eraser set to thirty is thirty the next time it is picked up`() {
        val t = tweaks()

        // Pick the eraser up and find it at whatever it was authored at.
        val held = pickUp(t, eraser)
        val authored = held.sizeMax
        assertNotEquals(30f, authored, "the preset would have to move for this test to say nothing")

        // Set it to 30 and put it down.
        held.sizeMax = 30f
        t.remember(eraser, held)

        // The pencil, and back.
        t.remember(pencil, pickUp(t, pencil))
        assertEquals(30f, pickUp(t, eraser).sizeMax, "the eraser forgot")
    }

    @Test
    fun `a brush that was never moved is not stored at all`() {
        val t = tweaks()
        t.remember(pencil, pencil.create())
        assertFalse(t.has(pencil.id), "an untouched brush should leave nothing behind")
    }

    @Test
    fun `putting a brush back where it was forgets it`() {
        val t = tweaks()
        val held = pencil.create()
        held.sizeMax = 7f
        t.remember(pencil, held)
        assertTrue(t.has(pencil.id))

        // What Revert does: the authored brush, remembered again.
        t.remember(pencil, pencil.create())
        assertFalse(t.has(pencil.id))
        assertEquals(pencil.create().sizeMax, pickUp(t, pencil).sizeMax)
    }

    @Test
    fun `one brush's settings never reach another`() {
        val t = tweaks()
        val e = eraser.create().apply { sizeMax = 30f }
        t.remember(eraser, e)
        assertEquals(pencil.create().sizeMax, pickUp(t, pencil).sizeMax, "the pencil took the eraser's size")
    }

    @Test
    fun `every slider on the toolbar is remembered`() {
        val t = tweaks()
        val held = pencil.create().apply {
            sizeMax = 11f
            opacity = 0.4f
            flow = 0.6f
            stabilization = 0.8f
            grain = grain.copy(strength = 0.3f)
        }
        t.remember(pencil, held)

        val back = pickUp(t, pencil)
        assertEquals(11f, back.sizeMax)
        assertEquals(0.4f, back.opacity)
        assertEquals(0.6f, back.flow)
        assertEquals(0.8f, back.stabilization)
        assertEquals(0.3f, back.grain.strength)
    }

    /**
     * The point of storing the tuning alongside. A remembered size from before
     * a retune would win over the new number forever, on a device where nobody
     * could tell why.
     */
    @Test
    fun `a retune drops what was remembered about a built-in`() {
        val t = tweaks()
        val held = pencil.create().apply { sizeMax = 11f }
        t.remember(pencil, held)

        // The same brush, authored against the next tuning.
        val retuned = BrushEntry(
            id = pencil.id,
            label = pencil.label,
            origin = pencil.origin,
            tuning = BrushPreset.TUNING + 1,
            text = BrushCodec.encode(pencil.create()),
        )
        val fresh = retuned.create()
        assertFalse(t.restore(retuned, fresh), "a tweak from before the retune was honoured")
        assertFalse(t.has(pencil.id), "and it should not still be there to be honoured next time")
    }

    /**
     * A saved brush has tuning 0 at both ends of that comparison, so a retune
     * never touches one. The shelf's promise that a brush you saved stays put.
     */
    @Test
    fun `a saved brush keeps its settings through a retune`() {
        val t = tweaks()
        val mine = BrushEntry.fromText("mine", "Mine", BrushCodec.encode(pencil.create()))
        val held = mine.create().apply { sizeMax = 11f }
        t.remember(mine, held)
        assertEquals(11f, pickUp(t, mine).sizeMax)
    }

    @Test
    fun `settings for a brush that is gone are pruned`() {
        val t = tweaks()
        t.remember(pencil, pencil.create().apply { sizeMax = 11f })
        val mine = BrushEntry.fromText("mine", "Mine", BrushCodec.encode(pencil.create()))
        t.remember(mine, mine.create().apply { sizeMax = 12f })

        t.prune(setOf(pencil.id))
        assertTrue(t.has(pencil.id), "the shelf still has the pencil")
        assertFalse(t.has("mine"), "and not the deleted brush")
    }

    @Test
    fun `pruning keeps what it keeps intact`() {
        val t = tweaks()
        t.remember(pencil, pencil.create().apply { sizeMax = 11f })
        t.prune(setOf(pencil.id, eraser.id))
        assertEquals(11f, pickUp(t, pencil).sizeMax, "pruning ate a live tweak")
    }

    @Test
    fun `text that will not parse is thrown away rather than retried`() {
        context.getSharedPreferences("brush-tweaks", Context.MODE_PRIVATE)
            .edit().putString("b/" + pencil.id, "not a brush").putInt("t/" + pencil.id, pencil.tuning)
            .commit()
        val t = tweaks()
        assertFalse(t.restore(pencil, pencil.create()))
        assertFalse(t.has(pencil.id))
    }

    /**
     * The wiring is the entry's, always. A tweak carries numbers and nothing
     * else — see `copyScalarsOnto`, and `copyWiringOnto` for the trap it avoids.
     */
    @Test
    fun `a tweak does not change how the brush answers the pen`() {
        val t = tweaks()
        t.remember(pencil, pencil.create().apply { sizeMax = 11f })

        val fresh = pencil.create()
        val inputsBefore = fresh.size.inputCount
        t.restore(pencil, fresh)
        assertEquals(inputsBefore, fresh.size.inputCount, "the tweak rewired the size sensor")
        assertEquals(pencil.create().tip, fresh.tip, "the tweak changed the tip")
    }
}
