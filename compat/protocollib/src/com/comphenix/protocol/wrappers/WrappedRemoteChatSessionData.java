// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import java.util.Arrays;
import java.util.UUID;

/**
 * A player's chat session as the player info packet carries it. On Vanillite it is kept as its
 * wire bytes (session id, then the profile public key); only the session id is readable.
 */
public class WrappedRemoteChatSessionData extends AbstractWrapper {
    public WrappedRemoteChatSessionData(Object handle) {
        super(byte[].class);
        this.handle = java.util.Objects.requireNonNull(handle);
    }

    /** The wire bytes. */
    public byte[] bytes() {
        return (byte[]) handle;
    }

    public UUID getSessionID() {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(bytes());
        return new UUID(b.getLong(), b.getLong());
    }

    public void setSessionID(UUID id) {
        byte[] copy = bytes().clone();
        java.nio.ByteBuffer.wrap(copy).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        handle = copy;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedRemoteChatSessionData d && Arrays.equals(d.bytes(), bytes());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes());
    }

    @Override
    public String toString() {
        return "WrappedRemoteChatSessionData[sessionId=" + getSessionID() + "]";
    }
}
