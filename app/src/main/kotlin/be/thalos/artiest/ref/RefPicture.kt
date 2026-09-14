package be.thalos.artiest.ref

/**
 * One picture in the reference library: what it is, not what it looks like.
 *
 * Lr3. The pixels live in a file beside this and are loaded when something
 * actually shows them — a library of forty photographs is forty full-size
 * bitmaps if the list carries them, which is a hundred megabytes to open a
 * panel with.
 *
 * [tags] are the whole of the organising. A **set** in `docs/learner-plan.md`
 * is a tag: *hands*, *bikes*, *this drawing*. A picture may be in several and
 * most are in none, which is what a flat list with words on it gives you and a
 * folder tree does not.
 */
data class RefPicture(
    /**
     * The file name stem, and the only thing that is ever a path.
     *
     * A slug in [RefFiles.SLUG]'s sense: letters, digits and dashes, so nothing
     * that reaches a `File` can carry a separator or a `..`. Allocated from the
     * clock, so the natural sort is oldest first and two pictures added in the
     * same millisecond still differ.
     */
    val id: String,
    /** What the user calls it. Never a file path, and may be empty. */
    val label: String = "",
    val tags: List<String> = emptyList(),
    /** `System.currentTimeMillis()` when it was added. Newest first in the list. */
    val addedMs: Long = 0L,
    /** What it is, after the downscale. Both zero if it was never recorded. */
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    /** What the stored file takes on disk. See `RefFiles.bytes`. */
    val bytes: Long = 0L,
)
