// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import java.lang.invoke.MethodHandle;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** A block state as Bukkit {@link BlockData}: its state id; text and type come from the server. */
public abstract class VBlockData implements BlockData {
    private static final MethodHandle NEW = Impl.constructor(VBlockData.class, int.class);

    final int state;

    protected VBlockData(int state) {
        this.state = state;
    }

    public static BlockData of(int state) {
        try {
            return (VBlockData) NEW.invokeExact(state);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** The block data of a text such as {@code minecraft:oak_log[axis=x]} (missing properties: defaults). */
    public static BlockData parse(String text) {
        String t = text.strip();
        if (!t.contains(":")) {
            t = "minecraft:" + t;
        }
        int id = NativeAccess.stateId(t);
        if (id < 0 && t.contains("[")) {
            // Fill the properties the text leaves out from the default state.
            String type = t.substring(0, t.indexOf('['));
            int def = NativeAccess.stateId(type);
            String full = def < 0 ? null : NativeAccess.stateName(def);
            if (full != null && full.contains("[")) {
                java.util.Map<String, String> props = props(full);
                props.putAll(props(t));
                StringBuilder b = new StringBuilder(type).append('[');
                props.forEach((k, v) -> b.append(b.charAt(b.length() - 1) == '[' ? "" : ",").append(k).append('=').append(v));
                id = NativeAccess.stateId(b.append(']').toString());
            }
        }
        if (id < 0) {
            throw new IllegalArgumentException("Could not parse data: " + text);
        }
        return of(id);
    }

    private static java.util.Map<String, String> props(String name) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        int open = name.indexOf('[');
        if (open < 0) {
            return out;
        }
        for (String p : name.substring(open + 1, name.length() - 1).split(",")) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
            }
        }
        return out;
    }

    public int stateId() {
        return state;
    }

    @Override
    public Material getMaterial() {
        return Materials.of(state);
    }

    @Override
    public String getAsString() {
        String s = NativeAccess.stateName(state);
        return s == null ? "minecraft:air" : s;
    }

    @Override
    public String getAsString(boolean hideUnspecified) {
        return getAsString();
    }

    @Override
    public boolean matches(BlockData data) {
        return data instanceof VBlockData d && d.state == state;
    }

    @Override
    public BlockData merge(BlockData data) {
        return data;
    }

    @Override
    public BlockData clone() {
        return of(state);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VBlockData d && d.state == state;
    }

    @Override
    public int hashCode() {
        return state;
    }

    @Override
    public String toString() {
        return "VanilliteBlockData{" + getAsString() + "}";
    }
}
