package be.thalos.artiest.io

import android.net.Uri

/**
 * What an export did, in a form the UI has to look at.
 *
 * A sealed result rather than a nullable `Uri`, and that is the whole of the
 * fix to the bug this type was written against. `:spike`'s `SessionExporter`
 * returns `Uri?`, swallows a null `openOutputStream` with `?.use`, and then
 * unconditionally flips `IS_PENDING` to 0 — so the failure path *publishes a
 * zero-byte file* and hands back a perfectly good `Uri` for it. The caller sees
 * success, the gallery shows an entry, and the entry is empty. Nothing about
 * that is visible until someone opens the file.
 *
 * So: every way out of [PngExporter] is one of these, [Failed] names the stage
 * so a bug report says where rather than that, and there is no `Uri` on the
 * failure branch because a failed export leaves nothing behind — the row is
 * deleted before this is returned.
 */
sealed interface ExportResult {

    /**
     * The PNG is on the tablet and published.
     *
     * [notYetStamped] is the number the plan did not have a place for and the
     * export needs one for: commits the render thread had still not applied
     * when the pixels were copied. See [PngExporter.export] — it waits for
     * these, and this field is what it says when the wait ran out. Zero on
     * every ordinary export; non-zero means the PNG is missing that many
     * strokes that the document nevertheless counts, which is exactly the
     * disagreement between history and pixels that W10 spent its time on.
     */
    data class Written(
        val uri: Uri,
        val bytes: Long,
        val strokes: Int,
        val notYetStamped: Int,
        val waitMs: Long,
        val copyMs: Long,
        val encodeMs: Long,
        val totalMs: Long,
    ) : ExportResult

    /** Nothing was written, nothing was published, and no row was left behind. */
    data class Failed(val stage: ExportStage, val detail: String) : ExportResult
}

/**
 * Where an export can fail. One value per irreversible step, because "export
 * failed" on a toast is not something anyone can act on.
 *
 * [OPEN] is the one that matters: it is `:spike`'s silent success, given a name
 * and a branch.
 */
enum class ExportStage {
    /** The document was closed under us. Nothing to export. */
    LAYER_CLOSED,

    /** The transient full-size bitmap could not be allocated. */
    ALLOCATE,

    /** `ContentResolver.insert` returned null — MediaStore refused the row. */
    INSERT,

    /** `openOutputStream` returned null or threw. `:spike` ignores this one. */
    OPEN,

    /** `Bitmap.compress` returned false, or the write threw. */
    ENCODE,

    /** The bytes are written but `IS_PENDING` could not be cleared. */
    PUBLISH,
}
