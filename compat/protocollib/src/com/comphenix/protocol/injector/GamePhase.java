// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.injector;

/** When a listener receives packets. */
public enum GamePhase {
    LOGIN, PLAYING, BOTH;

    public boolean hasLogin() {
        return this == LOGIN || this == BOTH;
    }

    public boolean hasPlaying() {
        return this == PLAYING || this == BOTH;
    }
}
