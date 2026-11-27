// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * A {@link PersistentDataContainer}: values in their primitive form, keyed by namespaced key. As NBT
 * it is a compound (an item's {@code custom_data.PublicBukkitValues}), written with {@link Snbt}.
 */
public final class VPersistentData implements PersistentDataContainer, PersistentDataAdapterContext {
    private final Map<NamespacedKey, Object> values = new LinkedHashMap<>();

    public VPersistentData() {}

    /** A container read from its NBT compound. */
    @SuppressWarnings("unchecked")
    static VPersistentData fromNbt(Map<String, Object> nbt) {
        VPersistentData d = new VPersistentData();
        nbt.forEach((k, v) -> {
            NamespacedKey key = NamespacedKey.fromString(k);
            if (key != null) {
                d.values.put(key, v instanceof Map<?, ?> m ? fromNbt((Map<String, Object>) m) : v);
            }
        });
        return d;
    }

    /** The NBT compound of this container. */
    Map<String, Object> toNbt() {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k.toString(), nbt(v)));
        return out;
    }

    private static Object nbt(Object v) {
        if (v instanceof VPersistentData d) {
            return d.toNbt();
        }
        if (v instanceof PersistentDataContainer[] a) {
            List<Object> l = new ArrayList<>();
            for (PersistentDataContainer c : a) {
                l.add(((VPersistentData) c).toNbt());
            }
            return l;
        }
        if (v instanceof List<?> list) {
            List<Object> l = new ArrayList<>();
            list.forEach(x -> l.add(nbt(x)));
            return l;
        }
        if (v instanceof Boolean b) {
            return (byte) (b ? 1 : 0);
        }
        return v;
    }

    @Override
    public PersistentDataContainer newPersistentDataContainer() {
        return new VPersistentData();
    }

    @Override
    public <P, C> void set(NamespacedKey key, PersistentDataType<P, C> type, C value) {
        values.put(key, type.toPrimitive(value, this));
    }

    @Override
    public void remove(NamespacedKey key) {
        values.remove(key);
    }

    @Override
    public void readFromBytes(byte[] bytes, boolean clear) throws IOException {
        if (clear) {
            values.clear();
        }
        try {
            values.putAll(fromNbt(Snbt.compound(new String(bytes, StandardCharsets.UTF_8))).values);
        } catch (RuntimeException e) {
            throw new IOException(e);
        }
    }

    @Override
    public <P, C> boolean has(NamespacedKey key, PersistentDataType<P, C> type) {
        Object v = values.get(key);
        return v != null && primitive(v, type.getPrimitiveType()) != null;
    }

    @Override
    public boolean has(NamespacedKey key) {
        return values.containsKey(key);
    }

    @Override
    public <P, C> C get(NamespacedKey key, PersistentDataType<P, C> type) {
        Object v = values.get(key);
        if (v == null) {
            return null;
        }
        P p = primitive(v, type.getPrimitiveType());
        if (p == null) {
            throw new IllegalArgumentException("The value of " + key + " is not a " + type.getPrimitiveType().getSimpleName());
        }
        return type.fromPrimitive(p, this);
    }

    /** A stored value as the primitive type, converting NBT number forms; null when it does not fit. */
    @SuppressWarnings("unchecked")
    private static <P> P primitive(Object v, Class<P> type) {
        if (type.isInstance(v)) {
            return (P) v;
        }
        if (v instanceof Number n) {
            Object c = switch (type.getName()) {
                case "java.lang.Byte" -> n.byteValue();
                case "java.lang.Short" -> n.shortValue();
                case "java.lang.Integer" -> n.intValue();
                case "java.lang.Long" -> n.longValue();
                case "java.lang.Float" -> n.floatValue();
                case "java.lang.Double" -> n.doubleValue();
                case "java.lang.Boolean" -> n.intValue() != 0;
                default -> null;
            };
            return (P) c;
        }
        if (v instanceof Boolean b && type == Byte.class) {
            return (P) (Byte) (byte) (b ? 1 : 0);
        }
        if (v instanceof List<?> l && type == PersistentDataContainer[].class) {
            PersistentDataContainer[] a = new PersistentDataContainer[l.size()];
            for (int i = 0; i < a.length; i++) {
                if (!(l.get(i) instanceof PersistentDataContainer c)) {
                    return null;
                }
                a[i] = c;
            }
            return (P) a;
        }
        return null;
    }

    @Override
    public <P, C> C getOrDefault(NamespacedKey key, PersistentDataType<P, C> type, C defaultValue) {
        C v = has(key, type) ? get(key, type) : null;
        return v != null ? v : defaultValue;
    }

    @Override
    public PersistentDataType<?, ?> getPrimitiveStorageType(Key key) {
        Object v = values.get(key instanceof NamespacedKey k ? k : new NamespacedKey(key.namespace(), key.value()));
        return switch (v) {
            case null -> null;
            case Byte b -> PersistentDataType.BYTE;
            case Short b -> PersistentDataType.SHORT;
            case Integer b -> PersistentDataType.INTEGER;
            case Long b -> PersistentDataType.LONG;
            case Float b -> PersistentDataType.FLOAT;
            case Double b -> PersistentDataType.DOUBLE;
            case Boolean b -> PersistentDataType.BOOLEAN;
            case String b -> PersistentDataType.STRING;
            case byte[] b -> PersistentDataType.BYTE_ARRAY;
            case int[] b -> PersistentDataType.INTEGER_ARRAY;
            case long[] b -> PersistentDataType.LONG_ARRAY;
            case PersistentDataContainer b -> PersistentDataType.TAG_CONTAINER;
            default -> null;
        };
    }

    @Override
    public Set<NamespacedKey> getKeys() {
        return Set.copyOf(values.keySet());
    }

    @Override
    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public void copyTo(PersistentDataContainer other, boolean replace) {
        VPersistentData o = (VPersistentData) other;
        values.forEach((k, v) -> {
            if (replace || !o.values.containsKey(k)) {
                o.values.put(k, v);
            }
        });
    }

    @Override
    public PersistentDataAdapterContext getAdapterContext() {
        return this;
    }

    @Override
    public byte[] serializeToBytes() {
        return Snbt.write(toNbt()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getSize() {
        return values.size();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VPersistentData d && Snbt.write(toNbt()).equals(Snbt.write(d.toNbt()));
    }

    @Override
    public int hashCode() {
        return Snbt.write(toNbt()).hashCode();
    }

    VPersistentData copy() {
        VPersistentData d = new VPersistentData();
        d.values.putAll(values);
        return d;
    }
}
