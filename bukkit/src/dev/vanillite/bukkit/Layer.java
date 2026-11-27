// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.JvmPlugin;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.io.File;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The Bukkit/Paper layer: loads plugin jars from the server's {@code plugins/} directory, turns
 * native events, commands and tasks into Bukkit calls and serves the Bukkit API from the host.
 */
public final class Layer implements JvmPlugin {
    /** The server object of the running layer. */
    public static VServer server;

    /** Native event kind → Bukkit event class, for the events the spike supports. */
    private static final Map<String, Integer> EVENTS = Map.of(
            "org/bukkit/event/player/PlayerJoinEvent", Abi.event.PLAYER_JOIN,
            "org/bukkit/event/player/PlayerQuitEvent", Abi.event.PLAYER_LEAVE,
            "org/bukkit/event/block/BlockBreakEvent", Abi.event.BLOCK_BREAK);

    private final List<JavaPlugin> plugins = new ArrayList<>();
    private final List<PluginCommand> commandList = new ArrayList<>();
    private final TreeSet<Integer> kinds = new TreeSet<>();
    private static final java.lang.reflect.Constructor<PluginCommand> NEW_COMMAND;

    static {
        try {
            NEW_COMMAND = PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
            NEW_COMMAND.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Map<Long, MethodHandle> TEXT_SETTERS = new HashMap<>();
    /** Number of the layer's own diagnostics command. */
    private static final int DIAG = 65_535;

    public Layer() {}

    /** A logger that writes through the host under {@code [name]}. */
    static Logger logger(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
        }
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                int level = r.getLevel().intValue() >= Level.SEVERE.intValue() ? Abi.log_level.ERROR
                        : r.getLevel().intValue() >= Level.WARNING.intValue() ? Abi.log_level.WARN
                        : Abi.log_level.INFO;
                String text = "[" + name + "] " + r.getMessage();
                if (r.getThrown() != null) {
                    text += "\n" + Native.trace(r.getThrown());
                }
                Native.log(level, text);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return logger;
    }

    @Override
    public void load() throws Exception {
        long start = System.nanoTime();
        Impl.of(VBridge.class);
        Impl.of(VPluginLoader.class);
        server = Impl.create(VServer.class);
        Bukkit.setServer(server);
        Path dir = Path.of(System.getProperty("vanillite.jvm.dir")).toAbsolutePath().getParent();
        server.pluginsFolder = dir.toFile();
        List<Path> jars;
        try (var files = Files.list(dir)) {
            jars = files.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        ClassLoader parent = Layer.class.getClassLoader();
        for (Path jar : jars) {
            try {
                loadPlugin(jar.toFile(), parent);
            } catch (Throwable t) {
                Native.log(Abi.log_level.ERROR, "Could not load " + jar.getFileName() + ": " + Native.trace(t));
            }
        }
        registerCommands();
        var upcall = Native.upcall(Layer.class, "event", Abi.Fn.EventFn);
        for (int kind : kinds) {
            Ctx.check(Host.subscribe(Native.plugin, kind, Abi.priority.NORMAL, upcall, MemorySegment.NULL), "subscribe");
        }
        Native.log(Abi.log_level.INFO, "Loaded " + plugins.size() + " Bukkit plugin(s) in "
                + (System.nanoTime() - start) / 1_000_000 + " ms; native events " + kinds);
    }

    private void loadPlugin(File file, ClassLoader parent) throws Exception {
        PluginDescriptionFile description;
        try (JarFile jar = new JarFile(file)) {
            JarEntry yml = jar.getJarEntry("plugin.yml");
            if (yml == null) {
                Native.log(Abi.log_level.WARN, file.getName() + ": no plugin.yml (Paper plugins are not supported yet)");
                return;
            }
            try (InputStream in = jar.getInputStream(yml)) {
                description = new PluginDescriptionFile(in);
            }
            scanListeners(jar);
        }
        File data = new File(file.getParentFile(), description.getName());
        var loader = new VPluginClassLoader(file, description, data, logger(description.getName()), parent);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Class<? extends JavaPlugin> main = Class.forName(description.getMain(), true, loader).asSubclass(JavaPlugin.class);
            JavaPlugin plugin = main.getDeclaredConstructor().newInstance();
            server.pluginManager.plugins.put(description.getName(), plugin);
            plugins.add(plugin);
            for (var entry : description.getCommands().entrySet()) {
                PluginCommand command = NEW_COMMAND.newInstance(entry.getKey(), plugin);
                Map<String, Object> props = entry.getValue();
                if (props.get("description") instanceof String d) {
                    command.setDescription(d);
                }
                if (props.get("usage") instanceof String u) {
                    command.setUsage(u);
                }
                if (props.get("permission") instanceof String p) {
                    command.setPermission(p);
                }
                Object aliases = props.get("aliases");
                if (aliases instanceof List<?> list) {
                    command.setAliases(list.stream().map(String::valueOf).toList());
                } else if (aliases instanceof String a) {
                    command.setAliases(List.of(a));
                }
                commandList.add(command);
                server.commands.put(entry.getKey().toLowerCase(Locale.ROOT), command);
                for (String alias : command.getAliases()) {
                    server.commands.putIfAbsent(alias.toLowerCase(Locale.ROOT), command);
                }
            }
            plugin.onLoad();
            Native.log(Abi.log_level.INFO, "Loaded " + description.getFullName());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** Finds the event classes of {@code @EventHandler} methods, so only needed events are subscribed. */
    private void scanListeners(JarFile jar) throws Exception {
        for (JarEntry entry : java.util.Collections.list(jar.entries())) {
            if (!entry.getName().endsWith(".class")) {
                continue;
            }
            byte[] bytes;
            try (InputStream in = jar.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            ClassModel model = ClassFile.of().parse(bytes);
            for (MethodModel m : model.methods()) {
                boolean handler = m.findAttribute(Attributes.runtimeVisibleAnnotations())
                        .map(a -> a.annotations().stream()
                                .anyMatch(an -> an.className().stringValue().equals("Lorg/bukkit/event/EventHandler;")))
                        .orElse(false);
                if (!handler || m.methodTypeSymbol().parameterCount() != 1) {
                    continue;
                }
                String desc = m.methodTypeSymbol().parameterType(0).descriptorString();
                Integer kind = EVENTS.get(desc.substring(1, desc.length() - 1));
                if (kind != null) {
                    kinds.add(kind);
                }
            }
        }
    }

    private void registerCommands() {
        var upcall = Native.upcall(Layer.class, "command", Abi.Fn.CommandFn);
        Map<String, Integer> trees = new java.util.LinkedHashMap<>();
        for (int i = 0; i < commandList.size(); i++) {
            PluginCommand c = commandList.get(i);
            trees.putIfAbsent(c.getName().toLowerCase(Locale.ROOT), i + 1);
            for (String alias : c.getAliases()) {
                trees.putIfAbsent(alias.toLowerCase(Locale.ROOT), i + 1);
            }
        }
        trees.put("vjvm", DIAG);
        try (Arena arena = Arena.ofConfined()) {
            for (var tree : trees.entrySet()) {
                long stride = Abi.CommandNode.SIZE;
                MemorySegment nodes = arena.allocate(stride * 2, 8);
                node(arena, nodes, -1, Abi.node.LITERAL, tree.getKey(), tree.getValue(), tree.getValue() == DIAG ? 4 : 0);
                node(arena, nodes.asSlice(stride), 0, Abi.node.ARGUMENT, "args", tree.getValue(),
                        tree.getValue() == DIAG ? 4 : 0);
                MemorySegment spec = arena.allocate(Abi.CommandSpec.LAYOUT);
                spec.set(JAVA_INT, Abi.CommandSpec.size, (int) Abi.CommandSpec.SIZE);
                spec.set(ADDRESS, Abi.CommandSpec.nodes, nodes);
                spec.set(JAVA_LONG, Abi.CommandSpec.count, 2);
                spec.set(JAVA_LONG, Abi.CommandSpec.stride, stride);
                spec.set(ADDRESS, Abi.CommandSpec.execute, upcall);
                spec.set(ADDRESS, Abi.CommandSpec.suggest, MemorySegment.NULL);
                spec.set(ADDRESS, Abi.CommandSpec.user, MemorySegment.NULL);
                int status = Host.register_command(Native.plugin, spec);
                if (status != Abi.status.OK) {
                    Native.log(Abi.log_level.WARN, "Command /" + tree.getKey() + " could not be registered (" + status + ")");
                }
            }
        }
    }

    private static void node(Arena arena, MemorySegment n, int parent, int kind, String name, int executes, int level) {
        n.fill((byte) 0);
        n.set(JAVA_INT, Abi.CommandNode.parent, parent);
        n.set(JAVA_INT, Abi.CommandNode.kind, kind);
        byte[] bytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment text = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, bytes);
        n.set(ADDRESS, Abi.CommandNode.name + Abi.Str.ptr, text);
        n.set(JAVA_LONG, Abi.CommandNode.name + Abi.Str.len, bytes.length);
        n.set(JAVA_INT, Abi.CommandNode.arg_type, Abi.arg.GREEDY_STRING);
        n.set(JAVA_INT, Abi.CommandNode.level, level);
        n.set(JAVA_INT, Abi.CommandNode.executes, executes);
    }

    @Override
    public int enable(MemorySegment ctx) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        try {
            server.discoverWorlds();
            Method setEnabled = JavaPlugin.class.getDeclaredMethod("setEnabled", boolean.class);
            setEnabled.setAccessible(true);
            for (JavaPlugin p : plugins) {
                try {
                    Native.log(Abi.log_level.INFO, "Enabling " + p.getPluginMeta().getDisplayName());
                    setEnabled.invoke(p, true);
                } catch (Throwable t) {
                    Native.log(Abi.log_level.ERROR, "Error enabling " + p.getName() + ": " + Native.trace(t));
                }
            }
            return Abi.status.OK;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        } finally {
            Ctx.current = previous;
        }
    }

    @Override
    public int disable(MemorySegment ctx) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        try {
            Method setEnabled = JavaPlugin.class.getDeclaredMethod("setEnabled", boolean.class);
            setEnabled.setAccessible(true);
            for (JavaPlugin p : plugins.reversed()) {
                try {
                    setEnabled.invoke(p, false);
                } catch (Throwable t) {
                    Native.log(Abi.log_level.ERROR, "Error disabling " + p.getName() + ": " + Native.trace(t));
                }
            }
            server.scheduler.cancelTasks(null);
            return Abi.status.OK;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        } finally {
            Ctx.current = previous;
        }
    }

    // ----- Native → Bukkit

    private static String str(MemorySegment rec, long offset) {
        return Native.string(rec.asSlice(offset, Abi.Str.SIZE));
    }

    private static void setText(MemorySegment out, String text, int format) {
        if (out.equals(MemorySegment.NULL)) {
            return;
        }
        MemorySegment rec = out.reinterpret(Abi.TextOut.SIZE);
        long fn = rec.get(ADDRESS, Abi.TextOut.set).address();
        MethodHandle set = TEXT_SETTERS.computeIfAbsent(fn,
                a -> Native.LINKER.downcallHandle(MemorySegment.ofAddress(a), Abi.Fn.TextSetFn));
        try (Arena arena = Arena.ofConfined()) {
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            MemorySegment str = arena.allocate(Abi.Str.LAYOUT);
            str.set(ADDRESS, Abi.Str.ptr, arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, bytes));
            str.set(JAVA_LONG, Abi.Str.len, bytes.length);
            set.invokeExact(rec.get(ADDRESS, Abi.TextOut.state), str, format);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    private static int event(MemorySegment user, MemorySegment ctx, MemorySegment header) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        try {
            MemorySegment h = header.reinterpret(Abi.EventHeader.SIZE);
            int kind = h.get(JAVA_INT, Abi.EventHeader.kind);
            switch (kind) {
                case Abi.event.PLAYER_JOIN -> {
                    MemorySegment e = header.reinterpret(Abi.PlayerEvent.SIZE);
                    UUID id = Native.uuid(e.asSlice(Abi.PlayerEvent.player, Abi.Uuid.SIZE));
                    VWorld world = server.world(e.get(JAVA_INT, Abi.PlayerEvent.world));
                    VPlayer player = VPlayer.create(world, id, str(e, Abi.PlayerEvent.name));
                    server.players.put(id, player);
                    Component message = Component.text(str(e, Abi.PlayerEvent.message));
                    PlayerJoinEvent event = new PlayerJoinEvent(player, message);
                    server.pluginManager.callEvent(event);
                    if (event.joinMessage() != message) {
                        Component out = event.joinMessage();
                        setText(e.get(ADDRESS, Abi.PlayerEvent.message_out),
                                out == null ? "" : Texts.json(out), Abi.text_format.JSON);
                    }
                }
                case Abi.event.PLAYER_LEAVE -> {
                    MemorySegment e = header.reinterpret(Abi.PlayerEvent.SIZE);
                    UUID id = Native.uuid(e.asSlice(Abi.PlayerEvent.player, Abi.Uuid.SIZE));
                    VPlayer player = server.players.get(id);
                    if (player != null) {
                        Component message = Component.text(str(e, Abi.PlayerEvent.message));
                        PlayerQuitEvent event = new PlayerQuitEvent(player, message, PlayerQuitEvent.QuitReason.DISCONNECTED);
                        server.pluginManager.callEvent(event);
                        if (event.quitMessage() != message) {
                            Component out = event.quitMessage();
                            setText(e.get(ADDRESS, Abi.PlayerEvent.message_out),
                                    out == null ? "" : Texts.json(out), Abi.text_format.JSON);
                        }
                        server.players.remove(id);
                    }
                }
                case Abi.event.BLOCK_BREAK -> {
                    MemorySegment e = header.reinterpret(Abi.BlockEvent.SIZE);
                    VPlayer player = server.players.get(Native.uuid(e.asSlice(Abi.BlockEvent.player, Abi.Uuid.SIZE)));
                    VWorld world = server.world(e.get(JAVA_INT, Abi.BlockEvent.world));
                    if (player == null || world == null) {
                        break;
                    }
                    Block block = world.getBlockAt(e.get(JAVA_INT, Abi.BlockEvent.pos + Abi.BlockPos.x),
                            e.get(JAVA_INT, Abi.BlockEvent.pos + Abi.BlockPos.y),
                            e.get(JAVA_INT, Abi.BlockEvent.pos + Abi.BlockPos.z));
                    BlockBreakEvent event = new BlockBreakEvent(block, player);
                    server.pluginManager.callEvent(event);
                    if (event.isCancelled()) {
                        h.set(JAVA_INT, Abi.EventHeader.cancelled, 1);
                    }
                }
                default -> {}
            }
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "Event dispatch failed: " + Native.trace(t));
        } finally {
            Ctx.current = previous;
        }
        return Abi.status.OK;
    }

    private static int command(MemorySegment user, MemorySegment ctx, MemorySegment invocation, MemorySegment result) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        try {
            MemorySegment inv = invocation.reinterpret(Abi.CommandInvocation.SIZE);
            int number = inv.get(JAVA_INT, Abi.CommandInvocation.command);
            String line = str(inv, Abi.CommandInvocation.line).strip();
            String[] words = line.split(" +");
            String label = words[0];
            String[] args = Arrays.copyOfRange(words, 1, words.length);
            long sender = Abi.CommandInvocation.sender;
            CommandSender who = server.console;
            if (inv.get(JAVA_INT, sender + Abi.Sender.kind) == Abi.sender.PLAYER) {
                VPlayer p = server.players.get(Native.uuid(inv.asSlice(sender + Abi.Sender.player, Abi.Uuid.SIZE)));
                if (p != null) {
                    who = p;
                }
            }
            boolean ok;
            if (number == DIAG) {
                ok = Diagnostics.run(who, args);
            } else {
                PluginCommand command = server.commands.get(label.toLowerCase(Locale.ROOT));
                ok = command != null && command.execute(who, label, args);
            }
            result.reinterpret(4).set(JAVA_INT, 0, ok ? 1 : 0);
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "Command failed: " + Native.trace(t));
            return Abi.status.ERROR;
        } finally {
            Ctx.current = previous;
        }
        return Abi.status.OK;
    }
}
