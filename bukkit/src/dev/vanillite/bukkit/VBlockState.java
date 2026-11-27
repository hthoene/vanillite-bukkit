// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.lang.invoke.MethodHandle;
import dev.vanillite.jvm.Native;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

/** A block's state at one moment (a snapshot of its state id), as Bukkit's {@link BlockState}. */
public abstract class VBlockState implements BlockState {
    private static final MethodHandle NEW =
            Impl.constructor(VBlockState.class, VWorld.class, int.class, int.class, int.class, int.class);

    final VWorld world;
    final int x;
    final int y;
    final int z;
    int state;

    protected VBlockState(VWorld world, int x, int y, int z, int state) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.state = state;
    }

    static BlockState create(VWorld world, int x, int y, int z, int state) {
        try {
            return (VBlockState) NEW.invokeExact(world, x, y, z, state);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    @Override
    public Material getType() {
        return Materials.of(state);
    }

    @Override
    public void setType(Material type) {
        state = Materials.state(type);
    }

    @Override
    public org.bukkit.block.data.BlockData getBlockData() {
        return VBlockData.of(state);
    }

    @Override
    public void setBlockData(org.bukkit.block.data.BlockData data) {
        state = ((VBlockData) data).state;
    }

    @Override
    public Block getBlock() {
        return world.getBlockAt(x, y, z);
    }

    @Override
    public World getWorld() {
        return world;
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
    public Location getLocation() {
        return new Location(world, x, y, z);
    }

    @Override
    public Location getLocation(Location loc) {
        if (loc != null) {
            loc.setWorld(world);
            loc.set(x, y, z);
            loc.setYaw(0);
            loc.setPitch(0);
        }
        return loc;
    }

    @Override
    public boolean isPlaced() {
        return true;
    }

    @Override
    public boolean update() {
        return update(false, true);
    }

    @Override
    public boolean update(boolean force) {
        return update(force, true);
    }

    @Override
    public boolean update(boolean force, boolean applyPhysics) {
        if (!force && NativeAccess.state(world, x, y, z) != state
                && Materials.of(NativeAccess.state(world, x, y, z)) != getType()) {
            return false;
        }
        return NativeAccess.setState(world, x, y, z, state, applyPhysics);
    }
}
