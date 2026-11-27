// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.injector.GamePhase;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.plugin.Plugin;

/** A packet listener with whitelists built from its packet types. */
public abstract class PacketAdapter implements PacketListener {
    protected Plugin plugin;
    protected ConnectionSide connectionSide;
    protected ListeningWhitelist receivingWhitelist = ListeningWhitelist.EMPTY_WHITELIST;
    protected ListeningWhitelist sendingWhitelist = ListeningWhitelist.EMPTY_WHITELIST;

    public PacketAdapter(AdapterParameteters params) {
        this(params.plugin, params.priority, params.types, params.phase, params.side, params.options);
    }

    public PacketAdapter(Plugin plugin, PacketType... types) {
        this(plugin, ListenerPriority.NORMAL, types);
    }

    public PacketAdapter(Plugin plugin, Iterable<? extends PacketType> types) {
        this(plugin, ListenerPriority.NORMAL, types);
    }

    public PacketAdapter(Plugin plugin, ListenerPriority priority, Iterable<? extends PacketType> types) {
        this(plugin, priority, types, new ListenerOptions[0]);
    }

    public PacketAdapter(Plugin plugin, ListenerPriority priority, Iterable<? extends PacketType> types,
            ListenerOptions... options) {
        this(plugin, priority, set(types), GamePhase.PLAYING, null, EnumSet.noneOf(ListenerOptions.class));
        this.receivingWhitelist = merge(receivingWhitelist, options);
        this.sendingWhitelist = merge(sendingWhitelist, options);
    }

    public PacketAdapter(Plugin plugin, ListenerPriority priority, PacketType... types) {
        this(plugin, priority, Arrays.asList(types));
    }

    private PacketAdapter(Plugin plugin, ListenerPriority priority, Set<PacketType> types, GamePhase phase,
            ConnectionSide side, Set<ListenerOptions> options) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }
        this.plugin = plugin;
        Set<PacketType> sending = new LinkedHashSet<>();
        Set<PacketType> receiving = new LinkedHashSet<>();
        for (PacketType t : types) {
            (t.isServer() ? sending : receiving).add(t);
        }
        boolean wantServer = side == null ? !sending.isEmpty() : side.isForServer();
        boolean wantClient = side == null ? !receiving.isEmpty() : side.isForClient();
        if (wantServer) {
            sendingWhitelist = ListeningWhitelist.newBuilder().priority(priority).types(sending).gamePhase(phase)
                    .options(options).build();
        }
        if (wantClient) {
            receivingWhitelist = ListeningWhitelist.newBuilder().priority(priority).types(receiving).gamePhase(phase)
                    .options(options).build();
        }
        this.connectionSide = wantServer && wantClient ? ConnectionSide.BOTH
                : wantClient ? ConnectionSide.CLIENT_SIDE : ConnectionSide.SERVER_SIDE;
    }

    private static Set<PacketType> set(Iterable<? extends PacketType> types) {
        Set<PacketType> out = new LinkedHashSet<>();
        for (PacketType t : types) {
            out.add(t);
        }
        return out;
    }

    private static ListeningWhitelist merge(ListeningWhitelist w, ListenerOptions[] options) {
        return w == ListeningWhitelist.EMPTY_WHITELIST || options.length == 0 ? w
                : ListeningWhitelist.newBuilder(w).mergeOptions(options).build();
    }

    public static String getPluginName(PacketListener listener) {
        return getPluginName(listener.getPlugin());
    }

    public static String getPluginName(Plugin plugin) {
        return plugin == null ? "UNKNOWN" : plugin.getName();
    }

    public static AdapterParameteters params() {
        return new AdapterParameteters();
    }

    public static AdapterParameteters params(Plugin plugin, PacketType... types) {
        return new AdapterParameteters().plugin(plugin).types(types);
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        throw new IllegalStateException("Override onPacketReceiving to get notifications of received packets!");
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        throw new IllegalStateException("Override onPacketSending to get notifications of sent packets!");
    }

    @Override
    public ListeningWhitelist getReceivingWhitelist() {
        return receivingWhitelist;
    }

    @Override
    public ListeningWhitelist getSendingWhitelist() {
        return sendingWhitelist;
    }

    @Override
    public Plugin getPlugin() {
        return plugin;
    }

    @Override
    public String toString() {
        return "PacketAdapter[plugin=" + getPluginName(this) + ", sending=" + sendingWhitelist + ", receiving="
                + receivingWhitelist + "]";
    }

    /** Parameters of a packet adapter (the misspelling is ProtocolLib's). */
    public static class AdapterParameteters {
        private Plugin plugin;
        private ConnectionSide side;
        private ListenerPriority priority = ListenerPriority.NORMAL;
        private GamePhase phase = GamePhase.PLAYING;
        private final Set<ListenerOptions> options = EnumSet.noneOf(ListenerOptions.class);
        private final Set<PacketType> types = new LinkedHashSet<>();

        public AdapterParameteters() {}

        public AdapterParameteters plugin(Plugin plugin) {
            this.plugin = plugin;
            return this;
        }

        public AdapterParameteters connectionSide(ConnectionSide side) {
            this.side = side;
            return this;
        }

        public AdapterParameteters clientSide() {
            return connectionSide(ConnectionSide.add(side, ConnectionSide.CLIENT_SIDE));
        }

        public AdapterParameteters serverSide() {
            return connectionSide(ConnectionSide.add(side, ConnectionSide.SERVER_SIDE));
        }

        public AdapterParameteters listenerPriority(ListenerPriority priority) {
            this.priority = priority;
            return this;
        }

        public AdapterParameteters gamePhase(GamePhase phase) {
            this.phase = phase;
            return this;
        }

        public AdapterParameteters loginPhase() {
            return gamePhase(GamePhase.LOGIN);
        }

        public AdapterParameteters options(ListenerOptions... options) {
            this.options.addAll(Arrays.asList(options));
            return this;
        }

        public AdapterParameteters options(Set<? extends ListenerOptions> options) {
            this.options.addAll(options);
            return this;
        }

        public AdapterParameteters optionAsync() {
            return options(ListenerOptions.ASYNC);
        }

        public AdapterParameteters optionSync() {
            return options(ListenerOptions.SYNC);
        }

        public AdapterParameteters types(PacketType... types) {
            this.types.addAll(Arrays.asList(types));
            return this;
        }

        public AdapterParameteters types(Set<PacketType> types) {
            this.types.addAll(types);
            return this;
        }
    }
}
