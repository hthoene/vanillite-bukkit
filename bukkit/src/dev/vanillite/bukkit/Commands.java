// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Bukkit commands on Vanillite. Every label of the command map (aliases, {@code plugin:label}
 * forms and WorldEdit's {@code /set}) gets a native command tree (a literal with one greedy
 * argument) for the console, command blocks and the client's command list and tab completion,
 * as Paper does; labels the server already has (Vanilla's commands) keep the native tree.
 * Labels that appear later (commands registered in {@code onEnable}) are added after each plugin
 * is enabled; the server then sends every player its tree again. A tree is shown to the players
 * who may use the command ({@link Command#testPermissionSilent}, through the permission provider)
 * while its plugin is enabled. Players' commands go through {@code PlayerCommandPreprocessEvent}
 * ({@link Events}), where a Bukkit command takes precedence over a Vanilla one with the same
 * label, as on Paper.
 */
final class Commands {
    private Commands() {}

    /** Number of the layer's own diagnostics command. */
    static final int DIAG = 65_535;
    /** Permission node prefix of the trees: the provider answers it for the label after it. */
    private static final String NODE = "vanillite-bukkit.command:";
    private static final List<String> LABELS = new ArrayList<>();
    private static final java.util.Set<String> KNOWN = new java.util.HashSet<>();
    private static MemorySegment execute;
    private static MemorySegment suggest;
    /** False once the server refused a late tree (a server older than ABI 1.12). */
    private static boolean late = true;

    /**
     * Registers native trees for the labels of the command map that have none yet: at load and
     * after a plugin was enabled. Returns whether trees were added.
     */
    static synchronized boolean register() {
        if (execute == null) {
            execute = Native.upcall(Commands.class, "execute", Abi.Fn.CommandFn);
            suggest = Native.upcall(Commands.class, "suggest", Abi.Fn.SuggestFn);
        }
        if (!late) {
            return false;
        }
        Map<String, Integer> trees = new java.util.LinkedHashMap<>();
        for (String label : Layer.server.commandMap.getKnownCommands().keySet()) {
            if (LABELS.size() < DIAG - 1 && label.length() <= 64 && !label.isEmpty()
                    && label.chars().noneMatch(Character::isWhitespace) && KNOWN.add(label)) {
                LABELS.add(label);
                trees.put(label, LABELS.size());
            }
        }
        if (KNOWN.add("vjvm")) {
            trees.put("vjvm", DIAG);
        }
        int added = 0;
        try (Arena arena = Arena.ofConfined()) {
            for (var tree : trees.entrySet()) {
                int number = tree.getValue();
                int level = number == DIAG ? 4 : 0;
                String permission = number == DIAG ? "" : NODE + tree.getKey();
                long stride = Abi.CommandNode.SIZE;
                MemorySegment nodes = arena.allocate(stride * 2, 8);
                node(arena, nodes, -1, Abi.node.LITERAL, tree.getKey(), number, level, 0, permission);
                node(arena, nodes.asSlice(stride), 0, Abi.node.ARGUMENT, "args", number, level, number, "");
                MemorySegment spec = arena.allocate(Abi.CommandSpec.LAYOUT);
                spec.set(JAVA_INT, Abi.CommandSpec.size, (int) Abi.CommandSpec.SIZE);
                spec.set(ADDRESS, Abi.CommandSpec.nodes, nodes);
                spec.set(JAVA_LONG, Abi.CommandSpec.count, 2);
                spec.set(JAVA_LONG, Abi.CommandSpec.stride, stride);
                spec.set(ADDRESS, Abi.CommandSpec.execute, execute);
                spec.set(ADDRESS, Abi.CommandSpec.suggest, suggest);
                spec.set(ADDRESS, Abi.CommandSpec.user, MemorySegment.NULL);
                int status = Host.register_command(Native.plugin, spec);
                if (status == Abi.status.WRONG_PHASE) {
                    late = false;
                    Native.log(Abi.log_level.WARN, "The server takes no commands after the start (ABI < 1.12): "
                            + "commands registered in onEnable work but are not in the players' command list");
                    break;
                } else if (status == Abi.status.OK) {
                    added++;
                }
            }
        }
        return added > 0;
    }

    /** Sends every online player its command tree again (a plugin was enabled or disabled). */
    static void updateAll() {
        for (org.bukkit.entity.Player p : Layer.server.getOnlinePlayers()) {
            p.updateCommands();
        }
    }

    private static void node(Arena arena, MemorySegment n, int parent, int kind, String name, int executes, int level,
            int suggests, String permission) {
        n.fill((byte) 0);
        n.set(JAVA_INT, Abi.CommandNode.parent, parent);
        n.set(JAVA_INT, Abi.CommandNode.kind, kind);
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        MemorySegment text = arena.allocateFrom(JAVA_BYTE, bytes);
        n.set(ADDRESS, Abi.CommandNode.name + Abi.Str.ptr, text);
        n.set(JAVA_LONG, Abi.CommandNode.name + Abi.Str.len, bytes.length);
        n.set(JAVA_INT, Abi.CommandNode.arg_type, Abi.arg.GREEDY_STRING);
        n.set(JAVA_INT, Abi.CommandNode.level, level);
        n.set(JAVA_INT, Abi.CommandNode.executes, executes);
        n.set(JAVA_INT, Abi.CommandNode.suggests, suggests);
        if (!permission.isEmpty()) {
            byte[] node = permission.getBytes(StandardCharsets.UTF_8);
            n.set(ADDRESS, Abi.CommandNode.permission + Abi.Str.ptr, arena.allocateFrom(JAVA_BYTE, node));
            n.set(JAVA_LONG, Abi.CommandNode.permission + Abi.Str.len, node.length);
        }
    }

    private static CommandSender sender(MemorySegment rec, long offset) {
        if (rec.get(JAVA_INT, offset + Abi.Sender.kind) == Abi.sender.PLAYER) {
            VPlayer p = Layer.server.players.get(Native.uuid(rec.asSlice(offset + Abi.Sender.player, Abi.Uuid.SIZE)));
            if (p != null) {
                return p;
            }
        }
        return Layer.server.console;
    }

    private static int execute(MemorySegment user, MemorySegment ctx, MemorySegment invocation, MemorySegment result) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        Native.epoch++;
        try {
            MemorySegment inv = invocation.reinterpret(Abi.CommandInvocation.SIZE);
            int number = inv.get(JAVA_INT, Abi.CommandInvocation.command);
            String line = Events.str(inv, Abi.CommandInvocation.line).strip();
            CommandSender who = sender(inv, Abi.CommandInvocation.sender);
            boolean ok;
            if (number == DIAG) {
                String[] words = line.split(" +");
                ok = Diagnostics.run(who, java.util.Arrays.copyOfRange(words, 1, words.length));
            } else {
                ok = dispatch(who, line);
            }
            result.reinterpret(4).set(JAVA_INT, 0, ok ? 1 : 0);
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "Command failed: " + Native.trace(t));
            return Abi.status.ERROR;
        } finally {
            Ctx.current = previous;
            Native.epoch++;
        }
        return Abi.status.OK;
    }

    /** Runs a command line of the command map; false when it has no such command. */
    static boolean dispatch(CommandSender who, String line) {
        try {
            return Layer.server.commandMap.dispatch(who, line);
        } catch (org.bukkit.command.CommandException e) {
            Native.log(Abi.log_level.ERROR, e.getMessage() + ": " + Native.trace(e.getCause() != null ? e.getCause() : e));
            who.sendMessage("§cAn internal error occurred while attempting to perform this command");
            return false;
        }
    }

    /** A player's Bukkit command: runs as a task on the player's world (in its next tick). */
    static void runAsPlayer(VPlayer player, String line) {
        Native.log(Abi.log_level.INFO, player.getName() + " issued server command: /" + line);
        Layer.server.scheduler.onWorld(player.worldId(), () -> {
            if (player.isOnline()) {
                dispatch(player, line);
            }
        });
    }

    private static final Map<Long, MethodHandle> ADDERS = new HashMap<>();

    private static int suggest(MemorySegment user, MemorySegment ctx, MemorySegment request, MemorySegment sink) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        Native.epoch++;
        try {
            MemorySegment req = request.reinterpret(Abi.SuggestRequest.SIZE);
            int number = req.get(JAVA_INT, Abi.SuggestRequest.provider);
            if (number < 1 || number > LABELS.size()) {
                return Abi.status.OK;
            }
            String remaining = Events.str(req, Abi.SuggestRequest.remaining);
            CommandSender who = sender(req, Abi.SuggestRequest.sender);
            String line = LABELS.get(number - 1) + " " + remaining;
            List<String> found = complete(who, line);
            if (found == null || found.isEmpty()) {
                return Abi.status.OK;
            }
            MemorySegment s = sink.reinterpret(Abi.SuggestSink.SIZE);
            long fn = s.get(ADDRESS, Abi.SuggestSink.add).address();
            MethodHandle add = ADDERS.computeIfAbsent(fn,
                    a -> Native.LINKER.downcallHandle(MemorySegment.ofAddress(a), Abi.Fn.SuggestAddFn));
            MemorySegment state = s.get(ADDRESS, Abi.SuggestSink.state);
            int lastSpace = remaining.lastIndexOf(' ');
            String prefix = lastSpace < 0 ? "" : remaining.substring(0, lastSpace + 1);
            try (Arena arena = Arena.ofConfined()) {
                for (String option : found) {
                    byte[] bytes = (prefix + option).getBytes(StandardCharsets.UTF_8);
                    MemorySegment str = arena.allocate(Abi.Str.LAYOUT);
                    str.set(ADDRESS, Abi.Str.ptr, arena.allocateFrom(JAVA_BYTE, bytes));
                    str.set(JAVA_LONG, Abi.Str.len, bytes.length);
                    add.invokeExact(state, str);
                }
            }
        } catch (Throwable t) {
            Native.log(Abi.log_level.WARN, "Tab completion failed: " + Native.trace(t));
        } finally {
            Ctx.current = previous;
            Native.epoch++;
        }
        return Abi.status.OK;
    }

    /**
     * Tab completion as on Paper: {@code AsyncTabCompleteEvent} first (a handler that sets it
     * handled answers), else the command's completer and {@code TabCompleteEvent}.
     */
    private static List<String> complete(CommandSender who, String line) {
        org.bukkit.Location at = who instanceof org.bukkit.entity.Entity e ? e.getLocation() : null;
        var async = new com.destroystokyo.paper.event.server.AsyncTabCompleteEvent(who, "/" + line, true, at);
        Layer.server.pluginManager.callEvent(async);
        if (async.isCancelled()) {
            return List.of();
        }
        if (async.isHandled()) {
            return async.getCompletions();
        }
        List<String> found = Layer.server.commandMap.tabComplete(who, line);
        var event = new org.bukkit.event.server.TabCompleteEvent(who, "/" + line,
                found == null ? new ArrayList<>() : new ArrayList<>(found), true, at);
        Layer.server.pluginManager.callEvent(event);
        return event.isCancelled() ? List.of() : event.getCompletions();
    }

    /** The native permission provider: Bukkit's permissibles (with permission plugins) decide set nodes. */
    static void providePermissions() {
        var fn = Native.upcall(Commands.class, "permission", Abi.Fn.PermissionFn);
        int status = Host.set_permission_provider(Native.plugin, fn, MemorySegment.NULL);
        if (status != Abi.status.OK) {
            Native.log(Abi.log_level.WARN, "Another plugin provides permissions (" + status
                    + "): server commands do not see Bukkit permissions");
        }
    }

    private static int permission(MemorySegment user, MemorySegment uuid, MemorySegment node, MemorySegment out) {
        int answer = Abi.permission.UNDEFINED;
        try {
            VPlayer p = Layer.server.players.get(Native.uuid(uuid));
            if (p != null) {
                String name = Native.string(node).toLowerCase(Locale.ROOT);
                if (name.startsWith(NODE)) {
                    answer = mayUse(p, name.substring(NODE.length())) ? Abi.permission.ALLOW : Abi.permission.DENY;
                } else if (p.isPermissionSet(name)) {
                    answer = p.hasPermission(name) ? Abi.permission.ALLOW : Abi.permission.DENY;
                }
            }
        } catch (Throwable t) {
            Native.log(Abi.log_level.WARN, "Permission check failed: " + Native.trace(t));
        }
        out.reinterpret(4).set(JAVA_INT, 0, answer);
        return Abi.status.OK;
    }

    /** Paper's {@code canUse} of a Bukkit command tree: known, its plugin enabled, permitted. */
    private static boolean mayUse(VPlayer p, String label) {
        Command c = Layer.server.commandMap.getCommand(label);
        if (c == null || c instanceof org.bukkit.command.PluginIdentifiableCommand pc && !pc.getPlugin().isEnabled()) {
            return false;
        }
        return c.testPermissionSilent(p);
    }

    /** The command a label names, if Bukkit has one. */
    static Command command(String label) {
        return Layer.server.commandMap.getCommand(label.toLowerCase(Locale.ROOT));
    }
}
