// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.ListeningWhitelist;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import dev.vanillite.bukkit.Ctx;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * ProtocolLib's packet listeners and packet sending on Vanillite's packet interface: one native
 * listener per packet type that has Java listeners, which runs them in priority order (monitors
 * last, read-only) and hands a changed packet back to the server.
 */
public final class Packets {
    private Packets() {}

    // ----- Ids and names

    public static int idOf(PacketType.Protocol protocol, PacketType.Sender sender, String vanilla) {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        int status = Host.packet_id(Native.plugin, protocol.state(), sender.direction(), s.str(vanilla), out);
        return status == Abi.status.OK ? out.get(JAVA_INT, 0) : -1;
    }

    public static String nameOf(PacketType.Protocol protocol, PacketType.Sender sender, int id) {
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(128);
        if (Host.packet_name(Native.plugin, protocol.state(), sender.direction(), id, buf) != Abi.status.OK) {
            return null;
        }
        String name = Native.string(buf, true);
        return name != null && name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    // ----- Entities

    /** The entity with a network id in a world; null when there is none. */
    public static Entity entity(World world, int id) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getEntityId() == id) {
                return p;
            }
        }
        if (world == null) {
            return null;
        }
        for (Entity e : world.getEntities()) {
            if (e.getEntityId() == id) {
                return e;
            }
        }
        return null;
    }

    // ----- Listeners

    /** The Java listeners of one packet type and direction, and its native listener. */
    private static final class Subscription {
        final PacketType type;
        final List<Entry> listeners = new CopyOnWriteArrayList<>();
        long handle;

        Subscription(PacketType type) {
            this.type = type;
        }
    }

    private record Entry(PacketListener listener, ListenerPriority priority) {}

    private static final Map<PacketType, Subscription> SUBSCRIPTIONS = new ConcurrentHashMap<>();
    private static final List<Subscription> BY_INDEX = new CopyOnWriteArrayList<>();
    private static final Set<PacketListener> LISTENERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile MemorySegment run;
    private static volatile MethodHandle setBody;
    private static volatile long setBodyAddress;

    public static Set<PacketListener> listeners() {
        return Set.copyOf(LISTENERS);
    }

    public static synchronized void add(PacketListener listener) {
        if (!LISTENERS.add(listener)) {
            return;
        }
        attach(listener, listener.getSendingWhitelist());
        attach(listener, listener.getReceivingWhitelist());
    }

    private static void attach(PacketListener listener, ListeningWhitelist whitelist) {
        if (ListeningWhitelist.isEmpty(whitelist)) {
            return;
        }
        for (PacketType type : whitelist.getTypes()) {
            if (!type.isSupported()) {
                warn(listener, "listens to " + type.name() + " (" + type.getProtocol() + "), which Minecraft "
                        + Bukkit.getMinecraftVersion() + " does not have; ignored");
                continue;
            }
            if (type.getProtocol() == PacketType.Protocol.PLAY && Codecs.of(type.isServer(), type.vanillaName()) == null) {
                warn(listener, "listens to " + type.name() + ": Vanillite delivers it (cancel, replace) but maps "
                        + "none of its fields");
            }
            Subscription sub = SUBSCRIPTIONS.computeIfAbsent(type, Subscription::new);
            sub.listeners.add(new Entry(listener, whitelist.getPriority()));
            List<Entry> sorted = new ArrayList<>(sub.listeners);
            sorted.sort(Comparator.comparingInt(e -> e.priority().getSlot()));
            sub.listeners.clear();
            sub.listeners.addAll(sorted);
            if (sub.handle == 0) {
                subscribe(sub);
            }
        }
    }

    private static void subscribe(Subscription sub) {
        if (run == null) {
            run = Native.upcall(Packets.class, "run", Abi.Fn.PacketFn);
        }
        int index = BY_INDEX.indexOf(sub);
        if (index < 0) {
            BY_INDEX.add(sub);
            index = BY_INDEX.size() - 1;
        }
        var s = Native.scratch();
        MemorySegment spec = s.alloc(Abi.PacketSpec.SIZE);
        spec.fill((byte) 0);
        spec.set(JAVA_INT, Abi.PacketSpec.size, (int) Abi.PacketSpec.SIZE);
        spec.set(JAVA_INT, Abi.PacketSpec.direction, sub.type.getSender().direction());
        spec.set(JAVA_INT, Abi.PacketSpec.state, sub.type.getProtocol().state());
        spec.set(JAVA_INT, Abi.PacketSpec.id, sub.type.getCurrentId());
        spec.set(JAVA_INT, Abi.PacketSpec.priority, Abi.priority.NORMAL);
        spec.set(JAVA_INT, Abi.PacketSpec.flags, 0);
        spec.set(ADDRESS, Abi.PacketSpec.run, run);
        spec.set(ADDRESS, Abi.PacketSpec.drop, MemorySegment.NULL);
        spec.set(ADDRESS, Abi.PacketSpec.user, MemorySegment.ofAddress(index + 1L));
        MemorySegment out = s.alloc(8);
        int status = Host.packet_subscribe(Native.plugin, spec, out);
        if (status != Abi.status.OK) {
            Native.log(Abi.log_level.ERROR, "[ProtocolLib] Could not listen to " + sub.type + " (status " + status + ")");
            return;
        }
        sub.handle = out.get(JAVA_LONG, 0);
    }

    public static synchronized void remove(PacketListener listener) {
        if (!LISTENERS.remove(listener)) {
            return;
        }
        for (Subscription sub : SUBSCRIPTIONS.values()) {
            sub.listeners.removeIf(e -> e.listener() == listener);
            if (sub.listeners.isEmpty() && sub.handle != 0) {
                Host.packet_unsubscribe(Native.plugin, sub.handle);
                sub.handle = 0;
            }
        }
    }

    public static synchronized void removeAll(org.bukkit.plugin.Plugin plugin) {
        for (PacketListener l : listeners()) {
            if (l.getPlugin() == plugin || (l.getPlugin() != null && plugin != null && l.getPlugin().getName().equals(plugin.getName()))) {
                remove(l);
            }
        }
    }

    public static Set<PacketType> types(boolean sending) {
        Set<PacketType> out = ConcurrentHashMap.newKeySet();
        for (Subscription sub : SUBSCRIPTIONS.values()) {
            if (!sub.listeners.isEmpty() && sub.type.isServer() == sending) {
                out.add(sub.type);
            }
        }
        return out;
    }

    private static void warn(PacketListener listener, String what) {
        String text = "[ProtocolLib] " + (listener.getPlugin() == null ? "A plugin" : listener.getPlugin().getName()) + " "
                + what;
        if (WARNED.add(text)) {
            Native.log(Abi.log_level.WARN, text);
        }
    }

    /** The native packet listener: runs the Java listeners of one packet type. */
    @SuppressWarnings("unused")
    private static int run(MemorySegment user, MemorySegment ctx, MemorySegment event) {
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        Native.epoch++;
        try {
            int index = (int) user.address() - 1;
            Subscription sub = index >= 0 && index < BY_INDEX.size() ? BY_INDEX.get(index) : null;
            if (sub == null || sub.listeners.isEmpty()) {
                return Abi.status.OK;
            }
            MemorySegment ev = event.reinterpret(Abi.PacketEvent.SIZE);
            long len = ev.get(JAVA_LONG, Abi.PacketEvent.body + Abi.Bytes.len);
            byte[] body = ev.get(ADDRESS, Abi.PacketEvent.body + Abi.Bytes.ptr).reinterpret(len).toArray(JAVA_BYTE);
            UUID uuid = Native.uuid(ev.asSlice(Abi.PacketEvent.player, Abi.Uuid.SIZE));
            Player player = uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0 ? null
                    : Bukkit.getPlayer(uuid);
            boolean sending = sub.type.isServer();
            PacketContainer packet = PacketContainer.fromBody(sub.type, body);
            PacketEvent pe = sending ? PacketEvent.fromServer(sub, packet, player) : PacketEvent.fromClient(sub, packet, player);
            pe.initCancelled(ev.get(JAVA_INT, Abi.PacketEvent.cancelled) != 0);
            for (Entry e : sub.listeners) {
                boolean monitor = e.priority() == ListenerPriority.MONITOR;
                pe.setReadOnly(monitor);
                try {
                    if (sending) {
                        e.listener().onPacketSending(pe);
                    } else {
                        e.listener().onPacketReceiving(pe);
                    }
                } catch (Throwable t) {
                    String name = e.listener().getPlugin() == null ? "?" : e.listener().getPlugin().getName();
                    Native.log(Abi.log_level.ERROR, "[ProtocolLib] " + name + " failed on " + sub.type.name() + ": "
                            + Native.trace(t));
                }
            }
            ev.set(JAVA_INT, Abi.PacketEvent.cancelled, pe.isCancelled() ? 1 : 0);
            PacketContainer result = pe.getPacket();
            if (!pe.isCancelled() && (pe.isReplaced() || result.isChanged())) {
                replace(ev, result.encode());
            }
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "[ProtocolLib] Packet listener failed: " + Native.trace(t));
        } finally {
            Ctx.current = previous;
            Native.epoch++;
        }
        return Abi.status.OK;
    }

    private static void replace(MemorySegment ev, byte[] body) throws Throwable {
        MemorySegment out = ev.get(ADDRESS, Abi.PacketEvent.out).reinterpret(Abi.PacketOut.SIZE);
        long fn = out.get(ADDRESS, Abi.PacketOut.set).address();
        MethodHandle set = setBody;
        if (set == null || setBodyAddress != fn) {
            set = Native.LINKER.downcallHandle(MemorySegment.ofAddress(fn), Abi.Fn.PacketSetFn);
            setBody = set;
            setBodyAddress = fn;
        }
        var s = Native.scratch();
        MemorySegment data = s.alloc(Math.max(body.length, 1));
        MemorySegment.copy(body, 0, data, JAVA_BYTE, 0, body.length);
        MemorySegment bytes = s.alloc(Abi.Bytes.SIZE);
        bytes.set(ADDRESS, Abi.Bytes.ptr, data);
        bytes.set(JAVA_LONG, Abi.Bytes.len, body.length);
        int status = (int) set.invokeExact(out.get(ADDRESS, Abi.PacketOut.state), bytes);
        if (status != Abi.status.OK) {
            Native.log(Abi.log_level.WARN, "[ProtocolLib] The server refused a changed packet (status " + status + ")");
        }
    }

    // ----- Sending

    /** Sends a play packet to a player; {@code filters}: through the packet listeners. */
    public static void send(Player player, PacketContainer packet, boolean filters) {
        if (!packet.getType().isServer() || packet.getType().getProtocol() != PacketType.Protocol.PLAY) {
            throw new IllegalArgumentException("Vanillite can only send play packets of the server, not " + packet.getType());
        }
        call(player, packet.encode(), filters, true);
    }

    /** Feeds a play packet into a player's connection as if the client sent it. */
    public static void receive(Player player, PacketContainer packet, boolean filters) {
        if (!packet.getType().isClient() || packet.getType().getProtocol() != PacketType.Protocol.PLAY) {
            throw new IllegalArgumentException("Vanillite can only receive play packets of the client, not " + packet.getType());
        }
        call(player, packet.encode(), filters, false);
    }

    /** Sends a raw packet body (id and fields). */
    public static void sendRaw(Player player, byte[] body) {
        call(player, body, false, true);
    }

    private static void call(Player player, byte[] body, boolean filters, boolean send) {
        var s = Native.scratch();
        MemorySegment uuid = s.uuid(player.getUniqueId());
        MemorySegment data = s.alloc(Math.max(body.length, 1));
        MemorySegment.copy(body, 0, data, JAVA_BYTE, 0, body.length);
        MemorySegment bytes = s.alloc(Abi.Bytes.SIZE);
        bytes.set(ADDRESS, Abi.Bytes.ptr, data);
        bytes.set(JAVA_LONG, Abi.Bytes.len, body.length);
        int flags = filters ? 0 : Abi.packet_send.SKIP_LISTENERS;
        int status = send ? Host.packet_send(Native.plugin, uuid, bytes, flags)
                : Host.packet_receive(Native.plugin, uuid, bytes, flags);
        if (status == Abi.status.NOT_FOUND) {
            return;
        }
        if (status != Abi.status.OK) {
            throw new IllegalStateException("Vanillite: the server refused the packet (status " + status + ")");
        }
    }

    /** The server's protocol version. */
    public static int protocolVersion() {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        return Host.protocol_version(Native.plugin, out) == Abi.status.OK ? out.get(JAVA_INT, 0) : -1;
    }
}
