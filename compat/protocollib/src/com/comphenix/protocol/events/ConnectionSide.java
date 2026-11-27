// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

import com.comphenix.protocol.PacketType;

/** Which packets a listener wants: sent by the server, by the client, or both. */
public enum ConnectionSide {
    SERVER_SIDE, CLIENT_SIDE, BOTH;

    public boolean isForClient() {
        return this == CLIENT_SIDE || this == BOTH;
    }

    public boolean isForServer() {
        return this == SERVER_SIDE || this == BOTH;
    }

    public PacketType.Sender getSender() {
        return switch (this) {
            case SERVER_SIDE -> PacketType.Sender.SERVER;
            case CLIENT_SIDE -> PacketType.Sender.CLIENT;
            default -> null;
        };
    }

    public static ConnectionSide add(ConnectionSide a, ConnectionSide b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        boolean client = a.isForClient() || b.isForClient();
        boolean server = a.isForServer() || b.isForServer();
        return client && server ? BOTH : client ? CLIENT_SIDE : SERVER_SIDE;
    }
}
