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
        if (networkId == 0) {
            // Abi.entity_prop.NETWORK_ID (ABI 1.12); the id stays the entity's for its life.
            double id = prop(22, 0);
            if (id > 0) {
                networkId = (int) id;
            } else {
                return uuid.hashCode();
            }
        }
        return networkId;
    }

    /** The network id once read; 0 before. */
    private volatile int networkId;

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

    @Override
    public boolean isInWater() {
        org.bukkit.block.Block b = getLocation().getBlock();
        org.bukkit.Material type = b.getType();
        return type == org.bukkit.Material.WATER || type == org.bukkit.Material.BUBBLE_COLUMN
                || b.getBlockData().getAsString().contains("waterlogged=true");
    }

    @Override
    public float getFallDistance() {
        return (float) prop(Abi.entity_prop.FALL_DISTANCE, 0);
    }

    @Override
    public void setFallDistance(float distance) {
        var s = Native.scratch();
        Ctx.check(Host.entity_set(Ctx.current, worldId(), s.uuid(uuid), Abi.entity_prop.FALL_DISTANCE,
                Math.max(0, distance)), "Entity.setFallDistance");
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

    // ----- Properties the server reports per entity (entity_prop)

    /**
     * Changes a property. One the server keeps no state for on this kind of entity (e.g. the
     * glowing tag of a mob) is left as it is instead of failing the plugin.
     */
    private void setProp(int prop, double value, String what) {
        var s = Native.scratch();
        int status = Host.entity_set(Ctx.current, worldId(), s.uuid(uuid), prop, value);
        if (status != Abi.status.UNSUPPORTED) {
            Ctx.check(status, what);
        }
    }

    @Override
    public double getX() {
        return getLocation().getX();
    }

    @Override
    public double getY() {
        return getLocation().getY();
    }

    @Override
    public double getZ() {
        return getLocation().getZ();
    }

    @Override
    public float getYaw() {
        return getLocation().getYaw();
    }

    @Override
    public float getPitch() {
        return getLocation().getPitch();
    }

    @Override
    public org.bukkit.block.BlockFace getFacing() {
        // Vanilla's Direction.fromYRot.
        return switch (Math.floorMod((int) Math.floor(getYaw() / 90.0 + 0.5), 4)) {
            case 0 -> org.bukkit.block.BlockFace.SOUTH;
            case 1 -> org.bukkit.block.BlockFace.WEST;
            case 2 -> org.bukkit.block.BlockFace.NORTH;
            default -> org.bukkit.block.BlockFace.EAST;
        };
    }

    @Override
    public org.bukkit.entity.Pose getPose() {
        return isSneaking() ? org.bukkit.entity.Pose.SNEAKING : org.bukkit.entity.Pose.STANDING;
    }

    @Override
    public boolean isOnGround() {
        return prop(Abi.entity_prop.ON_GROUND, 0) != 0;
    }

    @Override
    public boolean isSneaking() {
        return prop(Abi.entity_prop.SNEAKING, 0) != 0;
    }

    public boolean isSprinting() {
        return prop(Abi.entity_prop.SPRINTING, 0) != 0;
    }

    @Override
    public int getFireTicks() {
        return (int) prop(Abi.entity_prop.FIRE_TICKS, 0);
    }

    @Override
    public void setFireTicks(int ticks) {
        setProp(Abi.entity_prop.FIRE_TICKS, ticks, "Entity.setFireTicks");
    }

    @Override
    public int getMaxFireTicks() {
        // Vanilla's getFireImmuneTicks: 20 for players, 1 for every other entity.
        return this instanceof org.bukkit.entity.Player ? 20 : 1;
    }

    @Override
    public int getFreezeTicks() {
        return (int) prop(Abi.entity_prop.FREEZE_TICKS, 0);
    }

    @Override
    public void setFreezeTicks(int ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Freeze ticks cannot be less than 0");
        }
        setProp(Abi.entity_prop.FREEZE_TICKS, ticks, "Entity.setFreezeTicks");
    }

    @Override
    public int getMaxFreezeTicks() {
        return 140;
    }

    @Override
    public boolean isFrozen() {
        return getFreezeTicks() >= getMaxFreezeTicks();
    }

    @Override
    public boolean hasGravity() {
        return prop(Abi.entity_prop.NO_GRAVITY, 0) == 0;
    }

    @Override
    public void setGravity(boolean gravity) {
        setProp(Abi.entity_prop.NO_GRAVITY, gravity ? 0 : 1, "Entity.setGravity");
    }

    @Override
    public boolean isSilent() {
        return prop(Abi.entity_prop.SILENT, 0) != 0;
    }

    @Override
    public void setSilent(boolean silent) {
        setProp(Abi.entity_prop.SILENT, silent ? 1 : 0, "Entity.setSilent");
    }

    @Override
    public boolean isGlowing() {
        return prop(Abi.entity_prop.GLOWING, 0) != 0;
    }

    @Override
    public void setGlowing(boolean glowing) {
        setProp(Abi.entity_prop.GLOWING, glowing ? 1 : 0, "Entity.setGlowing");
    }

    @Override
    public void setInvulnerable(boolean invulnerable) {
        setProp(Abi.entity_prop.INVULNERABLE, invulnerable ? 1 : 0, "Entity.setInvulnerable");
    }

    @Override
    public boolean isPersistent() {
        return prop(Abi.entity_prop.PERSISTENT, 0) != 0;
    }

    @Override
    public void setPersistent(boolean persistent) {
        setProp(Abi.entity_prop.PERSISTENT, persistent ? 1 : 0, "Entity.setPersistent");
    }

    @Override
    public boolean isCustomNameVisible() {
        return prop(Abi.entity_prop.CUSTOM_NAME_VISIBLE, 0) != 0;
    }

    @Override
    public void setCustomNameVisible(boolean flag) {
        setProp(Abi.entity_prop.CUSTOM_NAME_VISIBLE, flag ? 1 : 0, "Entity.setCustomNameVisible");
    }

    @Override
    public boolean isInvisible() {
        return prop(Abi.entity_prop.INVISIBLE, 0) != 0;
    }

    @Override
    public void setInvisible(boolean invisible) {
        setProp(Abi.entity_prop.INVISIBLE, invisible ? 1 : 0, "Entity.setInvisible");
    }

    @Override
    public int getTicksLived() {
        return (int) prop(Abi.entity_prop.TICKS_LIVED, 0);
    }

    @Override
    public void setTicksLived(int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Age value (" + value + ") must be positive");
        }
        setProp(Abi.entity_prop.TICKS_LIVED, value, "Entity.setTicksLived");
    }

    @Override
    public int getPortalCooldown() {
        return (int) prop(Abi.entity_prop.PORTAL_COOLDOWN, 0);
    }

    @Override
    public void setPortalCooldown(int cooldown) {
        if (cooldown < 0) {
            throw new IllegalArgumentException("Portal cooldown must not be negative");
        }
        setProp(Abi.entity_prop.PORTAL_COOLDOWN, cooldown, "Entity.setPortalCooldown");
    }

    @Override
    public boolean isInLava() {
        return getLocation().getBlock().getType() == org.bukkit.Material.LAVA;
    }

    @Override
    public boolean isInWorld() {
        return true;
    }

    @Override
    public boolean isTicking() {
        return true;
    }

    // ----- Motion, teleports, removal

    /** The entity's data as NBT (also for players), empty when it cannot be read. */
    private java.util.Map<String, Object> nbt() {
        String snbt = NativeAccess.entityData(this);
        return snbt == null ? java.util.Map.of() : Snbt.compound(snbt);
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    @Override
    public org.bukkit.util.Vector getVelocity() {
        if (nbt().get("Motion") instanceof List<?> m && m.size() == 3) {
            return new org.bukkit.util.Vector(number(m.get(0)), number(m.get(1)), number(m.get(2)));
        }
        return new org.bukkit.util.Vector();
    }

    @Override
    public void setVelocity(org.bukkit.util.Vector velocity) {
        var s = Native.scratch();
        MemorySegment v = s.alloc(Abi.Vec3.SIZE);
        v.set(JAVA_DOUBLE, Abi.Vec3.x, velocity.getX());
        v.set(JAVA_DOUBLE, Abi.Vec3.y, velocity.getY());
        v.set(JAVA_DOUBLE, Abi.Vec3.z, velocity.getZ());
        Ctx.check(Host.entity_set_velocity(Ctx.current, worldId(), s.uuid(uuid), v), "Entity.setVelocity");
    }

    @Override
    public boolean teleport(Location location) {
        return teleport(location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override
    public boolean teleport(Location location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        if (location == null) {
            return false;
        }
        Location to = location.clone();
        if (to.getWorld() == null) {
            to.setWorld(getWorld());
        }
        var s = Native.scratch();
        MemorySegment loc = s.alloc(Abi.Location.SIZE);
        Events.writeLocation(loc, 0, to);
        return Host.entity_teleport(Ctx.current, worldId(), s.uuid(uuid), loc) == Abi.status.OK;
    }

    @Override
    public boolean teleport(Entity destination) {
        return teleport(destination.getLocation());
    }

    @Override
    public boolean teleport(Entity destination, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        return teleport(destination.getLocation(), cause);
    }

    @Override
    public void remove() {
        var s = Native.scratch();
        Ctx.check(Host.entity_remove(Ctx.current, worldId(), s.uuid(uuid)), "Entity.remove");
    }

    @Override
    public List<Entity> getNearbyEntities(double x, double y, double z) {
        List<Entity> result = new ArrayList<>(world.getNearbyEntities(getLocation(), x, y, z));
        result.remove(this);
        return result;
    }

    // ----- Vehicles

    @Override
    public Entity getVehicle() {
        var s = Native.scratch();
        MemorySegment out = s.alloc(Abi.Uuid.SIZE);
        int status = Host.entity_vehicle(Ctx.current, worldId(), s.uuid(uuid), out);
        if (status == Abi.status.NOT_FOUND) {
            return null;
        }
        Ctx.check(status, "Entity.getVehicle");
        return WorldEvents.entity(world, Native.uuid(out), null);
    }

    @Override
    public boolean isInsideVehicle() {
        return getVehicle() != null;
    }

    @Override
    public boolean leaveVehicle() {
        if (!isInsideVehicle()) {
            return false;
        }
        var s = Native.scratch();
        return Host.entity_dismount(Ctx.current, worldId(), s.uuid(uuid)) == Abi.status.OK;
    }

    @Override
    public boolean addPassenger(Entity passenger) {
        if (passenger == null || passenger.equals(this)) {
            return false;
        }
        var s = Native.scratch();
        MemorySegment rider = s.uuid(passenger.getUniqueId());
        return Host.entity_mount(Ctx.current, worldId(), rider, s.uuid(uuid)) == Abi.status.OK;
    }

    @Override
    public boolean removePassenger(Entity passenger) {
        if (passenger == null || !this.equals(passenger.getVehicle())) {
            return false;
        }
        return passenger.leaveVehicle();
    }

    @Override
    public boolean eject() {
        boolean any = false;
        for (Entity p : getPassengers()) {
            any |= p.leaveVehicle();
        }
        return any;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Entity getPassenger() {
        List<Entity> passengers = getPassengers();
        return passengers.isEmpty() ? null : passengers.get(0);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean setPassenger(Entity passenger) {
        eject();
        return passenger == null || addPassenger(passenger);
    }

    // ----- Names and messages

    @Override
    public String getName() {
        String custom = getCustomName();
        if (custom != null) {
            return custom;
        }
        StringBuilder b = new StringBuilder();
        for (String word : getType().name().toLowerCase(java.util.Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return b.toString();
    }

    @Override
    public net.kyori.adventure.text.Component name() {
        net.kyori.adventure.text.Component custom = customName();
        return custom != null ? custom : net.kyori.adventure.text.Component.text(getName());
    }

    @Override
    public net.kyori.adventure.text.Component customName() {
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(4096);
        int status = Host.entity_name(Ctx.current, worldId(), s.uuid(uuid), buf);
        if (status == Abi.status.NOT_FOUND) {
            return null;
        }
        Ctx.check(status, "Entity.customName");
        return net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(Native.string(buf, true));
    }

    @Override
    public void customName(net.kyori.adventure.text.Component name) {
        var s = Native.scratch();
        String json = name == null ? "" : Texts.json(name);
        Ctx.check(Host.entity_set_name(Ctx.current, worldId(), s.uuid(uuid), s.str(json), Abi.text_format.JSON),
                "Entity.customName");
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getCustomName() {
        net.kyori.adventure.text.Component c = customName();
        return c == null ? null : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setCustomName(String name) {
        customName(name == null || name.isEmpty() ? null : Texts.legacy(name));
    }

    /** Messages to entities that are not players go nowhere, as on Paper. */
    @Override
    public void sendMessage(String message) {}

    @Override
    public void sendMessage(String... messages) {}

    @Override
    public void sendMessage(UUID sender, String message) {}

    @Override
    public void sendMessage(UUID sender, String... messages) {}

    @Override
    public String getScoreboardEntryName() {
        return uuid.toString();
    }

    @Override
    public java.util.Set<String> getScoreboardTags() {
        java.util.Set<String> tags = new java.util.LinkedHashSet<>();
        if (nbt().get("Tags") instanceof List<?> l) {
            for (Object o : l) {
                tags.add(String.valueOf(o));
            }
        }
        return tags;
    }

    @Override
    public boolean addScoreboardTag(String tag) {
        java.util.Set<String> tags = getScoreboardTags();
        if (tags.size() >= 1024 || !tags.add(tag)) {
            return false;
        }
        return writeTags(tags);
    }

    @Override
    public boolean removeScoreboardTag(String tag) {
        java.util.Set<String> tags = getScoreboardTags();
        return tags.remove(tag) && writeTags(tags);
    }

    private boolean writeTags(java.util.Set<String> tags) {
        var s = Native.scratch();
        String snbt = "{Tags:" + Snbt.write(new ArrayList<>(tags)) + "}";
        return Host.entity_nbt_merge(Ctx.current, worldId(), s.uuid(uuid), s.str(snbt)) == Abi.status.OK;
    }

    // ----- Permissions: an entity that is not a player has the defaults, as on Paper.

    private org.bukkit.permissions.PermissibleBase entityPerm;

    private org.bukkit.permissions.PermissibleBase entityPerm() {
        if (entityPerm == null) {
            entityPerm = new org.bukkit.permissions.PermissibleBase(this);
        }
        return entityPerm;
    }

    @Override
    public boolean isOp() {
        return false;
    }

    @Override
    public void setOp(boolean value) {
        throw new UnsupportedOperationException("Cannot change op value for an entity");
    }

    @Override
    public boolean isPermissionSet(String name) {
        return entityPerm().isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(org.bukkit.permissions.Permission perm) {
        return entityPerm().isPermissionSet(perm);
    }

    @Override
    public boolean hasPermission(String name) {
        return entityPerm().hasPermission(name);
    }

    @Override
    public boolean hasPermission(org.bukkit.permissions.Permission perm) {
        return entityPerm().hasPermission(perm);
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) {
        return entityPerm().addAttachment(plugin, name, value);
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) {
        return entityPerm().addAttachment(plugin);
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value,
            int ticks) {
        return entityPerm().addAttachment(plugin, name, value, ticks);
    }

    @Override
    public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) {
        return entityPerm().addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) {
        entityPerm().removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        entityPerm().recalculatePermissions();
    }

    @Override
    public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() {
        return entityPerm().getEffectivePermissions();
    }

    // ----- What LivingEntity asks often (plain public methods, see above)

    public double getMaxHealth() {
        return prop(Abi.entity_prop.MAX_HEALTH, 20);
    }

    public double getAbsorptionAmount() {
        return prop(Abi.entity_prop.ABSORPTION, 0);
    }

    public void setAbsorptionAmount(double amount) {
        if (amount < 0 || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("amount < 0 or non-finite");
        }
        setProp(Abi.entity_prop.ABSORPTION, amount, "LivingEntity.setAbsorptionAmount");
    }

    public int getRemainingAir() {
        return (int) prop(Abi.entity_prop.AIR, 300);
    }

    public void setRemainingAir(int ticks) {
        setProp(Abi.entity_prop.AIR, ticks, "LivingEntity.setRemainingAir");
    }

    public int getMaximumAir() {
        return 300;
    }

    public boolean hasAI() {
        return prop(Abi.entity_prop.NO_AI, 0) == 0;
    }

    public void setAI(boolean ai) {
        setProp(Abi.entity_prop.NO_AI, ai ? 0 : 1, "LivingEntity.setAI");
    }

    public boolean isCollidable() {
        return true;
    }

    public boolean addPotionEffect(org.bukkit.potion.PotionEffect effect) {
        var s = Native.scratch();
        int flags = (effect.isAmbient() ? Abi.effect_flag.AMBIENT : 0)
                | (effect.hasParticles() ? 0 : Abi.effect_flag.HIDE_PARTICLES)
                | (effect.hasIcon() ? 0 : Abi.effect_flag.HIDE_ICON);
        int duration = effect.isInfinite() ? -1 : effect.getDuration();
        return Host.entity_effect_add(Ctx.current, worldId(), s.uuid(uuid), s.str(effect.getType().getKey().toString()),
                duration, effect.getAmplifier(), flags) == Abi.status.OK;
    }

    public boolean addPotionEffects(java.util.Collection<org.bukkit.potion.PotionEffect> effects) {
        boolean all = true;
        for (org.bukkit.potion.PotionEffect e : effects) {
            all &= addPotionEffect(e);
        }
        return all;
    }

    public void removePotionEffect(org.bukkit.potion.PotionEffectType type) {
        var s = Native.scratch();
        Host.entity_effect_remove(Ctx.current, worldId(), s.uuid(uuid), s.str(type.getKey().toString()));
    }

    public boolean clearActivePotionEffects() {
        if (getActivePotionEffects().isEmpty()) {
            return false;
        }
        var s = Native.scratch();
        return Host.entity_effect_remove(Ctx.current, worldId(), s.uuid(uuid), s.str("")) == Abi.status.OK;
    }

    public java.util.Collection<org.bukkit.potion.PotionEffect> getActivePotionEffects() {
        List<org.bukkit.potion.PotionEffect> out = new ArrayList<>();
        if (nbt().get("active_effects") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof java.util.Map<?, ?> m) {
                    NamespacedKey key = NamespacedKey.fromString(String.valueOf(m.get("id")));
                    org.bukkit.potion.PotionEffectType type = key == null ? null : org.bukkit.Registry.EFFECT.get(key);
                    if (type == null) {
                        continue;
                    }
                    int duration = (int) number(m.get("duration"));
                    out.add(new org.bukkit.potion.PotionEffect(type, duration, (int) number(m.get("amplifier")),
                            flag(m.get("ambient"), false), flag(m.get("show_particles"), true), flag(m.get("show_icon"), true)));
                }
            }
        }
        return out;
    }

    private static boolean flag(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o instanceof Number n ? n.intValue() != 0 : fallback;
    }

    public org.bukkit.potion.PotionEffect getPotionEffect(org.bukkit.potion.PotionEffectType type) {
        for (org.bukkit.potion.PotionEffect e : getActivePotionEffects()) {
            if (e.getType().equals(type)) {
                return e;
            }
        }
        return null;
    }

    public boolean hasPotionEffect(org.bukkit.potion.PotionEffectType type) {
        return getPotionEffect(type) != null;
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
