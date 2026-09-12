package be.thalos.artiest.ui

/**
 * One item, the cell its top-left corner sits in, and how many cells it takes.
 *
 * This replaces `Placement`, which carried a slot, a span and a depth — three
 * numbers that only meant anything once you knew which way the bar ran. The
 * four here mean the same thing on every shape, and the translation is worth
 * writing down because every bug in the migration is one of these inverted:
 *
 * | on a horizontal bar | on a vertical bar |
 * |---|---|
 * | `slot == x` | `slot == y` |
 * | `span == w` | `span == h` |
 * | `depth == h` | `depth == w` |
 *
 * **[arg] is what makes two of the same item different.** Rule 3 of `ToolItem`
 * says an item lives in exactly one place, and that is still true — of an
 * *item*. A brush button is `BRUSH` plus the id of the brush it loads, and two
 * of them side by side are two different controls that happen to share a
 * catalogue entry. Null for everything else, and everything else therefore
 * behaves exactly as it did.
 *
 * It is deliberately a bare `String` rather than a type. The layout has no idea
 * what a brush is and must not acquire one: it carries the argument and hands
 * it back to whoever knows, which is the same discipline that keeps this file
 * free of Compose.
 *
 * **The size is carried rather than looked up**, for the reason the span always
 * was: a panel can be resized, and once the user has chosen a size it is a
 * property of this placement and not of the catalogue. It also means everything
 * below this line — a button, a slider, a whole colour wheel — is one
 * rectangle, and the layout has no idea which is which.
 */
data class CellPlacement(
    val item: ToolItem,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** What this instance of [item] is for, or null. See the class KDoc. */
    val arg: String? = null,
) {

    init {
        require(w >= 1 && h >= 1) { "${item.id} is ${w}x$h cells" }
    }

    /** Exclusive. */
    val right: Int get() = x + w

    /** Exclusive. */
    val bottom: Int get() = y + h

    val cell: Cell get() = Cell(x, y)

    val rect: CellRect get() = CellRect(x, y, w, h)

    /** Whether this overhangs its shape rather than fitting in it. See [ToolItem.hangs]. */
    val hangs: Boolean get() = item.hangs

    fun covers(cx: Int, cy: Int): Boolean = cx in x until right && cy in y until bottom

    fun covers(cell: Cell): Boolean = covers(cell.x, cell.y)

    fun overlaps(other: CellPlacement): Boolean =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom

    /** The same item and size, moved to [cell]. */
    fun movedTo(cell: Cell): CellPlacement = copy(x = cell.x, y = cell.y)

    /** The pair that identifies this control: two brush buttons differ here. */
    val identity: Pair<ToolItem, String?> get() = item to arg

    override fun toString(): String =
        "${item.id}${arg?.let { "~$it" } ?: ""}@$x,$y(${w}x$h)"
}
