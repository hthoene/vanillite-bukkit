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
import static java.lang.foreign.ValueLayout.JAVA_INT;
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
    public org.bukkit.NamespacedKey getKey() {
        return org.bukkit.NamespacedKey.fromString(key);
    }

    @Override
    public net.kyori.adventure.key.Key key() {
        return getKey();
    }

    @Override
    public long getSeed() {
        var s = Native.scratch();
        MemorySegment details = s.alloc(Abi.WorldDetails.SIZE);
        details.set(JAVA_INT, Abi.WorldDetails.size, (int) Abi.WorldDetails.SIZE);
        Ctx.check(Host.world_info(Native.plugin, id, details, s.strBuf(1024)), "World.getSeed");
        return details.get(JAVA_LONG, Abi.WorldDetails.seed);
    }

    @Override
    public int getSeaLevel() {
        return switch (getEnvironment()) {
            case NETHER -> 32;
            case THE_END -> 0;
            default -> 63;
        };
    }

    @Override
    public Entity getEntity(UUID uuid) {
        VPlayer player = Layer.server.players.get(uuid);
        if (player != null) {
            return player.getWorld() == this ? player : null;
        }
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(128);
        if (Host.entity_type(Ctx.current, id, s.uuid(uuid), buf) != Abi.status.OK) {
            return null;
        }
        return VEntity.create(this, uuid, VEntity.typeOf(Native.string(buf, true)));
    }

    @Override
    public int getEntityCount() {
        return getEntities().size();
    }

    @Override
    public int getPlayerCount() {
        return getPlayers().size();
    }

    @Override
    public boolean getAllowAnimals() {
        return true;
    }

    @Override
    public boolean getAllowMonsters() {
        return true;
    }

    @Override
    public boolean isAutoSave() {
        return true;
    }

    // ----- Blocks by position

    @Override
    public org.bukkit.Material getType(int x, int y, int z) {
        return getBlockAt(x, y, z).getType();
    }

    @Override
    public org.bukkit.block.data.BlockData getBlockData(int x, int y, int z) {
        return getBlockAt(x, y, z).getBlockData();
    }

    @Override
    public org.bukkit.block.BlockState getBlockState(int x, int y, int z) {
        return getBlockAt(x, y, z).getState();
    }

    /**
     * The Y of the highest block that blocks motion or holds a fluid (the {@code MOTION_BLOCKING}
     * height map), the world's lowest Y minus one in an empty or unloaded column.
     */
    @Override
    public int getHighestBlockYAt(int x, int z) {
        for (int y = getMaxHeight() - 1; y >= getMinHeight(); y--) {
            int state = NativeAccess.state(this, x, y, z);
            if (state < 0) {
                break;
            }
            org.bukkit.Material type = Materials.of(state);
            if (type.isSolid() || type == org.bukkit.Material.WATER || type == org.bukkit.Material.LAVA
                    || type == org.bukkit.Material.BUBBLE_COLUMN) {
                return y;
            }
            String name = NativeAccess.stateName(state);
            if (name != null && name.contains("waterlogged=true")) {
                return y;
            }
        }
        return getMinHeight() - 1;
    }

    @Override
    public int getHighestBlockYAt(Location location) {
        return getHighestBlockYAt(location.getBlockX(), location.getBlockZ());
    }

    // ----- Weather and game rules

    @Override
    public void setStorm(boolean storm) {
        Ctx.check(Host.world_set_weather(Native.plugin, storm ? Abi.weather.RAIN : Abi.weather.CLEAR, -1), "World.setStorm");
    }

    /** Thunder off clears the weather: the host sets rain, thunder and clear as {@code /weather} does. */
    @Override
    public void setThundering(boolean thundering) {
        Ctx.check(Host.world_set_weather(Native.plugin, thundering ? Abi.weather.THUNDER : Abi.weather.CLEAR, -1),
                "World.setThundering");
    }

    private static String ruleName(org.bukkit.GameRule<?> rule) {
        return rule.getKey().getKey();
    }

    private String ruleText(String name) {
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(256);
        if (Host.game_rule_get(Native.plugin, s.str(name), buf) != Abi.status.OK) {
            return null;
        }
        return Native.string(buf, true);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getGameRuleValue(org.bukkit.GameRule<T> rule) {
        if (rule instanceof LegacyGameRule<?, T> legacy) {
            return (T) legacy.toLegacy(getGameRuleValue(legacy.modern));
        }
        String text = ruleText(ruleName(rule));
        if (text == null) {
            return null;
        }
        text = text.strip();
        return (T) (text.equals("true") || text.equals("false") ? (Object) Boolean.valueOf(text) : (Object) Integer.valueOf(text));
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getGameRuleValue(String rule) {
        return rule == null ? null : ruleText(rule);
    }

    @Override
    public <T> boolean setGameRule(org.bukkit.GameRule<T> rule, T value) {
        if (rule instanceof LegacyGameRule<?, T> legacy) {
            return setGameRuleValue(ruleName(legacy.modern), String.valueOf(legacy.toModern(value)));
        }
        var s = Native.scratch();
        return Host.game_rule_set(Native.plugin, s.str(ruleName(rule)), s.str(String.valueOf(value))) == Abi.status.OK;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean setGameRuleValue(String rule, String value) {
        if (rule == null || value == null) {
            return false;
        }
        var s = Native.scratch();
        return Host.game_rule_set(Native.plugin, s.str(rule), s.str(value)) == Abi.status.OK;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isGameRule(String rule) {
        return rule != null && ruleText(rule) != null;
    }

    // ----- Effects in the world

    @Override
    public org.bukkit.entity.LightningStrike strikeLightning(Location loc) {
        return (org.bukkit.entity.LightningStrike) spawnType(loc, org.bukkit.entity.EntityType.LIGHTNING_BOLT);
    }

    // ----- Spawning. The host adds an entity at once: a plugin's function runs on the entity
    // just after it entered the world, not before as on Paper.

    /** Spawns an entity of a type as {@code /summon} does, turned as the location. */
    private Entity spawnType(Location loc, org.bukkit.entity.EntityType type) {
        if (type == null || !type.isSpawnable() || type == org.bukkit.entity.EntityType.PLAYER) {
            throw new IllegalArgumentException("Cannot spawn an entity of type " + type);
        }
        var s = Native.scratch();
        MemorySegment at = s.alloc(Abi.Vec3.SIZE);
        at.set(JAVA_DOUBLE, Abi.Vec3.x, loc.getX());
        at.set(JAVA_DOUBLE, Abi.Vec3.y, loc.getY());
        at.set(JAVA_DOUBLE, Abi.Vec3.z, loc.getZ());
        MemorySegment out = s.alloc(Abi.Uuid.SIZE);
        Ctx.check(Host.entity_spawn(Ctx.current, id, s.str(type.getKey().toString()), at, s.str(""), out),
                "World.spawn " + type.getKey());
        Entity entity = VEntity.create(this, Native.uuid(out), type);
        if (loc.getYaw() != 0 || loc.getPitch() != 0) {
            entity.teleport(loc);
        }
        return entity;
    }

    private static org.bukkit.entity.EntityType typeOf(Class<?> clazz) {
        org.bukkit.entity.EntityType fallback = null;
        for (org.bukkit.entity.EntityType t : org.bukkit.entity.EntityType.values()) {
            Class<?> c = t.getEntityClass();
            if (c == clazz) {
                return t;
            }
            if (fallback == null && c != null && t.isSpawnable() && clazz.isAssignableFrom(c)
                    && clazz != Entity.class && clazz != org.bukkit.entity.LivingEntity.class) {
                fallback = t;
            }
        }
        return fallback;
    }

    private <T extends Entity> T spawnClass(Location loc, Class<T> clazz, java.util.function.Consumer<? super T> function) {
        if (loc == null || clazz == null) {
            throw new IllegalArgumentException("Location and entity class cannot be null");
        }
        org.bukkit.entity.EntityType type = typeOf(clazz);
        if (type == null) {
            throw new IllegalArgumentException("Cannot spawn an entity for " + clazz.getName());
        }
        T entity = clazz.cast(spawnType(loc, type));
        if (function != null) {
            function.accept(entity);
        }
        return entity;
    }

    @Override
    public Entity spawnEntity(Location loc, org.bukkit.entity.EntityType type, boolean randomizeData) {
        return spawnType(loc, type);
    }

    @Override
    public <T extends Entity> T spawn(Location location, Class<T> clazz, java.util.function.Consumer<? super T> function,
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        return spawnClass(location, clazz, function);
    }

    @Override
    public <T extends Entity> T spawn(Location location, Class<T> clazz, boolean randomizeData,
            java.util.function.Consumer<? super T> function) {
        return spawnClass(location, clazz, function);
    }

    @Override
    public <T extends org.bukkit.entity.LivingEntity> T spawn(Location location, Class<T> clazz,
            org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason, boolean randomizeData,
            java.util.function.Consumer<? super T> function) {
        return spawnClass(location, clazz, function);
    }

    private boolean explode(double x, double y, double z, float power, boolean fire, boolean breakBlocks, Entity source) {
        var s = Native.scratch();
        MemorySegment at = s.alloc(Abi.Vec3.SIZE);
        at.set(JAVA_DOUBLE, Abi.Vec3.x, x);
        at.set(JAVA_DOUBLE, Abi.Vec3.y, y);
        at.set(JAVA_DOUBLE, Abi.Vec3.z, z);
        int flags = (breakBlocks ? Abi.explode_flag.BREAK_BLOCKS : 0) | (fire ? Abi.explode_flag.FIRE : 0);
        MemorySegment who = s.uuid(source == null ? new UUID(0, 0) : source.getUniqueId());
        return Host.explode(Ctx.current, id, at, power, flags, who) == Abi.status.OK;
    }

    @Override
    public boolean createExplosion(double x, double y, double z, float power, boolean setFire, boolean breakBlocks,
            Entity source) {
        return explode(x, y, z, power, setFire, breakBlocks, source);
    }

    @Override
    public boolean createExplosion(Location loc, float power, boolean setFire, boolean breakBlocks, Entity source) {
        return explode(loc.getX(), loc.getY(), loc.getZ(), power, setFire, breakBlocks, source);
    }

    @Override
    public boolean createExplosion(Entity source, Location loc, float power, boolean setFire, boolean breakBlocks,
            boolean excludeSourceFromDamage) {
        return explode(loc.getX(), loc.getY(), loc.getZ(), power, setFire, breakBlocks, source);
    }

    /**
     * A ray through the blocks (their collision shapes; with {@code ALWAYS} or {@code
     * SOURCE_ONLY} also fluids). The host knows no block filter: {@code canCollide} and {@code
     * ignorePassableBlocks} are not applied.
     */
    @Override
    public org.bukkit.util.RayTraceResult rayTraceBlocks(io.papermc.paper.math.Position start, org.bukkit.util.Vector direction,
            double maxDistance, org.bukkit.FluidCollisionMode fluidCollisionMode, boolean ignorePassableBlocks,
            java.util.function.Predicate<? super Block> canCollide) {
        org.bukkit.util.Vector dir = direction.clone().normalize().multiply(maxDistance);
        var s = Native.scratch();
        MemorySegment from = s.alloc(Abi.Vec3.SIZE);
        from.set(JAVA_DOUBLE, Abi.Vec3.x, start.x());
        from.set(JAVA_DOUBLE, Abi.Vec3.y, start.y());
        from.set(JAVA_DOUBLE, Abi.Vec3.z, start.z());
        MemorySegment to = s.alloc(Abi.Vec3.SIZE);
        to.set(JAVA_DOUBLE, Abi.Vec3.x, start.x() + dir.getX());
        to.set(JAVA_DOUBLE, Abi.Vec3.y, start.y() + dir.getY());
        to.set(JAVA_DOUBLE, Abi.Vec3.z, start.z() + dir.getZ());
        int flags = Abi.raytrace_flag.BLOCKS
                | (fluidCollisionMode == null || fluidCollisionMode == org.bukkit.FluidCollisionMode.NEVER ? 0
                        : Abi.raytrace_flag.FLUIDS);
        MemorySegment hit = s.alloc(Abi.RayHit.SIZE);
        hit.set(JAVA_INT, Abi.RayHit.size, (int) Abi.RayHit.SIZE);
        Ctx.check(Host.raytrace(Ctx.current, id, from, to, flags, s.uuid(new UUID(0, 0)), hit), "World.rayTraceBlocks");
        if (hit.get(JAVA_INT, Abi.RayHit.kind) != Abi.ray_hit.BLOCK) {
            return null;
        }
        org.bukkit.util.Vector point = new org.bukkit.util.Vector(hit.get(JAVA_DOUBLE, Abi.RayHit.pos + Abi.Vec3.x),
                hit.get(JAVA_DOUBLE, Abi.RayHit.pos + Abi.Vec3.y), hit.get(JAVA_DOUBLE, Abi.RayHit.pos + Abi.Vec3.z));
        Block block = getBlockAt(hit.get(JAVA_INT, Abi.RayHit.block + Abi.BlockPos.x),
                hit.get(JAVA_INT, Abi.RayHit.block + Abi.BlockPos.y), hit.get(JAVA_INT, Abi.RayHit.block + Abi.BlockPos.z));
        org.bukkit.block.BlockFace face = switch (hit.get(JAVA_INT, Abi.RayHit.face)) {
            case 0 -> org.bukkit.block.BlockFace.DOWN;
            case 1 -> org.bukkit.block.BlockFace.UP;
            case 2 -> org.bukkit.block.BlockFace.NORTH;
            case 3 -> org.bukkit.block.BlockFace.SOUTH;
            case 4 -> org.bukkit.block.BlockFace.WEST;
            default -> org.bukkit.block.BlockFace.EAST;
        };
        return new org.bukkit.util.RayTraceResult(point, block, face);
    }

    /** A sound at a place for the players of this world in its range (Vanilla's: 16 blocks, more when louder). */
    private void sound(String key, int source, double x, double y, double z, float volume, float pitch, long seed) {
        double range = volume > 1 ? 16.0 * volume : 16.0;
        for (VPlayer p : Layer.server.players.values()) {
            if (p.worldId() != id) {
                continue;
            }
            Location at = p.getLocation();
            double dx = at.getX() - x;
            double dy = at.getY() - y;
            double dz = at.getZ() - z;
            if (dx * dx + dy * dy + dz * dz < range * range) {
                p.sound(key, source, x, y, z, volume, pitch, seed);
            }
        }
    }

    @Override
    public void playSound(Location location, org.bukkit.Sound sound, org.bukkit.SoundCategory category, float volume,
            float pitch, long seed) {
        if (location != null && sound != null) {
            sound(VPlayer.soundKey(sound), VPlayer.source(category), location.getX(), location.getY(), location.getZ(),
                    volume, pitch, seed);
        }
    }

    @Override
    public void playSound(Location location, org.bukkit.Sound sound, org.bukkit.SoundCategory category, float volume,
            float pitch) {
        playSound(location, sound, category, volume, pitch, java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    @Override
    public void playSound(Location location, String sound, org.bukkit.SoundCategory category, float volume, float pitch,
            long seed) {
        if (location != null && sound != null) {
            sound(sound, VPlayer.source(category), location.getX(), location.getY(), location.getZ(), volume, pitch, seed);
        }
    }

    @Override
    public void playSound(Location location, String sound, org.bukkit.SoundCategory category, float volume, float pitch) {
        playSound(location, sound, category, volume, pitch, java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    @Override
    public void playSound(Entity entity, org.bukkit.Sound sound, org.bukkit.SoundCategory category, float volume, float pitch,
            long seed) {
        playSound(entity.getLocation(), sound, category, volume, pitch, seed);
    }

    @Override
    public void playSound(Entity entity, org.bukkit.Sound sound, org.bukkit.SoundCategory category, float volume, float pitch) {
        playSound(entity.getLocation(), sound, category, volume, pitch);
    }

    @Override
    public void playSound(Entity entity, String sound, org.bukkit.SoundCategory category, float volume, float pitch,
            long seed) {
        playSound(entity.getLocation(), sound, category, volume, pitch, seed);
    }

    @Override
    public void playSound(Entity entity, String sound, org.bukkit.SoundCategory category, float volume, float pitch) {
        playSound(entity.getLocation(), sound, category, volume, pitch);
    }

    // ----- Metadata (Metadatable), kept for the server's lifetime

    private final java.util.Map<String, List<org.bukkit.metadata.MetadataValue>> metadata =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void setMetadata(String key, org.bukkit.metadata.MetadataValue value) {
        var owner = value.getOwningPlugin();
        if (owner == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        var values = metadata.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        values.removeIf(v -> v.getOwningPlugin() == owner);
        values.add(value);
    }

    @Override
    public List<org.bukkit.metadata.MetadataValue> getMetadata(String key) {
        var values = metadata.get(key);
        return values == null ? List.of() : List.copyOf(values);
    }

    @Override
    public boolean hasMetadata(String key) {
        var values = metadata.get(key);
        return values != null && !values.isEmpty();
    }

    @Override
    public void removeMetadata(String key, org.bukkit.plugin.Plugin owner) {
        var values = metadata.get(key);
        if (values != null) {
            values.removeIf(v -> v.getOwningPlugin() == owner);
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
