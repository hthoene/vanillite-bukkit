// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/**
 * Entity metadata types. On Vanillite only the serializer registry is used: metadata travels as
 * {@link WrappedDataValue} lists ({@code ENTITY_METADATA}); a whole data watcher is not available.
 */
public class WrappedDataWatcher {
    public WrappedDataWatcher() {
        throw new UnsupportedOperationException(
                "Vanillite: WrappedDataWatcher is not supported; use WrappedDataValue lists (ENTITY_METADATA)");
    }

    /** The type of a metadata value; its handle is the serializer's network id. */
    public static class Serializer extends AbstractWrapper {
        private final Type type;
        private final boolean optional;

        public Serializer(Class<?> type, Object handle, boolean optional) {
            this((Type) type, handle, optional);
        }

        public Serializer(Type type, Object handle, boolean optional) {
            super(Integer.class);
            this.type = type;
            this.optional = optional;
            this.handle = handle;
        }

        /** The serializer's network id. */
        public int id() {
            return (Integer) handle;
        }

        public Class<?> getType() {
            return type instanceof Class<?> c ? c : Object.class;
        }

        public Type getGenericType() {
            return type;
        }

        public boolean isOptional() {
            return optional;
        }

        @Override
        public String toString() {
            return "Serializer[type=" + type + ", id=" + handle + ", optional=" + optional + "]";
        }
    }

    /** The serializers of the protocol, by value type. */
    public static class Registry {
        private static final Serializer[] BY_ID = new Serializer[64];

        static {
            add(0, Byte.class, false);
            add(1, Integer.class, false);
            add(2, Long.class, false);
            add(3, Float.class, false);
            add(4, String.class, false);
            add(5, WrappedChatComponent.class, false);
            add(6, WrappedChatComponent.class, true);
            add(7, ItemStack.class, false);
            add(8, Boolean.class, false);
            add(9, float[].class, false);
            add(10, BlockPosition.class, false);
            add(11, BlockPosition.class, true);
            add(12, EnumWrappers.Direction.class, false);
            add(13, UUID.class, true);
            add(14, WrappedBlockData.class, false);
            add(15, WrappedBlockData.class, true);
            add(19, Integer.class, true);
            add(20, EnumWrappers.EntityPose.class, false);
            add(39, dev.vanillite.bukkit.compat.protocollib.Joml.VECTOR, false);
            add(40, dev.vanillite.bukkit.compat.protocollib.Joml.QUATERNION, false);
        }

        public Registry() {}

        private static void add(int id, Class<?> type, boolean optional) {
            BY_ID[id] = new Serializer(type, id, optional);
        }

        /** The serializer of a network id; null for one Vanillite does not decode. */
        public static Serializer byId(int id) {
            return id >= 0 && id < BY_ID.length ? BY_ID[id] : null;
        }

        public static Serializer get(Class<?> type) {
            return get(type, false);
        }

        public static Serializer get(Type type) {
            return get(type, false);
        }

        public static Serializer get(Class<?> type, boolean optional) {
            return get((Type) type, optional);
        }

        public static Serializer get(Type type, boolean optional) {
            Class<?> c = type instanceof Class<?> k ? k : Object.class;
            if (c == Optional.class) {
                throw new IllegalArgumentException("Use get(type, true) for optional values");
            }
            for (Serializer s : BY_ID) {
                if (s != null && s.isOptional() == optional && s.getType().isAssignableFrom(box(c))) {
                    return s;
                }
            }
            throw new IllegalArgumentException("No serializer found for " + type + (optional ? " (optional)" : ""));
        }

        private static Class<?> box(Class<?> c) {
            if (c == byte.class) {
                return Byte.class;
            }
            if (c == int.class) {
                return Integer.class;
            }
            if (c == long.class) {
                return Long.class;
            }
            if (c == float.class) {
                return Float.class;
            }
            if (c == boolean.class) {
                return Boolean.class;
            }
            return c;
        }

        public static Serializer fromHandle(Object handle) {
            Serializer s = handle instanceof Integer id ? byId(id) : null;
            if (s == null) {
                throw new IllegalArgumentException("Unknown serializer " + handle);
            }
            return s;
        }

        public static Serializer getChatComponentSerializer() {
            return getChatComponentSerializer(false);
        }

        public static Serializer getChatComponentSerializer(boolean optional) {
            return BY_ID[optional ? 6 : 5];
        }

        public static Serializer getItemStackSerializer(boolean optional) {
            return BY_ID[7];
        }

        public static Serializer getBlockDataSerializer(boolean optional) {
            return BY_ID[optional ? 15 : 14];
        }

        public static Serializer getVectorSerializer() {
            return BY_ID[9];
        }

        public static Serializer getBlockPositionSerializer(boolean optional) {
            return BY_ID[optional ? 11 : 10];
        }

        public static Serializer getDirectionSerializer() {
            return BY_ID[12];
        }

        public static Serializer getUUIDSerializer(boolean optional) {
            return BY_ID[13];
        }
    }

    /** An index and serializer of a metadata value. */
    public static class WrappedDataWatcherObject {
        private final int index;
        private final Serializer serializer;

        public WrappedDataWatcherObject(Object handle) {
            throw new UnsupportedOperationException("Vanillite: data watcher objects have no handle");
        }

        public WrappedDataWatcherObject(int index, Serializer serializer) {
            this.index = index;
            this.serializer = serializer;
        }

        public int getIndex() {
            return index;
        }

        public Serializer getSerializer() {
            return serializer;
        }

        public void checkSerializer() {
            if (serializer == null) {
                throw new IllegalStateException("No serializer");
            }
        }

        public Object getHandle() {
            return this;
        }

        public Class<?> getHandleType() {
            return WrappedDataWatcherObject.class;
        }

        @Override
        public String toString() {
            return "DataWatcherObject[index=" + index + ", serializer=" + serializer + "]";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WrappedDataWatcherObject w && w.index == index
                    && java.util.Objects.equals(w.serializer, serializer);
        }

        @Override
        public int hashCode() {
            return index * 31 + java.util.Objects.hashCode(serializer);
        }
    }
}
