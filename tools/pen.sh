#!/usr/bin/env bash
# One pen on the tablet, slowly enough to photograph.
#
# `adb shell input stylus swipe` takes a duration and this tablet ignores it: a
# swipe asked for 2500 ms arrives in about 125. Anything that has to be *seen*
# while the nib is down — the colour picker's ring, a drag preview — is over
# before a screenshot lands. This sends the pointer stream itself.
#
#   tools/pen.sh 700 1030 1250 900              # a stroke, 24 steps of 16 ms
#   tools/pen.sh 990 1030 990 897 20 20 0.6 2000 # and hold 2 s at the far end
#
# Arguments: <x0> <y0> <x1> <y1> [steps] [ms-per-step] [pressure] [hold-ms],
# in display pixels with the screen the way you are looking at it.
#
# Needs a debug-enabled device on adb, a JDK, and the SDK's android.jar and d8
# (ANDROID_HOME). The dex is built once into build/pen and reused.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$HERE")
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
OUT=$ROOT/build/pen
DEX=$OUT/classes.dex
ON_DEVICE=/data/local/tmp/artiest-pen.dex

if [ ! -f "$DEX" ] || [ "$HERE/pen/Pen.java" -nt "$DEX" ]; then
    JAR=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
    D8=$(ls -d "$SDK"/build-tools/*/d8 2>/dev/null | sort -V | tail -1 || true)
    if [ -z "$JAR" ] || [ -z "$D8" ]; then
        echo "no android.jar or d8 under $SDK — set ANDROID_HOME" >&2
        exit 2
    fi
    mkdir -p "$OUT/classes"
    javac -source 11 -target 11 -nowarn -classpath "$JAR" \
        -d "$OUT/classes" "$HERE/pen/Pen.java" 2>&1 | grep -v "bootstrap class path" || true
    "$D8" --min-api 26 --lib "$JAR" --output "$OUT" "$OUT/classes/Pen.class"
    adb push "$DEX" "$ON_DEVICE" > /dev/null
elif ! adb shell "test -f $ON_DEVICE"; then
    adb push "$DEX" "$ON_DEVICE" > /dev/null
fi

adb shell CLASSPATH=$ON_DEVICE app_process / Pen "$@"
