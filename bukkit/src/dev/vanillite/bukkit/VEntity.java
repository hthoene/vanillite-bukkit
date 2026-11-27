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

    private static final java.util.Map<EntityType, MethodHandle> TYPED = new java.util.concurrent.ConcurrentHashMap<>();
    /** Its type when known without asking the server (an entity that is not in its world yet). */
    private EntityType knownType;

    /**
     * An entity whose type is known: an instance of the type's API interface (a zombie is a
     * {@code Zombie}), so plugins' {@code instanceof} checks work; what the layer does not
     * implement of that interface throws.
     */
    static Entity create(VWorld world, UUID uuid, EntityType type) {
        if (type == null || type == EntityType.UNKNOWN || type == EntityType.PLAYER || type.getEntityClass() == null
                || !type.getEntityClass().isInterface()) {
            return create(world, uuid);
        }
        MethodHandle h = TYPED.computeIfAbsent(type, t -> {
            try {
                Class<? extends VEntity> c = Impl.of(VEntity.class, t.getEntityClass());
                return java.lang.invoke.MethodHandles.privateLookupIn(c, java.lang.invoke.MethodHandles.lookup())
                        .findConstructor(c, java.lang.invoke.MethodType.methodType(void.class, VWorld.class, UUID.class))
                        .asType(java.lang.invoke.MethodType.methodType(VEntity.class, VWorld.class, UUID.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            VEntity e = (VEntity) h.invokeExact(world, uuid);
            e.knownType = type;
            return e;
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    /** An entity about to be added at a place: reads before it exists answer from here. */
    static Entity pending(VWorld world, UUID uuid, EntityType type, Location at) {
        VEntity e = (VEntity) create(world, uuid, type);
        e.knownType = type;
        e.lastWorld = at.getWorld();
        e.lastX = at.getX();
        e.lastY = at.getY();
        e.lastZ = at.getZ();
        e.lastYaw = at.getYaw();
        e.lastPitch = at.getPitch();
        return e;
    }

    /** Remembers where the entity is (from an event): reads the server cannot answer use it. */
    public void cacheLocation(Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        lastWorld = at.getWorld();
        lastX = at.getX();
        lastY = at.getY();
        lastZ = at.getZ();
        lastYaw = at.getYaw();
        lastPitch = at.getPitch();
    }

    /** The API type of an entity type key such as {@code minecraft:zombie}. */
    static EntityType typeOf(String key) {
        NamespacedKey k = NamespacedKey.fromString(key);
        if (k == null) {
            return EntityType.UNKNOWN;
        }
        for (EntityType t : EntityType.values()) {
            if (t != EntityType.UNKNOWN && k.getKey().equals(t.name().toLowerCase(java.util.Locale.ROOT))) {
                return t;
            }
        }
        return EntityType.UNKNOWN;
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
        if (knownType != null) {
            return knownType;
        }
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

    // ----- What the API interfaces of entity kinds ask often (Tameable, Projectile, Ageable,
    // Damageable). Plain public methods: a generated entity class whose interface declares them
    // uses these instead of throwing.

    private java.util.Map<String, Object> data() {
        if (Layer.server.players.containsKey(uuid)) {
            return java.util.Map.of();
        }
        String snbt = NativeAccess.entityData(this);
        return Snbt.compound(snbt);
    }

    private static UUID uuidOf(Object o) {
        if (o instanceof int[] a && a.length == 4) {
            return new UUID((long) a[0] << 32 | (a[1] & 0xffffffffL), (long) a[2] << 32 | (a[3] & 0xffffffffL));
        }
        return null;
    }

    private double prop(int prop, double fallback) {
        var s = Native.scratch();
        MemorySegment out = s.alloc(8);
        if (Host.entity_get(Ctx.current, worldId(), s.uuid(uuid), prop, out) != Abi.status.OK) {
            return fallback;
        }
        return out.get(JAVA_DOUBLE, 0);
    }

    public boolean isTamed() {
        return getOwnerUniqueId() != null;
    }

    public UUID getOwnerUniqueId() {
        return uuidOf(data().get("Owner"));
    }

    public org.bukkit.entity.AnimalTamer getOwner() {
        UUID owner = getOwnerUniqueId();
        if (owner == null) {
            return null;
        }
        VPlayer p = Layer.server.players.get(owner);
        return p != null ? p : org.bukkit.Bukkit.getOfflinePlayer(owner);
    }

    public org.bukkit.projectiles.ProjectileSource getShooter() {
        UUID owner = uuidOf(data().get("Owner"));
        if (owner == null) {
            return null;
        }
        Entity e = WorldEvents.entity(world, owner, null);
        return e instanceof org.bukkit.projectiles.ProjectileSource ps ? ps : null;
    }

    public double getHealth() {
        return prop(Abi.entity_prop.HEALTH, 0);
    }

    public int getAge() {
        return (int) prop(Abi.entity_prop.AGE, 0);
    }

    public boolean isAdult() {
        return getAge() >= 0;
    }

    public boolean isDead() {
        return !isValid();
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
