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
        MemorySegment pos = s.alloc(Abi.Vec3.SIZE);
        pos.set(JAVA_DOUBLE, Abi.Vec3.x, location.getX());
        pos.set(JAVA_DOUBLE, Abi.Vec3.y, location.getY());
        pos.set(JAVA_DOUBLE, Abi.Vec3.z, location.getZ());
        int cap = 256;
        MemorySegment out = s.alloc(Abi.Uuid.SIZE * cap);
        MemorySegment count = s.alloc(8);
        double radius = Math.sqrt(x * x + y * y + z * z);
        Ctx.check(Host.entities_near(Ctx.current, id, pos, radius, s.str(""), out, cap, count), "getNearbyEntities");
        long n = Math.min(count.get(JAVA_LONG, 0), cap);
        List<Entity> result = new ArrayList<>((int) n);
        MemorySegment loc = s.alloc(Abi.Location.SIZE);
        for (long i = 0; i < n; i++) {
            MemorySegment rec = out.asSlice(i * Abi.Uuid.SIZE, Abi.Uuid.SIZE);
            UUID uuid = Native.uuid(rec);
            if (Host.entity_location(Ctx.current, id, rec, loc) != Abi.status.OK) {
                continue;
            }
            double dx = loc.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.x) - location.getX();
            double dy = loc.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.y) - location.getY();
            double dz = loc.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.z) - location.getZ();
            if (Math.abs(dx) <= x && Math.abs(dy) <= y && Math.abs(dz) <= z) {
                VPlayer player = Layer.server.players.get(uuid);
                result.add(player != null ? player : VEntity.create(this, uuid));
            }
        }
        return result;
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
