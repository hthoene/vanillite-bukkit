#!/usr/bin/env bash
# Builds the release archive of the layer for a target:
# build/release/vanillite-bukkit-<version>-<target>.tar.gz with the directory bukkit/ (the
# Vanillite plugin package). Vanillite's plugins.bukkit.auto-setup downloads it from the GitHub
# release and unpacks it into plugins/.
# Usage: scripts/package.sh [<target> [<library>]]
#   <target>   Rust target triple of the archive (default: this machine's)
#   <library>  the layer library built for <target> (vanillite_jvm.dll, libvanillite_jvm.dylib,
#              libvanillite_jvm.so) in place of this machine's; the Java parts are the same on
#              every target, so the release builds them once and only the library per target.
# SPDX-License-Identifier: Apache-2.0
source "$(dirname "$0")/env.sh"

VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' package/plugin.toml)"
TARGET="${1:-$(rustc -vV | sed -n 's/^host: //p')}"
LIBRARY="${2:-}"
if [[ -z "$LIBRARY" || ! -f "$BUILD/dist/plugins/bukkit/plugin.toml" ]]; then
    "$ROOT/scripts/build.sh"
fi
OUT="$BUILD/release"
STAGE="$OUT/stage-$TARGET"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -r "$BUILD/dist/plugins/bukkit" "$STAGE/"
if [[ -n "$LIBRARY" ]]; then
    rm -f "$STAGE"/bukkit/{libvanillite_jvm.so,libvanillite_jvm.dylib,vanillite_jvm.dll}
    cp "$LIBRARY" "$STAGE/bukkit/"
fi
cp LICENSE NOTICE "$STAGE/bukkit/"
NAME="vanillite-bukkit-$VERSION-$TARGET.tar.gz"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='@0' \
    -C "$STAGE" -czf "$OUT/$NAME" bukkit
rm -rf "$STAGE"
echo "packaged $OUT/$NAME"
