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
 * Bukkit commands on Vanillite. Every label of the command map gets a native command tree (a
 * literal with one greedy argument) for the console, command blocks and the client's command list
 * and tab completion; labels the server already has (Vanilla's commands) keep the native tree.
 * Players' commands go through {@code PlayerCommandPreprocessEvent} ({@link Events}), where a
 * Bukkit command takes precedence over a Vanilla one with the same label, as on Paper.
 */
final class Commands {
    private Commands() {}

    /** Number of the layer's own diagnostics command. */
    static final int DIAG = 65_535;
    private static final List<String> LABELS = new ArrayList<>();

    /** Registers native trees for the labels of the command map (at load). */
    static void register() {
        var execute = Native.upcall(Commands.class, "execute", Abi.Fn.CommandFn);
        var suggest = Native.upcall(Commands.class, "suggest", Abi.Fn.SuggestFn);
        Map<String, Integer> trees = new java.util.LinkedHashMap<>();
        for (String label : Layer.server.commandMap.getKnownCommands().keySet()) {
            if (label.indexOf(':') < 0 && label.matches("[a-z0-9_.-]+")) {
                LABELS.add(label);
                trees.put(label, LABELS.size());
            }
        }
        trees.put("vjvm", DIAG);
        int skipped = 0;
        try (Arena arena = Arena.ofConfined()) {
            for (var tree : trees.entrySet()) {
                int number = tree.getValue();
                int level = number == DIAG ? 4 : 0;
                long stride = Abi.CommandNode.SIZE;
                MemorySegment nodes = arena.allocate(stride * 2, 8);
                node(arena, nodes, -1, Abi.node.LITERAL, tree.getKey(), number, level, 0);
                node(arena, nodes.asSlice(stride), 0, Abi.node.ARGUMENT, "args", number, level, number);
                MemorySegment spec = arena.allocate(Abi.CommandSpec.LAYOUT);
                spec.set(JAVA_INT, Abi.CommandSpec.size, (int) Abi.CommandSpec.SIZE);
                spec.set(ADDRESS, Abi.CommandSpec.nodes, nodes);
                spec.set(JAVA_LONG, Abi.CommandSpec.count, 2);
                spec.set(JAVA_LONG, Abi.CommandSpec.stride, stride);
                spec.set(ADDRESS, Abi.CommandSpec.execute, execute);
                spec.set(ADDRESS, Abi.CommandSpec.suggest, suggest);
                spec.set(ADDRESS, Abi.CommandSpec.user, MemorySegment.NULL);
                if (Host.register_command(Native.plugin, spec) != Abi.status.OK) {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            Native.log(Abi.log_level.INFO, skipped + " Bukkit command label(s) are also server commands: players get the "
                    + "plugin's command, the console the server's");
        }
    }

    private static void node(Arena arena, MemorySegment n, int parent, int kind, String name, int executes, int level,
            int suggests) {
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
        try {
            MemorySegment req = request.reinterpret(Abi.SuggestRequest.SIZE);
            int number = req.get(JAVA_INT, Abi.SuggestRequest.provider);
            if (number < 1 || number > LABELS.size()) {
                return Abi.status.OK;
            }
            String remaining = Events.str(req, Abi.SuggestRequest.remaining);
            CommandSender who = sender(req, Abi.SuggestRequest.sender);
            String line = LABELS.get(number - 1) + " " + remaining;
            List<String> found = Layer.server.commandMap.tabComplete(who, line);
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
        }
        return Abi.status.OK;
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
                if (p.isPermissionSet(name)) {
                    answer = p.hasPermission(name) ? Abi.permission.ALLOW : Abi.permission.DENY;
                }
            }
        } catch (Throwable t) {
            Native.log(Abi.log_level.WARN, "Permission check failed: " + Native.trace(t));
        }
        out.reinterpret(4).set(JAVA_INT, 0, answer);
        return Abi.status.OK;
    }

    /** The command a label names, if Bukkit has one. */
    static Command command(String label) {
        return Layer.server.commandMap.getCommand(label.toLowerCase(Locale.ROOT));
    }
}
