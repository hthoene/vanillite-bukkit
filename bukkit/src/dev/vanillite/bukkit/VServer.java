// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.io.File;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.SimpleServicesManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.scheduler.BukkitScheduler;

/** Vanillite as a Bukkit {@link Server} (its generated class is {@code org.bukkit.craftbukkit.CraftServer$Impl}). */
public abstract class VServer implements Server {
    final Map<UUID, VPlayer> players = new ConcurrentHashMap<>();
    /** Players between login and join. */
    private final Map<UUID, VPlayer> pending = new ConcurrentHashMap<>();
    /** Players seen while the server runs, for offline-player lookups. */
    private final Map<UUID, VOfflinePlayer> offline = new ConcurrentHashMap<>();
    private final List<VWorld> worlds = new ArrayList<>();
    SimpleCommandMap commandMap;
    VPluginManager pluginManager;
    final VScheduler scheduler = Impl.create(VScheduler.class);
    final VConsole console = Impl.create(VConsole.class);
    final SimpleServicesManager services = new SimpleServicesManager();
    final StandardMessenger messenger = new StandardMessenger();
    private final Logger logger = Layer.logger("Vanillite");
    private final Properties properties = new Properties();
    File pluginsFolder;

    protected VServer() {}

    /** Creates the command map and plugin manager (after {@code Bukkit.setServer}). */
    void init(File pluginsFolder) {
        this.pluginsFolder = pluginsFolder;
        commandMap = new SimpleCommandMap(this, new HashMap<>());
        pluginManager = VPluginManager.create(this, commandMap);
        try (var in = Files.newBufferedReader(Path.of("server.properties"))) {
            properties.load(in);
        } catch (java.io.IOException e) {
            // defaults
        }
    }

    /** Reads the worlds from the host (once the worlds exist). */
    void discoverWorlds() {
        worlds.clear();
        for (int id = 0; id < 64; id++) {
            var s = Native.scratch();
            MemorySegment buf = s.strBuf(256);
            if (Host.world_name(Native.plugin, id, buf) != Abi.status.OK) {
                break;
            }
            try {
                worlds.add((VWorld) VWorld.NEW.invokeExact(id, Native.string(buf, true)));
            } catch (Throwable t) {
                throw Native.rethrow(t);
            }
        }
    }

    VWorld world(int id) {
        return id >= 0 && id < worlds.size() ? worlds.get(id) : null;
    }

    // ----- Players' life cycle

    VPlayer pending(UUID id, String name) {
        VPlayer p = VPlayer.create(world(0), id, name);
        pending.put(id, p);
        return p;
    }

    void dropPending(UUID id) {
        pending.remove(id);
    }

    VPlayer joined(UUID id, String name, VWorld world) {
        VPlayer p = pending.remove(id);
        if (p == null) {
            p = VPlayer.create(world, id, name);
        } else if (world != null) {
            p.world = world;
        }
        players.put(id, p);
        offline.put(id, VOfflinePlayer.create(id, name));
        return p;
    }

    void left(VPlayer player) {
        players.remove(player.getUniqueId(), player);
    }

    // ----- Server

    private String property(String key, String fallback) {
        return properties.getProperty(key, fallback).strip();
    }

    @Override
    public String getName() {
        return "Vanillite";
    }

    @Override
    public String getVersion() {
        return "26.3-vanillite (MC: 26.3)";
    }

    @Override
    public String getBukkitVersion() {
        return "26.3-R0.1-SNAPSHOT";
    }

    @Override
    public String getMinecraftVersion() {
        return "26.3";
    }

    @Override
    public org.bukkit.scoreboard.ScoreboardManager getScoreboardManager() {
        return VScoreboard.MANAGER;
    }

    @Override
    public <T extends org.bukkit.Keyed> org.bukkit.Tag<T> getTag(String registry, org.bukkit.NamespacedKey tag, Class<T> clazz) {
        return dev.vanillite.bukkit.registry.VTag.get(registry, tag, clazz);
    }

    @Override
    public <T extends org.bukkit.Keyed> Iterable<org.bukkit.Tag<T>> getTags(String registry, Class<T> clazz) {
        return dev.vanillite.bukkit.registry.VTag.all(registry, clazz);
    }

    @Override
    @SuppressWarnings("deprecation")
    public org.bukkit.UnsafeValues getUnsafe() {
        return VUnsafe.INSTANCE;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public PluginManager getPluginManager() {
        return pluginManager.spm;
    }

    @Override
    public BukkitScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public ServicesManager getServicesManager() {
        return services;
    }

    @Override
    public Messenger getMessenger() {
        return messenger;
    }

    @Override
    public CommandMap getCommandMap() {
        return commandMap;
    }

    @Override
    public ConsoleCommandSender getConsoleSender() {
        return console;
    }

    @Override
    public boolean getOnlineMode() {
        return Boolean.parseBoolean(property("online-mode", "true"));
    }

    @Override
    public int getMaxPlayers() {
        try {
            return Integer.parseInt(property("max-players", "20"));
        } catch (NumberFormatException e) {
            return 20;
        }
    }

    @Override
    public int getPort() {
        try {
            return Integer.parseInt(property("server-port", "25565"));
        } catch (NumberFormatException e) {
            return 25565;
        }
    }

    @Override
    public String getIp() {
        return property("server-ip", "");
    }

    @Override
    public int getViewDistance() {
        try {
            return Integer.parseInt(property("view-distance", "10"));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    @Override
    public String getMotd() {
        return property("motd", "A Minecraft Server");
    }

    @Override
    public Component motd() {
        return Component.text(getMotd());
    }

    @Override
    public boolean getAllowFlight() {
        return Boolean.parseBoolean(property("allow-flight", "false"));
    }

    @Override
    public boolean getAllowEnd() {
        return true;
    }

    @Override
    public boolean getAllowNether() {
        return true;
    }

    @Override
    public boolean hasWhitelist() {
        return Boolean.parseBoolean(property("white-list", "false"));
    }

    @Override
    public boolean isHardcore() {
        return Boolean.parseBoolean(property("hardcore", "false"));
    }

    @Override
    public File getWorldContainer() {
        return new File(".");
    }

    @Override
    public File getUpdateFolderFile() {
        return new File(pluginsFolder, "update");
    }

    @Override
    public String getUpdateFolder() {
        return "update";
    }

    /** Set when the server shuts the layer down. */
    volatile boolean stopping;

    @Override
    public File getPluginsFolder() {
        return pluginsFolder;
    }

    @Override
    public boolean isStopping() {
        return stopping;
    }

    @Override
    public boolean isPrimaryThread() {
        return !Ctx.current.equals(MemorySegment.NULL);
    }

    @Override
    public void shutdown() {
        var s = Native.scratch();
        Host.run_command(Native.plugin, s.str("stop"));
    }

    // ----- Players

    @Override
    public Collection<? extends Player> getOnlinePlayers() {
        return Collections.unmodifiableCollection(players.values());
    }

    @Override
    public Player getPlayer(UUID id) {
        return players.get(id);
    }

    @Override
    public Player getPlayerExact(String name) {
        for (VPlayer p : players.values()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public Player getPlayer(String name) {
        Player exact = getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        Player found = null;
        int delta = Integer.MAX_VALUE;
        for (VPlayer p : players.values()) {
            String n = p.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith(lower) && n.length() - lower.length() < delta) {
                found = p;
                delta = n.length() - lower.length();
            }
        }
        return found;
    }

    @Override
    public List<Player> matchPlayer(String name) {
        List<Player> out = new ArrayList<>();
        String lower = name.toLowerCase(Locale.ROOT);
        for (VPlayer p : players.values()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return List.of(p);
            }
            if (p.getName().toLowerCase(Locale.ROOT).contains(lower)) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public OfflinePlayer getOfflinePlayer(UUID id) {
        Player p = players.get(id);
        if (p != null) {
            return p;
        }
        return offline.computeIfAbsent(id, k -> VOfflinePlayer.create(k, null));
    }

    @Override
    public OfflinePlayer getOfflinePlayer(String name) {
        Player p = getPlayerExact(name);
        if (p != null) {
            return p;
        }
        for (VOfflinePlayer o : offline.values()) {
            if (name.equalsIgnoreCase(o.getName())) {
                return o;
            }
        }
        UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return VOfflinePlayer.create(id, name);
    }

    @Override
    public OfflinePlayer getOfflinePlayerIfCached(String name) {
        Player p = getPlayerExact(name);
        if (p != null) {
            return p;
        }
        for (VOfflinePlayer o : offline.values()) {
            if (name.equalsIgnoreCase(o.getName())) {
                return o;
            }
        }
        return null;
    }

    @Override
    public OfflinePlayer[] getOfflinePlayers() {
        return offline.values().toArray(OfflinePlayer[]::new);
    }

    @Override
    public Set<OfflinePlayer> getOperators() {
        Set<OfflinePlayer> ops = new java.util.HashSet<>();
        for (VPlayer p : players.values()) {
            if (p.isOp()) {
                ops.add(p);
            }
        }
        return ops;
    }

    // ----- Worlds

    @Override
    public List<World> getWorlds() {
        return new ArrayList<>(worlds);
    }

    @Override
    public World getWorld(String name) {
        for (VWorld w : worlds) {
            if (w.getName().equalsIgnoreCase(name) || w.dimensionKey().equals(name)) {
                return w;
            }
        }
        return null;
    }

    @Override
    public World getWorld(UUID uid) {
        for (VWorld w : worlds) {
            if (w.getUID().equals(uid)) {
                return w;
            }
        }
        return null;
    }

    @Override
    public World getWorld(net.kyori.adventure.key.Key key) {
        for (VWorld w : worlds) {
            if (w.dimensionKey().equals(key.asString())) {
                return w;
            }
        }
        return null;
    }

    // ----- Commands and messages

    @Override
    public PluginCommand getPluginCommand(String name) {
        return commandMap.getCommand(name) instanceof PluginCommand pc ? pc : null;
    }

    @Override
    public int broadcastMessage(String message) {
        var s = Native.scratch();
        Host.broadcast_message(Native.plugin, s.str(Texts.legacyJson(message)), Abi.text_format.JSON);
        return players.size();
    }

    @Override
    public int broadcast(Component message) {
        var s = Native.scratch();
        Host.broadcast_message(Native.plugin, s.str(Texts.json(message)), Abi.text_format.JSON);
        return players.size();
    }

    @Override
    public int broadcast(String message, String permission) {
        int n = 0;
        for (VPlayer p : players.values()) {
            if (p.hasPermission(permission)) {
                p.sendMessage(message);
                n++;
            }
        }
        console.sendMessage(message);
        return n;
    }

    @Override
    public int broadcast(Component message, String permission) {
        int n = 0;
        for (VPlayer p : players.values()) {
            if (p.hasPermission(permission)) {
                p.sendMessage(message);
                n++;
            }
        }
        console.sendMessage(message);
        return n;
    }

    @Override
    public boolean dispatchCommand(CommandSender sender, String line) {
        if (Commands.dispatch(sender, line)) {
            return true;
        }
        var s = Native.scratch();
        return Host.run_command(Native.plugin, s.str(line)) == Abi.status.OK;
    }

    @Override
    public Map<String, String[]> getCommandAliases() {
        return Map.of();
    }
}
