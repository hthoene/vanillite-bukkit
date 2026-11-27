// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

/** The order of packet listeners: lowest first, monitors last. */
public enum ListenerPriority {
    LOWEST(0), LOW(1), NORMAL(2), HIGH(3), HIGHEST(4), MONITOR(5);

    private final int slot;

    ListenerPriority(int slot) {
        this.slot = slot;
    }

    public int getSlot() {
        return slot;
    }
}
