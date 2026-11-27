// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** An entity of a world as a Bukkit {@link Entity}: a wrapper around its UUID. */
public abstract class VEntity implements Entity {
    private static final MethodHandle NEW = Impl.constructor(VEntity.class, VWorld.class, UUID.class);

    protected VWorld world;
    protected final UUID uuid;
    // The position of the last successful getLocation (one call into the layer at a time).
    private World lastWorld;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;
    private long lastEpoch = -1;

    protected VEntity(VWorld world, UUID uuid) {
        this.world = world;
        this.uuid = uuid;
    }

    static Entity create(VWorld world, UUID uuid) {
        try {
            return (VEntity) NEW.invokeExact(world, uuid);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    protected int worldId() {
        return world.id;
    }

    @Override
    public UUID getUniqueId() {
        return uuid;
    }

    @Override
    public int getEntityId() {
        return uuid.hashCode();
    }

    // ----- Metadata (Metadatable): kept per entity UUID for the server's lifetime, as Bukkit's
    // entity metadata store does.

    private static final java.util.Map<UUID, java.util.Map<String, List<org.bukkit.metadata.MetadataValue>>> METADATA =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void setMetadata(String key, org.bukkit.metadata.MetadataValue value) {
        var owner = value.getOwningPlugin();
        if (owner == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        var values = METADATA.computeIfAbsent(uuid, u -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        values.removeIf(v -> v.getOwningPlugin() == owner);
        values.add(value);
    }

    @Override
    public List<org.bukkit.metadata.MetadataValue> getMetadata(String key) {
        var map = METADATA.get(uuid);
        var values = map == null ? null : map.get(key);
        return values == null ? List.of() : List.copyOf(values);
    }

    @Override
    public boolean hasMetadata(String key) {
        var map = METADATA.get(uuid);
        var values = map == null ? null : map.get(key);
        return values != null && !values.isEmpty();
    }

    @Override
    public void removeMetadata(String key, org.bukkit.plugin.Plugin owner) {
        var map = METADATA.get(uuid);
        var values = map == null ? null : map.get(key);
        if (values != null) {
            values.removeIf(v -> v.getOwningPlugin() == owner);
        }
    }

    @Override
    public org.bukkit.Server getServer() {
        return Layer.server;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public Location getLocation() {
        return getLocation(new Location(world, 0, 0, 0));
    }

    @Override
    public Location getLocation(Location into) {
        if (into == null) {
            return null;
        }
        if (lastEpoch == Native.epoch && lastWorld != null) {
            // Nothing could have moved it since the last read (same read epoch).
            into.setWorld(lastWorld);
            into.set(lastX, lastY, lastZ);
            into.setYaw(lastYaw);
            into.setPitch(lastPitch);
            return into;
        }
        int w0 = worldId();
        var s = Native.scratch();
        MemorySegment out = s.alloc(Abi.Location.SIZE);
        int status = Host.entity_location(Ctx.current, w0, s.uuid(uuid), out);
        if (status != Abi.status.OK) {
            // Outside its world's tick (e.g. PlayerQuitEvent, which the server fires after the
            // player left its world): the last position read.
            if (lastWorld == null) {
                Ctx.check(status, "Entity.getLocation");
            }
            into.setWorld(lastWorld);
            into.set(lastX, lastY, lastZ);
            into.setYaw(lastYaw);
            into.setPitch(lastPitch);
            return into;
        }
        VWorld w = Layer.server.world(out.get(JAVA_INT, Abi.Location.world));
        lastWorld = w != null ? w : world;
        lastX = out.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.x);
        lastY = out.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.y);
        lastZ = out.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.z);
        lastYaw = out.get(JAVA_FLOAT, Abi.Location.yaw);
        lastPitch = out.get(JAVA_FLOAT, Abi.Location.pitch);
        lastEpoch = Native.epoch;
        into.setWorld(lastWorld);
        into.set(lastX, lastY, lastZ);
        into.setYaw(lastYaw);
        into.setPitch(lastPitch);
        return into;
    }

    @Override
    public EntityType getType() {
        int w0 = worldId();
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(128);
        Ctx.check(Host.entity_type(Ctx.current, w0, s.uuid(uuid), buf), "Entity.getType");
        NamespacedKey key = NamespacedKey.fromString(Native.string(buf, true));
        for (EntityType t : EntityType.values()) {
            if (t != EntityType.UNKNOWN && key != null && key.getKey().equals(t.name().toLowerCase(java.util.Locale.ROOT))) {
                return t;
            }
        }
        return EntityType.UNKNOWN;
    }

    @Override
    public List<Entity> getPassengers() {
        var s = Native.scratch();
        int cap = 16;
        MemorySegment out = s.alloc(Abi.Uuid.SIZE * cap);
        MemorySegment count = s.alloc(8);
        Ctx.check(Host.entity_passengers(Ctx.current, worldId(), s.uuid(uuid), out, cap, count), "Entity.getPassengers");
        long n = Math.min(count.get(JAVA_LONG, 0), cap);
        List<Entity> result = new ArrayList<>((int) n);
        for (long i = 0; i < n; i++) {
            UUID id = Native.uuid(out.asSlice(i * Abi.Uuid.SIZE, Abi.Uuid.SIZE));
            VPlayer player = Layer.server.players.get(id);
            result.add(player != null ? player : create(world, id));
        }
        return result;
    }

    @Override
    public boolean isEmpty() {
        return getPassengers().isEmpty();
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VEntity e && e.uuid.equals(uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSuperclass().getSimpleName() + "{uuid=" + uuid + "}";
    }
}
