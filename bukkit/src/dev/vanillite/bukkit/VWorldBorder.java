// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A world's border, read from the server on every call (changing it is not there yet). */
public abstract class VWorldBorder implements WorldBorder {
    private static final MethodHandle NEW = Impl.constructor(VWorldBorder.class, VWorld.class);

    private final VWorld world;

    protected VWorldBorder(VWorld world) {
        this.world = world;
    }

    static WorldBorder create(VWorld world) {
        try {
            return (VWorldBorder) NEW.invokeExact(world);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    private MemorySegment read() {
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.BorderInfo.SIZE);
        info.set(JAVA_INT, Abi.BorderInfo.size, (int) Abi.BorderInfo.SIZE);
        Ctx.check(Host.border_get(Ctx.current, world.id, info), "WorldBorder");
        return info;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public double getSize() {
        return read().get(JAVA_DOUBLE, Abi.BorderInfo.diameter);
    }

    @Override
    public Location getCenter() {
        MemorySegment info = read();
        return new Location(world, info.get(JAVA_DOUBLE, Abi.BorderInfo.center_x), 0,
                info.get(JAVA_DOUBLE, Abi.BorderInfo.center_z));
    }

    @Override
    public double getDamageBuffer() {
        return read().get(JAVA_DOUBLE, Abi.BorderInfo.safe_zone);
    }

    @Override
    public double getDamageAmount() {
        return read().get(JAVA_DOUBLE, Abi.BorderInfo.damage_per_block);
    }

    @Override
    public int getWarningDistance() {
        return read().get(JAVA_INT, Abi.BorderInfo.warning_blocks);
    }

    @Override
    public int getWarningTimeTicks() {
        return read().get(JAVA_INT, Abi.BorderInfo.warning_time) * 20;
    }

    @Override
    public int getWarningTime() {
        return read().get(JAVA_INT, Abi.BorderInfo.warning_time);
    }

    @Override
    public boolean isInside(Location location) {
        MemorySegment info = read();
        double half = info.get(JAVA_DOUBLE, Abi.BorderInfo.diameter) / 2;
        double cx = info.get(JAVA_DOUBLE, Abi.BorderInfo.center_x);
        double cz = info.get(JAVA_DOUBLE, Abi.BorderInfo.center_z);
        // WorldBorder.isWithinBounds: min inclusive, max exclusive.
        return location.getWorld() == world
                && location.getX() >= cx - half && location.getX() < cx + half
                && location.getZ() >= cz - half && location.getZ() < cz + half;
    }

    @Override
    public double getMaxSize() {
        return 5.9999968E7;
    }

    @Override
    public double getMaxCenterCoordinate() {
        return 2.9999984E7;
    }
}
