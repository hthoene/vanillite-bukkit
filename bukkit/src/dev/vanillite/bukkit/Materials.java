// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Locale;
import org.bukkit.Material;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Block state ids ↔ {@link Material}, cached in arrays indexed by state id and by ordinal. */
public final class Materials {
    private Materials() {}

    private static Material[] byState = new Material[0];
    private static final int[] STATE = new int[Material.values().length];

    static {
        Arrays.fill(STATE, -1);
    }

    /** The material of a block state id. */
    public static Material of(int state) {
        Material[] table = byState;
        if (state >= 0 && state < table.length && table[state] != null) {
            return table[state];
        }
        return slow(state);
    }

    private static synchronized Material slow(int state) {
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(256);
        Host.block_state_name(Native.plugin, state, buf);
        String name = Native.string(buf, true);
        if (name == null) {
            return Material.AIR;
        }
        int props = name.indexOf('[');
        String key = (props < 0 ? name : name.substring(0, props)).replace("minecraft:", "");
        Material m = Material.getMaterial(key.toUpperCase(Locale.ROOT));
        if (m == null) {
            m = Material.AIR;
        }
        if (state >= byState.length) {
            byState = Arrays.copyOf(byState, Math.max(state + 1, byState.length * 2));
        }
        byState[state] = m;
        return m;
    }

    /** The default block state id of a block material. */
    public static int state(Material m) {
        int id = STATE[m.ordinal()];
        if (id >= 0) {
            return id;
        }
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        Ctx.check(Host.block_state_id(Native.plugin, s.str(m.getKey().toString()), out), "block state of " + m);
        id = out.get(JAVA_INT, 0);
        STATE[m.ordinal()] = id;
        return id;
    }
}
