// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.utility.MinecraftVersion;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** ProtocolLib's protocol manager on Vanillite's packet interface. */
public final class VanilliteProtocolManager implements ProtocolManager {
    public static final VanilliteProtocolManager INSTANCE = new VanilliteProtocolManager();

    private VanilliteProtocolManager() {}

    @Override
    public int getProtocolVersion(Player player) {
        return Packets.protocolVersion();
    }

    @Override
    public void sendServerPacket(Player receiver, PacketContainer packet) {
        sendServerPacket(receiver, packet, true);
    }

    @Override
    public void sendServerPacket(Player receiver, PacketContainer packet, boolean filters) {
        Packets.send(receiver, packet, filters);
    }

    @Override
    public void sendWirePacket(Player receiver, int id, byte[] bytes) {
        byte[] body = new Wire.Out().varInt(id).raw(bytes).bytes();
        Packets.sendRaw(receiver, body);
    }

    @Override
    public void receiveClientPacket(Player sender, PacketContainer packet) {
        receiveClientPacket(sender, packet, true);
    }

    @Override
    public void receiveClientPacket(Player sender, PacketContainer packet, boolean filters) {
        Packets.receive(sender, packet, filters);
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet) {
        broadcastServerPacket(packet, Bukkit.getOnlinePlayers());
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet, Entity entity, boolean includeTracker) {
        List<Player> targets = new ArrayList<>(getEntityTrackers(entity));
        if (includeTracker && entity instanceof Player p && !targets.contains(p)) {
            targets.add(p);
        }
        broadcastServerPacket(packet, targets);
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet, Location origin, int maxObserverDistance) {
        double max = (double) maxObserverDistance * maxObserverDistance;
        List<Player> targets = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location at = p.getLocation();
            if (at.getWorld() != null && at.getWorld().equals(origin.getWorld()) && at.distanceSquared(origin) <= max) {
                targets.add(p);
            }
        }
        broadcastServerPacket(packet, targets);
    }

    @Override
    public void broadcastServerPacket(PacketContainer packet, Collection<? extends Player> targetPlayers) {
        for (Player p : targetPlayers) {
            sendServerPacket(p, packet);
        }
    }

    @Override
    public ImmutableSet<PacketListener> getPacketListeners() {
        return ImmutableSet.copyOf(Packets.listeners());
    }

    @Override
    public void addPacketListener(PacketListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        Packets.add(listener);
    }

    @Override
    public void removePacketListener(PacketListener listener) {
        Packets.remove(listener);
    }

    @Override
    public void removePacketListeners(Plugin plugin) {
        Packets.removeAll(plugin);
    }

    @Override
    public PacketContainer createPacket(PacketType type) {
        return createPacket(type, true);
    }

    @Override
    public PacketContainer createPacket(PacketType type, boolean forceDefaults) {
        if (!type.isSupported()) {
            throw new IllegalArgumentException("Minecraft " + Bukkit.getMinecraftVersion() + " has no packet " + type);
        }
        return new PacketContainer(type);
    }

    @Override
    public void updateEntity(Entity entity, List<Player> observers) {
        throw new UnsupportedOperationException("Vanillite: updateEntity is not supported");
    }

    @Override
    public Entity getEntityFromID(World container, int id) {
        return Packets.entity(container, id);
    }

    @Override
    public List<Player> getEntityTrackers(Entity entity) {
        List<Player> out = new ArrayList<>();
        int view = Bukkit.getViewDistance() * 16;
        double max = (double) view * view;
        Location at = entity.getLocation();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != entity && p.getWorld().equals(entity.getWorld()) && p.getLocation().distanceSquared(at) <= max) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public Set<PacketType> getSendingFilterTypes() {
        return Packets.types(true);
    }

    @Override
    public Set<PacketType> getReceivingFilterTypes() {
        return Packets.types(false);
    }

    @Override
    public MinecraftVersion getMinecraftVersion() {
        return MinecraftVersion.getCurrentVersion();
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public void verifyWhitelist(PacketListener listener, ListeningWhitelist whitelist) {
        for (PacketType type : whitelist.getTypes()) {
            if (!type.isSupported()) {
                throw new IllegalArgumentException("Minecraft " + Bukkit.getMinecraftVersion() + " has no packet " + type);
            }
        }
    }
}
