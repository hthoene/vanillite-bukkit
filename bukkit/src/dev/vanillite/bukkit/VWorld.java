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
        List<Entity> result = new ArrayList<>();
        inBox(location.getX() - x, location.getY() - y, location.getZ() - z,
                location.getX() + x, location.getY() + y, location.getZ() + z, result::add);
        return result;
    }

    @Override
    public Collection<Entity> getNearbyEntities(Location location, double x, double y, double z,
            java.util.function.Predicate<? super Entity> filter) {
        List<Entity> result = new ArrayList<>();
        for (Entity e : getNearbyEntities(location, x, y, z)) {
            if (filter == null || filter.test(e)) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public Collection<Entity> getNearbyEntities(org.bukkit.util.BoundingBox box) {
        return getNearbyEntities(box, null);
    }

    @Override
    public Collection<Entity> getNearbyEntities(org.bukkit.util.BoundingBox box,
            java.util.function.Predicate<? super Entity> filter) {
        List<Entity> result = new ArrayList<>();
        inBox(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ(), e -> {
            if (filter == null || filter.test(e)) {
                result.add(e);
            }
        });
        return result;
    }

    @Override
    public long getFullTime() {
        var s = Native.scratch();
        MemorySegment out = s.alloc(8);
        Ctx.check(Host.world_time(Native.plugin, id, out), "World.getFullTime");
        return out.get(JAVA_LONG, 0);
    }

    @Override
    public long getTime() {
        return Math.floorMod(getFullTime(), 24000L);
    }

    @Override
    public void setFullTime(long time) {
        Ctx.check(Host.world_set_time(Native.plugin, id, time), "World.setFullTime");
    }

    @Override
    public void setTime(long time) {
        long full = getFullTime();
        setFullTime(full - Math.floorMod(full, 24000L) + time);
    }

    /** Half the side of the boxes {@link #getEntities} asks for (the host allows 2048 blocks a side). */
    private static final double REACH = 1024;

    /**
     * All entities of the world. Entities exist only in loaded chunks, which lie around players
     * (and the spawn): one box query per area around them, without duplicates.
     */
    @Override
    public List<Entity> getEntities() {
        List<double[]> centers = new ArrayList<>();
        for (Player p : getPlayers()) {
            Location at = p.getLocation();
            centers.add(new double[] {at.getX(), at.getZ()});
        }
        Location spawn = getSpawnLocation();
        centers.add(new double[] {spawn.getX(), spawn.getZ()});
        List<double[]> asked = new ArrayList<>();
        java.util.Map<UUID, Entity> found = new java.util.LinkedHashMap<>();
        for (double[] c : centers) {
            boolean covered = false;
            for (double[] a : asked) {
                if (Math.abs(a[0] - c[0]) <= REACH / 2 && Math.abs(a[1] - c[1]) <= REACH / 2) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }
            asked.add(c);
            try {
                inBox(c[0] - REACH, getMinHeight(), c[1] - REACH, c[0] + REACH, getMaxHeight(), c[1] + REACH,
                        e -> found.putIfAbsent(e.getUniqueId(), e));
            } catch (IllegalStateException e) {
                // Another world's task owns it (e.g. /gc listing every world): its entities
                // cannot be read from here, as Folia; the listing leaves them out.
                return List.of();
            }
        }
        return new ArrayList<>(found.values());
    }

    @Override
    public List<org.bukkit.entity.LivingEntity> getLivingEntities() {
        List<org.bukkit.entity.LivingEntity> out = new ArrayList<>();
        for (Entity e : getEntities()) {
            if (e instanceof org.bukkit.entity.LivingEntity l) {
                out.add(l);
            }
        }
        return out;
    }

    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public <T extends Entity> Collection<T> getEntitiesByClass(Class<T>... classes) {
        return (Collection<T>) getEntitiesByClasses(classes);
    }

    @Override
    public <T extends Entity> Collection<T> getEntitiesByClass(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Entity e : getEntities()) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    @Override
    public Collection<Entity> getEntitiesByClasses(Class<?>... classes) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : getEntities()) {
            for (Class<?> c : classes) {
                if (c.isInstance(e)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    /** The entities whose bounding box meets a box (each side at most 2048 blocks). */
    private void inBox(double x0, double y0, double z0, double x1, double y1, double z1,
            java.util.function.Consumer<Entity> sink) {
        var s = Native.scratch();
        MemorySegment min = s.alloc(Abi.Vec3.SIZE);
        min.set(JAVA_DOUBLE, Abi.Vec3.x, x0);
        min.set(JAVA_DOUBLE, Abi.Vec3.y, y0);
        min.set(JAVA_DOUBLE, Abi.Vec3.z, z0);
        MemorySegment max = s.alloc(Abi.Vec3.SIZE);
        max.set(JAVA_DOUBLE, Abi.Vec3.x, x1);
        max.set(JAVA_DOUBLE, Abi.Vec3.y, y1);
        max.set(JAVA_DOUBLE, Abi.Vec3.z, z1);
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
            for (long i = 0; i < n; i++) {
                UUID uuid = Native.uuid(out.asSlice(i * Abi.EntityInfo.SIZE + Abi.EntityInfo.uuid, Abi.Uuid.SIZE));
                VPlayer player = Layer.server.players.get(uuid);
                sink.accept(player != null ? player : VEntity.create(this, uuid));
            }
            return;
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
