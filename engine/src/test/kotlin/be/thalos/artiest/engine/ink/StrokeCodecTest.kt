package be.thalos.artiest.engine.ink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The file format, tested the way `BrushCodec` and the trace format are: round
 * trip, truncation, garbage, and a version byte that refuses a file from a
 * newer build rather than guessing at it.
 *
 * The refusals are the half that matters. A stroke file is a drawing's
 * contents, and a decoder that recovers from a corrupt one produces a
 * *different drawing* — which then opens, looks plausible, and is saved back
 * over the original on the next debounce. So every malformed input throws, and
 * each of these tests is one way a file can be malformed.
 */
class StrokeCodecTest {

    @Test
    fun `an empty sheet round trips`() {
        val bytes = StrokeCodec.encode(emptyList())
        assertEquals(emptyList(), StrokeCodec.decode(bytes))
        assertTrue(bytes.size < 32, "${bytes.size} bytes for nothing")
    }

    @Test
    fun `every field survives the round trip`() {
        val records = listOf(
            record(id = 7, brush = 2, color = 0x80FF0000.toInt(), erase = false, seed = 9, base = 0),
            record(
                id = 8, brush = 0, color = 0xFF00FF00.toInt(), erase = true, seed = -3,
                base = 41, clip = 1, guide = 2,
            ),
        )
        val back = StrokeCodec.decode(StrokeCodec.encode(records))
        assertEquals(2, back.size)
        for (i in records.indices) {
            val a = records[i]
            val b = back[i]
            assertEquals(a.id, b.id)
            assertEquals(a.brush, b.brush)
            assertEquals(a.colorArgb, b.colorArgb)
            assertEquals(a.erase, b.erase)
            assertEquals(a.seed, b.seed)
            assertEquals(a.dabBase, b.dabBase)
            assertEquals(a.clip, b.clip)
            assertEquals(a.guide, b.guide)
            assertEquals(a.bounds, b.bounds)
            assertEquals(a.sampleCount, b.sampleCount)
            assertContentEquals(a.copyPackedBytes(), b.copyPackedBytes())
        }
    }

    @Test
    fun `an empty bounds survives as an empty bounds`() {
        val r = record(id = 1, bounds = Bounds.EMPTY)
        val back = StrokeCodec.decode(StrokeCodec.encode(listOf(r))).single()
        assertTrue(back.bounds.isEmpty)
    }

    @Test
    fun `a record with no samples round trips`() {
        val r = StrokeRecord(
            id = 3, brush = 0, colorArgb = -1, erase = false, seed = 0, dabBase = 0,
            clip = StrokeRecord.NO_CLIP, bounds = Bounds.EMPTY,
            packed = ByteArray(StrokeCodec.ORIGIN_BYTES), sampleCount = 0,
        )
        val back = StrokeCodec.decode(StrokeCodec.encode(listOf(r))).single()
        assertEquals(0, back.sampleCount)
    }

    @Test
    fun `the order of the records is the order they are drawn in`() {
        val many = (0 until 50).map { record(id = it.toLong()) }
        val back = StrokeCodec.decode(StrokeCodec.encode(many))
        assertContentEquals(many.map { it.id }, back.map { it.id })
    }

    // ------------------------------------------------------------- refusals

    @Test
    fun `something that is not a stroke file is refused`() {
        val thrown = assertFailsWith<StrokeFormatException> {
            StrokeCodec.decode("this is a png, honestly".toByteArray())
        }
        assertTrue(thrown.message!!.contains("ARTINK"), thrown.message!!)
    }

    @Test
    fun `a file from a newer build is refused rather than guessed at`() {
        val bytes = StrokeCodec.encode(listOf(record()))
        bytes[StrokeCodec.MAGIC.length] = (StrokeCodec.VERSION + 1).toByte()
        val thrown = assertFailsWith<StrokeFormatException> { StrokeCodec.decode(bytes) }
        assertTrue(thrown.message!!.contains("version"), thrown.message!!)
    }

    @Test
    fun `a reserved byte that means something is refused`() {
        val bytes = StrokeCodec.encode(listOf(record()))
        bytes[StrokeCodec.MAGIC.length + 1] = 1
        assertFailsWith<StrokeFormatException> { StrokeCodec.decode(bytes) }
    }

    @Test
    fun `a truncated file is refused at every length`() {
        val bytes = StrokeCodec.encode(listOf(record(), record(id = 2)))
        // Every prefix but the whole thing. Truncation at a record boundary and
        // truncation in the middle of a packed buffer take different paths, and
        // walking every length is the only way to be sure both are covered.
        for (n in 0 until bytes.size) {
            assertFailsWith<StrokeFormatException>("prefix of $n bytes decoded") {
                StrokeCodec.decode(bytes.copyOf(n))
            }
        }
        assertEquals(2, StrokeCodec.decode(bytes).size)
    }

    @Test
    fun `a record count that lies is refused`() {
        val bytes = StrokeCodec.encode(listOf(record()))
        val at = StrokeCodec.MAGIC.length + 2
        bytes[at] = 0x7F
        bytes[at + 1] = 0x00
        assertFailsWith<StrokeFormatException> { StrokeCodec.decode(bytes) }
    }

    @Test
    fun `a negative record count is refused`() {
        val bytes = StrokeCodec.encode(emptyList())
        val at = StrokeCodec.MAGIC.length + 2
        bytes[at] = 0xFF.toByte()
        assertFailsWith<StrokeFormatException> { StrokeCodec.decode(bytes) }
    }

    /**
     * The one that allocates a gigabyte if it is not checked. The sample count
     * is read before the buffer it describes, so a corrupt four bytes there is
     * an `OutOfMemoryError` in a save path rather than a message naming the
     * file.
     */
    @Test
    fun `an absurd sample count is refused before it is allocated`() {
        val bytes = StrokeCodec.encode(listOf(record()))
        // The sample count is the last int before the packed buffer.
        val at = bytes.size - (StrokeCodec.ORIGIN_BYTES + 4 * StrokeCodec.SAMPLE_BYTES) - 4
        bytes[at] = 0x3F
        bytes[at + 1] = 0xFF.toByte()
        bytes[at + 2] = 0xFF.toByte()
        bytes[at + 3] = 0xFF.toByte()
        val thrown = assertFailsWith<StrokeFormatException> { StrokeCodec.decode(bytes) }
        assertTrue(thrown.message!!.contains("samples"), thrown.message!!)
    }

    @Test
    fun `a bounds that is not a rectangle is refused`() {
        val r = record(bounds = Bounds.of(10f, 10f, 20f, 20f))
        val bytes = StrokeCodec.encode(listOf(r))
        // The four bounds floats sit between the erase byte and the sample
        // count: rewrite `left` to something past `right`.
        val at = bytes.size - (StrokeCodec.ORIGIN_BYTES + 4 * StrokeCodec.SAMPLE_BYTES) - 4 - 16
        val big = java.lang.Float.floatToIntBits(1000f)
        bytes[at] = (big ushr 24).toByte()
        bytes[at + 1] = (big ushr 16).toByte()
        bytes[at + 2] = (big ushr 8).toByte()
        bytes[at + 3] = big.toByte()
        val thrown = assertFailsWith<StrokeFormatException> { StrokeCodec.decode(bytes) }
        assertTrue(thrown.message!!.contains("bounds"), thrown.message!!)
    }

    // --------------------------------------------------------------- budget

    /**
     * The size claim `docs/inker-plan.md` makes about Ik6's save, with the file
     * header and the per-record header included, because a format that is nine
     * bytes a sample and forty a record is not nine bytes a sample on a page of
     * short strokes.
     */
    @Test
    fun `a file from before Ik13 opens, and its strokes were drawn freehand`() {
        // Version 3 added the guide column. Every drawing saved before it is a
        // version 2 file with no such column, and every stroke in one really
        // was drawn freehand — there was no guide to draw against — so this is
        // not a default standing in for a lost value.
        val v3 = StrokeCodec.encode(listOf(record(id = 4, clip = 1, guide = 0)))
        val v2 = downgradeToVersion2(v3)
        val back = StrokeCodec.decode(v2).single()
        assertEquals(4L, back.id)
        assertEquals(1, back.clip, "the columns before the new one still line up")
        assertEquals(StrokeRecord.NO_GUIDE, back.guide)
        assertEquals(4, back.sampleCount)
    }

    /**
     * The same file as a version 2 one: the version byte down, and the four
     * bytes of the guide column taken out of each record header.
     *
     * Written out rather than checked in as a fixture, because a fixture is a
     * file nobody can see the shape of — and the point of the test is that the
     * *shape* of the header before the new column is unchanged.
     */
    private fun downgradeToVersion2(v3: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val header = StrokeCodec.MAGIC.length + 2 + 4
        out.write(v3, 0, header)
        var at = header
        // id(8) brush(4) color(4) seed(4) dabBase(4) clip(4) | guide(4) | erase(1)
        val beforeGuide = 8 + 4 + 4 + 4 + 4 + 4
        while (at < v3.size) {
            out.write(v3, at, beforeGuide)
            at += beforeGuide + 4
            out.write(v3, at, 1)
            at += 1
            val tag = v3[at].toInt()
            out.write(v3, at, 1)
            at += 1
            if (tag == 1) {
                out.write(v3, at, 16)
                at += 16
            }
            val samples = java.nio.ByteBuffer.wrap(v3, at, 4).int
            out.write(v3, at, 4)
            at += 4
            val bytes = StrokeCodec.ORIGIN_BYTES + samples * StrokeCodec.SAMPLE_BYTES
            out.write(v3, at, bytes)
            at += bytes
        }
        val bytes = out.toByteArray()
        bytes[StrokeCodec.MAGIC.length] = 2
        return bytes
    }

    @Test
    fun `a three hundred stroke page is under a megabyte on disk`() {
        val records = (0 until 300).map { i -> record(id = i.toLong(), samples = 247) }
        val bytes = StrokeCodec.encode(records)
        assertTrue(bytes.size < 1024 * 1024, "${bytes.size} bytes")
        assertTrue(bytes.size > 600 * 1024, "${bytes.size} bytes — the packing got smaller?")
        assertEquals(300, StrokeCodec.decode(bytes).size)
    }

    private fun record(
        id: Long = 1L,
        brush: Int = 0,
        color: Int = 0xFF101010.toInt(),
        erase: Boolean = false,
        seed: Int = 5,
        base: Int = 0,
        clip: Int = StrokeRecord.NO_CLIP,
        guide: Int = StrokeRecord.NO_GUIDE,
        bounds: Bounds = Bounds.of(1f, 2f, 30f, 40f),
        samples: Int = 4,
    ): StrokeRecord {
        val log = SampleLog()
        for (i in 0 until samples) {
            log.add(10f + i * 0.7f, 20f - i * 0.3f, 0.4f + i * 0.001f, 0.2f, 0.1f, i * 3.1f)
        }
        return StrokeRecord(
            id = id, brush = brush, colorArgb = color, erase = erase, seed = seed,
            dabBase = base, clip = clip, guide = guide, bounds = bounds,
            packed = log.pack(), sampleCount = samples,
        )
    }
}
