#!/usr/bin/env bash
# Regenerates the Java bindings of the plugin ABI (vanillite-jvm/java/src/dev/vanillite/jvm/abi).
# SPDX-License-Identifier: Apache-2.0
source "$(dirname "$0")/env.sh"
cargo run --release -q -p vanillite-jvm --bin vanillite-jvm-bindgen -- vanillite-jvm/java/src
