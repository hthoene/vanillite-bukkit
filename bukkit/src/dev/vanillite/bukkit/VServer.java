// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.io.File;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.SimpleServicesManager;
import org.bukkit.scheduler.BukkitScheduler;

/** Vanillite as a Bukkit {@link Server}. */
public abstract class VServer implements Server {
    final Map<UUID, VPlayer> players = new ConcurrentHashMap<>();
    final Map<String, PluginCommand> commands = new ConcurrentHashMap<>();
    private final List<VWorld> worlds = new ArrayList<>();
    final VPluginManager pluginManager = Impl.create(VPluginManager.class);
    final VScheduler scheduler = Impl.create(VScheduler.class);
    final VConsole console = Impl.create(VConsole.class);
    private final ServicesManager services = new SimpleServicesManager();
    private final Logger logger = Layer.logger("Vanillite");
    File pluginsFolder;

    public VServer() {}

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

    @Override
    public String getName() {
        return "Vanillite";
    }

    @Override
    public String getVersion() {
        return "Vanillite Bukkit layer 0.1.0 (MC: 26.3)";
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
    public Logger getLogger() {
        return logger;
    }

    @Override
    public PluginManager getPluginManager() {
        return pluginManager;
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
    public ConsoleCommandSender getConsoleSender() {
        return console;
    }

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
        for (VPlayer p : players.values()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public List<World> getWorlds() {
        return new ArrayList<>(worlds);
    }

    @Override
    public World getWorld(String name) {
        for (VWorld w : worlds) {
            if (w.getName().equals(name)) {
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
    public PluginCommand getPluginCommand(String name) {
        return commands.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean isPrimaryThread() {
        return !Ctx.current.equals(MemorySegment.NULL);
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
    public boolean dispatchCommand(CommandSender sender, String line) {
        var s = Native.scratch();
        return Host.run_command(Native.plugin, s.str(line)) == Abi.status.OK;
    }

    @Override
    public File getPluginsFolder() {
        return pluginsFolder;
    }

    @Override
    public int getMaxPlayers() {
        return 20;
    }
}
