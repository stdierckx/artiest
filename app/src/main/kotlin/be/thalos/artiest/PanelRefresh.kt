package be.thalos.artiest

import kotlin.math.abs

/**
 * Why the refresh buttons cannot make this panel run at 90 Hz, and what is left
 * that they can honestly do.
 *
 * [RefreshPolicy] decides what the *window* asks for, and it has always asked
 * correctly: `dumpsys display` shows
 * `PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE -> 90.0` with `baseModeId` on
 * the 90 Hz mode, and that vote survives everything, including a screen blank.
 * It loses to `PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE`, which caps the
 * render range at 60 from a priority band higher up. That is the whole reason
 * `max` looked broken: it was making the only request an app may make, and the
 * request was never the binding constraint.
 *
 * **The binding constraint is out of reach, and this was established by trying.**
 * It is the `peak_refresh_rate` value in `Settings.System`. Writing it needs
 * more than `WRITE_SETTINGS`: that key is not in `Settings.System.PUBLIC_SETTINGS`,
 * so `SettingsProvider` refuses with
 * `warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk`. Granting
 * `WRITE_SECURE_SETTINGS` over adb does not help either — it was granted here
 * and the write still threw, because that check exempts only the system, shell
 * and root UIDs and consults no permission at all. Wacom has also removed the
 * refresh control from the Settings app, so there is no on-device route of any
 * kind. `tools/panel-90hz.sh`, over adb from a PC, is the only thing that works.
 *
 * So the button does the two things that are genuinely the app's to do: it holds
 * the screen awake, because the cap is recomputed as 60 on every screen-on and a
 * rate set from a PC survives only until the tablet next blanks; and it reports
 * the rate the panel is actually holding, which is the check worth having before
 * a take. It cannot set the rate, and it says so rather than pretending.
 */
object PanelRefresh {

    /**
     * Whether a reported rate counts as having reached [targetHz].
     *
     * The slop matters: this panel's 60 Hz mode reports 60.000004, and an exact
     * comparison would call a panel that is exactly where it was asked to be a
     * failure.
     */
    fun settled(actualHz: Float, targetHz: Float): Boolean =
        abs(actualHz - targetHz) <= RefreshPolicy.HZ_SLOP
}
