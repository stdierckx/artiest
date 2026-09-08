package be.thalos.artiest

/**
 * What the app asks the display for, as three explicit choices instead of one
 * unconditional request.
 *
 * `:spike` calls `requestHighestRefreshRate()` in `onCreate` and never again,
 * which made every Phase 0 measurement "the app asked for 90 and the vendor cap
 * decided". W16 has to compare 60 against 90 and cannot do it from that
 * position: deleting the `adb` refresh override does not produce a 60 Hz
 * control either, because both Phase 0 runs were already asking for 90. The
 * only way to construct a genuine control is to be able to *not* ask, and to
 * ask for 60 on purpose, from the running app.
 *
 * The vendor still has the last word. Wacom's
 * `/vendor/etc/displayconfig/display_id_0.xml` pins peak to 61 with
 * `mAlwaysRespectAppRequest=false`, so 90 Hz needs
 * `adb shell settings put system peak_refresh_rate 90.0` as well. That is why
 * the toolbar shows the rate the display actually reports next to the one that
 * was requested: on this device the two disagree by default, and a toggle whose
 * effect cannot be seen is a toggle nobody can trust.
 */
enum class RefreshPolicy {
    /** The highest refresh rate the panel offers at its current resolution. */
    HIGHEST,

    /** No preference: whatever the system would have done on its own. */
    UNSPECIFIED,

    /** 60 Hz, or the closest mode at or below it. W16's control. */
    SIXTY,
    ;

    companion object {

        /** `WindowManager.LayoutParams.preferredDisplayModeId`'s "no preference". */
        const val NO_PREFERENCE = 0

        private const val SIXTY_HZ = 60f

        /**
         * Which `modeId` to put in `preferredDisplayModeId`, or
         * [NO_PREFERENCE].
         *
         * **Modes are filtered to the current resolution first, and that is the
         * whole reason this is a function rather than a `maxByOrNull`.**
         * `:spike` picks `supportedModes.maxByOrNull { it.refreshRate }`, and a
         * `Display.Mode` carries a physical size as well as a rate — so on a
         * panel that offers a faster mode at a smaller resolution, the spike's
         * line asks the compositor to *change the panel resolution* in order to
         * gain refresh rate. Nothing about the call says so, the app is simply
         * rescaled, and on a drawing app that is the pen and the ink landing in
         * different places. This device happens to offer 60 and 90 at the same
         * 1440x2200, so the bug is invisible here; it is still wrong, and
         * `RefreshPolicyTest` drives a panel where it bites.
         *
         * [currentModeId] is what defines "the current resolution". If it is not
         * in [modes] — a mode list that changed under us, or an empty one — the
         * answer is [NO_PREFERENCE], which is the choice that cannot make
         * anything worse.
         */
        fun chooseModeId(modes: List<ModeInfo>, currentModeId: Int, policy: RefreshPolicy): Int {
            if (policy == UNSPECIFIED) return NO_PREFERENCE
            val current = modes.firstOrNull { it.modeId == currentModeId } ?: return NO_PREFERENCE
            val sameSize = modes.filter {
                it.widthPx == current.widthPx && it.heightPx == current.heightPx
            }
            return when (policy) {
                HIGHEST -> sameSize.maxByOrNull { it.refreshHz }?.modeId ?: NO_PREFERENCE
                // At or below 60, and the highest of those: a panel whose only
                // modes are 90 and 120 has no 60 Hz control to offer, and
                // asking for 90 while the readout says "60" would be worse than
                // admitting there is nothing to ask for.
                SIXTY -> sameSize.filter { it.refreshHz <= SIXTY_HZ + HZ_SLOP }
                    .maxByOrNull { it.refreshHz }?.modeId ?: NO_PREFERENCE
                UNSPECIFIED -> NO_PREFERENCE
            }
        }

        /**
         * Reported refresh rates are floats and rarely round: this panel's
         * "60 Hz" mode reports 60.000004 in `dumpsys`, and a strict `<= 60f`
         * would reject it and leave W16 with no control at all. Half a hertz is
         * far below the gap between any two real modes.
         */
        const val HZ_SLOP = 0.5f
    }
}
