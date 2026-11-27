// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

/** Options of a packet listener (accepted; Vanillite always calls listeners where packets are written and read). */
public enum ListenerOptions {
    DISABLE_GAMEPHASE_DETECTION, SKIP_PLUGIN_VERIFIER, ASYNC, SYNC
}
