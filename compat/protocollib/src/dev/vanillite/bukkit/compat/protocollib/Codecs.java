// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.protocollib;

import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.MovingObjectPositionBlock;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedRemoteChatSessionData;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;

/**
 * The fields of the packets Vanillite maps for ProtocolLib, in the order of the Vanilla 26.3
 * packet classes, with their wire formats. A packet is decoded into its fields; bytes after the
 * mapped fields are kept and written back unchanged.
 */
public final class Codecs {
    private Codecs() {}

    /** Reads one field; {@code before} holds the fields read so far. */
    interface Reader {
        Object read(Wire.In in, List<Object> before);
    }

    /** Writes one field; {@code all} holds every field of the packet. */
    interface Writer {
        void write(Wire.Out out, Object value, Object[] all);
    }

    /** One field: its kind, wire format and the value of a new packet. */
    record Field(Kind kind, Reader reader, Writer writer, java.util.function.Supplier<Object> initial) {}

    /** The fields of one packet. */
    public record Codec(List<Field> fields) {
        public Kind[] kinds() {
            Kind[] out = new Kind[fields.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = fields.get(i).kind();
            }
            return out;
        }

        /** The fields of a new packet. */
        public Object[] initial() {
            Object[] out = new Object[fields.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = fields.get(i).initial().get();
            }
            return out;
        }

        /** Decodes the fields after the id; the position after them is where the kept tail starts. */
        public Object[] decode(Wire.In in) {
            List<Object> values = new ArrayList<>(fields.size());
            for (Field f : fields) {
                values.add(f.reader().read(in, values));
            }
            return values.toArray();
        }

        public void encode(Wire.Out out, Object[] values) {
            for (int i = 0; i < fields.size(); i++) {
                fields.get(i).writer().write(out, values[i], values);
            }
        }
    }

    private static final Map<String, Codec> CLIENTBOUND = new HashMap<>();
    private static final Map<String, Codec> SERVERBOUND = new HashMap<>();

    /** The codec of a play packet; null for packets whose fields Vanillite does not map. */
    public static Codec of(boolean clientbound, String name) {
        return (clientbound ? CLIENTBOUND : SERVERBOUND).get(name);
    }

    // ----- Field formats

    private static Field f(Kind kind, Reader r, Writer w, java.util.function.Supplier<Object> initial) {
        return new Field(kind, r, w, initial);
    }

    private static Field varInt() {
        return f(Kind.INT, (in, b) -> in.varInt(), (out, v, a) -> out.varInt((Integer) v), () -> 0);
    }

    private static Field int32() {
        return f(Kind.INT, (in, b) -> in.i32(), (out, v, a) -> out.i32((Integer) v), () -> 0);
    }

    private static Field bool() {
        return f(Kind.BOOLEAN, (in, b) -> in.bool(), (out, v, a) -> out.bool((Boolean) v), () -> false);
    }

    private static Field i8() {
        return f(Kind.BYTE, (in, b) -> in.i8(), (out, v, a) -> out.u8((Byte) v), () -> (byte) 0);
    }

    private static Field i64() {
        return f(Kind.LONG, (in, b) -> in.i64(), (out, v, a) -> out.i64((Long) v), () -> 0L);
    }

    private static Field f32() {
        return f(Kind.FLOAT, (in, b) -> in.f32(), (out, v, a) -> out.f32((Float) v), () -> 0f);
    }

    private static Field f64() {
        return f(Kind.DOUBLE, (in, b) -> in.f64(), (out, v, a) -> out.f64((Double) v), () -> 0.0);
    }

    private static Field string() {
        return f(Kind.STRING, (in, b) -> in.string(), (out, v, a) -> out.string((String) v), () -> "");
    }

    private static Field uuid() {
        return f(Kind.UUID, (in, b) -> in.uuid(), (out, v, a) -> out.uuid((UUID) v), () -> new UUID(0, 0));
    }

    private static Field component() {
        return f(Kind.COMPONENT, (in, b) -> WrappedChatComponent.fromNbt(in.nbt()),
                (out, v, a) -> out.raw(component(v).toNbt()), () -> WrappedChatComponent.fromText(""));
    }

    private static WrappedChatComponent component(Object v) {
        return WrappedChatComponent.fromHandle(v);
    }

    private static Field blockPos() {
        return f(Kind.BLOCK_POS, (in, b) -> {
            int[] p = in.blockPos();
            return new BlockPosition(p[0], p[1], p[2]);
        }, (out, v, a) -> {
            BlockPosition p = (BlockPosition) v;
            out.blockPos(p.getX(), p.getY(), p.getZ());
        }, () -> BlockPosition.ORIGIN);
    }

    private static Field blockState() {
        return f(Kind.BLOCK_DATA, (in, b) -> WrappedBlockData.fromStateId(in.varInt()),
                (out, v, a) -> out.varInt(WrappedBlockData.createData(v).getStateId()), () -> WrappedBlockData.fromStateId(0));
    }

    private static Field soundCategory() {
        return f(Kind.SOUND_CATEGORY, (in, b) -> EnumWrappers.SoundCategory.values()[in.varInt()],
                (out, v, a) -> out.varInt(((EnumWrappers.SoundCategory) v).ordinal()), () -> EnumWrappers.SoundCategory.MASTER);
    }

    /** A sound holder: a registry id + 1, or 0 and an inline sound event. */
    private static Field sound() {
        return f(Kind.SOUND, (in, b) -> {
            int id = in.varInt();
            if (id != 0) {
                Sound s = Registries.sound(id - 1);
                return s != null ? s : new RawSound(id, null);
            }
            Wire.Out raw = new Wire.Out();
            String key = in.string();
            raw.string(key);
            boolean range = in.bool();
            raw.bool(range);
            if (range) {
                raw.f32(in.f32());
            }
            Sound s = Registries.sound(key);
            return s != null ? s : new RawSound(0, raw.bytes());
        }, (out, v, a) -> {
            if (v instanceof RawSound r) {
                out.varInt(r.id());
                if (r.inline() != null) {
                    out.raw(r.inline());
                }
                return;
            }
            Sound s = (Sound) v;
            int id = Registries.soundId(s);
            if (id >= 0) {
                out.varInt(id + 1);
            } else {
                out.varInt(0).string(s.getKey().toString()).bool(false);
            }
        }, () -> Sound.UI_BUTTON_CLICK);
    }

    /** A sound Bukkit does not know, written back as it came. */
    record RawSound(int id, byte[] inline) {}

    private static Field entityType() {
        return f(Kind.ENTITY_TYPE, (in, b) -> Registries.entityType(in.varInt()),
                (out, v, a) -> out.varInt(Registries.entityTypeId((EntityType) v)), () -> EntityType.ARMOR_STAND);
    }

    private static Field lpVec3() {
        return f(Kind.VECTOR, (in, b) -> {
            double[] v = Wire.lpVec3(in);
            return new Vector(v[0], v[1], v[2]);
        }, (out, v, a) -> {
            Vector vec = (Vector) v;
            Wire.lpVec3(out, vec.getX(), vec.getY(), vec.getZ());
        }, Vector::new);
    }

    private static Field intList() {
        return f(Kind.INT_LIST, (in, b) -> {
            int n = in.varInt();
            List<Integer> out = new ArrayList<>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) {
                out.add(in.varInt());
            }
            return out;
        }, (out, v, a) -> {
            @SuppressWarnings("unchecked")
            List<Integer> list = (List<Integer>) v;
            out.varInt(list.size());
            for (int x : list) {
                out.varInt(x);
            }
        }, ArrayList::new);
    }

    private static Field uuidList() {
        return f(Kind.UUID_LIST, (in, b) -> {
            int n = in.varInt();
            List<UUID> out = new ArrayList<>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) {
                out.add(in.uuid());
            }
            return out;
        }, (out, v, a) -> {
            @SuppressWarnings("unchecked")
            List<UUID> list = (List<UUID>) v;
            out.varInt(list.size());
            for (UUID x : list) {
                out.uuid(x);
            }
        }, ArrayList::new);
    }

    // ----- Entity metadata (SynchedEntityData.DataValue)

    /** The values Vanillite cannot decode, from the first such value to the end marker. */
    record RawValues(byte[] bytes) {}

    private static Field dataValues() {
        return f(Kind.DATA_VALUES, (in, b) -> {
            List<WrappedDataValue> out = new ArrayList<>();
            while (true) {
                int start = in.position();
                int index = in.u8();
                if (index == 0xff) {
                    return out;
                }
                int type = in.varInt();
                WrappedDataWatcher.Serializer s = WrappedDataWatcher.Registry.byId(type);
                Object value = s == null ? null : readValue(in, type);
                if (s == null || value == UNKNOWN) {
                    // Values after one Vanillite cannot decode are kept as bytes.
                    out.add(new WrappedDataValue(-1, null, new RawValues(in.restFrom(start))));
                    return out;
                }
                out.add(new WrappedDataValue(index, s, value));
            }
        }, (out, v, a) -> {
            @SuppressWarnings("unchecked")
            List<WrappedDataValue> list = (List<WrappedDataValue>) v;
            byte[] raw = null;
            for (WrappedDataValue d : list) {
                if (d.getValue() instanceof RawValues r) {
                    raw = r.bytes();
                    continue;
                }
                if (d.getSerializer() == null) {
                    throw new IllegalArgumentException("Vanillite: metadata value " + d.getIndex() + " has no serializer");
                }
                out.u8(d.getIndex());
                int type = d.getSerializer().id();
                out.varInt(type);
                writeValue(out, type, d.getValue());
            }
            if (raw != null) {
                out.raw(raw);
            } else {
                out.u8(0xff);
            }
        }, ArrayList::new);
    }

    private static final Object UNKNOWN = new Object();

    private static Object readValue(Wire.In in, int type) {
        return switch (type) {
            case 0 -> in.i8();
            case 1 -> in.varInt();
            case 2 -> in.varLong();
            case 3 -> in.f32();
            case 4 -> in.string();
            case 5 -> WrappedChatComponent.fromNbt(in.nbt());
            case 6 -> in.bool() ? Optional.of(WrappedChatComponent.fromNbt(in.nbt())) : Optional.empty();
            case 8 -> in.bool();
            case 9 -> new float[] {in.f32(), in.f32(), in.f32()};
            case 10 -> {
                int[] p = in.blockPos();
                yield new BlockPosition(p[0], p[1], p[2]);
            }
            case 11 -> {
                if (!in.bool()) {
                    yield Optional.empty();
                }
                int[] p = in.blockPos();
                yield Optional.of(new BlockPosition(p[0], p[1], p[2]));
            }
            case 12 -> EnumWrappers.Direction.values()[in.varInt()];
            case 13 -> in.bool() ? Optional.of(in.uuid()) : Optional.empty();
            case 14 -> WrappedBlockData.fromStateId(in.varInt());
            case 15 -> {
                int id = in.varInt();
                yield id == 0 ? Optional.empty() : Optional.of(WrappedBlockData.fromStateId(id));
            }
            case 19 -> {
                int v = in.varInt();
                yield v == 0 ? Optional.empty() : Optional.of(v - 1);
            }
            case 20 -> EnumWrappers.EntityPose.values()[in.varInt()];
            case 39 -> Joml.vector(in.f32(), in.f32(), in.f32());
            case 40 -> Joml.quaternion(in.f32(), in.f32(), in.f32(), in.f32());
            default -> UNKNOWN;
        };
    }

    private static void writeValue(Wire.Out out, int type, Object v) {
        switch (type) {
            case 0 -> out.u8(((Number) v).byteValue());
            case 1 -> out.varInt(((Number) v).intValue());
            case 2 -> out.varLong(((Number) v).longValue());
            case 3 -> out.f32(((Number) v).floatValue());
            case 4 -> out.string((String) v);
            case 5 -> out.raw(component(v).toNbt());
            case 6 -> {
                Optional<?> o = optional(v);
                out.bool(o.isPresent());
                o.ifPresent(c -> out.raw(component(c).toNbt()));
            }
            case 8 -> out.bool((Boolean) v);
            case 9 -> {
                float[] f = (float[]) v;
                out.f32(f[0]).f32(f[1]).f32(f[2]);
            }
            case 10 -> {
                BlockPosition p = (BlockPosition) v;
                out.blockPos(p.getX(), p.getY(), p.getZ());
            }
            case 11 -> {
                Optional<?> o = optional(v);
                out.bool(o.isPresent());
                o.ifPresent(p -> out.blockPos(((BlockPosition) p).getX(), ((BlockPosition) p).getY(),
                        ((BlockPosition) p).getZ()));
            }
            case 12 -> out.varInt(((Enum<?>) v).ordinal());
            case 13 -> {
                Optional<?> o = optional(v);
                out.bool(o.isPresent());
                o.ifPresent(u -> out.uuid((UUID) u));
            }
            case 14 -> out.varInt(WrappedBlockData.createData(v).getStateId());
            case 15 -> {
                Optional<?> o = optional(v);
                out.varInt(o.isPresent() ? WrappedBlockData.createData(o.get()).getStateId() : 0);
            }
            case 19 -> {
                if (v instanceof OptionalInt oi) {
                    out.varInt(oi.isPresent() ? oi.getAsInt() + 1 : 0);
                } else {
                    Optional<?> o = optional(v);
                    out.varInt(o.isPresent() ? ((Number) o.get()).intValue() + 1 : 0);
                }
            }
            case 20 -> out.varInt(((Enum<?>) v).ordinal());
            case 39 -> {
                float[] f = Joml.floats(v, "x", "y", "z");
                out.f32(f[0]).f32(f[1]).f32(f[2]);
            }
            case 40 -> {
                float[] q = Joml.floats(v, "x", "y", "z", "w");
                out.f32(q[0]).f32(q[1]).f32(q[2]).f32(q[3]);
            }
            default -> throw new IllegalArgumentException("Vanillite cannot write metadata values of serializer " + type);
        }
    }

    private static Optional<?> optional(Object v) {
        return v instanceof Optional<?> o ? o : Optional.ofNullable(v);
    }

    // ----- Player info (ClientboundPlayerInfoUpdatePacket)

    private static final EnumWrappers.PlayerInfoAction[] ACTIONS = {
        EnumWrappers.PlayerInfoAction.ADD_PLAYER, EnumWrappers.PlayerInfoAction.INITIALIZE_CHAT,
        EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE, EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
        EnumWrappers.PlayerInfoAction.UPDATE_LATENCY, EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME,
        EnumWrappers.PlayerInfoAction.UPDATE_LIST_ORDER, EnumWrappers.PlayerInfoAction.UPDATE_HAT,
    };

    private static Field infoActions() {
        return f(Kind.INFO_ACTIONS, (in, b) -> {
            int bits = in.u8();
            Set<EnumWrappers.PlayerInfoAction> out = EnumSet.noneOf(EnumWrappers.PlayerInfoAction.class);
            for (int i = 0; i < ACTIONS.length; i++) {
                if ((bits & (1 << i)) != 0) {
                    out.add(ACTIONS[i]);
                }
            }
            return out;
        }, (out, v, a) -> out.u8(actionBits(v)), () -> EnumSet.noneOf(EnumWrappers.PlayerInfoAction.class));
    }

    private static int actionBits(Object v) {
        int bits = 0;
        for (Object o : (Set<?>) v) {
            int i = java.util.Arrays.asList(ACTIONS).indexOf(o);
            if (i < 0) {
                throw new IllegalArgumentException("Vanillite: " + o + " is not a player info update action");
            }
            bits |= 1 << i;
        }
        return bits;
    }

    private static Field infoData() {
        return f(Kind.INFO_DATA, (in, before) -> {
            int bits = actionBits(before.get(0));
            int n = in.varInt();
            List<PlayerInfoData> out = new ArrayList<>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) {
                out.add(readInfo(in, bits));
            }
            return out;
        }, (out, v, all) -> {
            int bits = actionBits(all[0]);
            @SuppressWarnings("unchecked")
            List<PlayerInfoData> list = (List<PlayerInfoData>) v;
            out.varInt(list.size());
            for (PlayerInfoData d : list) {
                writeInfo(out, bits, d);
            }
        }, ArrayList::new);
    }

    private static PlayerInfoData readInfo(Wire.In in, int bits) {
        UUID id = in.uuid();
        WrappedGameProfile profile = null;
        WrappedRemoteChatSessionData chat = null;
        EnumWrappers.NativeGameMode mode = EnumWrappers.NativeGameMode.SURVIVAL;
        boolean listed = false;
        boolean hat = false;
        int latency = 0;
        int order = 0;
        WrappedChatComponent name = null;
        if ((bits & 1) != 0) {
            profile = new WrappedGameProfile(id, in.string());
            int n = in.varInt();
            for (int i = 0; i < n; i++) {
                String key = in.string();
                String value = in.string();
                String signature = in.bool() ? in.string() : null;
                profile.getProperties().put(key, new WrappedSignedProperty(key, value, signature));
            }
        }
        if ((bits & 2) != 0 && in.bool()) {
            int start = in.position();
            in.skipChatSession();
            chat = new WrappedRemoteChatSessionData(in.slice(start));
        }
        if ((bits & 4) != 0) {
            mode = EnumWrappers.NativeGameMode.byId(in.varInt());
        }
        if ((bits & 8) != 0) {
            listed = in.bool();
        }
        if ((bits & 16) != 0) {
            latency = in.varInt();
        }
        if ((bits & 32) != 0 && in.bool()) {
            name = WrappedChatComponent.fromNbt(in.nbt());
        }
        if ((bits & 64) != 0) {
            order = in.varInt();
        }
        if ((bits & 128) != 0) {
            hat = in.bool();
        }
        return new PlayerInfoData(id, latency, listed, mode, profile, name, hat, order, chat);
    }

    private static void writeInfo(Wire.Out out, int bits, PlayerInfoData d) {
        UUID id = d.getProfileId() != null ? d.getProfileId() : d.getProfile().getUUID();
        out.uuid(id);
        if ((bits & 1) != 0) {
            WrappedGameProfile p = d.getProfile();
            if (p == null) {
                throw new IllegalArgumentException("Vanillite: ADD_PLAYER needs a game profile");
            }
            out.string(p.getName());
            out.varInt(p.getProperties().size());
            for (WrappedSignedProperty prop : p.getProperties().values()) {
                out.string(prop.getName()).string(prop.getValue()).bool(prop.hasSignature());
                if (prop.hasSignature()) {
                    out.string(prop.getSignature());
                }
            }
        }
        if ((bits & 2) != 0) {
            WrappedRemoteChatSessionData chat = d.getRemoteChatSessionData();
            out.bool(chat != null);
            if (chat != null) {
                out.raw(chat.bytes());
            }
        }
        if ((bits & 4) != 0) {
            EnumWrappers.NativeGameMode mode = d.getGameMode();
            out.varInt(mode == null ? 0 : Math.max(mode.id(), 0));
        }
        if ((bits & 8) != 0) {
            out.bool(d.isListed());
        }
        if ((bits & 16) != 0) {
            out.varInt(d.getLatency());
        }
        if ((bits & 32) != 0) {
            out.bool(d.getDisplayName() != null);
            if (d.getDisplayName() != null) {
                out.raw(d.getDisplayName().toNbt());
            }
        }
        if ((bits & 64) != 0) {
            out.varInt(d.getListOrder());
        }
        if ((bits & 128) != 0) {
            out.bool(d.isShowHat());
        }
    }

    // ----- Serverbound

    private static Field instant() {
        return f(Kind.INSTANT, (in, b) -> Instant.ofEpochMilli(in.i64()), (out, v, a) -> out.i64(((Instant) v).toEpochMilli()),
                Instant::now);
    }

    private static Field hand() {
        return f(Kind.HAND, (in, b) -> EnumWrappers.Hand.values()[in.varInt()],
                (out, v, a) -> out.varInt(((EnumWrappers.Hand) v).ordinal()), () -> EnumWrappers.Hand.MAIN_HAND);
    }

    private static Field blockHit() {
        return f(Kind.MOVING_BLOCK, (in, b) -> {
            int[] p = in.blockPos();
            EnumWrappers.Direction dir = EnumWrappers.Direction.values()[in.varInt()];
            float x = in.f32();
            float y = in.f32();
            float z = in.f32();
            boolean inside = in.bool();
            boolean border = in.bool();
            MovingObjectPositionBlock hit = new MovingObjectPositionBlock(new BlockPosition(p[0], p[1], p[2]),
                    new Vector(p[0] + x, p[1] + y, p[2] + z), dir, inside);
            hit.setWorldBorderHit(border);
            return hit;
        }, (out, v, a) -> {
            MovingObjectPositionBlock hit = (MovingObjectPositionBlock) v;
            BlockPosition p = hit.getBlockPosition();
            out.blockPos(p.getX(), p.getY(), p.getZ());
            out.varInt(hit.getDirection().ordinal());
            Vector at = hit.getPosVector() == null ? new Vector(p.getX(), p.getY(), p.getZ()) : hit.getPosVector();
            out.f32((float) (at.getX() - p.getX())).f32((float) (at.getY() - p.getY())).f32((float) (at.getZ() - p.getZ()));
            out.bool(hit.isInsideBlock()).bool(hit.isWorldBorderHit());
        }, () -> new MovingObjectPositionBlock(BlockPosition.ORIGIN, new Vector(), EnumWrappers.Direction.UP, false));
    }

    static {
        CLIENTBOUND.put("system_chat", new Codec(List.of(component(), bool())));
        CLIENTBOUND.put("set_title_text", new Codec(List.of(component())));
        CLIENTBOUND.put("set_subtitle_text", new Codec(List.of(component())));
        CLIENTBOUND.put("set_action_bar_text", new Codec(List.of(component())));
        CLIENTBOUND.put("set_titles_animation", new Codec(List.of(int32(), int32(), int32())));
        CLIENTBOUND.put("block_update", new Codec(List.of(blockPos(), blockState())));
        CLIENTBOUND.put("remove_entities", new Codec(List.of(intList())));
        CLIENTBOUND.put("set_entity_data", new Codec(List.of(varInt(), dataValues())));
        CLIENTBOUND.put("add_entity", new Codec(List.of(varInt(), uuid(), entityType(), f64(), f64(), f64(), lpVec3(), i8(),
                i8(), i8(), varInt())));
        CLIENTBOUND.put("sound", new Codec(List.of(sound(), soundCategory(), int32(), int32(), int32(), f32(), f32(), i64())));
        CLIENTBOUND.put("sound_entity", new Codec(List.of(sound(), soundCategory(), varInt(), f32(), f32(), i64())));
        CLIENTBOUND.put("player_info_update", new Codec(List.of(infoActions(), infoData())));
        CLIENTBOUND.put("player_info_remove", new Codec(List.of(uuidList())));
        SERVERBOUND.put("chat", new Codec(List.of(string(), instant(), i64())));
        SERVERBOUND.put("chat_command", new Codec(List.of(string())));
        SERVERBOUND.put("use_item_on", new Codec(List.of(hand(), blockHit(), varInt())));
    }
}
