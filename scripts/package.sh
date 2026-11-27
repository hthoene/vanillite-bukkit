#!/usr/bin/env bash
# Builds the release archive of the layer for this machine's target:
# build/release/vanillite-bukkit-<version>-<target>.tar.gz with the directory bukkit/ (the
# Vanillite plugin package). Vanillite's plugins.bukkit.auto-setup downloads it from the GitHub
# release and unpacks it into plugins/. Usage: scripts/package.sh [<target>]
# SPDX-License-Identifier: Apache-2.0
source "$(dirname "$0")/env.sh"

VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' package/plugin.toml)"
TARGET="${1:-$(rustc -vV | sed -n 's/^host: //p')}"
"$ROOT/scripts/build.sh"
OUT="$BUILD/release"
mkdir -p "$OUT"
NAME="vanillite-bukkit-$VERSION-$TARGET.tar.gz"
cp LICENSE NOTICE "$BUILD/dist/plugins/bukkit/"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='@0' \
    -C "$BUILD/dist/plugins" -czf "$OUT/$NAME" bukkit
echo "packaged $OUT/$NAME"
