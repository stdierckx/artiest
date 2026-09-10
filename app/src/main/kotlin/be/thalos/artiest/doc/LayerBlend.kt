package be.thalos.artiest.doc

import android.graphics.BlendMode

/**
 * How a sheet mixes with what is under it.
 *
 * ## Seven, and why not thirty
 *
 * Skia offers about thirty separable blend modes and a painting app that
 * exposed them all would be a compositing app. These seven are the ones that
 * answer a question a person drawing actually has: *darken this without
 * repainting it* (Multiply), *lighten it* (Screen), *do both from the middle*
 * (Overlay), *keep only the darker/lighter of the two* (Darken, Lighten), and
 * *show me what changed* (Difference). Colour, Hue, Saturation and the rest are
 * separable-in-name-only and belong to a phase that has a colour model to talk
 * about them with.
 *
 * The two that carry most of the value for drawing are Multiply and Screen: a
 * shading sheet over line art, and a light sheet over a dark ground. The rest
 * are cheap to keep and easy to remove.
 *
 * ## Why this enum exists rather than `BlendMode` itself
 *
 * Three reasons, and the first is the one that will matter later. **[id] is a
 * persisted name.** Nothing about a document is written to disk yet — that is
 * Phase 4's `.ora` — but this is the field it will have to write, and a format
 * that stored `BlendMode.ordinal` would be a format that broke when the
 * platform inserted a mode.
 *
 * Second, [label] is what the panel shows, and the platform's names are not it:
 * "SRC_OVER" is not a word anybody drawing would use for "normal".
 *
 * Third, the set is a decision. An enum with seven entries says so; a
 * `BlendMode` field says "any of the thirty", and the panel would then be a
 * list somebody has to trim every time it is looked at.
 */
enum class LayerBlend(
    /** Stable across renames and releases. See the class header. */
    val id: String,
    /** What the panel calls it. */
    val label: String,
    /**
     * The platform mode, or null for the default.
     *
     * Null rather than `BlendMode.SRC_OVER` so that [NORMAL] can leave the
     * `Paint` alone entirely: a `Paint` carrying an explicit blend mode takes
     * Skia off its fastest blitter even when the mode is the one it would have
     * used, and the normal case is every sheet in almost every drawing.
     */
    val mode: BlendMode?,
) {
    NORMAL("normal", "Normal", null),
    MULTIPLY("multiply", "Multiply", BlendMode.MULTIPLY),
    SCREEN("screen", "Screen", BlendMode.SCREEN),
    OVERLAY("overlay", "Overlay", BlendMode.OVERLAY),
    DARKEN("darken", "Darken", BlendMode.DARKEN),
    LIGHTEN("lighten", "Lighten", BlendMode.LIGHTEN),
    DIFFERENCE("difference", "Difference", BlendMode.DIFFERENCE),
    ;

    /** The next one along, for a control that cycles rather than opening a list. */
    val next: LayerBlend get() = entries[(ordinal + 1) % entries.size]

    companion object {
        private val BY_ID: Map<String, LayerBlend> = entries.associateBy { it.id }

        /** Unknown ids decode to [NORMAL], which is the safe way to be wrong. */
        fun byId(id: String?): LayerBlend = BY_ID[id] ?: NORMAL
    }
}
