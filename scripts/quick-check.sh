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
compile_java "$BUILD/check/examples" "$CLASSPATH_LIBS" examples/hello/src examples/bench/src
echo "quick check passed"
