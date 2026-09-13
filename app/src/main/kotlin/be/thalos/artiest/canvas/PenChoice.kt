package be.thalos.artiest.canvas

import be.thalos.artiest.engine.brush.Brush

/**
 * Which brush a stroke is made with, and how it takes ink away if it does.
 *
 * ## Why this is its own file
 *
 * Because it is the whole of Us2 in three lines, and because those three lines
 * are the only part of [InkSurfaceView]'s stroke setup that is a *decision*
 * rather than plumbing. Everything around it — copying into the live brush,
 * re-beginning the builder when the barrel turns out to have been held — needs
 * a view, a render thread and a surface. This needs two brushes and a boolean,
 * so it runs in a plain JVM test and `PenChoiceTest` is the record of what the
 * rule actually is.
 *
 * ## The rule
 *
 * 1. **The brush in the hand erases?** Then it erases, and the barrel has
 *    nothing to add. Turning an eraser over is not a gesture with a meaning.
 * 2. **The barrel is held?** Then this stroke takes ink away, with the rubber
 *    the user named if they named one and with the drawing brush's own shape if
 *    they did not. A pencil rubs out with the pencil's tilt, which is the
 *    default and the nicer one.
 * 3. **Otherwise** it draws.
 *
 * There used to be a fourth input, a toolbar toggle, and this is what replaced
 * it. See `ToolItem.HARD_ERASER`.
 */
internal object PenChoice {

    /**
     * The brush to copy from, whether this stroke erases, and whether the
     * eraser is one it borrowed.
     *
     * [borrowed] is the bit that decides how *hard* it rubs out. A drawing
     * brush pressed into service has translucency that is about laying ink
     * down — the pencil's 0.02 flow floor, its grain, its 0.90 ceiling — and
     * inheriting all of it made a full-pressure wipe remove about a quarter of
     * what was under it. That is the "eraser is too soft" report, and
     * `InkSurfaceView.compositeAlpha` answers it by forcing a borrowed rubber
     * to full strength.
     *
     * A brush that *is* an eraser keeps its own numbers, because for the soft
     * eraser they are the tool: one sweep fades a passage and two fade it
     * further. Forcing those to 1 would leave two erasers that differ only at
     * the rim.
     */
    data class Choice(val from: Brush, val erase: Boolean, val borrowed: Boolean)

    fun of(ink: Brush, rubber: Brush?, barrel: Boolean): Choice {
        if (ink.erase) return Choice(ink, erase = true, borrowed = false)
        if (!barrel) return Choice(ink, erase = false, borrowed = false)
        val from = rubber ?: ink
        return Choice(from, erase = true, borrowed = !from.erase)
    }
}
