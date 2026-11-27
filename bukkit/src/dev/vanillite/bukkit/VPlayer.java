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
        // Callers evaluate this between their own scratch allocations: keep those.
        var s = Native.scratchKept();
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

    /** The hand slot ({@code /item} name) a running interaction is about, and its item. */
    private String eventHandSlot;
    private VItemStack eventHandItem;

    void eventHand(String slot, org.bukkit.inventory.ItemStack item) {
        eventHandSlot = slot;
        eventHandItem = item == null || item.isEmpty() ? null : VItemStack.of(item);
    }

    /** The item of a slot the core lent out to the running interaction, or null. */
    VItemStack eventSlot(String slot) {
        return Events.depth > 0 && slot.equals(eventHandSlot) ? eventHandItem : null;
    }

    /** Elytra flight is not reported to plugins yet: never gliding. */
    @Override
    public boolean isGliding() {
        return false;
    }

    /** The swimming pose is not reported to plugins yet: never swimming. */
    @Override
    public boolean isSwimming() {
        return false;
    }

    /** Riding is not reported to plugins yet: no vehicle. */
    @Override
    public org.bukkit.entity.Entity getVehicle() {
        return null;
    }

    @Override
    public boolean isInsideVehicle() {
        return false;
    }

    /** Level events (sounds, particles) are not sent for plugins yet: plugins' effects stay invisible. */
    @Override
    public void playEffect(org.bukkit.Location location, org.bukkit.Effect effect, int data) {}

    @Override
    public <T> void playEffect(org.bukkit.Location location, org.bukkit.Effect effect, T data) {}

    @Override
    public boolean isPlayerTimeRelative() {
        return true;
    }

    @Override
    public long getPlayerTimeOffset() {
        return 0;
    }

    @Override
    public org.bukkit.WeatherType getPlayerWeather() {
        return null;
    }

    // ----- Menus

    /** The menu the player has open (null: its own inventory), and one being opened by a plugin. */
    VInventoryView openView;
    VInventoryView pendingView;

    /** The item in a slot of the open menu, null when no menu is open. */
    VItemStack menuSlot(int slot) {
        var s = Native.scratch();
        var sink = Native.sink(s);
        if (Host.menu_item(Ctx.current, worldId(), s.uuid(uuid), slot, sink) != Abi.status.OK) {
            return null;
        }
        return VItemStack.fromSnbt(Native.sunk());
    }

    /** Puts an item into a slot of the open menu (after the running inventory event, if one runs). */
    void sendMenuSlot(int slot, VItemStack stack) {
        String snbt = stack == null ? "{}" : stack.toSnbt();
        Runnable send = () -> {
            var s = Native.scratch();
            Host.menu_set_item(Ctx.current, worldId(), s.uuid(uuid), slot, s.str(snbt));
        };
        if (Events.inMenuEvent) {
            Layer.server.scheduler.onWorld(worldId(), send);
        } else {
            send.run();
        }
    }

    /** The player's own inventory as a view. */
    private VInventoryView ownView() {
        return VInventoryView.create(this, VMenuInventory.create(this, "minecraft:inventory"), Component.text("Crafting"));
    }

    @Override
    public org.bukkit.inventory.InventoryView getOpenInventory() {
        return openView != null ? openView : ownView();
    }

    @Override
    public org.bukkit.inventory.InventoryView openInventory(org.bukkit.inventory.Inventory inventory) {
        if (inventory instanceof VPlayerInventory) {
            return getOpenInventory();
        }
        if (!(inventory instanceof VCustomInventory custom)) {
            throw new UnsupportedOperationException("Vanillite: only inventories made by Bukkit.createInventory can be opened yet");
        }
        VInventoryView view = VInventoryView.create(this, custom, custom.title);
        openView(view);
        return view;
    }

    @Override
    public void openInventory(org.bukkit.inventory.InventoryView view) {
        if (view instanceof VInventoryView v && v.top instanceof VCustomInventory) {
            openView(VInventoryView.create(this, v.top, v.title));
        } else if (view != null) {
            openInventory(view.getTopInventory());
        }
    }

    private void openView(VInventoryView view) {
        Runnable open = () -> {
            VCustomInventory custom = (VCustomInventory) view.top;
            // The core's open event would re-enter this plugin call and be dropped: fire it here.
            var opening = new org.bukkit.event.inventory.InventoryOpenEvent(view);
            Layer.server.pluginManager.callEvent(opening);
            if (opening.isCancelled()) {
                return;
            }
            // Serialising items may use the scratch arena itself: build every text before it.
            String menuType = custom.menuType();
            String title = Texts.json(view.title);
            String items = custom.itemsSnbt();
            int w = worldId();
            var s = Native.scratch();
            MemorySegment spec = s.alloc(Abi.MenuSpec.SIZE);
            spec.fill((byte) 0);
            spec.set(JAVA_INT, Abi.MenuSpec.size, (int) Abi.MenuSpec.SIZE);
            MemorySegment.copy(s.str(menuType), 0, spec, Abi.MenuSpec.menu_type, Abi.Str.SIZE);
            MemorySegment.copy(s.str(title), 0, spec, Abi.MenuSpec.title, Abi.Str.SIZE);
            spec.set(JAVA_INT, Abi.MenuSpec.format, Abi.text_format.JSON);
            MemorySegment.copy(s.str(items), 0, spec, Abi.MenuSpec.items, Abi.Str.SIZE);
            pendingView = view;
            int status = Host.menu_open(Ctx.current, w, s.uuid(uuid), spec, s.alloc(4));
            if (status != Abi.status.OK) {
                Native.log(Abi.log_level.WARN, "Could not open a menu for " + name + " (status " + status + ")");
            }
            if (status == Abi.status.OK) {
                if (openView != null && openView.top instanceof VCustomInventory old) {
                    old.viewers.remove(this);
                }
                if (pendingView == view) {
                    openView = view;
                    custom.viewers.add(this);
                }
            }
            pendingView = null;
        };
        if (Events.inMenuEvent) {
            Layer.server.scheduler.onWorld(worldId(), open);
        } else {
            open.run();
        }
    }

    /** The open menu closed (its event, or the player left). */
    void menuClosed() {
        if (openView != null && openView.top instanceof VCustomInventory custom) {
            custom.viewers.remove(this);
        }
        openView = null;
    }

    @Override
    public void closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason reason) {
        Runnable close = () -> {
            VInventoryView view = openView;
            int w = worldId();
            Host.menu_close(Ctx.current, w, Native.scratch().uuid(uuid));
            // The core's close event re-enters this plugin call and is dropped: fire it here.
            if (view != null) {
                Layer.server.pluginManager.callEvent(new org.bukkit.event.inventory.InventoryCloseEvent(view, reason));
            }
            menuClosed();
        };
        if (Events.inMenuEvent) {
            Layer.server.scheduler.onWorld(worldId(), close);
        } else {
            close.run();
        }
    }

    @Override
    public void closeInventory() {
        closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.PLUGIN);
    }

    @Override
    public org.bukkit.inventory.ItemStack getItemOnCursor() {
        return openView != null ? openView.getCursor() : VItemStack.empty();
    }

    @Override
    public void setItemOnCursor(org.bukkit.inventory.ItemStack item) {
        if (openView != null) {
            openView.cursor(item);
        }
    }

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

    /**
     * The last value read per property. While the player's own action fires an event (breaking,
     * placing, using), the core lends the player out and its properties cannot be read; handlers
     * then see the last known value (Vanilla's defaults before the first read).
     */
    private final double[] lastProps = {0, 20, 20, 20, 5, 0, 0, 0, 0, 0, 0, 0, 0.05, 0.1, 0, 300, 0};

    double get(int prop) {
        int w = worldId();
        var s = Native.scratch();
        MemorySegment out = s.alloc(8);
        int status = Host.player_get(Ctx.current, w, s.uuid(uuid), prop, out);
        boolean known = prop > 0 && prop < lastProps.length;
        if (status == Abi.status.OK) {
            double v = out.get(JAVA_DOUBLE, 0);
            if (known) {
                lastProps[prop] = v;
            }
            return v;
        }
        if (known && Events.depth > 0 && (status == Abi.status.NOT_FOUND || status == Abi.status.WRONG_OWNER)) {
            return lastProps[prop];
        }
        Ctx.check(status, "Player property " + prop);
        return 0;
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
