// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/** A player who is not online (known by UUID, maybe by name). */
public abstract class VOfflinePlayer implements OfflinePlayer {
    private static final MethodHandle NEW = Impl.constructor(VOfflinePlayer.class, UUID.class, String.class);

    private final UUID uuid;
    private final String name;

    protected VOfflinePlayer(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    static VOfflinePlayer create(UUID uuid, String name) {
        try {
            return (VOfflinePlayer) NEW.invokeExact(uuid, name);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public String getName() {
        Player p = getPlayer();
        return p != null ? p.getName() : name;
    }

    @Override
    public boolean isOnline() {
        return Layer.server.players.containsKey(uuid);
    }

    @Override
    public boolean isConnected() {
        return isOnline();
    }

    @Override
    public Player getPlayer() {
        return Layer.server.players.get(uuid);
    }

    @Override
    public boolean hasPlayedBefore() {
        return name != null;
    }

    @Override
    public boolean isBanned() {
        return false;
    }

    @Override
    public boolean isWhitelisted() {
        return false;
    }

    @Override
    public boolean isOp() {
        Player p = getPlayer();
        return p != null && p.isOp();
    }

    @Override
    public long getFirstPlayed() {
        return 0;
    }

    @Override
    public long getLastPlayed() {
        return 0;
    }

    @Override
    public long getLastLogin() {
        return 0;
    }

    @Override
    public long getLastSeen() {
        return 0;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of("UUID", uuid.toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof OfflinePlayer p && p.getUniqueId().equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return "CraftOfflinePlayer[UUID=" + uuid + "]";
    }
}
