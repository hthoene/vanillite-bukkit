// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import com.destroystokyo.paper.profile.PlayerProfile;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Native events → Bukkit events. Every native kind some plugin may listen to is subscribed once
 * per Bukkit priority, so Bukkit listeners interleave with native plugins: the listeners of
 * {@code LOWEST} run at the native {@code LOWEST} stage, and so on. The Bukkit event objects of one
 * native event live from its first stage to its {@code MONITOR} stage; after each stage the
 * changes (cancelled, texts, locations) are written back into the native event.
 */
final class Events {
    private Events() {}

    /** Bukkit event class (internal name) → native kind. */
    static final Map<String, Integer> KINDS = WorldEvents.withKinds(
            Map.entry("org/bukkit/event/player/PlayerJoinEvent", Abi.event.PLAYER_JOIN),
            Map.entry("org/bukkit/event/player/PlayerQuitEvent", Abi.event.PLAYER_LEAVE),
            Map.entry("org/bukkit/event/block/BlockBreakEvent", Abi.event.BLOCK_BREAK),
            Map.entry("org/bukkit/event/player/AsyncPlayerPreLoginEvent", Abi.event.PLAYER_LOGIN),
            Map.entry("org/bukkit/event/player/PlayerLoginEvent", Abi.event.PLAYER_LOGIN),
            Map.entry("org/bukkit/event/player/AsyncPlayerChatEvent", Abi.event.PLAYER_CHAT),
            Map.entry("io/papermc/paper/event/player/AsyncChatEvent", Abi.event.PLAYER_CHAT),
            Map.entry("org/bukkit/event/player/PlayerCommandPreprocessEvent", Abi.event.PLAYER_COMMAND),
            Map.entry("org/bukkit/event/player/PlayerTeleportEvent", Abi.event.PLAYER_TELEPORT),
            Map.entry("org/bukkit/event/player/PlayerRespawnEvent", Abi.event.PLAYER_RESPAWN));

    /** True while Bukkit listeners of a menu event run: menu changes they make wait for the next tick. */
    static boolean inMenuEvent;

    /** How many native events are being dispatched right now (nested events count each). */
    static int depth;

    /** Kinds subscribed at load. */
    static final TreeSet<Integer> subscribed = new TreeSet<>();
    private static final Set<Class<?>> warned = new HashSet<>();

    private static final EventPriority[] PRIORITIES = EventPriority.values();

    /** A plugin registered a listener: warn once when its native event was not subscribed. */
    static void listened(Class<?> type) {
        Integer kind = KINDS.get(type.getName().replace('.', '/'));
        if (kind != null && !subscribed.contains(kind) && warned.add(type)) {
            Native.log(Abi.log_level.WARN, type.getSimpleName() + " listeners registered at run time get no events: "
                    + "the event was not found in any plugin jar at load");
        } else if (kind == null && warned.add(type) && !type.getName().startsWith("org.bukkit.event.server")
                && type != org.bukkit.event.world.WorldInitEvent.class && type != org.bukkit.event.world.WorldLoadEvent.class
                && (type.getName().startsWith("org.bukkit.") || type.getName().startsWith("io.papermc.")
                        || type.getName().startsWith("com.destroystokyo."))) {
            Native.log(Abi.log_level.INFO, "Event " + type.getName() + " is not delivered by Vanillite yet");
        }
    }

    static void subscribe(Set<Integer> kinds) {
        var upcall = Native.upcall(Events.class, "event", Abi.Fn.EventFn);
        for (int kind : kinds) {
            for (EventPriority p : PRIORITIES) {
                Ctx.check(Host.subscribe(Native.plugin, kind, nativePriority(p), upcall, MemorySegment.ofAddress(p.ordinal())),
                        "subscribe");
            }
            subscribed.add(kind);
        }
    }

    private static int nativePriority(EventPriority p) {
        return switch (p) {
            case LOWEST -> Abi.priority.LOWEST;
            case LOW -> Abi.priority.LOW;
            case NORMAL -> Abi.priority.NORMAL;
            case HIGH -> Abi.priority.HIGH;
            case HIGHEST -> Abi.priority.HIGHEST;
            case MONITOR -> Abi.priority.MONITOR;
        };
    }

    /** The Bukkit side of one native event, alive from its first to its last stage. */
    abstract static class State {
        final MemorySegment header;

        State(MemorySegment header) {
            this.header = header;
        }

        /** Runs the listeners of one priority and writes the changes back. */
        abstract void stage(EventPriority p);

        /** After the {@code MONITOR} stage. */
        void done() {}

        boolean cancelled() {
            return header.get(JAVA_INT, Abi.EventHeader.cancelled) != 0;
        }

        void cancel(boolean on) {
            header.set(JAVA_INT, Abi.EventHeader.cancelled, on ? 1 : 0);
        }
    }

    private static final Map<Long, State> STATES = new HashMap<>();

    private static int event(MemorySegment user, MemorySegment ctx, MemorySegment header) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        Native.epoch++;
        EventPriority priority = PRIORITIES[(int) user.address()];
        long key = header.address();
        depth++;
        try {
            State state = STATES.get(key);
            if (state == null) {
                if (priority != EventPriority.LOWEST) {
                    return Abi.status.OK;
                }
                state = create(header);
                if (state == null) {
                    return Abi.status.OK;
                }
                STATES.put(key, state);
            }
            state.stage(priority);
            if (priority == EventPriority.MONITOR) {
                STATES.remove(key);
                state.done();
            }
        } catch (Throwable t) {
            STATES.remove(key);
            Native.log(Abi.log_level.ERROR, "Event dispatch failed: " + Native.trace(t));
        } finally {
            depth--;
            Ctx.current = previous;
            Native.epoch++;
        }
        return Abi.status.OK;
    }

    private static State create(MemorySegment header) {
        int kind = header.reinterpret(Abi.EventHeader.SIZE).get(JAVA_INT, Abi.EventHeader.kind);
        return switch (kind) {
            case Abi.event.PLAYER_LOGIN -> new Login(header.reinterpret(Abi.LoginEvent.SIZE));
            case Abi.event.PLAYER_JOIN -> new Join(header.reinterpret(Abi.PlayerEvent.SIZE));
            case Abi.event.PLAYER_LEAVE -> Quit.of(header.reinterpret(Abi.PlayerEvent.SIZE));
            case Abi.event.PLAYER_CHAT -> Chat.of(header.reinterpret(Abi.PlayerTextEvent.SIZE));
            case Abi.event.PLAYER_COMMAND -> CommandLine.of(header.reinterpret(Abi.PlayerTextEvent.SIZE));
            case Abi.event.BLOCK_BREAK -> BreakBlock.of(header.reinterpret(Abi.BlockEvent.SIZE));
            case Abi.event.PLAYER_TELEPORT -> Teleport.of(header.reinterpret(Abi.GameEvent.SIZE));
            case Abi.event.PLAYER_RESPAWN -> Respawn.of(header.reinterpret(Abi.PlayerWorldEvent.SIZE));
            default -> WorldEvents.create(kind, header);
        };
    }

    // ----- Helpers

    static String str(MemorySegment rec, long offset) {
        return Native.string(rec.asSlice(offset, Abi.Str.SIZE));
    }

    static UUID uuid(MemorySegment rec, long offset) {
        return Native.uuid(rec.asSlice(offset, Abi.Uuid.SIZE));
    }

    private static final Map<Long, MethodHandle> TEXT_SETTERS = new HashMap<>();

    /** Writes a text through a native {@code TextOut}. */
    static void setText(MemorySegment out, String text, int format) {
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

    static Component fromJson(String json) {
        if (json.isEmpty()) {
            return Component.empty();
        }
        try {
            return GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException e) {
            return Component.text(json);
        }
    }

    static String legacy(Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c);
    }

    static Location location(MemorySegment loc, long offset) {
        VWorld world = Layer.server.world(loc.get(JAVA_INT, offset + Abi.Location.world));
        return new Location(world,
                loc.get(JAVA_DOUBLE, offset + Abi.Location.pos + Abi.Vec3.x),
                loc.get(JAVA_DOUBLE, offset + Abi.Location.pos + Abi.Vec3.y),
                loc.get(JAVA_DOUBLE, offset + Abi.Location.pos + Abi.Vec3.z),
                loc.get(JAVA_FLOAT, offset + Abi.Location.yaw),
                loc.get(JAVA_FLOAT, offset + Abi.Location.pitch));
    }

    static void writeLocation(MemorySegment rec, long offset, Location l) {
        rec.set(JAVA_INT, offset + Abi.Location.world, l.getWorld() instanceof VWorld w ? w.id : 0);
        rec.set(JAVA_DOUBLE, offset + Abi.Location.pos + Abi.Vec3.x, l.getX());
        rec.set(JAVA_DOUBLE, offset + Abi.Location.pos + Abi.Vec3.y, l.getY());
        rec.set(JAVA_DOUBLE, offset + Abi.Location.pos + Abi.Vec3.z, l.getZ());
        rec.set(JAVA_FLOAT, offset + Abi.Location.yaw, l.getYaw());
        rec.set(JAVA_FLOAT, offset + Abi.Location.pitch, l.getPitch());
    }

    static boolean listens(Class<? extends Event> type) {
        return VPluginManager.handlers(type).getRegisteredListeners().length > 0;
    }

    // ----- Login: AsyncPlayerPreLoginEvent (whole, first), then PlayerLoginEvent per priority

    private static final class Login extends State {
        private final PlayerLoginEvent login;

        Login(MemorySegment e) {
            super(e);
            UUID id = uuid(e, Abi.LoginEvent.player);
            String name = str(e, Abi.LoginEvent.name);
            String address = str(e, Abi.LoginEvent.address);
            InetAddress inet;
            try {
                inet = InetAddress.getByName(address.isEmpty() ? "127.0.0.1" : address);
            } catch (java.net.UnknownHostException ex) {
                inet = InetAddress.getLoopbackAddress();
            }
            if (listens(AsyncPlayerPreLoginEvent.class)) {
                PlayerProfile profile = VProfile.create(id, name);
                AsyncPlayerPreLoginEvent pre = new AsyncPlayerPreLoginEvent(name, inet, inet, id, false, profile);
                Layer.server.pluginManager.callEvent(pre);
                if (pre.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    cancel(true);
                    setText(e.get(ADDRESS, Abi.LoginEvent.message_out), Texts.json(pre.kickMessage()), Abi.text_format.JSON);
                }
            }
            VPlayer player = Layer.server.pending(id, name);
            login = new PlayerLoginEvent(player, address, inet, inet);
            if (cancelled()) {
                int reason = e.get(JAVA_INT, Abi.LoginEvent.reason);
                PlayerLoginEvent.Result result = switch (reason) {
                    case Abi.login_reason.BANNED -> PlayerLoginEvent.Result.KICK_BANNED;
                    case Abi.login_reason.NOT_WHITELISTED -> PlayerLoginEvent.Result.KICK_WHITELIST;
                    case Abi.login_reason.FULL -> PlayerLoginEvent.Result.KICK_FULL;
                    default -> PlayerLoginEvent.Result.KICK_OTHER;
                };
                login.disallow(result, fromJson(str(e, Abi.LoginEvent.message)));
            }
        }

        @Override
        void stage(EventPriority p) {
            boolean wasAllowed = login.getResult() == PlayerLoginEvent.Result.ALLOWED;
            Component before = login.kickMessage();
            Layer.server.pluginManager.call(login, p);
            boolean allowed = login.getResult() == PlayerLoginEvent.Result.ALLOWED;
            if (allowed != wasAllowed || (!allowed && !before.equals(login.kickMessage()))) {
                cancel(!allowed);
                if (!allowed) {
                    setText(header.get(ADDRESS, Abi.LoginEvent.message_out), Texts.json(login.kickMessage()), Abi.text_format.JSON);
                }
            }
        }

        @Override
        void done() {
            if (cancelled()) {
                Layer.server.dropPending(login.getPlayer().getUniqueId());
            }
        }
    }

    // ----- Join and quit

    private static final class Join extends State {
        private final PlayerJoinEvent event;
        private Component message;

        Join(MemorySegment e) {
            super(e);
            UUID id = uuid(e, Abi.PlayerEvent.player);
            VWorld world = Layer.server.world(e.get(JAVA_INT, Abi.PlayerEvent.world));
            VPlayer player = Layer.server.joined(id, str(e, Abi.PlayerEvent.name), world);
            try {
                player.getLocation();
            } catch (RuntimeException ex) {
                // not readable here; the first move tells it
            }
            message = fromJson(str(e, Abi.PlayerEvent.message));
            event = new PlayerJoinEvent(player, message);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            Component now = event.joinMessage();
            if (now != message) {
                message = now;
                setText(header.get(ADDRESS, Abi.PlayerEvent.message_out), now == null ? "" : Texts.json(now), Abi.text_format.JSON);
            }
        }
    }

    private static final class Quit extends State {
        private final PlayerQuitEvent event;
        private final VPlayer player;
        private Component message;

        private Quit(MemorySegment e, VPlayer player) {
            super(e);
            this.player = player;
            message = fromJson(str(e, Abi.PlayerEvent.message));
            event = new PlayerQuitEvent(player, message, PlayerQuitEvent.QuitReason.DISCONNECTED);
        }

        static Quit of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.PlayerEvent.player));
            return player == null ? null : new Quit(e, player);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            Component now = event.quitMessage();
            if (now != message) {
                message = now;
                setText(header.get(ADDRESS, Abi.PlayerEvent.message_out), now == null ? "" : Texts.json(now), Abi.text_format.JSON);
            }
        }

        @Override
        void done() {
            Layer.server.left(player);
        }
    }

    // ----- Chat: AsyncPlayerChatEvent (whole, first), then AsyncChatEvent per priority. A message
    // whose text, format or recipients plugins changed is sent as system messages to its recipients.

    private static final class Chat extends State {
        private final VPlayer player;
        private final AsyncChatEvent event;
        private final Component original;
        private Set<Player> legacyRecipients;
        private String legacyLine;

        private Chat(MemorySegment e, VPlayer player) {
            super(e);
            this.player = player;
            String text = str(e, Abi.PlayerTextEvent.text);
            Component message = Component.text(text);
            if (listens(AsyncPlayerChatEvent.class)) {
                Set<Player> recipients = new LinkedHashSet<>(Layer.server.players.values());
                AsyncPlayerChatEvent legacy = new AsyncPlayerChatEvent(true, player, text, recipients);
                Layer.server.pluginManager.callEvent(legacy);
                if (legacy.isCancelled()) {
                    cancel(true);
                }
                if (!legacy.getFormat().equals("<%1$s> %2$s") || !legacy.getMessage().equals(text)
                        || legacy.getRecipients().size() != Layer.server.players.size()) {
                    legacyLine = String.format(legacy.getFormat(), player.getDisplayName(), legacy.getMessage());
                    legacyRecipients = legacy.getRecipients();
                }
                message = Component.text(legacy.getMessage());
            }
            original = message;
            Set<Audience> viewers = new LinkedHashSet<>(Layer.server.players.values());
            viewers.add(Layer.server.console);
            event = new AsyncChatEvent(true, player, viewers, ChatRenderer.defaultRenderer(), message, message,
                    SignedMessage.system(text, message));
            event.setCancelled(cancelled());
        }

        static Chat of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.PlayerTextEvent.player));
            return player == null ? null : new Chat(e, player);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p == EventPriority.HIGHEST) {
                finish();
            } else if (p != EventPriority.MONITOR) {
                cancel(event.isCancelled());
            }
        }

        /** After the last stage that may change the event: decides how the message is shown. */
        private void finish() {
            if (event.isCancelled()) {
                cancel(true);
                return;
            }
            boolean rendered = event.renderer() != ChatRenderer.defaultRenderer();
            if (!rendered && legacyLine == null && event.message().equals(original)
                    && event.viewers().size() == Layer.server.players.size() + 1) {
                cancel(false);
                return;
            }
            if (!rendered && legacyLine == null) {
                // Only the text changed: the server shows it in the player's own chat line.
                cancel(false);
                setText(header.get(ADDRESS, Abi.PlayerTextEvent.text_out), Texts.json(event.message()), Abi.text_format.JSON);
                return;
            }
            cancel(true);
            Set<Audience> viewers = new LinkedHashSet<>(event.viewers());
            if (legacyRecipients != null) {
                viewers.removeIf(v -> v instanceof Player q && !legacyRecipients.contains(q));
            }
            for (Audience viewer : viewers) {
                Component line = rendered || legacyLine == null
                        ? event.renderer().render(player, player.displayName(), event.message(), viewer)
                        : LegacyComponentSerializer.legacySection().deserialize(legacyLine);
                if (viewer instanceof VConsole) {
                    Native.log(Abi.log_level.INFO, PlainTextComponentSerializer.plainText().serialize(line));
                } else {
                    viewer.sendMessage(line);
                }
            }
        }
    }

    // ----- Commands: PlayerCommandPreprocessEvent per priority; Bukkit commands run as a task on
    // the player's world (the native command is cancelled after the HIGHEST stage).

    private static final class CommandLine extends State {
        private final VPlayer player;
        private final PlayerCommandPreprocessEvent event;
        private String line;

        private CommandLine(MemorySegment e, VPlayer player) {
            super(e);
            this.player = player;
            line = "/" + str(e, Abi.PlayerTextEvent.text);
            event = new PlayerCommandPreprocessEvent(player, line, new HashSet<>(Layer.server.players.values()));
            event.setCancelled(cancelled());
        }

        static CommandLine of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.PlayerTextEvent.player));
            return player == null ? null : new CommandLine(e, player);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p == EventPriority.MONITOR) {
                return;
            }
            cancel(event.isCancelled());
            if (event.isCancelled()) {
                return;
            }
            if (!event.getMessage().equals(line)) {
                line = event.getMessage();
                setText(header.get(ADDRESS, Abi.PlayerTextEvent.text_out), line.startsWith("/") ? line.substring(1) : line,
                        Abi.text_format.PLAIN);
            }
            if (p == EventPriority.HIGHEST) {
                String commandLine = line.startsWith("/") ? line.substring(1) : line;
                String label = commandLine.split(" ", 2)[0].toLowerCase(Locale.ROOT);
                Command command = Layer.server.commandMap.getCommand(label);
                if (command != null) {
                    cancel(true);
                    Commands.runAsPlayer(player, commandLine);
                }
            }
        }
    }

    // ----- Blocks

    private static final class BreakBlock extends State {
        private final BlockBreakEvent event;

        private BreakBlock(MemorySegment e, BlockBreakEvent event) {
            super(e);
            this.event = event;
            event.setCancelled(cancelled());
        }

        static BreakBlock of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.BlockEvent.player));
            VWorld world = Layer.server.world(e.get(JAVA_INT, Abi.BlockEvent.world));
            if (player == null || world == null) {
                return null;
            }
            Block block = world.getBlockAt(e.get(JAVA_INT, Abi.BlockEvent.pos + Abi.BlockPos.x),
                    e.get(JAVA_INT, Abi.BlockEvent.pos + Abi.BlockPos.y),
                    e.get(JAVA_INT, Abi.BlockEvent.pos + Abi.BlockPos.z));
            return new BreakBlock(e, new BlockBreakEvent(block, player));
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p != EventPriority.MONITOR) {
                cancel(event.isCancelled());
            }
        }
    }

    // ----- Teleport and respawn

    private static final class Teleport extends State {
        private final PlayerTeleportEvent event;
        private Location to;

        private Teleport(MemorySegment e, VPlayer player) {
            super(e);
            Location from = location(e, Abi.GameEvent.from);
            to = location(e, Abi.GameEvent.to);
            PlayerTeleportEvent.TeleportCause cause = switch (e.get(JAVA_INT, Abi.GameEvent.cause)) {
                case Abi.teleport_cause.COMMAND -> PlayerTeleportEvent.TeleportCause.COMMAND;
                case Abi.teleport_cause.PLUGIN -> PlayerTeleportEvent.TeleportCause.PLUGIN;
                case Abi.teleport_cause.ENDER_PEARL -> PlayerTeleportEvent.TeleportCause.ENDER_PEARL;
                case Abi.teleport_cause.CONSUMABLE -> PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT;
                case Abi.teleport_cause.SPECTATE -> PlayerTeleportEvent.TeleportCause.SPECTATE;
                case Abi.teleport_cause.END_GATEWAY -> PlayerTeleportEvent.TeleportCause.END_GATEWAY;
                case Abi.teleport_cause.DISMOUNT -> PlayerTeleportEvent.TeleportCause.DISMOUNT;
                default -> PlayerTeleportEvent.TeleportCause.UNKNOWN;
            };
            PlayerTeleportEvent.TeleportCause pending = player.takeTeleportCause();
            event = new PlayerTeleportEvent(player, from, to.clone(), pending != null ? pending : cause);
            event.setCancelled(cancelled());
        }

        @Override
        void done() {
            if (!cancelled()) {
                ((VPlayer) event.getPlayer()).cacheLocation(to);
            }
        }

        static Teleport of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.GameEvent.entity));
            return player == null ? null : new Teleport(e, player);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
            if (p == EventPriority.MONITOR) {
                return;
            }
            cancel(event.isCancelled());
            if (!event.getTo().equals(to)) {
                to = event.getTo().clone();
                writeLocation(header, Abi.GameEvent.to, to);
            }
        }
    }

    private static final class Respawn extends State {
        private final PlayerRespawnEvent event;
        private final VPlayer player;
        private final Location at;

        private Respawn(MemorySegment e, VPlayer player) {
            super(e);
            this.player = player;
            VWorld world = Layer.server.world(e.get(JAVA_INT, Abi.PlayerWorldEvent.world));
            if (world != null) {
                player.world = world;
            }
            at = player.getLocation();
            event = new PlayerRespawnEvent(player, at.clone(), false, false, false,
                    PlayerRespawnEvent.RespawnReason.DEATH);
        }

        static Respawn of(MemorySegment e) {
            VPlayer player = Layer.server.players.get(uuid(e, Abi.PlayerWorldEvent.player));
            return player == null ? null : new Respawn(e, player);
        }

        @Override
        void stage(EventPriority p) {
            Layer.server.pluginManager.call(event, p);
        }

        @Override
        void done() {
            if (!event.getRespawnLocation().equals(at)) {
                player.teleport(event.getRespawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }
    }
}
