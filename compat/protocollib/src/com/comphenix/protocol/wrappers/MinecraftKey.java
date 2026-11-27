// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import java.util.Locale;

/** A namespaced key. */
public class MinecraftKey {
    private final String prefix;
    private final String key;

    public MinecraftKey(String prefix, String key) {
        this.prefix = prefix;
        this.key = key;
    }

    public MinecraftKey(String key) {
        this("minecraft", key);
    }

    public static MinecraftKey fromHandle(Object handle) {
        String s = String.valueOf(handle);
        int colon = s.indexOf(':');
        return colon < 0 ? new MinecraftKey(s) : new MinecraftKey(s.substring(0, colon), s.substring(colon + 1));
    }

    public static MinecraftKey fromEnum(Enum<?> value) {
        return new MinecraftKey(value.name().toLowerCase(Locale.ROOT).replace('_', '.'));
    }

    public String getPrefix() {
        return prefix;
    }

    public String getKey() {
        return key;
    }

    public String getFullKey() {
        return prefix + ":" + key;
    }

    public String getEnumFormat() {
        return key.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MinecraftKey k && k.prefix.equals(prefix) && k.key.equals(key);
    }

    @Override
    public int hashCode() {
        return getFullKey().hashCode();
    }

    @Override
    public String toString() {
        return "MinecraftKey[" + getFullKey() + "]";
    }
}
