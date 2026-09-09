#!/usr/bin/env bash
# Put the MovinkPad's panel at 90 Hz and prove it got there.
#
# Why this exists
# ---------------
# W16 compares 60 Hz against 90 Hz, and the latency film is only meaningful if
# the panel was actually holding the rate the analysis is told to assume. Run C
# was filmed at 60 Hz while everyone believed it was 90, and nobody noticed for
# a day; `latency-from-video.py` now cross-checks the camera rate and would
# catch it, but catching it after the take still costs the take.
#
# What is actually wrong with this device
# ---------------------------------------
# The app is not the problem, and the in-app max/auto/60 buttons are not the
# lever. `dumpsys display` shows the app's vote arriving correctly and surviving
# everything:
#
#     PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE -> appRequestBaseModeRefreshRate: 90.0
#     mDisplayModeSpecs={baseModeId=2 ...}          # id 2 is the 90 Hz mode
#
# What blocks it is a *system* vote, one priority band above anything an app can
# reach:
#
#     PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE -> render: (0.0 60.0)
#
# and that vote is recomputed as 60 on **every screen-on**, even though the
# stored setting still reads 90.0. Caught in the act in logcat:
#
#     RefreshRateSelector: Previous: appRequestRanges={render=[0.00 Hz, 90.00 Hz]}
#     RefreshRateSelector: Current:  appRequestRanges={render=[0.00 Hz, 60.00 Hz]}
#
# So a plain `settings put system peak_refresh_rate 90.0` is not enough twice
# over: writing the value it already holds fires no observer at all, and even a
# successful write is undone by the next screen blank.
#
# Hence the two things this script does that the one-liner does not:
#   1. It *changes* the value (60 then 90) so the settings observer fires.
#   2. It holds the screen on for the session, because a blank undoes the lot.
# And then it checks, because a knob whose effect you cannot see is a knob
# nobody should trust.
#
# `--release` gives the tablet its normal power behaviour back. The refresh
# setting is left alone: it is undone by the next screen blank anyway.
set -u

TARGET_HZ=${TARGET_HZ:-90}
NUDGE_HZ=60.0
TIMEOUT=10

active_hz() {
    adb shell dumpsys SurfaceFlinger 2>/dev/null |
        grep -oE 'activeMode=[0-9.]+ Hz' | head -1 |
        grep -oE '[0-9.]+' | head -1
}

if [ "${1:-}" = "--release" ]; then
    adb shell svc power stayon false
    echo "screen timeout restored; the panel returns to 60 Hz on the next blank"
    exit 0
fi

if ! adb shell true >/dev/null 2>&1; then
    echo "no device: check the cable and 'adb devices'" >&2
    exit 1
fi

# A blank screen undoes everything below, so stop it blanking first.
adb shell svc power stayon true >/dev/null

# The observer fires on a *change*, so pass through another value first.
adb shell settings put system peak_refresh_rate "$NUDGE_HZ" >/dev/null
sleep 1
adb shell settings put system peak_refresh_rate "${TARGET_HZ}.0" >/dev/null
# min must not pin us anywhere: a min of 90 reads back as a 60 clamp here.
adb shell settings put system min_refresh_rate 0.0 >/dev/null

for _ in $(seq "$TIMEOUT"); do
    sleep 1
    hz=$(active_hz)
    case "$hz" in
        "$TARGET_HZ"|"$TARGET_HZ".*) 
            echo "panel at ${hz} Hz  (screen held on for this session)"
            echo "run '$0 --release' when you are done filming"
            exit 0
            ;;
    esac
done

echo "FAILED: panel is at ${hz:-unknown} Hz, wanted ${TARGET_HZ}" >&2
echo "do not film: the take would be at the wrong rate" >&2
adb shell dumpsys display | grep -E 'PRIORITY_|mDisplayModeSpecs=' >&2
exit 1
