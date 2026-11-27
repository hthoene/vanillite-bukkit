#!/usr/bin/env bash
# Development helper: builds the layer, installs it into a test instance on the test server and
# shows the layer's log lines after a restart. Usage: scripts/try.sh [port] (default 30050).
# SPDX-License-Identifier: Apache-2.0
source "$(dirname "$0")/env.sh"
port="${1:-30050}"
"$ROOT/scripts/build.sh" >/dev/null
scp -qr "$BUILD/dist/plugins/." "vanillite-test:/opt/vanillite/$port/plugins/"
ssh vanillite-test "chown -R vanillite:vanillite /opt/vanillite/$port/plugins; systemctl restart vanillite@$port; \
  for i in \$(seq 60); do sleep 1; journalctl -u vanillite@$port --since -\${i}s -o cat | grep -q 'Done (' && break; done; \
  journalctl -u vanillite@$port --since -90s --no-pager -o cat | grep -E '\\[bukkit\\]|\\[Hello|\\[VBench|plugin|at |Caused|Exception' | grep -v Downloaded | tail -40"
