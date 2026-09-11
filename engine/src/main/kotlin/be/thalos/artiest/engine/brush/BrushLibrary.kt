package be.thalos.artiest.engine.brush

/**
 * The brushes you can pick from, as values rather than as code.
 *
 * ## Why this exists at all
 *
 * The app knew three brushes and it knew them *as Kotlin*: [BrushPreset] is an
 * enum whose `applyTo` is a function body, the store persisted the enum's
 * `name`, the toolbar had three hardcoded buttons and the catalogue three
 * hardcoded entries. Every one of those is right for three authored tools and
 * none of them can hold a fourth that came from a file.
 *
 * So this is not a list — it is **the moment a brush stops being a function and
 * becomes a value**. A built-in makes its brush by calling the enum and a
 * file-backed one by decoding its text, and nothing downstream is allowed to
 * know which it is holding. That is the whole of the abstraction, and it is
 * what makes "save the pencil I tuned" and "open a brush somebody sent me" the
 * same feature rather than two.
 *
 * See `docs/brush-shelf-plan.md`.
 */
class BrushLibrary(
    /**
     * Everything that is not shipped: what is on disk, in the order the caller
     * wants it listed. An entry whose id collides with a built-in is dropped
     * rather than allowed to shadow it — a file cannot redefine the pencil,
     * because the pencil is what `BrushPreset.TUNING` is allowed to move.
     */
    extra: List<BrushEntry> = emptyList(),
) {

    /** Built-ins first, then the rest. Keyed by [BrushEntry.id], which is unique. */
    val entries: List<BrushEntry> = BUILT_INS + extra.filter { it.id !in BUILT_IN_IDS }
        .distinctBy { it.id }

    private val byId: Map<String, BrushEntry> = entries.associateBy { it.id }

    /** The entry called [id], or null. An unknown id is a file that went away. */
    fun find(id: String?): BrushEntry? = if (id == null) null else byId[id]

    /**
     * The entry called [id], or the pen.
     *
     * **The fallback is the point.** A brush that has been deleted while it was
     * in the hand, an id from a build that had one more brush than this one, a
     * corrupted preference — all three arrive here, and all three should leave
     * with a working pen rather than an exception on the path that draws.
     */
    fun entryFor(id: String?): BrushEntry = find(id) ?: entries.first()

    /** Whether anything here can be deleted. False on a fresh install. */
    val hasSaved: Boolean get() = entries.any { it.origin != BrushOrigin.BUILT_IN }

    companion object {

        /** The three the app ships with, in the order the toolbar lists them. */
        val BUILT_INS: List<BrushEntry> = BrushPreset.entries.map { BrushEntry.of(it) }

        private val BUILT_IN_IDS: Set<String> = BUILT_INS.map { it.id }.toSet()

        /** Nothing but the shipped three. What a first run has. */
        val DEFAULT: BrushLibrary = BrushLibrary()

        /**
         * The id a stored value means, migrating the one old spelling.
         *
         * `BrushStore` used to persist the enum's `name` — `"PENCIL"` — and now
         * persists [BrushPreset.id] — `"pencil"`. Both are read here, once, and
         * the old one is recognised case-insensitively because that is the only
         * thing it can collide with: a saved brush's id is generated from its
         * name and a file called "PENCIL" would have been refused as a
         * duplicate anyway.
         *
         * Written down rather than left to a fallback because a silent one here
         * means *"the app forgot which brush I was holding"*, which is the
         * complaint `BrushStore` exists to prevent.
         */
        fun idOf(stored: String?): String? {
            if (stored.isNullOrEmpty()) return null
            if (stored in BUILT_IN_IDS) return stored
            BrushPreset.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) }
                ?.let { return it.id }
            return stored
        }
    }
}

/**
 * Where a brush came from, which decides two things that must not be guessed.
 *
 * - Whether [BrushPreset.TUNING] may overwrite it. **Only a built-in.** The
 *   whole point of saving a brush is that it stays put, so a tuning bump that
 *   re-solved the pencil must not touch a pencil somebody saved.
 * - Whether it can be deleted. **Never a built-in**, because the shelf would
 *   then have a state from which the app ships no brushes at all.
 */
enum class BrushOrigin {
    /** Shipped, as a [BrushPreset]. */
    BUILT_IN,

    /** Made here, by saving the brush in the hand. */
    SAVED,

    /** Came in from a file. Not reachable yet; the shelf is what it needs. */
    IMPORTED,
}

/**
 * One brush the user can pick: an identity, a label, an origin, and a way to
 * make a [Brush].
 *
 * **The two sources are deliberately private.** A built-in holds a
 * [BrushPreset] and a saved one holds its text, and every caller goes through
 * [create] — which is what lets the shelf, the toolbar and the store treat the
 * pencil and a brush somebody made this morning as the same kind of thing.
 *
 * [tags] is carried and read by nothing. Search is the right answer at thirty
 * brushes and premature at six, and the data is cheap where the UI is not —
 * so a file that carries tags keeps them across a round trip rather than losing
 * them the first time it is saved by a build that has no search yet.
 */
class BrushEntry(
    /** Stable across renames. For a built-in, [BrushPreset.id]. */
    val id: String,
    /** What the shelf calls it. */
    val label: String,
    val origin: BrushOrigin,
    /**
     * The [BrushPreset.TUNING] this was authored against, or 0.
     *
     * Zero for everything that is not a built-in, and that is what makes a
     * saved brush immune to a re-tune: the store compares what it saved against
     * this number, and 0 never changes.
     */
    val tuning: Int = 0,
    val tags: List<String> = emptyList(),
    private val preset: BrushPreset? = null,
    /** The brush as [BrushCodec] text, for everything that is not shipped. */
    val text: String? = null,
) {

    /** A fresh brush configured as this entry. Never fails; see [BrushLibrary.entryFor]. */
    fun create(): Brush = preset?.create()
        ?: BrushCodec.decode(text)
        ?: BrushPreset.PEN.create()

    /**
     * Make [brush] be this entry, overwriting everything.
     *
     * What picking a row does. The brush in the hand is a live object the
     * render thread reads, so it is *configured* rather than replaced — see
     * `InkSurfaceView.pen`, which is a `val` for that reason.
     */
    fun applyTo(brush: Brush) {
        adoptBrush(create(), brush)
    }

    /**
     * Re-attach this entry's sensor wiring to [brush], leaving every scalar
     * alone. See [BrushPreset.applyToShapeOnly], which this generalises.
     */
    fun applyShapeOnlyTo(brush: Brush) {
        copyWiringOnto(create(), brush)
    }

    /**
     * A short value that changes whenever anything about how this draws does.
     *
     * The key a rendered swatch is cached under. It is not [id] alone, because
     * saving over a brush keeps the id and changes the mark — and a shelf that
     * kept showing the old picture would be a shelf that lies about the one
     * thing it exists to show.
     */
    val stamp: String get() = "$id/" + (text?.hashCode() ?: tuning)

    /** Whether the shelf may delete this. Never a built-in. */
    val removable: Boolean get() = origin != BrushOrigin.BUILT_IN

    /**
     * Whether [brush] is still this entry, or has been moved since.
     *
     * What the shelf's modified mark reads. The erase lines are excluded on
     * purpose: the eraser is a *mode* the brush in the hand is in, not a
     * property of the brush that was picked, so a pencil that is currently
     * rubbing something out is still the pencil.
     */
    fun matches(brush: Brush): Boolean = comparable(brush) == comparable(create())

    private fun comparable(brush: Brush): List<String> =
        BrushCodec.encode(brush).split('\n').filterNot { it.startsWith("erase") }

    companion object {

        fun of(preset: BrushPreset): BrushEntry = BrushEntry(
            id = preset.id,
            label = preset.label,
            origin = BrushOrigin.BUILT_IN,
            tuning = BrushPreset.TUNING,
            preset = preset,
        )

        /** A brush that came from text: saved here, or imported from elsewhere. */
        fun fromText(
            id: String,
            label: String,
            text: String,
            origin: BrushOrigin = BrushOrigin.SAVED,
            tags: List<String> = emptyList(),
        ): BrushEntry =
            BrushEntry(id = id, label = label, origin = origin, tags = tags, text = text)

        /**
         * An id built from a name, as `Workspace.slug` builds one from a
         * workspace's: lowercase, words joined by hyphens, nothing else.
         *
         * Duplicated rather than shared because `:engine` has no dependencies
         * by construction and this is four lines.
         */
        fun slug(name: String): String {
            val out = StringBuilder()
            for (c in name.lowercase()) {
                when {
                    c.isLetterOrDigit() -> out.append(c)
                    out.isNotEmpty() && out.last() != '-' -> out.append('-')
                }
            }
            return out.toString().trim('-').take(MAX_ID).ifEmpty { "brush" }
        }

        /** Long enough for a sentence, short enough to be a file name. */
        const val MAX_ID = 48

        /** What the shelf will show without truncating it to nothing. */
        const val MAX_LABEL = 48
    }
}

/**
 * Copy [from]'s sensor wiring onto [to] without touching [to]'s numbers.
 *
 * Extracted from `BrushPreset.applyToShapeOnly` when a saved brush needed the
 * same treatment, and the rule it encodes is unchanged: a stored file is the
 * one place a *partial* brush comes from — an older build's save has no
 * `aspect.drive` line at all — and a pencil that loads without its tilt is a
 * pencil that has silently become a fat pen.
 *
 * Size and flow get the wiring back but keep their numbers, and the difference
 * matters: `sizeMax` and `flow` are what two sliders on the toolbar hold, so
 * copying the source's values over them would undo the user's last drag every
 * time the app started.
 */
internal fun copyWiringOnto(from: Brush, to: Brush) {
    for ((src, dst) in listOf(
        from.aspect to to.aspect,
        from.rotation to to.rotation,
        from.scatter to to.scatter,
        from.sizeJitter to to.sizeJitter,
    )) {
        if (dst.inputCount > 0) continue
        dst.min = src.min
        dst.max = src.max
        dst.combine = src.combine
        for (i in 0 until src.inputCount) dst.drive(src.sensorAt(i), src.curveAt(i))
    }
    for ((src, dst) in listOf(
        from.size to to.size,
        from.flowOption to to.flowOption,
    )) {
        if (dst.inputCount > 0) continue
        if (src.inputCount == 0) continue
        dst.combine = src.combine
        for (i in 0 until src.inputCount) dst.drive(src.sensorAt(i), src.curveAt(i))
    }
}

/**
 * Every scalar and every sensor wiring of [from], onto [to].
 *
 * The other half of [copyWiringOnto], and the two are deliberately separate:
 * this one is *"be that brush"* and is what picking a row from the shelf does,
 * while that one is *"you are already mostly that brush, put back what is
 * missing"* and is what restoring a saved file does. Using this one for a
 * restore would throw away the sliders the user last dragged; using that one to
 * pick a brush would leave the previous brush's numbers in place, and the shelf
 * would appear not to work.
 *
 * [to] is configured rather than replaced because the brush in the hand is a
 * live object the render thread reads.
 */
fun adoptBrush(from: Brush, to: Brush) {
    to.sizeMin = from.sizeMin
    to.sizeMax = from.sizeMax
    to.sizeCurve = from.sizeCurve
    to.spacing = from.spacing
    to.isotropicSpacing = from.isotropicSpacing
    to.hardness = from.hardness
    to.opacity = from.opacity
    to.flow = from.flow
    to.stabilization = from.stabilization
    to.antiAlias = from.antiAlias
    to.onsetMillis = from.onsetMillis
    to.onsetPressure = from.onsetPressure
    to.grain = from.grain
    to.burnish = from.burnish
    to.erase = from.erase
    to.eraseSizeMax = from.eraseSizeMax
    for ((src, dst) in listOf(
        from.size to to.size,
        from.flowOption to to.flowOption,
        from.aspect to to.aspect,
        from.rotation to to.rotation,
        from.scatter to to.scatter,
        from.sizeJitter to to.sizeJitter,
    )) {
        // Cleared first, and then driven once per input: a brush that kept the
        // old one's sensors would be the two brushes at once, which reads as
        // the shelf half working rather than as a bug.
        dst.clearInputs()
        dst.min = src.min
        dst.max = src.max
        dst.combine = src.combine
        for (i in 0 until src.inputCount) dst.drive(src.sensorAt(i), src.curveAt(i))
    }
}
