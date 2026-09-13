#!/usr/bin/env bash
# Two fingers on the tablet, from here.
#
# `adb shell input` has one pointer, and every canvas gesture this app has —
# pan, zoom, rotate — takes two. That gap is not academic: a pinch that moved
# the page and left the guides nailed to the glass shipped, was reported from
# the tablet, and could not have been caught by any test that existed, because
# nothing in the tree could perform a pinch.
#
#   tools/pinch.sh                  # spread about the middle of the screen: zoom in
#   tools/pinch.sh 1100 720 520 180 # squeeze: zoom out
#   tools/pinch.sh 1100 720 300 300 24 16 40   # hold the span, twist 40 degrees
#
# Arguments are <cx> <cy> <from> <to> [steps] [ms-per-step] [degrees], in
# display pixels with the screen the way you are looking at it. The two
# contacts sit either side of (cx, cy), <from> apart, and end <to> apart.
#
# Needs a debug-enabled device on adb, a JDK, and the SDK's android.jar and d8
# (ANDROID_HOME). The dex is built once into build/pinch and reused.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$HERE")
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
OUT=$ROOT/build/pinch
DEX=$OUT/classes.dex
ON_DEVICE=/data/local/tmp/artiest-pinch.dex

if [ ! -f "$DEX" ] || [ "$HERE/pinch/Pinch.java" -nt "$DEX" ]; then
    JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
    D8=$(ls -d "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1 || true)
    if [ -z "$JAR" ] || [ -z "$D8" ]; then
        echo "no android.jar or d8 under $SDK — set ANDROID_HOME" >&2
        exit 2
    fi
    mkdir -p "$OUT/classes"
    # -source/-target rather than --release: --release refuses a bootclasspath,
    # and the bootclasspath has to be android.jar or the platform's own classes
    # are not the ones being compiled against.
    javac -source 11 -target 11 -nowarn -classpath "$JAR" \
        -d "$OUT/classes" "$HERE/pinch/Pinch.java" 2>&1 | grep -v "bootstrap class path" || true
    "$D8" --min-api 26 --lib "$JAR" --output "$OUT" "$OUT/classes/Pinch.class"
    adb push "$DEX" "$ON_DEVICE" > /dev/null
elif ! adb shell "test -f $ON_DEVICE"; then
    adb push "$DEX" "$ON_DEVICE" > /dev/null
fi

CX=${1:-1100}
CY=${2:-720}
FROM=${3:-180}
TO=${4:-520}
shift $(( $# < 4 ? $# : 4 ))

# app_process and not `am instrument`: this is a command, not a test, and it
# has to work against whatever build happens to be installed.
adb shell CLASSPATH=$ON_DEVICE app_process / Pinch "$CX" "$CY" "$FROM" "$TO" "$@"
