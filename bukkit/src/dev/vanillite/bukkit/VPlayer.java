// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** An online player as a Bukkit {@link Player}. Permissions go through its {@code perm} permissible. */
public abstract class VPlayer extends CraftHumanEntity implements Player {
    static final MethodHandle NEW = Impl.constructor(VPlayer.class, VWorld.class, UUID.class, String.class);

    private final String name;
    private String displayName;
    private Component displayComponent;
    private String listName;
    private PlayerTeleportEvent.TeleportCause teleportCause;
    private long firstPlayed = System.currentTimeMillis();

    @SuppressWarnings("this-escape")
    protected VPlayer(VWorld world, UUID uuid, String name) {
        super(world, uuid);
        this.name = name;
        this.perm = new PermissibleBase(this);
    }

    static VPlayer create(VWorld world, UUID uuid, String name) {
        try {
            return (VPlayer) NEW.invokeExact(world, uuid, name);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    @Override
    protected int worldId() {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        if (Host.player_world(Native.plugin, s.uuid(uuid), out) == Abi.status.OK) {
            int id = out.get(JAVA_INT, 0);
            if (world == null || id != world.id) {
                VWorld w = Layer.server.world(id);
                if (w != null) {
                    world = w;
                }
            }
        }
        return world == null ? 0 : world.id;
    }

    @Override
    public org.bukkit.World getWorld() {
        worldId();
        return world;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName != null ? displayName : name;
    }

    @Override
    public void setDisplayName(String name) {
        displayName = name;
        displayComponent = name == null ? null : LegacyComponentSerializer.legacySection().deserialize(name);
    }

    @Override
    public Component displayName() {
        return displayComponent != null ? displayComponent : Component.text(name);
    }

    @Override
    public void displayName(Component name) {
        displayComponent = name;
        displayName = name == null ? null : LegacyComponentSerializer.legacySection().serialize(name);
    }

    @Override
    public String getPlayerListName() {
        return listName != null ? listName : name;
    }

    @Override
    public void setPlayerListName(String name) {
        listName = name;
    }

    @Override
    public Component playerListName() {
        return LegacyComponentSerializer.legacySection().deserialize(getPlayerListName());
    }

    @Override
    public void playerListName(Component name) {
        listName = name == null ? null : LegacyComponentSerializer.legacySection().serialize(name);
    }

    @Override
    public EntityType getType() {
        return EntityType.PLAYER;
    }

    @Override
    public boolean isOnline() {
        return Layer.server.players.get(uuid) == this;
    }

    @Override
    public boolean isConnected() {
        return isOnline();
    }

    @Override
    public boolean isValid() {
        return isOnline();
    }

    @Override
    public Player getPlayer() {
        return isOnline() ? this : null;
    }

    @Override
    public boolean hasPlayedBefore() {
        return true;
    }

    @Override
    public long getFirstPlayed() {
        return firstPlayed;
    }

    @Override
    public long getLastPlayed() {
        return System.currentTimeMillis();
    }

    @Override
    public long getLastLogin() {
        return firstPlayed;
    }

    @Override
    public long getLastSeen() {
        return System.currentTimeMillis();
    }

    @Override
    public boolean isBanned() {
        return false;
    }

    @Override
    public boolean isWhitelisted() {
        return true;
    }

    // ----- Messages

    private void send(String text, int format) {
        var s = Native.scratch();
        Host.player_send_message(Native.plugin, s.uuid(uuid), s.str(text), format);
    }

    @Override
    public void sendMessage(String message) {
        if (message == null) {
            return;
        }
        if (message.indexOf('§') < 0) {
            send(message, Abi.text_format.PLAIN);
        } else {
            send(Texts.legacyJson(message), Abi.text_format.JSON);
        }
    }

    @Override
    public void sendMessage(String... messages) {
        for (String m : messages) {
            sendMessage(m);
        }
    }

    @Override
    public void sendMessage(UUID sender, String message) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(UUID sender, String... messages) {
        sendMessage(messages);
    }

    @Override
    public void sendMessage(Component message) {
        send(Texts.json(message), Abi.text_format.JSON);
    }

    @Override
    public void sendRawMessage(String message) {
        sendMessage(message);
    }

    @Override
    public void kickPlayer(String reason) {
        var s = Native.scratch();
        Host.player_kick(Native.plugin, s.uuid(uuid), s.str(Texts.legacyJson(reason == null ? "" : reason)), Abi.text_format.JSON);
    }

    @Override
    public void kick(Component reason) {
        var s = Native.scratch();
        Host.player_kick(Native.plugin, s.uuid(uuid), s.str(Texts.json(reason == null ? Component.empty() : reason)),
                Abi.text_format.JSON);
    }

    @Override
    public void kick() {
        kick(Component.translatable("multiplayer.disconnect.kicked"));
    }

    @Override
    public boolean performCommand(String command) {
        return Layer.server.dispatchCommand(this, command);
    }

    @Override
    public void chat(String message) {
        throw new UnsupportedOperationException("Vanillite: Player.chat is not implemented yet (org.bukkit.entity.Player)");
    }

    @Override
    public InetSocketAddress getAddress() {
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(128);
        if (Host.player_address(Native.plugin, s.uuid(uuid), buf) != Abi.status.OK) {
            return null;
        }
        String text = Native.string(buf, true);
        return text == null ? null : new InetSocketAddress(text, 0);
    }

    // ----- Permissions

    @Override
    public boolean isPermissionSet(String name) {
        return perm.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission p) {
        return perm.isPermissionSet(p);
    }

    @Override
    public boolean hasPermission(String name) {
        return perm.hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission p) {
        return perm.hasPermission(p);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return perm.addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return perm.addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return perm.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return perm.addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        perm.removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        perm.recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return perm.getEffectivePermissions();
    }

    @Override
    public boolean isOp() {
        if (Native.host.byteSize() == 0) {
            return false;
        }
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.PlayerInfo.SIZE);
        info.set(JAVA_INT, Abi.PlayerInfo.size, (int) Abi.PlayerInfo.SIZE);
        return Host.player_info(Native.plugin, s.uuid(uuid), info) == Abi.status.OK
                && info.get(JAVA_INT, Abi.PlayerInfo.op_level) >= 1;
    }

    @Override
    public void setOp(boolean value) {
        if (value != isOp()) {
            var s = Native.scratch();
            Host.run_command(Native.plugin, s.str((value ? "op " : "deop ") + name));
        }
    }

    @Override
    public org.bukkit.Location getRespawnLocation() {
        // Respawn points (beds, anchors, /spawnpoint) are not exposed by the server yet.
        return null;
    }

    @Override
    public org.bukkit.Location getRespawnLocation(boolean load) {
        return null;
    }

    @Override
    public boolean canSee(Player player) {
        // Hiding players (hidePlayer) is not supported yet: everyone sees everyone.
        return true;
    }

    @Override
    public boolean canSee(org.bukkit.entity.Entity entity) {
        return true;
    }

    @Override
    public String getLocale() {
        // The client's language is not exposed by the server yet.
        return "en_us";
    }

    @Override
    public java.util.Locale locale() {
        return java.util.Locale.US;
    }

    @Override
    public org.bukkit.scoreboard.Scoreboard getScoreboard() {
        return Layer.server.getScoreboardManager().getMainScoreboard();
    }

    @Override
    public float getFlySpeed() {
        return (float) get(Abi.player_prop.FLY_SPEED) * 2;
    }

    @Override
    public void setFlySpeed(float value) {
        if (value < -1 || value > 1) {
            throw new IllegalArgumentException(value + " is not between -1 and 1");
        }
        if (Math.abs(value / 2 - get(Abi.player_prop.FLY_SPEED)) > 1e-6) {
            set(Abi.player_prop.FLY_SPEED, value / 2);
        }
    }

    @Override
    public float getWalkSpeed() {
        return (float) get(Abi.player_prop.WALK_SPEED) * 2;
    }

    @Override
    public void setWalkSpeed(float value) {
        if (value < -1 || value > 1) {
            throw new IllegalArgumentException(value + " is not between -1 and 1");
        }
        if (Math.abs(value / 2 - get(Abi.player_prop.WALK_SPEED)) > 1e-6) {
            set(Abi.player_prop.WALK_SPEED, value / 2);
        }
    }

    private org.bukkit.inventory.PlayerInventory inventory;

    @Override
    public org.bukkit.inventory.PlayerInventory getInventory() {
        if (inventory == null) {
            inventory = VPlayerInventory.create(this);
        }
        return inventory;
    }

    @Override
    public void updateCommands() {
        Host.player_update_commands(Native.plugin, Native.scratch().uuid(uuid));
    }

    // ----- Game state

    double get(int prop) {
        int w = worldId();
        var s = Native.scratch();
        MemorySegment out = s.alloc(8);
        Ctx.check(Host.player_get(Ctx.current, w, s.uuid(uuid), prop, out), "Player property " + prop);
        return out.get(JAVA_DOUBLE, 0);
    }

    void set(int prop, double value) {
        int w = worldId();
        var s = Native.scratch();
        Ctx.check(Host.player_set(Ctx.current, w, s.uuid(uuid), prop, value), "Player property " + prop);
    }

    @Override
    public GameMode getGameMode() {
        // player_info answers on any thread (LuckPerms reads the game mode asynchronously).
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.PlayerInfo.SIZE);
        info.set(JAVA_INT, Abi.PlayerInfo.size, (int) Abi.PlayerInfo.SIZE);
        Ctx.check(Host.player_info(Native.plugin, s.uuid(uuid), info), "Player.getGameMode");
        int mode = info.get(JAVA_INT, Abi.PlayerInfo.game_mode);
        return switch (mode) {
            case 1 -> GameMode.CREATIVE;
            case 2 -> GameMode.ADVENTURE;
            case 3 -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
    }

    @Override
    public void setGameMode(GameMode mode) {
        set(Abi.player_prop.GAME_MODE, switch (mode) {
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
            default -> 0;
        });
    }

    @Override
    public double getHealth() {
        return get(Abi.player_prop.HEALTH);
    }

    @Override
    public void setHealth(double health) {
        set(Abi.player_prop.HEALTH, health);
    }

    @Override
    public int getFoodLevel() {
        return (int) get(Abi.player_prop.FOOD);
    }

    @Override
    public void setFoodLevel(int value) {
        set(Abi.player_prop.FOOD, value);
    }

    @Override
    public boolean getAllowFlight() {
        return get(Abi.player_prop.MAY_FLY) != 0;
    }

    @Override
    public void setAllowFlight(boolean flight) {
        set(Abi.player_prop.MAY_FLY, flight ? 1 : 0);
    }

    @Override
    public boolean isFlying() {
        return get(Abi.player_prop.FLYING) != 0;
    }

    @Override
    public void setFlying(boolean value) {
        set(Abi.player_prop.FLYING, value ? 1 : 0);
    }

    @Override
    public int getLevel() {
        return (int) get(Abi.player_prop.XP_LEVEL);
    }

    @Override
    public float getExp() {
        return (float) get(Abi.player_prop.XP_PROGRESS);
    }

    @Override
    public int getTotalExperience() {
        return (int) get(Abi.player_prop.XP_TOTAL);
    }

    // ----- Teleports

    /** The cause of a teleport this layer started, for its {@code PlayerTeleportEvent}. */
    PlayerTeleportEvent.TeleportCause takeTeleportCause() {
        PlayerTeleportEvent.TeleportCause c = teleportCause;
        teleportCause = null;
        return c;
    }

    @Override
    public boolean teleport(Location location) {
        return teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override
    public boolean teleport(Location location, PlayerTeleportEvent.TeleportCause cause) {
        if (location == null || !isOnline()) {
            return false;
        }
        var s = Native.scratch();
        MemorySegment loc = s.alloc(Abi.Location.SIZE);
        Location to = location.getWorld() == null ? location.clone() : location;
        if (to.getWorld() == null) {
            to.setWorld(getWorld());
        }
        Events.writeLocation(loc, 0, to);
        teleportCause = cause;
        int status = Host.player_move(Native.plugin, s.uuid(uuid), loc);
        if (status != Abi.status.OK) {
            teleportCause = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean teleport(org.bukkit.entity.Entity destination) {
        return teleport(destination.getLocation());
    }

    @Override
    public boolean teleport(org.bukkit.entity.Entity destination, PlayerTeleportEvent.TeleportCause cause) {
        return teleport(destination.getLocation(), cause);
    }

    @Override
    public String toString() {
        return "CraftPlayer{name=" + name + "}";
    }
}
