#!/usr/bin/env bash
# Full check: build everything, Rust tests, and the layer's self test (generates every API
# implementation class and reads the example plugins, without a server).
# SPDX-License-Identifier: Apache-2.0
source "$(dirname "$0")/env.sh"
"$ROOT/scripts/build.sh"
cargo test --workspace
fetch_libraries
compile_java "$BUILD/classes/test" "$BUILD/classes/jvm:$BUILD/classes/bukkit:$CLASSPATH_LIBS" bukkit/test
"$JAVA" --enable-native-access=ALL-UNNAMED \
    -cp "$BUILD/classes/test:$BUILD/classes/jvm:$BUILD/classes/bukkit:$CLASSPATH_LIBS" \
    dev.vanillite.bukkit.SelfTest "$BUILD/dist/plugins"
echo "check passed"
