// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Direct world access by block state id for the layer's plugin adapters ({@code compat/}): what
 * the Bukkit API has no form for (state ids, block entity and entity data as SNBT). Every call
 * goes straight to the host; nothing is copied into the Java heap.
 */
public final class NativeAccess {
    private NativeAccess() {}

    private static String[] names = new String[0];

    private static MemorySegment pos(Native.Scratch s, int x, int y, int z) {
        MemorySegment pos = s.alloc(Abi.BlockPos.SIZE);
        pos.set(JAVA_INT, Abi.BlockPos.x, x);
        pos.set(JAVA_INT, Abi.BlockPos.y, y);
        pos.set(JAVA_INT, Abi.BlockPos.z, z);
        return pos;
    }

    private static int id(World world) {
        return ((VWorld) world).id;
    }

    /** The block state id at a position, -1 outside loaded chunks. */
    public static int state(World world, int x, int y, int z) {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        int status = Host.block_get_id(Ctx.current, id(world), pos(s, x, y, z), out);
        if (status == Abi.status.NOT_FOUND) {
            return -1;
        }
        Ctx.check(status, "block read");
        return out.get(JAVA_INT, 0);
    }

    /** Sets a block state by id, with Vanilla's updates or without physics; false outside loaded chunks. */
    public static boolean setState(World world, int x, int y, int z, int state, boolean physics) {
        int status = Host.block_set_id(Ctx.current, id(world), pos(Native.scratch(), x, y, z), state,
                physics ? Abi.set_mode.UPDATE : Abi.set_mode.NO_PHYSICS);
        if (status == Abi.status.NOT_FOUND) {
            return false;
        }
        Ctx.check(status, "block write");
        return true;
    }

    /** The text of a block state id, e.g. {@code minecraft:oak_log[axis=y]}; null for an unknown id. */
    public static String stateName(int state) {
        String[] table = names;
        if (state >= 0 && state < table.length && table[state] != null) {
            return table[state];
        }
        return slowName(state);
    }

    private static synchronized String slowName(int state) {
        if (state < 0) {
            return null;
        }
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(512);
        if (Host.block_state_name(Native.plugin, state, buf) != Abi.status.OK) {
            return null;
        }
        String name = Native.string(buf, true);
        if (state >= names.length) {
            names = Arrays.copyOf(names, Math.max(state + 1, Math.max(1024, names.length * 2)));
        }
        names[state] = name;
        return name;
    }

    /** The id of a block state text, -1 when the server does not know it. */
    public static int stateId(String name) {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        if (Host.block_state_id(Native.plugin, s.str(name), out) != Abi.status.OK) {
            return -1;
        }
        return out.get(JAVA_INT, 0);
    }

    /**
     * A block type's states: {@code {default state, first state, state count, flags}} (flags from
     * {@code block_type_flag}); null for an unknown block. A type's states have consecutive ids.
     */
    public static int[] blockType(String key) {
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.BlockTypeInfo.SIZE);
        info.set(JAVA_INT, Abi.BlockTypeInfo.size, (int) Abi.BlockTypeInfo.SIZE);
        if (Host.block_type_info(Native.plugin, s.str(key), info) != Abi.status.OK) {
            return null;
        }
        int def = info.get(JAVA_INT, Abi.BlockTypeInfo.default_state);
        int count = info.get(JAVA_INT, Abi.BlockTypeInfo.state_count);
        int flags = info.get(JAVA_INT, Abi.BlockTypeInfo.flags);
        int first = def;
        String prefix = key + "[";
        while (first > 0 && first > def - count) {
            String name = stateName(first - 1);
            if (name == null || !(name.equals(key) || name.startsWith(prefix))) {
                break;
            }
            first--;
        }
        return new int[] {def, first, count, flags};
    }

    /** A block type's numbers: {@code {hardness, blast resistance, friction}}; null for an unknown block. */
    public static float[] blockFacts(String key) {
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.BlockTypeInfo.SIZE);
        info.set(JAVA_INT, Abi.BlockTypeInfo.size, (int) Abi.BlockTypeInfo.SIZE);
        if (Host.block_type_info(Native.plugin, s.str(key), info) != Abi.status.OK) {
            return null;
        }
        return new float[] {info.get(JAVA_FLOAT, Abi.BlockTypeInfo.hardness),
            info.get(JAVA_FLOAT, Abi.BlockTypeInfo.blast_resistance), info.get(JAVA_FLOAT, Abi.BlockTypeInfo.friction)};
    }

    /** The block entity at a position as SNBT ({@code /data get block}), null without one. */
    public static String blockEntity(World world, int x, int y, int z) {
        var s = Native.scratch();
        MemorySegment sink = Native.sink(s);
        int status = Host.block_entity_nbt(Ctx.current, id(world), pos(s, x, y, z), sink);
        if (status != Abi.status.OK) {
            return null;
        }
        return Native.sunk();
    }

    /** Merges SNBT into the block entity at a position ({@code /data merge block}); false without one. */
    public static boolean mergeBlockEntity(World world, int x, int y, int z, String snbt) {
        var s = Native.scratch();
        return Host.block_entity_merge(Ctx.current, id(world), pos(s, x, y, z), s.str(snbt)) == Abi.status.OK;
    }

    /** The biome at a position, e.g. {@code minecraft:plains}; null outside loaded chunks. */
    public static String biome(World world, int x, int y, int z) {
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(256);
        if (Host.biome_get(Ctx.current, id(world), pos(s, x, y, z), buf) != Abi.status.OK) {
            return null;
        }
        return Native.string(buf, true);
    }

    /** Sets the biome of a box ({@code /fillbiome}); false when it failed. */
    public static boolean setBiome(World world, int x0, int y0, int z0, int x1, int y1, int z1, String biome) {
        var s = Native.scratch();
        MemorySegment box = s.alloc(Abi.BlockBox.SIZE);
        box.set(JAVA_INT, Abi.BlockBox.min + Abi.BlockPos.x, Math.min(x0, x1));
        box.set(JAVA_INT, Abi.BlockBox.min + Abi.BlockPos.y, Math.min(y0, y1));
        box.set(JAVA_INT, Abi.BlockBox.min + Abi.BlockPos.z, Math.min(z0, z1));
        box.set(JAVA_INT, Abi.BlockBox.max + Abi.BlockPos.x, Math.max(x0, x1));
        box.set(JAVA_INT, Abi.BlockBox.max + Abi.BlockPos.y, Math.max(y0, y1));
        box.set(JAVA_INT, Abi.BlockBox.max + Abi.BlockPos.z, Math.max(z0, z1));
        return Host.biome_set(Ctx.current, id(world), box, s.str(biome)) == Abi.status.OK;
    }

    /** An entity's data as SNBT ({@code /data get entity}), null when it is gone. */
    public static String entityData(Entity entity) {
        var s = Native.scratch();
        MemorySegment sink = Native.sink(s);
        if (Host.entity_nbt(Ctx.current, id(entity.getWorld()), s.uuid(entity.getUniqueId()), sink) != Abi.status.OK) {
            return null;
        }
        return Native.sunk();
    }

    /** Spawns an entity as {@code /summon} does (type, position, SNBT data or empty); null when it failed. */
    public static Entity spawn(World world, String type, double x, double y, double z, String snbt) {
        var s = Native.scratch();
        MemorySegment at = s.alloc(Abi.Vec3.SIZE);
        at.set(JAVA_DOUBLE, Abi.Vec3.x, x);
        at.set(JAVA_DOUBLE, Abi.Vec3.y, y);
        at.set(JAVA_DOUBLE, Abi.Vec3.z, z);
        MemorySegment out = s.alloc(Abi.Uuid.SIZE);
        if (Host.entity_spawn(Ctx.current, id(world), s.str(type), at, s.str(snbt == null ? "" : snbt), out)
                != Abi.status.OK) {
            return null;
        }
        UUID uuid = Native.uuid(out);
        return VEntity.create((VWorld) world, uuid);
    }
}
