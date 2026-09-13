package be.thalos.artiest.ui

import android.content.Context
import be.thalos.artiest.ink.Tips
import java.io.File

/**
 * The brushes a fresh install has, copied out of the APK the first time it runs.
 *
 * ## Why copy rather than read from the assets every time
 *
 * Because then they are **ordinary brushes**. A starter set that lived in the
 * assets would need a third origin beside built-in and saved, and every question
 * the shelf asks — can I tune it, can I save over it, can I delete it, does a
 * tuning bump move it — would need a third answer. Copied out, a charcoal
 * pencil from Krita's bundle is exactly as much yours as one you made this
 * morning: rename it, retune it, throw it away.
 *
 * The cost is that throwing one away is permanent, and that is the right cost.
 * A starter set that grew back would be a shelf that will not let you tidy it.
 *
 * ## Why a version and not "is the directory empty"
 *
 * An empty directory is what you have after deleting all sixteen, and seeding
 * on that would put them back. [SEED] is a number this file owns: it goes up
 * when the shipped set changes, and each value seeds exactly once per device.
 *
 * ## The licence, which is the reason this is allowed to exist at all
 *
 * Everything in `assets/brushes` is converted from Krita's own default resource
 * bundle, whose `meta.xml` declares `CC-0` alongside its author line. Nothing
 * is copied from Krita's *source*, which is GPL — a preset and a program are
 * two objects with two licences, and `docs/brushes-plan.md` exists largely to
 * keep them apart. `tools/brush-picks.txt` records which sixteen, and
 * `NOTICE` says so where somebody looking for it would look.
 */
object StarterBrushes {

    /**
     * Bumped when the shipped set changes. Each value is seeded once per device.
     *
     * 1: sixteen brushes and six tips from Krita's default bundle — four
     * pencils, two charcoals, four inks, a chisel marker, two bristles, a
     * chalk, an airbrush and a grass stamp. See `docs/brushes-plan.md`, Wb8.
     */
    const val SEED = 1

    /**
     * Copy the starter set out, unless this device has already had this one.
     *
     * Returns how many brushes were written, which is zero on every run after
     * the first. Nothing here throws: a device with no room left gets a shelf
     * of the three built-ins, which is what it had before this existed, and not
     * a crash on the first frame.
     */
    fun seed(context: Context): Int = runCatching {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SEED, 0) >= SEED) return 0
        val brushes = copy(context, "brushes", File(context.filesDir, "brushes"), ".brush")
        copy(context, "tips", Tips.directoryIn(context.filesDir), ".png")
        // Written last and only on success, so a copy that died half way is
        // retried next time rather than remembered as done.
        prefs.edit().putInt(KEY_SEED, SEED).apply()
        brushes
    }.getOrDefault(0)

    /**
     * One asset folder into one directory, skipping what is already there.
     *
     * Skipping rather than overwriting, because a second seed — a later [SEED]
     * adding two brushes — must not undo the tuning somebody did to the first
     * fourteen. A file that exists wins, always.
     */
    private fun copy(context: Context, from: String, to: File, suffix: String): Int {
        val names = context.assets.list(from) ?: return 0
        to.mkdirs()
        var written = 0
        for (name in names) {
            if (!name.endsWith(suffix)) continue
            val target = File(to, name)
            if (target.exists()) continue
            runCatching {
                context.assets.open("$from/$name").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
                written++
            }
        }
        return written
    }

    private const val FILE = "artiest.brushes"
    private const val KEY_SEED = "starter_seed"
}
