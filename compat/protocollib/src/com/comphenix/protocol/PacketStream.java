// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol;

import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.entity.Player;

/** Sends packets to players and feeds packets as if players sent them. */
public interface PacketStream {
    void sendServerPacket(Player receiver, PacketContainer packet);

    void sendServerPacket(Player receiver, PacketContainer packet, boolean filters);

    void sendWirePacket(Player receiver, int id, byte[] bytes);

    void receiveClientPacket(Player sender, PacketContainer packet);

    void receiveClientPacket(Player sender, PacketContainer packet, boolean filters);
}
