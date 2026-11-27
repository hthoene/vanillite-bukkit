// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol;

import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** ProtocolLib's packet API. */
public interface ProtocolManager extends PacketStream {
    int getProtocolVersion(Player player);

    @Override
    void sendServerPacket(Player receiver, PacketContainer packet, boolean filters);

    @Override
    void receiveClientPacket(Player sender, PacketContainer packet, boolean filters);

    void broadcastServerPacket(PacketContainer packet);

    void broadcastServerPacket(PacketContainer packet, Entity entity, boolean includeTracker);

    void broadcastServerPacket(PacketContainer packet, Location origin, int maxObserverDistance);

    void broadcastServerPacket(PacketContainer packet, Collection<? extends Player> targetPlayers);

    ImmutableSet<PacketListener> getPacketListeners();

    void addPacketListener(PacketListener listener);

    void removePacketListener(PacketListener listener);

    void removePacketListeners(Plugin plugin);

    PacketContainer createPacket(PacketType type);

    PacketContainer createPacket(PacketType type, boolean forceDefaults);

    void updateEntity(Entity entity, List<Player> observers);

    Entity getEntityFromID(World container, int id);

    List<Player> getEntityTrackers(Entity entity);

    Set<PacketType> getSendingFilterTypes();

    Set<PacketType> getReceivingFilterTypes();

    MinecraftVersion getMinecraftVersion();

    boolean isClosed();

    void verifyWhitelist(PacketListener listener, ListeningWhitelist whitelist);
}
