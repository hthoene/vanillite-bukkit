// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A block position of a world as a Bukkit {@link Block}; reads and writes go to the host. */
public abstract class VBlock implements Block {
    private static final MethodHandle NEW =
            Impl.constructor(VBlock.class, VWorld.class, int.class, int.class, int.class);

    final VWorld world;
    final int x;
    final int y;
    final int z;

    protected VBlock(VWorld world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    static Block create(VWorld world, int x, int y, int z) {
        try {
            return (VBlock) NEW.invokeExact(world, x, y, z);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    private MemorySegment pos(Native.Scratch s) {
        MemorySegment pos = s.alloc(Abi.BlockPos.SIZE);
        pos.set(JAVA_INT, Abi.BlockPos.x, x);
        pos.set(JAVA_INT, Abi.BlockPos.y, y);
        pos.set(JAVA_INT, Abi.BlockPos.z, z);
        return pos;
    }

    /** The block state id here. */
    public int stateId() {
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        int status = Host.block_get_id(Ctx.current, world.id, pos(s), out);
        if (status == Abi.status.NOT_FOUND) {
            return 0;
        }
        Ctx.check(status, "Block.getType");
        return out.get(JAVA_INT, 0);
    }

    @Override
    public Material getType() {
        return Materials.of(stateId());
    }

    @Override
    public void setType(Material type) {
        setType(type, true);
    }

    @Override
    public void setType(Material type, boolean applyPhysics) {
        int state = Materials.state(type);
        Ctx.check(Host.block_set_id(Ctx.current, world.id, pos(Native.scratch()), state,
                applyPhysics ? Abi.set_mode.UPDATE : Abi.set_mode.NO_PHYSICS), "Block.setType");
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
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
    public Location getLocation() {
        return new Location(world, x, y, z);
    }

    @Override
    public Block getRelative(int dx, int dy, int dz) {
        return create(world, x + dx, y + dy, z + dz);
    }

    @Override
    public Block getRelative(BlockFace face) {
        return getRelative(face.getModX(), face.getModY(), face.getModZ());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VBlock b && b.world == world && b.x == x && b.y == y && b.z == z;
    }

    @Override
    public int hashCode() {
        return ((y * 31 + z) * 31 + x) * 31 + world.id;
    }

    @Override
    public String toString() {
        return "VanilliteBlock{world=" + world.getName() + ",x=" + x + ",y=" + y + ",z=" + z + "}";
    }
}
