// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol.wrappers;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/** A block position. */
public class BlockPosition {
    public static BlockPosition ORIGIN = new BlockPosition(0, 0, 0);
    protected final int x;
    protected final int y;
    protected final int z;

    public BlockPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public BlockPosition(Vector vector) {
        this(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ());
    }

    public Vector toVector() {
        return new Vector(x, y, z);
    }

    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public BlockPosition add(BlockPosition other) {
        return new BlockPosition(x + other.x, y + other.y, z + other.z);
    }

    public BlockPosition subtract(BlockPosition other) {
        return new BlockPosition(x - other.x, y - other.y, z - other.z);
    }

    public BlockPosition multiply(int factor) {
        return new BlockPosition(x * factor, y * factor, z * factor);
    }

    public BlockPosition divide(int divisor) {
        return new BlockPosition(x / divisor, y / divisor, z / divisor);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockPosition b && b.x == x && b.y == y && b.z == z;
    }

    @Override
    public int hashCode() {
        return (x * 31 + y) * 31 + z;
    }

    @Override
    public String toString() {
        return "BlockPosition [x=" + x + ", y=" + y + ", z=" + z + "]";
    }
}
