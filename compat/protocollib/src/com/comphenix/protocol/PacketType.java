// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol;

import com.comphenix.protocol.events.ConnectionSide;
import com.comphenix.protocol.utility.MinecraftVersion;
import dev.vanillite.bukkit.compat.protocollib.Packets;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A packet type: protocol state, sender and, on Vanillite, the packet's Vanilla name (e.g.
 * {@code set_entity_data}). Ids are looked up from the server; types the server does not have
 * (older Minecraft versions) are not supported ({@link #isSupported()} is false).
 */
public class PacketType implements Serializable, Cloneable, Comparable<PacketType> {
    private static final long serialVersionUID = 1L;
    public static final int UNKNOWN_PACKET = -1;

    private static final List<PacketType> ALL = new CopyOnWriteArrayList<>();
    private static final Map<String, PacketType> BY_NAME = new ConcurrentHashMap<>();

    /** Protocol states. */
    public enum Protocol {
        HANDSHAKING("Handshaking", "handshake"), PLAY("Play", "game"), STATUS("Status", "status"),
        LOGIN("Login", "login"), CONFIGURATION("Configuration", "configuration"), UNKNOWN("UNKNOWN", "");

        private final String packetName;
        private final String mojangName;

        Protocol(String packetName, String mojangName) {
            this.packetName = packetName;
            this.mojangName = mojangName;
        }

        public static Protocol fromVanilla(Enum<?> vanilla) {
            return switch (vanilla.name()) {
                case "HANDSHAKING", "HANDSHAKE" -> HANDSHAKING;
                case "PLAY" -> PLAY;
                case "STATUS" -> STATUS;
                case "LOGIN" -> LOGIN;
                case "CONFIGURATION" -> CONFIGURATION;
                default -> UNKNOWN;
            };
        }

        public String getPacketName() {
            return packetName;
        }

        public String getMojangName() {
            return mojangName;
        }

        public String getMcpPacketName() {
            return packetName;
        }

        /** The server's packet state number. */
        public int state() {
            return switch (this) {
                case HANDSHAKING -> 0;
                case STATUS -> 1;
                case LOGIN -> 2;
                case CONFIGURATION -> 3;
                default -> 4;
            };
        }
    }

    /** Who sends a packet. */
    public enum Sender {
        CLIENT, SERVER;

        public ConnectionSide toSide() {
            return this == CLIENT ? ConnectionSide.CLIENT_SIDE : ConnectionSide.SERVER_SIDE;
        }

        public String getPacketName() {
            return this == CLIENT ? "In" : "Out";
        }

        public String getMcpPacketName() {
            return this == CLIENT ? "Client" : "Server";
        }

        public String getMojangName() {
            return this == CLIENT ? "Serverbound" : "Clientbound";
        }

        /** The server's packet direction number. */
        public int direction() {
            return this == CLIENT ? 1 : 0;
        }
    }

    private final Protocol protocol;
    private final Sender sender;
    private final String vanilla;
    private final String constant;
    private final List<String> classNames;
    private volatile int id = Integer.MIN_VALUE;

    private PacketType(Protocol protocol, Sender sender, String vanilla, String constant) {
        this.protocol = protocol;
        this.sender = sender;
        this.vanilla = vanilla;
        this.constant = constant;
        this.classNames = vanilla.isEmpty() ? List.of() : List.of(vanilla);
        ALL.add(this);
        if (!vanilla.isEmpty()) {
            BY_NAME.putIfAbsent(key(protocol, sender, vanilla), this);
        }
    }

    public PacketType(Protocol protocol, Sender sender, int currentId, String... names) {
        this(protocol, sender, currentId, MinecraftVersion.getCurrentVersion(), names);
    }

    public PacketType(Protocol protocol, Sender sender, int currentId, MinecraftVersion version, String... names) {
        this.protocol = protocol;
        this.sender = sender;
        this.classNames = List.of(names);
        String name = currentId >= 0 ? Packets.nameOf(protocol, sender, currentId) : null;
        this.vanilla = name != null ? name : names.length > 0 ? names[0] : "";
        this.constant = this.vanilla.toUpperCase(Locale.ROOT);
        this.id = currentId;
    }

    private static String key(Protocol protocol, Sender sender, String vanilla) {
        return protocol + "/" + sender + "/" + vanilla;
    }

    private static void loadAll() {
        Handshake.Client.getInstance();
        Handshake.Server.getInstance();
        Play.Server.getInstance();
        Play.Client.getInstance();
        Status.Server.getInstance();
        Status.Client.getInstance();
        Login.Server.getInstance();
        Login.Client.getInstance();
        Configuration.Server.getInstance();
        Configuration.Client.getInstance();
    }

    public static Iterable<PacketType> values() {
        loadAll();
        return List.copyOf(ALL);
    }

    public static PacketType findLegacy(int packetId) {
        throw new IllegalArgumentException("Vanillite: legacy packet ids are not supported");
    }

    public static PacketType findLegacy(int packetId, Sender preference) {
        return findLegacy(packetId);
    }

    public static boolean hasLegacy(int packetId) {
        return false;
    }

    public static PacketType findCurrent(Protocol protocol, Sender sender, int packetId) {
        String name = Packets.nameOf(protocol, sender, packetId);
        if (name == null) {
            throw new IllegalArgumentException("No packet " + packetId + " in " + protocol + " sent by " + sender);
        }
        return byVanillaName(protocol, sender, name);
    }

    public static PacketType findCurrent(Protocol protocol, Sender sender, String name) {
        loadAll();
        for (PacketType t : ALL) {
            if (t.protocol == protocol && t.sender == sender
                    && (t.constant.equalsIgnoreCase(name) || t.vanilla.equalsIgnoreCase(name))) {
                return t;
            }
        }
        return null;
    }

    /** The type of a Vanilla packet name; a new dynamic type when no constant names it. */
    public static PacketType byVanillaName(Protocol protocol, Sender sender, String vanilla) {
        loadAll();
        PacketType t = BY_NAME.get(key(protocol, sender, vanilla));
        if (t == null) {
            t = BY_NAME.computeIfAbsent(key(protocol, sender, vanilla),
                    k -> new PacketType(protocol, sender, vanilla, vanilla.toUpperCase(Locale.ROOT)));
        }
        return t;
    }

    public static boolean hasCurrent(Protocol protocol, Sender sender, int packetId) {
        return Packets.nameOf(protocol, sender, packetId) != null;
    }

    public static PacketType fromID(Protocol protocol, Sender sender, int packetId, Class<?> packetClass) {
        return findCurrent(protocol, sender, packetId);
    }

    public static PacketType fromCurrent(Protocol protocol, Sender sender, int packetId, Class<?> packetClass) {
        return findCurrent(protocol, sender, packetId);
    }

    public static PacketType fromClass(Class<?> packetClass) {
        throw new IllegalArgumentException("Vanillite: packets have no Minecraft classes (" + packetClass + ")");
    }

    public static Collection<PacketType> fromName(String name) {
        loadAll();
        List<PacketType> out = new ArrayList<>();
        for (PacketType t : ALL) {
            if (t.constant.equalsIgnoreCase(name) || t.vanilla.equalsIgnoreCase(name)) {
                out.add(t);
            }
        }
        return out;
    }

    public static boolean hasClass(Class<?> packetClass) {
        return false;
    }

    public static void scheduleRegister(PacketType type, String name) {}

    public static PacketTypeEnum getObjectEnum(PacketType type) {
        return switch (type.protocol) {
            case HANDSHAKING -> type.isClient() ? Handshake.Client.getInstance() : Handshake.Server.getInstance();
            case PLAY -> type.isClient() ? Play.Client.getInstance() : Play.Server.getInstance();
            case STATUS -> type.isClient() ? Status.Client.getInstance() : Status.Server.getInstance();
            case LOGIN -> type.isClient() ? Login.Client.getInstance() : Login.Server.getInstance();
            case CONFIGURATION -> type.isClient() ? Configuration.Client.getInstance() : Configuration.Server.getInstance();
            default -> null;
        };
    }

    /** Whether the server has this packet. */
    public boolean isSupported() {
        return getCurrentId() >= 0;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public Sender getSender() {
        return sender;
    }

    public boolean isClient() {
        return sender == Sender.CLIENT;
    }

    public boolean isServer() {
        return sender == Sender.SERVER;
    }

    /** The packet id on this server, -1 when it has no such packet. */
    public int getCurrentId() {
        int v = id;
        if (v == Integer.MIN_VALUE) {
            v = vanilla.isEmpty() ? -1 : Packets.idOf(protocol, sender, vanilla);
            id = v;
        }
        return v;
    }

    /** The Vanilla name of the packet (e.g. {@code set_entity_data}); empty for none. */
    public String vanillaName() {
        return vanilla;
    }

    public List<String> getClassNames() {
        return classNames;
    }

    public Class<?> getPacketClass() {
        return null;
    }

    public String name() {
        return constant;
    }

    public boolean isDeprecated() {
        return vanilla.isEmpty();
    }

    public boolean isAsyncForced() {
        return false;
    }

    public MinecraftVersion getCurrentVersion() {
        return MinecraftVersion.getCurrentVersion();
    }

    public boolean isDynamic() {
        return !ALL.contains(this);
    }

    @Override
    public int hashCode() {
        return (protocol.hashCode() * 31 + sender.hashCode()) * 31 + (vanilla.isEmpty() ? constant : vanilla).hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof PacketType t && t.protocol == protocol && t.sender == sender
                && (vanilla.isEmpty() ? t.vanilla.isEmpty() && t.constant.equals(constant) : t.vanilla.equals(vanilla));
    }

    @Override
    public int compareTo(PacketType o) {
        int c = protocol.compareTo(o.protocol);
        if (c == 0) {
            c = sender.compareTo(o.sender);
        }
        return c == 0 ? Integer.compare(getCurrentId(), o.getCurrentId()) : c;
    }

    @Override
    public String toString() {
        return constant + "[" + protocol + ", " + sender + ", " + getCurrentId() + ", vanilla=" + vanilla + "]";
    }

    @Override
    public PacketType clone() {
        return this;
    }

    /** Packets of the handshake state. */
    public static class Handshake {
        private static final Protocol PROTOCOL = Protocol.HANDSHAKING;
        /** The protocol of these packets. */
        public static Protocol getProtocol() {
            return PROTOCOL;
        }
        /** Packets the server sends. */
        public static class Server extends PacketTypeEnum {
            private static final Sender SENDER = Sender.SERVER;
            private static final Server INSTANCE = new Server();
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Server getInstance() {
                return INSTANCE;
            }
        }
        /** Packets the client sends. */
        public static class Client extends PacketTypeEnum {
            private static final Sender SENDER = Sender.CLIENT;
            private static final Client INSTANCE = new Client();
            public static final PacketType SET_PROTOCOL = new PacketType(PROTOCOL, SENDER, "intention", "SET_PROTOCOL");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Client getInstance() {
                return INSTANCE;
            }
        }
    }
    /** Packets of the play state. */
    public static class Play {
        private static final Protocol PROTOCOL = Protocol.PLAY;
        /** The protocol of these packets. */
        public static Protocol getProtocol() {
            return PROTOCOL;
        }
        /** Packets the server sends. */
        public static class Server extends PacketTypeEnum {
            private static final Sender SENDER = Sender.SERVER;
            private static final Server INSTANCE = new Server();
            public static final PacketType BUNDLE = new PacketType(PROTOCOL, SENDER, "bundle_delimiter", "BUNDLE");
            public static final PacketType SPAWN_ENTITY = new PacketType(PROTOCOL, SENDER, "add_entity", "SPAWN_ENTITY");
            public static final PacketType ANIMATION = new PacketType(PROTOCOL, SENDER, "animate", "ANIMATION");
            public static final PacketType STATISTIC = new PacketType(PROTOCOL, SENDER, "award_stats", "STATISTIC");
            public static final PacketType BLOCK_CHANGED_ACK = new PacketType(PROTOCOL, SENDER, "block_changed_ack", "BLOCK_CHANGED_ACK");
            public static final PacketType BLOCK_BREAK_ANIMATION = new PacketType(PROTOCOL, SENDER, "block_destruction", "BLOCK_BREAK_ANIMATION");
            public static final PacketType TILE_ENTITY_DATA = new PacketType(PROTOCOL, SENDER, "block_entity_data", "TILE_ENTITY_DATA");
            public static final PacketType BLOCK_ACTION = new PacketType(PROTOCOL, SENDER, "block_event", "BLOCK_ACTION");
            public static final PacketType BLOCK_CHANGE = new PacketType(PROTOCOL, SENDER, "block_update", "BLOCK_CHANGE");
            public static final PacketType BOSS = new PacketType(PROTOCOL, SENDER, "boss_event", "BOSS");
            public static final PacketType SERVER_DIFFICULTY = new PacketType(PROTOCOL, SENDER, "change_difficulty", "SERVER_DIFFICULTY");
            public static final PacketType CHUNK_BATCH_FINISHED = new PacketType(PROTOCOL, SENDER, "chunk_batch_finished", "CHUNK_BATCH_FINISHED");
            public static final PacketType CHUNK_BATCH_START = new PacketType(PROTOCOL, SENDER, "chunk_batch_start", "CHUNK_BATCH_START");
            public static final PacketType CHUNKS_BIOMES = new PacketType(PROTOCOL, SENDER, "chunks_biomes", "CHUNKS_BIOMES");
            public static final PacketType CLEAR_TITLES = new PacketType(PROTOCOL, SENDER, "clear_titles", "CLEAR_TITLES");
            public static final PacketType TAB_COMPLETE = new PacketType(PROTOCOL, SENDER, "command_suggestions", "TAB_COMPLETE");
            public static final PacketType COMMANDS = new PacketType(PROTOCOL, SENDER, "commands", "COMMANDS");
            public static final PacketType CLOSE_WINDOW = new PacketType(PROTOCOL, SENDER, "container_close", "CLOSE_WINDOW");
            public static final PacketType WINDOW_ITEMS = new PacketType(PROTOCOL, SENDER, "container_set_content", "WINDOW_ITEMS");
            public static final PacketType WINDOW_DATA = new PacketType(PROTOCOL, SENDER, "container_set_data", "WINDOW_DATA");
            public static final PacketType SET_SLOT = new PacketType(PROTOCOL, SENDER, "container_set_slot", "SET_SLOT");
            public static final PacketType COOKIE_REQUEST = new PacketType(PROTOCOL, SENDER, "cookie_request", "COOKIE_REQUEST");
            public static final PacketType SET_COOLDOWN = new PacketType(PROTOCOL, SENDER, "cooldown", "SET_COOLDOWN");
            public static final PacketType CUSTOM_CHAT_COMPLETIONS = new PacketType(PROTOCOL, SENDER, "custom_chat_completions", "CUSTOM_CHAT_COMPLETIONS");
            public static final PacketType CUSTOM_PAYLOAD = new PacketType(PROTOCOL, SENDER, "custom_payload", "CUSTOM_PAYLOAD");
            public static final PacketType DAMAGE_EVENT = new PacketType(PROTOCOL, SENDER, "damage_event", "DAMAGE_EVENT");
            public static final PacketType DEBUG_SAMPLE = new PacketType(PROTOCOL, SENDER, "debug_sample", "DEBUG_SAMPLE");
            public static final PacketType DELETE_CHAT_MESSAGE = new PacketType(PROTOCOL, SENDER, "delete_chat", "DELETE_CHAT_MESSAGE");
            public static final PacketType KICK_DISCONNECT = new PacketType(PROTOCOL, SENDER, "disconnect", "KICK_DISCONNECT");
            public static final PacketType DISGUISED_CHAT = new PacketType(PROTOCOL, SENDER, "disguised_chat", "DISGUISED_CHAT");
            public static final PacketType ENTITY_STATUS = new PacketType(PROTOCOL, SENDER, "entity_event", "ENTITY_STATUS");
            public static final PacketType ENTITY_POSITION_SYNC = new PacketType(PROTOCOL, SENDER, "entity_position_sync", "ENTITY_POSITION_SYNC");
            public static final PacketType EXPLOSION = new PacketType(PROTOCOL, SENDER, "explode", "EXPLOSION");
            public static final PacketType UNLOAD_CHUNK = new PacketType(PROTOCOL, SENDER, "forget_level_chunk", "UNLOAD_CHUNK");
            public static final PacketType GAME_STATE_CHANGE = new PacketType(PROTOCOL, SENDER, "game_event", "GAME_STATE_CHANGE");
            public static final PacketType OPEN_WINDOW_HORSE = new PacketType(PROTOCOL, SENDER, "", "OPEN_WINDOW_HORSE");
            public static final PacketType HURT_ANIMATION = new PacketType(PROTOCOL, SENDER, "hurt_animation", "HURT_ANIMATION");
            public static final PacketType INITIALIZE_BORDER = new PacketType(PROTOCOL, SENDER, "initialize_border", "INITIALIZE_BORDER");
            public static final PacketType KEEP_ALIVE = new PacketType(PROTOCOL, SENDER, "keep_alive", "KEEP_ALIVE");
            public static final PacketType MAP_CHUNK = new PacketType(PROTOCOL, SENDER, "level_chunk_with_light", "MAP_CHUNK");
            public static final PacketType WORLD_EVENT = new PacketType(PROTOCOL, SENDER, "level_event", "WORLD_EVENT");
            public static final PacketType WORLD_PARTICLES = new PacketType(PROTOCOL, SENDER, "level_particles", "WORLD_PARTICLES");
            public static final PacketType LIGHT_UPDATE = new PacketType(PROTOCOL, SENDER, "light_update", "LIGHT_UPDATE");
            public static final PacketType LOGIN = new PacketType(PROTOCOL, SENDER, "login", "LOGIN");
            public static final PacketType MAP = new PacketType(PROTOCOL, SENDER, "map_item_data", "MAP");
            public static final PacketType OPEN_WINDOW_MERCHANT = new PacketType(PROTOCOL, SENDER, "merchant_offers", "OPEN_WINDOW_MERCHANT");
            public static final PacketType REL_ENTITY_MOVE = new PacketType(PROTOCOL, SENDER, "move_entity_pos", "REL_ENTITY_MOVE");
            public static final PacketType REL_ENTITY_MOVE_LOOK = new PacketType(PROTOCOL, SENDER, "move_entity_pos_rot", "REL_ENTITY_MOVE_LOOK");
            public static final PacketType MOVE_MINECART = new PacketType(PROTOCOL, SENDER, "move_minecart_along_track", "MOVE_MINECART");
            public static final PacketType ENTITY_LOOK = new PacketType(PROTOCOL, SENDER, "move_entity_rot", "ENTITY_LOOK");
            public static final PacketType VEHICLE_MOVE = new PacketType(PROTOCOL, SENDER, "move_vehicle", "VEHICLE_MOVE");
            public static final PacketType OPEN_BOOK = new PacketType(PROTOCOL, SENDER, "open_book", "OPEN_BOOK");
            public static final PacketType OPEN_WINDOW = new PacketType(PROTOCOL, SENDER, "open_screen", "OPEN_WINDOW");
            public static final PacketType OPEN_SIGN_EDITOR = new PacketType(PROTOCOL, SENDER, "open_sign_editor", "OPEN_SIGN_EDITOR");
            public static final PacketType PING = new PacketType(PROTOCOL, SENDER, "ping", "PING");
            public static final PacketType PONG_RESPONSE = new PacketType(PROTOCOL, SENDER, "pong_response", "PONG_RESPONSE");
            public static final PacketType AUTO_RECIPE = new PacketType(PROTOCOL, SENDER, "place_ghost_recipe", "AUTO_RECIPE");
            public static final PacketType ABILITIES = new PacketType(PROTOCOL, SENDER, "player_abilities", "ABILITIES");
            public static final PacketType CHAT = new PacketType(PROTOCOL, SENDER, "player_chat", "CHAT");
            public static final PacketType PLAYER_COMBAT_END = new PacketType(PROTOCOL, SENDER, "player_combat_end", "PLAYER_COMBAT_END");
            public static final PacketType PLAYER_COMBAT_ENTER = new PacketType(PROTOCOL, SENDER, "player_combat_enter", "PLAYER_COMBAT_ENTER");
            public static final PacketType PLAYER_COMBAT_KILL = new PacketType(PROTOCOL, SENDER, "player_combat_kill", "PLAYER_COMBAT_KILL");
            public static final PacketType PLAYER_INFO_REMOVE = new PacketType(PROTOCOL, SENDER, "player_info_remove", "PLAYER_INFO_REMOVE");
            public static final PacketType PLAYER_INFO = new PacketType(PROTOCOL, SENDER, "player_info_update", "PLAYER_INFO");
            public static final PacketType LOOK_AT = new PacketType(PROTOCOL, SENDER, "player_look_at", "LOOK_AT");
            public static final PacketType POSITION = new PacketType(PROTOCOL, SENDER, "player_position", "POSITION");
            public static final PacketType PLAYER_ROTATION = new PacketType(PROTOCOL, SENDER, "player_rotation", "PLAYER_ROTATION");
            public static final PacketType RECIPE_BOOK_ADD = new PacketType(PROTOCOL, SENDER, "recipe_book_add", "RECIPE_BOOK_ADD");
            public static final PacketType RECIPE_BOOK_REMOVE = new PacketType(PROTOCOL, SENDER, "recipe_book_remove", "RECIPE_BOOK_REMOVE");
            public static final PacketType RECIPE_BOOK_SETTINGS = new PacketType(PROTOCOL, SENDER, "recipe_book_settings", "RECIPE_BOOK_SETTINGS");
            public static final PacketType ENTITY_DESTROY = new PacketType(PROTOCOL, SENDER, "remove_entities", "ENTITY_DESTROY");
            public static final PacketType REMOVE_ENTITY_EFFECT = new PacketType(PROTOCOL, SENDER, "remove_mob_effect", "REMOVE_ENTITY_EFFECT");
            public static final PacketType RESET_SCORE = new PacketType(PROTOCOL, SENDER, "reset_score", "RESET_SCORE");
            public static final PacketType REMOVE_RESOURCE_PACK = new PacketType(PROTOCOL, SENDER, "resource_pack_pop", "REMOVE_RESOURCE_PACK");
            public static final PacketType ADD_RESOURCE_PACK = new PacketType(PROTOCOL, SENDER, "resource_pack_push", "ADD_RESOURCE_PACK");
            public static final PacketType RESPAWN = new PacketType(PROTOCOL, SENDER, "respawn", "RESPAWN");
            public static final PacketType ENTITY_HEAD_ROTATION = new PacketType(PROTOCOL, SENDER, "rotate_head", "ENTITY_HEAD_ROTATION");
            public static final PacketType MULTI_BLOCK_CHANGE = new PacketType(PROTOCOL, SENDER, "section_blocks_update", "MULTI_BLOCK_CHANGE");
            public static final PacketType SELECT_ADVANCEMENT_TAB = new PacketType(PROTOCOL, SENDER, "select_advancements_tab", "SELECT_ADVANCEMENT_TAB");
            public static final PacketType SERVER_DATA = new PacketType(PROTOCOL, SENDER, "server_data", "SERVER_DATA");
            public static final PacketType SET_ACTION_BAR_TEXT = new PacketType(PROTOCOL, SENDER, "set_action_bar_text", "SET_ACTION_BAR_TEXT");
            public static final PacketType SET_BORDER_CENTER = new PacketType(PROTOCOL, SENDER, "set_border_center", "SET_BORDER_CENTER");
            public static final PacketType SET_BORDER_LERP_SIZE = new PacketType(PROTOCOL, SENDER, "set_border_lerp_size", "SET_BORDER_LERP_SIZE");
            public static final PacketType SET_BORDER_SIZE = new PacketType(PROTOCOL, SENDER, "set_border_size", "SET_BORDER_SIZE");
            public static final PacketType SET_BORDER_WARNING_DELAY = new PacketType(PROTOCOL, SENDER, "set_border_warning_delay", "SET_BORDER_WARNING_DELAY");
            public static final PacketType SET_BORDER_WARNING_DISTANCE = new PacketType(PROTOCOL, SENDER, "set_border_warning_distance", "SET_BORDER_WARNING_DISTANCE");
            public static final PacketType CAMERA = new PacketType(PROTOCOL, SENDER, "set_camera", "CAMERA");
            public static final PacketType VIEW_CENTRE = new PacketType(PROTOCOL, SENDER, "set_chunk_cache_center", "VIEW_CENTRE");
            public static final PacketType VIEW_DISTANCE = new PacketType(PROTOCOL, SENDER, "set_chunk_cache_radius", "VIEW_DISTANCE");
            public static final PacketType SET_CURSOR_ITEM = new PacketType(PROTOCOL, SENDER, "set_cursor_item", "SET_CURSOR_ITEM");
            public static final PacketType SPAWN_POSITION = new PacketType(PROTOCOL, SENDER, "set_default_spawn_position", "SPAWN_POSITION");
            public static final PacketType SCOREBOARD_DISPLAY_OBJECTIVE = new PacketType(PROTOCOL, SENDER, "set_display_objective", "SCOREBOARD_DISPLAY_OBJECTIVE");
            public static final PacketType ENTITY_METADATA = new PacketType(PROTOCOL, SENDER, "set_entity_data", "ENTITY_METADATA");
            public static final PacketType ATTACH_ENTITY = new PacketType(PROTOCOL, SENDER, "set_entity_link", "ATTACH_ENTITY");
            public static final PacketType ENTITY_VELOCITY = new PacketType(PROTOCOL, SENDER, "set_entity_motion", "ENTITY_VELOCITY");
            public static final PacketType ENTITY_EQUIPMENT = new PacketType(PROTOCOL, SENDER, "set_equipment", "ENTITY_EQUIPMENT");
            public static final PacketType EXPERIENCE = new PacketType(PROTOCOL, SENDER, "set_experience", "EXPERIENCE");
            public static final PacketType UPDATE_HEALTH = new PacketType(PROTOCOL, SENDER, "set_health", "UPDATE_HEALTH");
            public static final PacketType HELD_ITEM_SLOT = new PacketType(PROTOCOL, SENDER, "set_held_slot", "HELD_ITEM_SLOT");
            public static final PacketType SCOREBOARD_OBJECTIVE = new PacketType(PROTOCOL, SENDER, "set_objective", "SCOREBOARD_OBJECTIVE");
            public static final PacketType MOUNT = new PacketType(PROTOCOL, SENDER, "set_passengers", "MOUNT");
            public static final PacketType SET_PLAYER_INVENTORY = new PacketType(PROTOCOL, SENDER, "set_player_inventory", "SET_PLAYER_INVENTORY");
            public static final PacketType SCOREBOARD_TEAM = new PacketType(PROTOCOL, SENDER, "set_player_team", "SCOREBOARD_TEAM");
            public static final PacketType SCOREBOARD_SCORE = new PacketType(PROTOCOL, SENDER, "set_score", "SCOREBOARD_SCORE");
            public static final PacketType UPDATE_SIMULATION_DISTANCE = new PacketType(PROTOCOL, SENDER, "set_simulation_distance", "UPDATE_SIMULATION_DISTANCE");
            public static final PacketType SET_SUBTITLE_TEXT = new PacketType(PROTOCOL, SENDER, "set_subtitle_text", "SET_SUBTITLE_TEXT");
            public static final PacketType UPDATE_TIME = new PacketType(PROTOCOL, SENDER, "set_time", "UPDATE_TIME");
            public static final PacketType SET_TITLE_TEXT = new PacketType(PROTOCOL, SENDER, "set_title_text", "SET_TITLE_TEXT");
            public static final PacketType SET_TITLES_ANIMATION = new PacketType(PROTOCOL, SENDER, "set_titles_animation", "SET_TITLES_ANIMATION");
            public static final PacketType ENTITY_SOUND = new PacketType(PROTOCOL, SENDER, "sound_entity", "ENTITY_SOUND");
            public static final PacketType NAMED_SOUND_EFFECT = new PacketType(PROTOCOL, SENDER, "sound", "NAMED_SOUND_EFFECT");
            public static final PacketType START_CONFIGURATION = new PacketType(PROTOCOL, SENDER, "start_configuration", "START_CONFIGURATION");
            public static final PacketType STOP_SOUND = new PacketType(PROTOCOL, SENDER, "stop_sound", "STOP_SOUND");
            public static final PacketType STORE_COOKIE = new PacketType(PROTOCOL, SENDER, "store_cookie", "STORE_COOKIE");
            public static final PacketType SYSTEM_CHAT = new PacketType(PROTOCOL, SENDER, "system_chat", "SYSTEM_CHAT");
            public static final PacketType PLAYER_LIST_HEADER_FOOTER = new PacketType(PROTOCOL, SENDER, "tab_list", "PLAYER_LIST_HEADER_FOOTER");
            public static final PacketType NBT_QUERY = new PacketType(PROTOCOL, SENDER, "tag_query", "NBT_QUERY");
            public static final PacketType COLLECT = new PacketType(PROTOCOL, SENDER, "take_item_entity", "COLLECT");
            public static final PacketType ENTITY_TELEPORT = new PacketType(PROTOCOL, SENDER, "teleport_entity", "ENTITY_TELEPORT");
            public static final PacketType TEST_INSTANCE_BLOCK_STATUS = new PacketType(PROTOCOL, SENDER, "test_instance_block_status", "TEST_INSTANCE_BLOCK_STATUS");
            public static final PacketType TICKING_STATE = new PacketType(PROTOCOL, SENDER, "ticking_state", "TICKING_STATE");
            public static final PacketType TICKING_STEP_STATE = new PacketType(PROTOCOL, SENDER, "ticking_step", "TICKING_STEP_STATE");
            public static final PacketType TRANSFER = new PacketType(PROTOCOL, SENDER, "transfer", "TRANSFER");
            public static final PacketType ADVANCEMENTS = new PacketType(PROTOCOL, SENDER, "update_advancements", "ADVANCEMENTS");
            public static final PacketType UPDATE_ATTRIBUTES = new PacketType(PROTOCOL, SENDER, "update_attributes", "UPDATE_ATTRIBUTES");
            public static final PacketType ENTITY_EFFECT = new PacketType(PROTOCOL, SENDER, "update_mob_effect", "ENTITY_EFFECT");
            public static final PacketType RECIPE_UPDATE = new PacketType(PROTOCOL, SENDER, "update_recipes", "RECIPE_UPDATE");
            public static final PacketType TAGS = new PacketType(PROTOCOL, SENDER, "update_tags", "TAGS");
            public static final PacketType PROJECTILE_POWER = new PacketType(PROTOCOL, SENDER, "projectile_power", "PROJECTILE_POWER");
            public static final PacketType REPORT_DETAILS = new PacketType(PROTOCOL, SENDER, "custom_report_details", "REPORT_DETAILS");
            public static final PacketType SERVER_LINKS = new PacketType(PROTOCOL, SENDER, "server_links", "SERVER_LINKS");
            public static final PacketType TRACKED_WAYPOINT = new PacketType(PROTOCOL, SENDER, "waypoint", "TRACKED_WAYPOINT");
            public static final PacketType CLEAR_DIALOG = new PacketType(PROTOCOL, SENDER, "clear_dialog", "CLEAR_DIALOG");
            public static final PacketType SHOW_DIALOG = new PacketType(PROTOCOL, SENDER, "show_dialog", "SHOW_DIALOG");
            public static final PacketType MAP_CHUNK_BULK = new PacketType(PROTOCOL, SENDER, "", "MAP_CHUNK_BULK");
            public static final PacketType SET_COMPRESSION = new PacketType(PROTOCOL, SENDER, "", "SET_COMPRESSION");
            public static final PacketType UPDATE_ENTITY_NBT = new PacketType(PROTOCOL, SENDER, "", "UPDATE_ENTITY_NBT");
            public static final PacketType CRAFT_PROGRESS_BAR = new PacketType(PROTOCOL, SENDER, "", "CRAFT_PROGRESS_BAR");
            public static final PacketType ENTITY_MOVE_LOOK = new PacketType(PROTOCOL, SENDER, "", "ENTITY_MOVE_LOOK");
            public static final PacketType STATISTICS = new PacketType(PROTOCOL, SENDER, "", "STATISTICS");
            public static final PacketType OPEN_SIGN_ENTITY = new PacketType(PROTOCOL, SENDER, "", "OPEN_SIGN_ENTITY");
            public static final PacketType UPDATE_SIGN = new PacketType(PROTOCOL, SENDER, "", "UPDATE_SIGN");
            public static final PacketType BED = new PacketType(PROTOCOL, SENDER, "", "BED");
            public static final PacketType USE_BED = new PacketType(PROTOCOL, SENDER, "", "USE_BED");
            public static final PacketType SPAWN_ENTITY_WEATHER = new PacketType(PROTOCOL, SENDER, "", "SPAWN_ENTITY_WEATHER");
            public static final PacketType TITLE = new PacketType(PROTOCOL, SENDER, "", "TITLE");
            public static final PacketType WORLD_BORDER = new PacketType(PROTOCOL, SENDER, "", "WORLD_BORDER");
            public static final PacketType COMBAT_EVENT = new PacketType(PROTOCOL, SENDER, "", "COMBAT_EVENT");
            public static final PacketType TRANSACTION = new PacketType(PROTOCOL, SENDER, "", "TRANSACTION");
            public static final PacketType ENTITY = new PacketType(PROTOCOL, SENDER, "", "ENTITY");
            public static final PacketType SPAWN_ENTITY_LIVING = new PacketType(PROTOCOL, SENDER, "", "SPAWN_ENTITY_LIVING");
            public static final PacketType SPAWN_ENTITY_PAINTING = new PacketType(PROTOCOL, SENDER, "", "SPAWN_ENTITY_PAINTING");
            public static final PacketType ADD_VIBRATION_SIGNAL = new PacketType(PROTOCOL, SENDER, "", "ADD_VIBRATION_SIGNAL");
            public static final PacketType BLOCK_BREAK = new PacketType(PROTOCOL, SENDER, "", "BLOCK_BREAK");
            public static final PacketType CHAT_PREVIEW = new PacketType(PROTOCOL, SENDER, "", "CHAT_PREVIEW");
            public static final PacketType PLAYER_CHAT_HEADER = new PacketType(PROTOCOL, SENDER, "", "PLAYER_CHAT_HEADER");
            public static final PacketType SET_DISPLAY_CHAT_PREVIEW = new PacketType(PROTOCOL, SENDER, "", "SET_DISPLAY_CHAT_PREVIEW");
            public static final PacketType CUSTOM_SOUND_EFFECT = new PacketType(PROTOCOL, SENDER, "", "CUSTOM_SOUND_EFFECT");
            public static final PacketType UPDATE_ENABLED_FEATURES = new PacketType(PROTOCOL, SENDER, "", "UPDATE_ENABLED_FEATURES");
            public static final PacketType NAMED_ENTITY_SPAWN = new PacketType(PROTOCOL, SENDER, "", "NAMED_ENTITY_SPAWN");
            public static final PacketType RESOURCE_PACK_SEND = new PacketType(PROTOCOL, SENDER, "", "RESOURCE_PACK_SEND");
            public static final PacketType RECIPES = new PacketType(PROTOCOL, SENDER, "", "RECIPES");
            public static final PacketType SPAWN_ENTITY_EXPERIENCE_ORB = new PacketType(PROTOCOL, SENDER, "", "SPAWN_ENTITY_EXPERIENCE_ORB");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Server getInstance() {
                return INSTANCE;
            }
        }
        /** Packets the client sends. */
        public static class Client extends PacketTypeEnum {
            private static final Sender SENDER = Sender.CLIENT;
            private static final Client INSTANCE = new Client();
            public static final PacketType TELEPORT_ACCEPT = new PacketType(PROTOCOL, SENDER, "accept_teleportation", "TELEPORT_ACCEPT");
            public static final PacketType TILE_NBT_QUERY = new PacketType(PROTOCOL, SENDER, "block_entity_tag_query", "TILE_NBT_QUERY");
            public static final PacketType SELECT_BUNDLE_ITEM = new PacketType(PROTOCOL, SENDER, "bundle_item_selected", "SELECT_BUNDLE_ITEM");
            public static final PacketType DIFFICULTY_CHANGE = new PacketType(PROTOCOL, SENDER, "change_difficulty", "DIFFICULTY_CHANGE");
            public static final PacketType CHANGE_GAME_MODE = new PacketType(PROTOCOL, SENDER, "change_game_mode", "CHANGE_GAME_MODE");
            public static final PacketType CHAT_ACK = new PacketType(PROTOCOL, SENDER, "chat_ack", "CHAT_ACK");
            public static final PacketType CHAT_COMMAND = new PacketType(PROTOCOL, SENDER, "chat_command", "CHAT_COMMAND");
            public static final PacketType CHAT_COMMAND_SIGNED = new PacketType(PROTOCOL, SENDER, "chat_command_signed", "CHAT_COMMAND_SIGNED");
            public static final PacketType CHAT = new PacketType(PROTOCOL, SENDER, "chat", "CHAT");
            public static final PacketType CHAT_SESSION_UPDATE = new PacketType(PROTOCOL, SENDER, "chat_session_update", "CHAT_SESSION_UPDATE");
            public static final PacketType CHUNK_BATCH_RECEIVED = new PacketType(PROTOCOL, SENDER, "chunk_batch_received", "CHUNK_BATCH_RECEIVED");
            public static final PacketType CLIENT_COMMAND = new PacketType(PROTOCOL, SENDER, "client_command", "CLIENT_COMMAND");
            public static final PacketType CLIENT_TICK_END = new PacketType(PROTOCOL, SENDER, "client_tick_end", "CLIENT_TICK_END");
            public static final PacketType SETTINGS = new PacketType(PROTOCOL, SENDER, "client_information", "SETTINGS");
            public static final PacketType TAB_COMPLETE = new PacketType(PROTOCOL, SENDER, "command_suggestion", "TAB_COMPLETE");
            public static final PacketType CONFIGURATION_ACK = new PacketType(PROTOCOL, SENDER, "configuration_acknowledged", "CONFIGURATION_ACK");
            public static final PacketType ENCHANT_ITEM = new PacketType(PROTOCOL, SENDER, "container_button_click", "ENCHANT_ITEM");
            public static final PacketType WINDOW_CLICK = new PacketType(PROTOCOL, SENDER, "container_click", "WINDOW_CLICK");
            public static final PacketType CLOSE_WINDOW = new PacketType(PROTOCOL, SENDER, "container_close", "CLOSE_WINDOW");
            public static final PacketType CONTAINER_SLOT_STATE_CHANGED = new PacketType(PROTOCOL, SENDER, "container_slot_state_changed", "CONTAINER_SLOT_STATE_CHANGED");
            public static final PacketType COOKIE_RESPONSE = new PacketType(PROTOCOL, SENDER, "cookie_response", "COOKIE_RESPONSE");
            public static final PacketType CUSTOM_PAYLOAD = new PacketType(PROTOCOL, SENDER, "custom_payload", "CUSTOM_PAYLOAD");
            public static final PacketType DEBUG_SAMPLE_SUBSCRIPTION = new PacketType(PROTOCOL, SENDER, "debug_subscription_request", "DEBUG_SAMPLE_SUBSCRIPTION");
            public static final PacketType B_EDIT = new PacketType(PROTOCOL, SENDER, "edit_book", "B_EDIT");
            public static final PacketType ENTITY_NBT_QUERY = new PacketType(PROTOCOL, SENDER, "entity_tag_query", "ENTITY_NBT_QUERY");
            public static final PacketType USE_ENTITY = new PacketType(PROTOCOL, SENDER, "interact", "USE_ENTITY");
            public static final PacketType JIGSAW_GENERATE = new PacketType(PROTOCOL, SENDER, "jigsaw_generate", "JIGSAW_GENERATE");
            public static final PacketType KEEP_ALIVE = new PacketType(PROTOCOL, SENDER, "keep_alive", "KEEP_ALIVE");
            public static final PacketType DIFFICULTY_LOCK = new PacketType(PROTOCOL, SENDER, "lock_difficulty", "DIFFICULTY_LOCK");
            public static final PacketType POSITION = new PacketType(PROTOCOL, SENDER, "move_player_pos", "POSITION");
            public static final PacketType POSITION_LOOK = new PacketType(PROTOCOL, SENDER, "move_player_pos_rot", "POSITION_LOOK");
            public static final PacketType LOOK = new PacketType(PROTOCOL, SENDER, "move_player_rot", "LOOK");
            public static final PacketType GROUND = new PacketType(PROTOCOL, SENDER, "move_player_status_only", "GROUND");
            public static final PacketType VEHICLE_MOVE = new PacketType(PROTOCOL, SENDER, "move_vehicle", "VEHICLE_MOVE");
            public static final PacketType BOAT_MOVE = new PacketType(PROTOCOL, SENDER, "paddle_boat", "BOAT_MOVE");
            public static final PacketType PICK_ITEM_FROM_BLOCK = new PacketType(PROTOCOL, SENDER, "pick_item_from_block", "PICK_ITEM_FROM_BLOCK");
            public static final PacketType PICK_ITEM = new PacketType(PROTOCOL, SENDER, "pick_item_from_entity", "PICK_ITEM");
            public static final PacketType PING_REQUEST = new PacketType(PROTOCOL, SENDER, "ping_request", "PING_REQUEST");
            public static final PacketType AUTO_RECIPE = new PacketType(PROTOCOL, SENDER, "place_recipe", "AUTO_RECIPE");
            public static final PacketType ABILITIES = new PacketType(PROTOCOL, SENDER, "player_abilities", "ABILITIES");
            public static final PacketType BLOCK_DIG = new PacketType(PROTOCOL, SENDER, "player_action", "BLOCK_DIG");
            public static final PacketType ENTITY_ACTION = new PacketType(PROTOCOL, SENDER, "player_command", "ENTITY_ACTION");
            public static final PacketType STEER_VEHICLE = new PacketType(PROTOCOL, SENDER, "player_input", "STEER_VEHICLE");
            public static final PacketType PLAYER_LOADED = new PacketType(PROTOCOL, SENDER, "player_loaded", "PLAYER_LOADED");
            public static final PacketType PONG = new PacketType(PROTOCOL, SENDER, "pong", "PONG");
            public static final PacketType RECIPE_SETTINGS = new PacketType(PROTOCOL, SENDER, "recipe_book_change_settings", "RECIPE_SETTINGS");
            public static final PacketType RECIPE_DISPLAYED = new PacketType(PROTOCOL, SENDER, "recipe_book_seen_recipe", "RECIPE_DISPLAYED");
            public static final PacketType ITEM_NAME = new PacketType(PROTOCOL, SENDER, "rename_item", "ITEM_NAME");
            public static final PacketType RESOURCE_PACK_STATUS = new PacketType(PROTOCOL, SENDER, "resource_pack", "RESOURCE_PACK_STATUS");
            public static final PacketType ADVANCEMENTS = new PacketType(PROTOCOL, SENDER, "seen_advancements", "ADVANCEMENTS");
            public static final PacketType TR_SEL = new PacketType(PROTOCOL, SENDER, "select_trade", "TR_SEL");
            public static final PacketType BEACON = new PacketType(PROTOCOL, SENDER, "set_beacon", "BEACON");
            public static final PacketType HELD_ITEM_SLOT = new PacketType(PROTOCOL, SENDER, "set_carried_item", "HELD_ITEM_SLOT");
            public static final PacketType SET_COMMAND_BLOCK = new PacketType(PROTOCOL, SENDER, "set_command_block", "SET_COMMAND_BLOCK");
            public static final PacketType SET_COMMAND_MINECART = new PacketType(PROTOCOL, SENDER, "set_command_minecart", "SET_COMMAND_MINECART");
            public static final PacketType SET_CREATIVE_SLOT = new PacketType(PROTOCOL, SENDER, "set_creative_mode_slot", "SET_CREATIVE_SLOT");
            public static final PacketType SET_JIGSAW = new PacketType(PROTOCOL, SENDER, "set_jigsaw_block", "SET_JIGSAW");
            public static final PacketType STRUCT = new PacketType(PROTOCOL, SENDER, "set_structure_block", "STRUCT");
            public static final PacketType SET_TEST_BLOCK = new PacketType(PROTOCOL, SENDER, "set_test_block", "SET_TEST_BLOCK");
            public static final PacketType UPDATE_SIGN = new PacketType(PROTOCOL, SENDER, "sign_update", "UPDATE_SIGN");
            public static final PacketType ARM_ANIMATION = new PacketType(PROTOCOL, SENDER, "", "ARM_ANIMATION");
            public static final PacketType SPECTATE = new PacketType(PROTOCOL, SENDER, "teleport_to_entity", "SPECTATE");
            public static final PacketType TEST_INSTANCE_BLOCK_ACTION = new PacketType(PROTOCOL, SENDER, "test_instance_block_action", "TEST_INSTANCE_BLOCK_ACTION");
            public static final PacketType USE_ITEM_ON = new PacketType(PROTOCOL, SENDER, "use_item_on", "USE_ITEM_ON");
            public static final PacketType USE_ITEM = new PacketType(PROTOCOL, SENDER, "use_item", "USE_ITEM");
            public static final PacketType CUSTOM_CLICK_ACTION = new PacketType(PROTOCOL, SENDER, "custom_click_action", "CUSTOM_CLICK_ACTION");
            public static final PacketType TRANSACTION = new PacketType(PROTOCOL, SENDER, "", "TRANSACTION");
            public static final PacketType FLYING = new PacketType(PROTOCOL, SENDER, "", "FLYING");
            public static final PacketType CHAT_PREVIEW = new PacketType(PROTOCOL, SENDER, "", "CHAT_PREVIEW");
            public static final PacketType BLOCK_PLACE = new PacketType(PROTOCOL, SENDER, "", "BLOCK_PLACE");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Client getInstance() {
                return INSTANCE;
            }
        }
    }
    /** Packets of the status state. */
    public static class Status {
        private static final Protocol PROTOCOL = Protocol.STATUS;
        /** The protocol of these packets. */
        public static Protocol getProtocol() {
            return PROTOCOL;
        }
        /** Packets the server sends. */
        public static class Server extends PacketTypeEnum {
            private static final Sender SENDER = Sender.SERVER;
            private static final Server INSTANCE = new Server();
            public static final PacketType SERVER_INFO = new PacketType(PROTOCOL, SENDER, "status_response", "SERVER_INFO");
            public static final PacketType PONG = new PacketType(PROTOCOL, SENDER, "pong_response", "PONG");
            public static final PacketType OUT_SERVER_INFO = new PacketType(PROTOCOL, SENDER, "status_response", "OUT_SERVER_INFO");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Server getInstance() {
                return INSTANCE;
            }
        }
        /** Packets the client sends. */
        public static class Client extends PacketTypeEnum {
            private static final Sender SENDER = Sender.CLIENT;
            private static final Client INSTANCE = new Client();
            public static final PacketType START = new PacketType(PROTOCOL, SENDER, "status_request", "START");
            public static final PacketType PING = new PacketType(PROTOCOL, SENDER, "ping_request", "PING");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Client getInstance() {
                return INSTANCE;
            }
        }
    }
    /** Packets of the login state. */
    public static class Login {
        private static final Protocol PROTOCOL = Protocol.LOGIN;
        /** The protocol of these packets. */
        public static Protocol getProtocol() {
            return PROTOCOL;
        }
        /** Packets the server sends. */
        public static class Server extends PacketTypeEnum {
            private static final Sender SENDER = Sender.SERVER;
            private static final Server INSTANCE = new Server();
            public static final PacketType DISCONNECT = new PacketType(PROTOCOL, SENDER, "login_disconnect", "DISCONNECT");
            public static final PacketType ENCRYPTION_BEGIN = new PacketType(PROTOCOL, SENDER, "hello", "ENCRYPTION_BEGIN");
            public static final PacketType SUCCESS = new PacketType(PROTOCOL, SENDER, "login_finished", "SUCCESS");
            public static final PacketType SET_COMPRESSION = new PacketType(PROTOCOL, SENDER, "login_compression", "SET_COMPRESSION");
            public static final PacketType CUSTOM_PAYLOAD = new PacketType(PROTOCOL, SENDER, "custom_query", "CUSTOM_PAYLOAD");
            public static final PacketType COOKIE_REQUEST = new PacketType(PROTOCOL, SENDER, "cookie_request", "COOKIE_REQUEST");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Server getInstance() {
                return INSTANCE;
            }
        }
        /** Packets the client sends. */
        public static class Client extends PacketTypeEnum {
            private static final Sender SENDER = Sender.CLIENT;
            private static final Client INSTANCE = new Client();
            public static final PacketType START = new PacketType(PROTOCOL, SENDER, "hello", "START");
            public static final PacketType ENCRYPTION_BEGIN = new PacketType(PROTOCOL, SENDER, "key", "ENCRYPTION_BEGIN");
            public static final PacketType CUSTOM_PAYLOAD = new PacketType(PROTOCOL, SENDER, "custom_query_answer", "CUSTOM_PAYLOAD");
            public static final PacketType LOGIN_ACK = new PacketType(PROTOCOL, SENDER, "login_acknowledged", "LOGIN_ACK");
            public static final PacketType COOKIE_RESPONSE = new PacketType(PROTOCOL, SENDER, "cookie_response", "COOKIE_RESPONSE");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Client getInstance() {
                return INSTANCE;
            }
        }
    }
    /** Packets of the configuration state. */
    public static class Configuration {
        private static final Protocol PROTOCOL = Protocol.CONFIGURATION;
        /** The protocol of these packets. */
        public static Protocol getProtocol() {
            return PROTOCOL;
        }
        /** Packets the server sends. */
        public static class Server extends PacketTypeEnum {
            private static final Sender SENDER = Sender.SERVER;
            private static final Server INSTANCE = new Server();
            public static final PacketType COOKIE_REQUEST = new PacketType(PROTOCOL, SENDER, "cookie_request", "COOKIE_REQUEST");
            public static final PacketType CUSTOM_PAYLOAD = new PacketType(PROTOCOL, SENDER, "custom_payload", "CUSTOM_PAYLOAD");
            public static final PacketType DISCONNECT = new PacketType(PROTOCOL, SENDER, "disconnect", "DISCONNECT");
            public static final PacketType FINISH_CONFIGURATION = new PacketType(PROTOCOL, SENDER, "finish_configuration", "FINISH_CONFIGURATION");
            public static final PacketType KEEP_ALIVE = new PacketType(PROTOCOL, SENDER, "keep_alive", "KEEP_ALIVE");
            public static final PacketType PING = new PacketType(PROTOCOL, SENDER, "ping", "PING");
            public static final PacketType RESET_CHAT = new PacketType(PROTOCOL, SENDER, "reset_chat", "RESET_CHAT");
            public static final PacketType REGISTRY_DATA = new PacketType(PROTOCOL, SENDER, "registry_data", "REGISTRY_DATA");
            public static final PacketType REMOVE_RESOURCE_PACK = new PacketType(PROTOCOL, SENDER, "resource_pack_pop", "REMOVE_RESOURCE_PACK");
            public static final PacketType ADD_RESOURCE_PACK = new PacketType(PROTOCOL, SENDER, "resource_pack_push", "ADD_RESOURCE_PACK");
            public static final PacketType STORE_COOKIE = new PacketType(PROTOCOL, SENDER, "store_cookie", "STORE_COOKIE");
            public static final PacketType TRANSFER = new PacketType(PROTOCOL, SENDER, "transfer", "TRANSFER");
            public static final PacketType UPDATE_ENABLED_FEATURES = new PacketType(PROTOCOL, SENDER, "update_enabled_features", "UPDATE_ENABLED_FEATURES");
            public static final PacketType UPDATE_TAGS = new PacketType(PROTOCOL, SENDER, "update_tags", "UPDATE_TAGS");
            public static final PacketType SELECT_KNOWN_PACKS = new PacketType(PROTOCOL, SENDER, "select_known_packs", "SELECT_KNOWN_PACKS");
            public static final PacketType REPORT_DETAILS = new PacketType(PROTOCOL, SENDER, "custom_report_details", "REPORT_DETAILS");
            public static final PacketType SERVER_LINKS = new PacketType(PROTOCOL, SENDER, "server_links", "SERVER_LINKS");
            public static final PacketType CLEAR_DIALOG = new PacketType(PROTOCOL, SENDER, "clear_dialog", "CLEAR_DIALOG");
            public static final PacketType SHOW_DIALOG = new PacketType(PROTOCOL, SENDER, "show_dialog", "SHOW_DIALOG");
            public static final PacketType RESOURCE_PACK = new PacketType(PROTOCOL, SENDER, "resource_pack_push", "RESOURCE_PACK");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Server getInstance() {
                return INSTANCE;
            }
        }
        /** Packets the client sends. */
        public static class Client extends PacketTypeEnum {
            private static final Sender SENDER = Sender.CLIENT;
            private static final Client INSTANCE = new Client();
            public static final PacketType CLIENT_INFORMATION = new PacketType(PROTOCOL, SENDER, "client_information", "CLIENT_INFORMATION");
            public static final PacketType COOKIE_RESPONSE = new PacketType(PROTOCOL, SENDER, "cookie_response", "COOKIE_RESPONSE");
            public static final PacketType CUSTOM_PAYLOAD = new PacketType(PROTOCOL, SENDER, "custom_payload", "CUSTOM_PAYLOAD");
            public static final PacketType FINISH_CONFIGURATION = new PacketType(PROTOCOL, SENDER, "finish_configuration", "FINISH_CONFIGURATION");
            public static final PacketType KEEP_ALIVE = new PacketType(PROTOCOL, SENDER, "keep_alive", "KEEP_ALIVE");
            public static final PacketType PONG = new PacketType(PROTOCOL, SENDER, "pong", "PONG");
            public static final PacketType RESOURCE_PACK_ACK = new PacketType(PROTOCOL, SENDER, "resource_pack", "RESOURCE_PACK_ACK");
            public static final PacketType SELECT_KNOWN_PACKS = new PacketType(PROTOCOL, SENDER, "select_known_packs", "SELECT_KNOWN_PACKS");
            public static final PacketType CUSTOM_CLICK_ACTION = new PacketType(PROTOCOL, SENDER, "custom_click_action", "CUSTOM_CLICK_ACTION");
            static {
                for (PacketType t : ALL) {
                    if (t.getProtocol() == PROTOCOL && t.getSender() == SENDER) {
                        INSTANCE.registerMember(t, t.name());
                    }
                }
            }
            /** The sender of these packets. */
            public static Sender getSender() {
                return SENDER;
            }
            /** All of these packets. */
            public static Client getInstance() {
                return INSTANCE;
            }
        }
    }
}
