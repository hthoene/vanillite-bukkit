#!/usr/bin/env bash
# Builds the layer. Output in build/dist/plugins/: bukkit/ (the Vanillite plugin package:
# plugin.toml, libvanillite_jvm.so, vanillite-jvm.jar, vanillite-bukkit.jar) and the example
# plugins HelloBukkit.jar and VBench.jar. Copy the contents into a server's plugins/ directory.
# SPDX-License-Identifier: Apache-2.0
source "$(dirname "$0")/env.sh"

cargo build --release -p vanillite-jvm
cargo run --release -q -p vanillite-jvm --bin vanillite-jvm-bindgen -- --check vanillite-jvm/java/src
fetch_libraries

compile_java "$BUILD/classes/jvm" "" vanillite-jvm/java/src
compile_java "$BUILD/classes/bukkit" "$BUILD/classes/jvm:$CLASSPATH_LIBS" bukkit/src
cp -r bukkit/resources/. "$BUILD/classes/bukkit/"
compile_java "$BUILD/classes/hello" "$CLASSPATH_LIBS" examples/hello/src
cp -r examples/hello/resources/. "$BUILD/classes/hello/"
compile_java "$BUILD/classes/bench" "$CLASSPATH_LIBS" examples/bench/src
cp -r examples/bench/resources/. "$BUILD/classes/bench/"

DIST="$BUILD/dist/plugins"
rm -rf "$BUILD/dist"
mkdir -p "$DIST/bukkit"
cp package/plugin.toml "$DIST/bukkit/"
cp target/release/libvanillite_jvm.so "$DIST/bukkit/"
"$JAR" --create --file "$DIST/bukkit/vanillite-jvm.jar" -C "$BUILD/classes/jvm" .
"$JAR" --create --file "$DIST/bukkit/vanillite-bukkit.jar" -C "$BUILD/classes/bukkit" .
"$JAR" --create --file "$DIST/HelloBukkit.jar" -C "$BUILD/classes/hello" .
"$JAR" --create --file "$DIST/VBench.jar" -C "$BUILD/classes/bench" .
echo "built $DIST"
