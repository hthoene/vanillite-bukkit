// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.events;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.reflect.FieldAccessException;
import com.comphenix.protocol.reflect.StructureModifier;
import dev.vanillite.bukkit.compat.protocollib.Codecs;
import dev.vanillite.bukkit.compat.protocollib.Kind;
import dev.vanillite.bukkit.compat.protocollib.Wire;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A packet: its type and fields. On Vanillite a packet is its wire body (the server's protocol),
 * decoded on the first field access into the fields Vanillite maps for its type and encoded again
 * when a field changed. Bytes after the mapped fields are kept as they are.
 */
public class PacketContainer extends AbstractStructure implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Set<Kind> MUTABLE = EnumSet.of(Kind.COMPONENT, Kind.DATA_VALUES, Kind.INT_LIST, Kind.UUID_LIST,
            Kind.INFO_ACTIONS, Kind.INFO_DATA, Kind.MOVING_BLOCK, Kind.VECTOR);

    private final PacketType type;
    private final transient Codecs.Codec codec;
    private transient byte[] body;
    private transient Kind[] kinds;
    private transient Object[] values;
    private transient byte[] tail = new byte[0];
    private transient boolean dirty;
    private final transient Map<String, Object> meta = new ConcurrentHashMap<>();

    private final transient StructureModifier.Fields fields = new StructureModifier.Fields() {
        @Override
        public Object get(int slot) {
            decode();
            if (MUTABLE.contains(kinds[slot])) {
                dirty = true;
            }
            return values[slot];
        }

        @Override
        public void set(int slot, Object value) {
            decode();
            if (value == null) {
                throw new FieldAccessException("Vanillite: field " + slot + " of " + type.name() + " cannot be null");
            }
            values[slot] = value;
            dirty = true;
        }

        @Override
        public int[] slots(Class<?> wanted) {
            decode();
            int[] out = new int[kinds.length];
            int n = 0;
            for (int i = 0; i < kinds.length; i++) {
                if (kinds[i].matches(wanted)) {
                    out[n++] = i;
                }
            }
            return Arrays.copyOf(out, n);
        }

        @Override
        public String describe() {
            return "packet " + type.name() + " (" + type.vanillaName() + ")";
        }

        @Override
        public Object target() {
            return PacketContainer.this;
        }
    };

    /** A new packet of {@code type} with default field values. */
    public PacketContainer(PacketType type) {
        this.type = type;
        this.codec = codecOf(type);
        if (codec != null) {
            kinds = codec.kinds();
            values = codec.initial();
        } else {
            kinds = new Kind[0];
            values = new Object[0];
        }
        dirty = true;
    }

    public PacketContainer(PacketType type, Object handle) {
        this(type, handle, null);
    }

    public PacketContainer(PacketType type, Object handle, StructureModifier<Object> structure) {
        this.type = type;
        this.codec = codecOf(type);
        if (handle instanceof PacketContainer other) {
            other.decode();
            this.body = other.body;
            this.kinds = other.kinds;
            this.values = other.values.clone();
            this.tail = other.tail;
            this.dirty = other.dirty;
        } else if (handle instanceof byte[] b) {
            this.body = b;
        } else {
            throw new IllegalArgumentException("Vanillite: packets have no Minecraft handle objects");
        }
    }

    protected PacketContainer() {
        this.type = null;
        this.codec = null;
        this.kinds = new Kind[0];
        this.values = new Object[0];
    }

    private static Codecs.Codec codecOf(PacketType type) {
        return type.getProtocol() == PacketType.Protocol.PLAY ? Codecs.of(type.isServer(), type.vanillaName()) : null;
    }

    /** A packet read from the wire: {@code body} is the packet id and its fields. */
    public static PacketContainer fromBody(PacketType type, byte[] body) {
        return new PacketContainer(type, body);
    }

    public static PacketContainer fromPacket(Object packet) {
        if (packet instanceof PacketContainer p) {
            return p;
        }
        throw new IllegalArgumentException("Vanillite: packets have no Minecraft handle objects");
    }

    private void decode() {
        if (kinds != null) {
            return;
        }
        if (codec == null) {
            kinds = new Kind[0];
            values = new Object[0];
            return;
        }
        try {
            Wire.In in = new Wire.In(body, 0);
            in.varInt();
            Object[] decoded = codec.decode(in);
            tail = in.rest();
            values = decoded;
            kinds = codec.kinds();
        } catch (RuntimeException e) {
            kinds = new Kind[0];
            values = new Object[0];
            throw new FieldAccessException("Vanillite could not decode " + type + ": " + e.getMessage(), e);
        }
    }

    /** Whether a field may have changed since the packet was read. */
    public boolean isChanged() {
        return dirty;
    }

    /** The wire body (id and fields): the packet as read when no field changed, else encoded. */
    public byte[] encode() {
        if (!dirty && body != null) {
            return body;
        }
        decode();
        if (codec == null) {
            if (body != null) {
                return body;
            }
            throw new UnsupportedOperationException("Vanillite cannot build packets of type " + type
                    + ": ProtocolLib's fields of this packet are not mapped");
        }
        int id = type.getCurrentId();
        if (id < 0) {
            throw new UnsupportedOperationException("Minecraft " + org.bukkit.Bukkit.getMinecraftVersion()
                    + " has no packet " + type);
        }
        Wire.Out out = new Wire.Out().varInt(id);
        codec.encode(out, values);
        out.raw(tail);
        return out.bytes();
    }

    @Override
    StructureModifier.Fields fields() {
        return fields;
    }

    @Override
    int[] slots(Kind kind) {
        decode();
        int[] out = new int[kinds.length];
        int n = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == kind) {
                out[n++] = i;
            }
        }
        return Arrays.copyOf(out, n);
    }

    @Override
    public Object getHandle() {
        return this;
    }

    @Override
    public StructureModifier<Object> getModifier() {
        return super.getModifier();
    }

    @Deprecated
    public int getId() {
        return type.getCurrentId();
    }

    public PacketType getType() {
        return type;
    }

    public PacketContainer shallowClone() {
        return new PacketContainer(type, this);
    }

    public PacketContainer deepClone() {
        PacketContainer copy = new PacketContainer(type, this);
        for (int i = 0; i < copy.values.length; i++) {
            if (copy.values[i] instanceof List<?> l) {
                copy.values[i] = new ArrayList<>(l);
            } else if (copy.values[i] instanceof com.comphenix.protocol.wrappers.WrappedChatComponent c) {
                copy.values[i] = c.deepClone();
            } else if (copy.values[i] instanceof Set<?> s) {
                copy.values[i] = s.isEmpty() ? EnumSet.noneOf(com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction.class)
                        : EnumSet.copyOf(cast(s));
            }
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Set<com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction> cast(Set<?> s) {
        return (Set<com.comphenix.protocol.wrappers.EnumWrappers.PlayerInfoAction>) s;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> getMeta(String key) {
        return Optional.ofNullable((T) meta.get(key));
    }

    public <T> void setMeta(String key, T value) {
        meta.put(key, value);
    }

    public void removeMeta(String key) {
        meta.remove(key);
    }

    @Override
    public String toString() {
        return "PacketContainer[type=" + type + ", structureModifier=" + getModifier() + "]";
    }
}
