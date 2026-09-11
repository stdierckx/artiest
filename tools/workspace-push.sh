#!/usr/bin/env bash
# Put a workspace file onto the tablet, as if it had been imported.
#
# The app reads workspaces from its own private directory, which nothing
# outside the app can write to — so this goes in through `run-as`, which the
# debug build allows and a release build does not. It is a development tool for
# trying layouts without building an import screen first; the real way in is
# the importer, and this exercises exactly the same decoder.
#
#   tools/workspace-push.sh docs/examples/inker.json
#
# The file is named on the tablet by its own "id" field, not by its filename,
# because that is what the app does with an import. A file whose id collides
# with one already there will replace it, which is the one thing the real
# importer refuses to do — so do not point this at a workspace you have spent
# time on.
set -euo pipefail

PKG=be.thalos.artiest
DIR=files/workspaces

if [ $# -lt 1 ]; then
    echo "usage: $0 <workspace.json> [more.json ...]" >&2
    exit 2
fi

for file in "$@"; do
    [ -f "$file" ] || { echo "no such file: $file" >&2; exit 1; }

    id=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['id'])" "$file")

    # Through a world-readable staging file, because `run-as` cannot read the
    # host filesystem and `adb push` cannot write the app's private directory.
    adb push "$file" "/data/local/tmp/$id.json" > /dev/null
    adb shell "run-as $PKG mkdir -p $DIR"
    adb shell "run-as $PKG cp /data/local/tmp/$id.json $DIR/$id.json"
    adb shell "rm /data/local/tmp/$id.json"

    echo "pushed $id  <- $file"
done

echo
echo "Open Arrange on the tablet and tap the workspace name to switch."
