// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** An entity of a world as a Bukkit {@link Entity}: a wrapper around its UUID. */
public abstract class VEntity implements Entity {
    private static final MethodHandle NEW = Impl.constructor(VEntity.class, VWorld.class, UUID.class);

    VWorld world;
    final UUID uuid;

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

    int worldId() {
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
        int w0 = worldId();
        var s = Native.scratch();
        MemorySegment out = s.alloc(Abi.Location.SIZE);
        Ctx.check(Host.entity_location(Ctx.current, w0, s.uuid(uuid), out), "Entity.getLocation");
        VWorld w = Layer.server.world(out.get(JAVA_INT, Abi.Location.world));
        into.setWorld(w != null ? w : world);
        into.setX(out.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.x));
        into.setY(out.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.y));
        into.setZ(out.get(JAVA_DOUBLE, Abi.Location.pos + Abi.Vec3.z));
        into.setYaw(out.get(JAVA_FLOAT, Abi.Location.yaw));
        into.setPitch(out.get(JAVA_FLOAT, Abi.Location.pitch));
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
