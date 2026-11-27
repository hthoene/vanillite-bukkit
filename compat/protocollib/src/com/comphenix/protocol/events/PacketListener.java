// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

import org.bukkit.plugin.Plugin;

/** Receives packets sent by the server and by clients. */
public interface PacketListener {
    void onPacketSending(PacketEvent event);

    void onPacketReceiving(PacketEvent event);

    ListeningWhitelist getSendingWhitelist();

    ListeningWhitelist getReceivingWhitelist();

    Plugin getPlugin();
}
