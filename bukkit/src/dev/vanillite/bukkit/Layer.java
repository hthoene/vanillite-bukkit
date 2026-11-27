// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.JvmPlugin;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoadOrder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.LibraryLoader;
import org.yaml.snakeyaml.Yaml;

/**
 * The Bukkit/Paper layer: loads plugin jars from the server's {@code plugins/} directory in
 * dependency order, turns native events, commands and tasks into Bukkit calls and serves the Bukkit
 * API from the host.
 */
public final class Layer implements JvmPlugin {
    /** The server object of the running layer. */
    public static VServer server;

    private final List<JavaPlugin> plugins = new ArrayList<>();
    private final TreeSet<Integer> kinds = new TreeSet<>();

    public Layer() {}

    /** A logger that writes through the host under {@code [name]}. */
    static Logger logger(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        for (Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
        }
        logger.addHandler(handler("[" + name + "] "));
        return logger;
    }

    /** Sends what reaches the root logger (plugin libraries, Log4j through its JUL bridge) to the server log. */
    static void rootLogger() {
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
        }
        root.addHandler(handler(""));
    }

    private static Handler handler(String prefix) {
        return new Handler() {
            @Override
            public void publish(LogRecord r) {
                int level = r.getLevel().intValue() >= Level.SEVERE.intValue() ? Abi.log_level.ERROR
                        : r.getLevel().intValue() >= Level.WARNING.intValue() ? Abi.log_level.WARN
                        : Abi.log_level.INFO;
                String message = r.getMessage();
                if (r.getParameters() != null && message != null) {
                    try {
                        message = java.text.MessageFormat.format(message, r.getParameters());
                    } catch (IllegalArgumentException e) {
                        // keep the pattern
                    }
                }
                String text = prefix + message;
                if (r.getThrown() != null) {
                    text += "\n" + Native.trace(r.getThrown());
                }
                Native.log(level, text);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    /** A plugin jar and its description. */
    private record Candidate(File file, PluginDescriptionFile description, boolean paper) {}

    @Override
    public void load() throws Exception {
        long start = System.nanoTime();
        rootLogger();
        Impl.of(VBridge.class);
        Impl.of(VPluginLoader.class);
        server = Impl.create(org.bukkit.craftbukkit.CraftServer.class);
        Bukkit.setServer(server);
        Path dir = Path.of(System.getProperty("vanillite.jvm.dir")).toAbsolutePath().getParent();
        server.init(dir.toFile());
        List<Path> jars;
        try (var files = Files.list(dir)) {
            jars = files.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        Map<String, Candidate> found = new LinkedHashMap<>();
        for (Path jar : jars) {
            try {
                Candidate c = describe(jar.toFile());
                if (c == null) {
                    continue;
                }
                Candidate other = found.putIfAbsent(c.description.getName(), c);
                if (other != null) {
                    Native.log(Abi.log_level.ERROR, "Ambiguous plugin name " + c.description.getName() + ": "
                            + other.file.getName() + " and " + jar.getFileName() + "; the second is skipped");
                }
            } catch (Throwable t) {
                Native.log(Abi.log_level.ERROR, "Could not read " + jar.getFileName() + ": " + Native.trace(t));
            }
        }
        for (Candidate c : order(found)) {
            try {
                loadPlugin(c);
            } catch (Throwable t) {
                Native.log(Abi.log_level.ERROR, "Could not load " + c.file.getName() + ": " + Native.trace(t));
            }
        }
        if (!plugins.isEmpty()) {
            kinds.add(Abi.event.PLAYER_JOIN);
            kinds.add(Abi.event.PLAYER_LEAVE);
            kinds.add(Abi.event.PLAYER_LOGIN);
            kinds.add(Abi.event.PLAYER_COMMAND);
            Commands.providePermissions();
        }
        Commands.register();
        WorldEvents.implied(kinds);
        Events.subscribe(kinds);
        Native.log(Abi.log_level.INFO, "Loaded " + plugins.size() + " Bukkit plugin(s) in "
                + (System.nanoTime() - start) / 1_000_000 + " ms; native events " + kinds);
    }

    /** Reads {@code paper-plugin.yml} or {@code plugin.yml} of a jar; null without either. */
    private Candidate describe(File file) throws Exception {
        try (JarFile jar = new JarFile(file)) {
            JarEntry paper = jar.getJarEntry("paper-plugin.yml");
            if (paper != null) {
                try (InputStream in = jar.getInputStream(paper)) {
                    return new Candidate(file, fromPaperYml(new Yaml().load(in), file), true);
                }
            }
            JarEntry yml = jar.getJarEntry("plugin.yml");
            if (yml == null) {
                Native.log(Abi.log_level.WARN, file.getName() + ": no plugin.yml or paper-plugin.yml; skipped");
                return null;
            }
            try (InputStream in = jar.getInputStream(yml)) {
                return new Candidate(file, new PluginDescriptionFile(in), false);
            }
        }
    }

    /**
     * A {@code paper-plugin.yml} as a Bukkit description: its server dependencies become
     * {@code depend}/{@code softdepend} ({@code load: BEFORE}, the default: the dependency loads
     * first) or {@code loadbefore} ({@code load: AFTER}).
     */
    @SuppressWarnings("unchecked")
    private static PluginDescriptionFile fromPaperYml(Map<String, Object> yml, File file) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("name", "version", "main", "api-version", "description", "author", "authors",
                "contributors", "website", "prefix", "load", "permissions", "default-permission", "provides")) {
            if (yml.containsKey(key)) {
                out.put(key, yml.get(key));
            }
        }
        List<String> depend = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        List<String> before = new ArrayList<>();
        if (yml.get("dependencies") instanceof Map<?, ?> deps && deps.get("server") instanceof Map<?, ?> serverDeps) {
            for (var e : ((Map<String, Object>) serverDeps).entrySet()) {
                Map<String, Object> spec = e.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                String load = String.valueOf(spec.getOrDefault("load", "OMIT")).toUpperCase(Locale.ROOT);
                boolean required = !Boolean.FALSE.equals(spec.get("required"));
                if (load.equals("AFTER")) {
                    before.add(e.getKey());
                } else if (required) {
                    depend.add(e.getKey());
                } else {
                    soft.add(e.getKey());
                }
            }
        }
        out.put("depend", depend);
        out.put("softdepend", soft);
        out.put("loadbefore", before);
        for (String unsupported : List.of("bootstrapper", "loader")) {
            if (yml.containsKey(unsupported)) {
                Native.log(Abi.log_level.WARN, file.getName() + ": paper-plugin.yml " + unsupported + " " + yml.get(unsupported)
                        + " is not supported yet; the plugin loads without it");
            }
        }
        return new PluginDescriptionFile(new StringReader(new Yaml().dump(out)));
    }

    /**
     * Load order: dependencies ({@code depend}, {@code softdepend}) first, {@code loadbefore}
     * reversed, {@code STARTUP} plugins before {@code POSTWORLD} ones; a plugin whose required
     * dependency is missing is skipped. Cycles are broken in file order.
     */
    private static List<Candidate> order(Map<String, Candidate> found) {
        Map<String, String> provided = new HashMap<>();
        for (Candidate c : found.values()) {
            provided.put(c.description.getName(), c.description.getName());
            for (String p : c.description.getProvides()) {
                provided.putIfAbsent(p, c.description.getName());
            }
        }
        Map<String, Set<String>> after = new LinkedHashMap<>();
        Set<String> skip = new HashSet<>();
        for (Candidate c : found.values()) {
            String name = c.description.getName();
            Set<String> deps = after.computeIfAbsent(name, k -> new HashSet<>());
            for (String d : c.description.getDepend()) {
                String real = provided.get(d);
                if (real == null) {
                    Native.log(Abi.log_level.ERROR, "Could not load " + c.file.getName() + ": it needs the plugin " + d);
                    skip.add(name);
                } else {
                    deps.add(real);
                }
            }
            for (String d : c.description.getSoftDepend()) {
                String real = provided.get(d);
                if (real != null) {
                    deps.add(real);
                }
            }
            for (String d : c.description.getLoadBefore()) {
                String real = provided.get(d);
                if (real != null) {
                    after.computeIfAbsent(real, k -> new HashSet<>()).add(name);
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var e : after.entrySet()) {
                if (!skip.contains(e.getKey()) && found.get(e.getKey()) != null
                        && found.get(e.getKey()).description.getDepend().stream()
                                .map(provided::get).anyMatch(skip::contains)) {
                    Native.log(Abi.log_level.ERROR, "Could not load " + e.getKey() + ": a plugin it needs is not loaded");
                    skip.add(e.getKey());
                    changed = true;
                }
            }
        }
        List<Candidate> out = new ArrayList<>();
        Set<String> done = new HashSet<>(skip);
        List<Candidate> rest = new ArrayList<>(found.values());
        rest.removeIf(c -> skip.contains(c.description.getName()));
        rest.sort((a, b) -> Boolean.compare(a.description.getLoad() != PluginLoadOrder.STARTUP,
                b.description.getLoad() != PluginLoadOrder.STARTUP));
        while (!rest.isEmpty()) {
            Candidate next = null;
            for (Candidate c : rest) {
                if (done.containsAll(after.getOrDefault(c.description.getName(), Set.of()).stream()
                        .filter(found::containsKey).toList())) {
                    next = c;
                    break;
                }
            }
            if (next == null) {
                next = rest.getFirst();
                Native.log(Abi.log_level.WARN, "Circular dependency involving " + next.description.getName()
                        + "; loading it first");
            }
            rest.remove(next);
            done.add(next.description.getName());
            out.add(next);
        }
        return out;
    }

    private LibraryLoader libraryLoader;

    private void loadPlugin(Candidate c) throws Exception {
        PluginDescriptionFile description = c.description;
        scan(c.file);
        ClassLoader libraries = null;
        if (!description.getLibraries().isEmpty()) {
            if (libraryLoader == null) {
                libraryLoader = new LibraryLoader(logger("LibraryLoader"));
            }
            libraries = libraryLoader.createLoader(description);
        }
        File data = new File(c.file.getParentFile(), description.getName());
        var loader = new VPluginClassLoader(c.file, description, data, logger(description.getPrefix() != null
                ? description.getPrefix() : description.getName()), Layer.class.getClassLoader(), libraries);
        attachCompat(description.getName(), loader);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Class<? extends JavaPlugin> main = Class.forName(description.getMain(), true, loader).asSubclass(JavaPlugin.class);
            JavaPlugin plugin = main.getDeclaredConstructor().newInstance();
            server.pluginManager.plugins.put(description.getName(), plugin);
            if (c.paper) {
                server.pluginManager.paperPlugins.add(description.getName());
            }
            plugins.add(plugin);
            if (!c.paper) {
                server.commandMap.registerAll(description.getName(), commands(plugin, description));
            }
            for (Permission p : description.getPermissions()) {
                try {
                    server.pluginManager.addPermission(p, false);
                } catch (IllegalArgumentException e) {
                    Native.log(Abi.log_level.WARN, "Plugin " + description.getFullName() + " tried to register permission "
                            + p.getName() + " but it is already registered");
                }
            }
            Native.log(Abi.log_level.INFO, "Loading " + description.getFullName());
            plugin.onLoad();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Plugins that reach into a server's internals get the layer's adapter for them, when it has one
     * ({@code compat/<plugin>.jar}): it joins the plugin's class path and the plugin is told to use it.
     */
    private static void attachCompat(String plugin, VPluginClassLoader loader) throws java.net.MalformedURLException {
        Path jar = Path.of(System.getProperty("vanillite.jvm.dir"), "compat", plugin.toLowerCase(java.util.Locale.ROOT) + ".jar");
        if (!Files.isRegularFile(jar)) {
            return;
        }
        loader.addCompat(jar.toUri().toURL());
        if (plugin.equals("WorldEdit")) {
            System.setProperty("worldedit.bukkit.adapter", "dev.vanillite.bukkit.compat.worldedit.VanilliteAdapter");
        }
        Native.log(Abi.log_level.INFO, "Vanillite adapter for " + plugin + " attached");
    }

    private static final java.lang.reflect.Constructor<org.bukkit.command.PluginCommand> NEW_COMMAND;

    static {
        try {
            NEW_COMMAND = org.bukkit.command.PluginCommand.class.getDeclaredConstructor(String.class, org.bukkit.plugin.Plugin.class);
            NEW_COMMAND.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** The commands of a {@code plugin.yml}. */
    private static List<Command> commands(JavaPlugin plugin, PluginDescriptionFile description) throws Exception {
        List<Command> out = new ArrayList<>();
        for (var entry : description.getCommands().entrySet()) {
            if (entry.getKey().contains(":")) {
                Native.log(Abi.log_level.WARN, "Could not load command " + entry.getKey() + " for plugin "
                        + description.getName() + ": illegal characters");
                continue;
            }
            org.bukkit.command.PluginCommand command = NEW_COMMAND.newInstance(entry.getKey(), plugin);
            Map<String, Object> props = entry.getValue() == null ? Map.of() : entry.getValue();
            if (props.get("description") != null) {
                command.setDescription(props.get("description").toString());
            }
            if (props.get("usage") != null) {
                command.setUsage(props.get("usage").toString());
            }
            if (props.get("permission") != null) {
                command.setPermission(props.get("permission").toString());
            }
            if (props.get("permission-message") != null) {
                command.setPermissionMessage(props.get("permission-message").toString());
            }
            Object aliases = props.get("aliases");
            List<String> list = new ArrayList<>();
            if (aliases instanceof List<?> l) {
                for (Object a : l) {
                    if (!a.toString().contains(":")) {
                        list.add(a.toString());
                    }
                }
            } else if (aliases != null && !aliases.toString().contains(":")) {
                list.add(aliases.toString());
            }
            command.setAliases(list);
            out.add(command);
        }
        return out;
    }

    /**
     * Finds the event classes a jar (and the jars inside it) refers to, so only those native events
     * are subscribed.
     */
    private void scan(File file) throws Exception {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            scan(in, 0);
        }
    }

    private void scan(InputStream raw, int depth) throws Exception {
        java.util.jar.JarInputStream jar = new java.util.jar.JarInputStream(raw);
        for (JarEntry entry; (entry = jar.getNextJarEntry()) != null; ) {
            String name = entry.getName();
            if ((name.endsWith(".jar") || name.endsWith(".jarinjar")) && depth < 2) {
                scan(new java.io.ByteArrayInputStream(jar.readAllBytes()), depth + 1);
                continue;
            }
            if (!name.endsWith(".class")) {
                continue;
            }
            ClassModel model;
            try {
                model = ClassFile.of().parse(jar.readAllBytes());
            } catch (IllegalArgumentException e) {
                continue;
            }
            for (PoolEntry p : model.constantPool()) {
                if (p instanceof ClassEntry ce) {
                    Integer kind = Events.KINDS.get(ce.asInternalName());
                    if (kind != null) {
                        kinds.add(kind);
                    }
                }
            }
        }
    }

    @Override
    public int enable(MemorySegment ctx) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        Native.epoch++;
        try {
            // As Paper: STARTUP plugins see no worlds yet; the worlds then load (WorldInitEvent,
            // WorldLoadEvent), then the POSTWORLD plugins are enabled and the server is loaded.
            List<JavaPlugin> postWorld = new ArrayList<>();
            for (JavaPlugin p : plugins) {
                if (p.getDescription().getLoad() == PluginLoadOrder.STARTUP) {
                    server.pluginManager.enablePlugin(p);
                } else {
                    postWorld.add(p);
                }
            }
            server.discoverWorlds();
            for (org.bukkit.World w : server.getWorlds()) {
                server.pluginManager.callEvent(new org.bukkit.event.world.WorldInitEvent(w));
                server.pluginManager.callEvent(new org.bukkit.event.world.WorldLoadEvent(w));
            }
            for (JavaPlugin p : postWorld) {
                server.pluginManager.enablePlugin(p);
            }
            server.pluginManager.callEvent(new org.bukkit.event.server.ServerLoadEvent(
                    org.bukkit.event.server.ServerLoadEvent.LoadType.STARTUP));
            server.pluginManager.dirtyPermissibles();
            return Abi.status.OK;
        } finally {
            Ctx.current = previous;
            Native.epoch++;
        }
    }

    @Override
    public int disable(MemorySegment ctx) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        Native.epoch++;
        try {
            server.stopping = true;
            server.pluginManager.disablePlugins();
            server.scheduler.cancelTasks(null);
            return Abi.status.OK;
        } finally {
            Ctx.current = previous;
            Native.epoch++;
        }
    }
}
