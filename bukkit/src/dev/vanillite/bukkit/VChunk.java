// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A chunk position of a world as a Bukkit {@link Chunk}; it holds no chunk data. */
public abstract class VChunk implements Chunk {
    private static final MethodHandle NEW = Impl.constructor(VChunk.class, VWorld.class, int.class, int.class);

    private final VWorld world;
    private final int x;
    private final int z;

    protected VChunk(VWorld world, int x, int z) {
        this.world = world;
        this.x = x;
        this.z = z;
    }

    static Chunk create(VWorld world, int x, int z) {
        try {
            return (VChunk) NEW.invokeExact(world, x, z);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** The load state ({@code Abi.chunk_state}) of a chunk of a world. */
    static int state(VWorld world, int x, int z) {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        Ctx.check(Host.chunk_state(Ctx.current, world.id, x, z, out), "Chunk state");
        return out.get(JAVA_INT, 0);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getZ() {
        return z;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public Block getBlock(int bx, int y, int bz) {
        return world.getBlockAt((x << 4) | (bx & 15), y, (z << 4) | (bz & 15));
    }

    @Override
    public boolean isLoaded() {
        return state(world, x, z) != Abi.chunk_state.UNLOADED;
    }

    @Override
    public boolean isEntitiesLoaded() {
        return state(world, x, z) == Abi.chunk_state.ENTITY_TICKING;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VChunk c && c.world.equals(world) && c.x == x && c.z == z;
    }

    @Override
    public int hashCode() {
        return (world.id * 31 + x) * 31 + z;
    }

    @Override
    public String toString() {
        return "VanilliteChunk{world=" + world.getName() + ", x=" + x + ", z=" + z + "}";
    }
}
