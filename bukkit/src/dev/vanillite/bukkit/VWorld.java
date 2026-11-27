// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** A Vanillite world (dimension) as a Bukkit {@link World}: a thin wrapper around its id. */
public abstract class VWorld implements World {
    static final MethodHandle NEW = Impl.constructor(VWorld.class, int.class, String.class);

    final int id;
    private final String key;
    private final String name;
    private final UUID uid;

    protected VWorld(int id, String key) {
        this.id = id;
        this.key = key;
        this.name = switch (key) {
            case "minecraft:overworld" -> "world";
            case "minecraft:the_nether" -> "world_nether";
            case "minecraft:the_end" -> "world_the_end";
            default -> key.substring(key.indexOf(':') + 1).replace('/', '_');
        };
        this.uid = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public int id() {
        return id;
    }

    /** The dimension key, e.g. {@code minecraft:overworld}. */
    public String dimensionKey() {
        return key;
    }

    @Override
    public Location getSpawnLocation() {
        return new Location(this, 0.5, 64, 0.5);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public UUID getUID() {
        return uid;
    }

    @Override
    public Environment getEnvironment() {
        return switch (key) {
            case "minecraft:the_nether" -> Environment.NETHER;
            case "minecraft:the_end" -> Environment.THE_END;
            default -> Environment.NORMAL;
        };
    }

    @Override
    public org.bukkit.Chunk[] getLoadedChunks() {
        // The server does not list its loaded chunks to plugins; callers ask per chunk.
        return new org.bukkit.Chunk[0];
    }

    @Override
    public org.bukkit.Chunk getChunkAt(int x, int z) {
        return VChunk.create(this, x, z);
    }

    @Override
    public org.bukkit.Chunk getChunkAt(int x, int z, boolean generate) {
        return VChunk.create(this, x, z);
    }

    @Override
    public org.bukkit.Chunk getChunkAt(Block block) {
        return VChunk.create(this, block.getX() >> 4, block.getZ() >> 4);
    }

    @Override
    public boolean isChunkLoaded(int x, int z) {
        return VChunk.state(this, x, z) != Abi.chunk_state.UNLOADED;
    }

    @Override
    public boolean isChunkLoaded(org.bukkit.Chunk chunk) {
        return chunk.getWorld() == this && isChunkLoaded(chunk.getX(), chunk.getZ());
    }

    @Override
    public void getChunkAtAsync(int x, int z, boolean generate, boolean urgent,
            java.util.function.Consumer<? super org.bukkit.Chunk> callback) {
        // The server loads (and generates) the chunks a teleport needs itself; the handle is
        // ready at once.
        callback.accept(getChunkAt(x, z));
    }

    @Override
    public org.bukkit.WorldBorder getWorldBorder() {
        return VWorldBorder.create(this);
    }

    @Override
    public int getMinHeight() {
        return getEnvironment() == Environment.NORMAL ? -64 : 0;
    }

    @Override
    public int getMaxHeight() {
        return getEnvironment() == Environment.NORMAL ? 320 : 256;
    }

    @Override
    public int getLogicalHeight() {
        return getEnvironment() == Environment.NETHER ? 128 : getMaxHeight() - getMinHeight();
    }

    @Override
    public Block getBlockAt(int x, int y, int z) {
        return VBlock.create(this, x, y, z);
    }

    @Override
    public Block getBlockAt(Location location) {
        return getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public List<Player> getPlayers() {
        List<Player> out = new ArrayList<>();
        for (VPlayer p : Layer.server.players.values()) {
            if (p.worldId() == id) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public Collection<Entity> getNearbyEntities(Location location, double x, double y, double z) {
        var s = Native.scratch();
        MemorySegment min = s.alloc(Abi.Vec3.SIZE);
        min.set(JAVA_DOUBLE, Abi.Vec3.x, location.getX() - x);
        min.set(JAVA_DOUBLE, Abi.Vec3.y, location.getY() - y);
        min.set(JAVA_DOUBLE, Abi.Vec3.z, location.getZ() - z);
        MemorySegment max = s.alloc(Abi.Vec3.SIZE);
        max.set(JAVA_DOUBLE, Abi.Vec3.x, location.getX() + x);
        max.set(JAVA_DOUBLE, Abi.Vec3.y, location.getY() + y);
        max.set(JAVA_DOUBLE, Abi.Vec3.z, location.getZ() + z);
        MemorySegment empty = s.str("");
        MemorySegment count = s.alloc(8);
        int cap = 64;
        while (true) {
            MemorySegment out = s.alloc(Abi.EntityInfo.SIZE * cap);
            Ctx.check(Host.entities_in_box(Ctx.current, id, min, max, empty, out, cap, count), "getNearbyEntities");
            long n = count.get(JAVA_LONG, 0);
            if (n > cap) {
                cap = (int) Math.min(n, 1 << 20);
                continue;
            }
            List<Entity> result = new ArrayList<>((int) n);
            for (long i = 0; i < n; i++) {
                UUID uuid = Native.uuid(out.asSlice(i * Abi.EntityInfo.SIZE + Abi.EntityInfo.uuid, Abi.Uuid.SIZE));
                VPlayer player = Layer.server.players.get(uuid);
                result.add(player != null ? player : VEntity.create(this, uuid));
            }
            return result;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VWorld w && w.id == id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return "VanilliteWorld{name=" + name + "}";
    }

    static int idOf(World w) {
        return ((VWorld) w).id;
    }
}
