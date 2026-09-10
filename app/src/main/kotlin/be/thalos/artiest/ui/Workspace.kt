package be.thalos.artiest.ui

/**
 * A whole program, named.
 *
 * **This is the thing `DockLayout` is not.** A `DockLayout` is an arrangement:
 * which control is in which cell of which surface. A workspace is an
 * arrangement *plus* what you are offered when you go looking for a control,
 * *plus* what the tools are set to when you arrive. That is the difference
 * between moving your buttons around and switching from sketching to inking,
 * and it is why the two are separate types with separate names.
 *
 * It is also the unit that gets shared. One file, one workspace, and a name and
 * an author on it because a file somebody sends you should say who made it and
 * what it is for before you open it.
 *
 * ## Why the revision, and why it is not a timestamp
 *
 * [revision] goes up when the file is saved. It is what a merge, a sync or a
 * "you already have this one" check reads, and it is an integer rather than a
 * clock because two tablets in different time zones are not a problem anybody
 * should have to think about to know which of two files is newer.
 */
data class Workspace(
    /** A slug. Stable, it is the file name, and it is not [name]. */
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val revision: Int = 1,
    val layout: DockLayout,
    val filter: CatalogueFilter = CatalogueFilter.EVERYTHING,
    val defaults: WorkspaceDefaults = WorkspaceDefaults(),
) {

    /** The same workspace, one revision on. What every save goes through. */
    fun revised(layout: DockLayout = this.layout): Workspace =
        copy(layout = layout, revision = revision + 1)

    /** The same workspace under a new name, keeping its id. */
    fun renamed(name: String): Workspace = copy(name = name, revision = revision + 1)

    companion object {
        /**
         * A file name from a display name.
         *
         * Lower case, letters, digits and dashes, and nothing else — the same
         * shape as a `ToolItem` id and for the same reason: this ends up in a
         * path, in a preference and in the compact layout string, and a name a
         * user typed contains whatever their keyboard had on it.
         *
         * Two details that are not obvious and both come from real names.
         * **Accents are folded rather than replaced**, so *Stéphane* is
         * `stephane` and not `st-phane`; a slug is never shown, but a slug full
         * of dashes where the letters were is a file nobody can find. And an
         * **apostrophe is dropped rather than separating**, because *Anna's
         * Inker* is two words and not three.
         */
        fun slug(name: String): String {
            val folded = java.text.Normalizer
                .normalize(name, java.text.Normalizer.Form.NFD)
                .replace(COMBINING, "")
            val out = StringBuilder(folded.length)
            for (c in folded.lowercase()) {
                when {
                    c in 'a'..'z' || c in '0'..'9' -> out.append(c)
                    c in APOSTROPHES -> Unit
                    out.isNotEmpty() && out.last() != '-' -> out.append('-')
                }
            }
            val trimmed = out.toString().trim('-').take(MAX_SLUG).trim('-')
            return trimmed.ifEmpty { FALLBACK_SLUG }
        }

        /** The marks NFD splits an accented letter into. */
        private val COMBINING = "\\p{Mn}+".toRegex()

        /** Straight and curly. A keyboard gives you whichever it feels like. */
        private const val APOSTROPHES = "'’ʼ"

        /** What a workspace is called when the name it was given had nothing in it. */
        const val FALLBACK_SLUG = "workspace"

        /** Long enough for a sentence, short enough for a file name. */
        const val MAX_SLUG = 48
    }
}

/**
 * What this workspace offers you when you go looking for a control.
 *
 * ## The rule that makes switching safe
 *
 * **The chooser filters. The layout never does.** A tool that is already placed
 * but outside the filter keeps working and keeps its cell — a filter is about
 * what you are *offered*, never about what is taken away. Without that rule,
 * switching to *Sketcher* would silently strip the perspective ruler off
 * somebody's bar, and switching back would not bring it home; with it,
 * switching is a change of what is in the menu and nothing else, which is what
 * makes it something people will actually do.
 *
 * ## Three fields and not one
 *
 * [groups] is the broad stroke — "the drawing tools and the editing ones" — and
 * null means everything, which is what *Everything* is. [hide] takes a named
 * tool out of a group you wanted. [show] puts a named tool back in, and it wins
 * over both of the others, because it is where the search box's *"add this to
 * my workspace"* writes. That escape hatch is the mitigation for the whole
 * feature's worst failure — hiding a tool is a promise it was not needed — and
 * a hatch that some other field could override would not be one.
 */
data class CatalogueFilter(
    /** Null means every group. */
    val groups: Set<ToolGroup>? = null,
    val hide: Set<String> = emptySet(),
    val show: Set<String> = emptySet(),
) {

    operator fun contains(item: ToolItem): Boolean {
        if (item.id in show) return true
        if (item.id in hide) return false
        return groups == null || item.group in groups
    }

    /** Everything this filter offers, in catalogue order. */
    fun offered(): List<ToolItem> = ToolItem.entries.filter { it in this }

    /** True when nothing is filtered out at all. */
    val isEverything: Boolean
        get() = groups == null && hide.isEmpty()

    /**
     * The same filter with [item] offered.
     *
     * Where the search box writes. It adds to [show] rather than removing from
     * [hide] so that the act is recorded as a decision — "I want this one" —
     * and survives a later change to the groups.
     */
    fun offering(item: ToolItem): CatalogueFilter =
        if (item in this) this else copy(show = show + item.id)

    /** The same filter with [item] no longer offered. */
    fun hiding(item: ToolItem): CatalogueFilter =
        copy(hide = hide + item.id, show = show - item.id)

    companion object {
        val EVERYTHING = CatalogueFilter()

        /** Only these groups, and nothing named either way. */
        fun of(vararg groups: ToolGroup) = CatalogueFilter(groups = groups.toSet())
    }
}

/**
 * What the tools are set to when this workspace opens.
 *
 * Empty for now in every field, and that is not a placeholder: each one lands
 * **in the commit that makes it do something**, which is `ToolItem`'s rule 1
 * applied to a different list. A workspace that promised a brush this build
 * cannot select would be a workspace that lies on the first tap.
 *
 * The fields that exist are the ones whose subsystems exist. Page setup,
 * guides, onion skin and the rest arrive with the features that own them — see
 * `docs/brush-shelf-plan.md`, `docs/guides-plan.md` and
 * `docs/animation-plan.md`.
 */
data class WorkspaceDefaults(
    /** A `BrushPreset` id, or null to leave whatever was in the hand. */
    val brush: String? = null,
    /** Which brushes are on the shelf, in order. Empty means the shelf's own default. */
    val shelf: List<String> = emptyList(),
    /** Stabilisation, nought to one, or null to leave it alone. */
    val stabilisation: Float? = null,
) {
    val isEmpty: Boolean get() = brush == null && shelf.isEmpty() && stabilisation == null
}
