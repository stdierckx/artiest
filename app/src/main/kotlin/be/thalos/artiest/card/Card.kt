package be.thalos.artiest.card

/**
 * One card in your deck: a drawing, a title, a note in your own words, and
 * tags.
 *
 * Lr7, and it is the user's *"knowledge base: the artist can create its own
 * knowledgebase with his own examples on how to draw stuff"* — with one
 * decision made for them, which is that **you author it by drawing**. A
 * beginner will not keep written notes. They are already drawing, so the card
 * is the drawing plus one sentence, and nothing else is offered.
 *
 * `docs/learner-plan.md` trap 2 is the rule this type exists to enforce: the
 * moment a card wants rich text, folders, links and search across notes, it is
 * a different product and the drawing program has stopped being the point.
 * **Title, one note, tags, one drawing.** Four fields, and the type says so.
 *
 * A **tutorial** is a card somebody else made. Same format, same deck, same
 * *Practice this* button — which is why the format is one file and not a row in
 * a database.
 */
data class Card(
    /** The file name stem. A slug, and the only thing here that becomes a path. */
    val id: String,
    val title: String = "",
    /** One sentence, in the user's own words. Not a document. */
    val note: String = "",
    val tags: List<String> = emptyList(),
    val addedMs: Long = 0L,
    /**
     * The project this was kept from, or empty.
     *
     * A note to the user and not a link: drawings get deleted and renamed, and
     * a card that broke when its drawing did would be a card nobody trusted.
     * What it is for is *"where did this come from"*, answered in the card and
     * not by following anything.
     */
    val fromProject: String = "",
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)
