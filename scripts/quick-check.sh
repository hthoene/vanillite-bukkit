#!/usr/bin/env bash
# Quick check (format and lint): Rust format and clippy, generated bindings up to date, Java
# compiles without warnings.
# SPDX-License-Identifier: Apache-2.0
source "$(dirname "$0")/env.sh"
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo run -q -p vanillite-jvm --bin vanillite-jvm-bindgen -- --check vanillite-jvm/java/src
fetch_libraries
compile_java "$BUILD/check/jvm" "" vanillite-jvm/java/src
compile_java "$BUILD/check/bukkit" "$BUILD/check/jvm:$CLASSPATH_LIBS" bukkit/src bukkit/test
for compat in compat/*/; do
    fetch_compat "$(basename "$compat")"
    compile_java "$BUILD/check/compat-$(basename "$compat")" "$BUILD/check/jvm:$BUILD/check/bukkit:$CLASSPATH_LIBS:$CLASSPATH_COMPAT" "$compat/src"
done
compile_java "$BUILD/check/examples" "$CLASSPATH_LIBS" examples/hello/src examples/bench/src
echo "quick check passed"
