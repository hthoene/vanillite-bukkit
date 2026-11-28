// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A block position of a world as a Bukkit {@link Block}; reads and writes go to the host. */
public abstract class VBlock implements Block {
    private static final MethodHandle NEW =
            Impl.constructor(VBlock.class, VWorld.class, int.class, int.class, int.class);

    final VWorld world;
    final int x;
    final int y;
    final int z;

    protected VBlock(VWorld world, int x, int y, int z) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** A fixed state this block reports (a block about to be placed), -1 to read the world. */
    private int fixedState = -1;

    /** A block that reports {@code state} instead of the world's (Bukkit fires place events after placing). */
    static Block withState(VWorld world, int x, int y, int z, int state) {
        VBlock b = (VBlock) create(world, x, y, z);
        b.fixedState = state;
        return b;
    }

    static Block create(VWorld world, int x, int y, int z) {
        try {
            return (VBlock) NEW.invokeExact(world, x, y, z);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    private MemorySegment pos(Native.Scratch s) {
        MemorySegment pos = s.alloc(Abi.BlockPos.SIZE);
        pos.set(JAVA_INT, Abi.BlockPos.x, x);
        pos.set(JAVA_INT, Abi.BlockPos.y, y);
        pos.set(JAVA_INT, Abi.BlockPos.z, z);
        return pos;
    }

    /** The block state id here. */
    public int stateId() {
        if (fixedState >= 0) {
            return fixedState;
        }
        var s = Native.scratch();
        MemorySegment out = s.alloc(4);
        int status = Host.block_get_id(Ctx.current, world.id, pos(s), out);
        if (status == Abi.status.NOT_FOUND) {
            return 0;
        }
        Ctx.check(status, "Block.getType");
        return out.get(JAVA_INT, 0);
    }

    @Override
    public Material getType() {
        return Materials.of(stateId());
    }

    @Override
    public void setType(Material type) {
        setType(type, true);
    }

    @Override
    public void setType(Material type, boolean applyPhysics) {
        int state = Materials.state(type);
        Ctx.check(Host.block_set_id(Ctx.current, world.id, pos(Native.scratch()), state,
                applyPhysics ? Abi.set_mode.UPDATE : Abi.set_mode.NO_PHYSICS), "Block.setType");
    }

    @Override
    public org.bukkit.block.data.BlockData getBlockData() {
        return VBlockData.of(stateId());
    }

    @Override
    public void setBlockData(org.bukkit.block.data.BlockData data) {
        setBlockData(data, true);
    }

    @Override
    public void setBlockData(org.bukkit.block.data.BlockData data, boolean applyPhysics) {
        Ctx.check(Host.block_set_id(Ctx.current, world.id, pos(Native.scratch()), ((VBlockData) data).state,
                applyPhysics ? Abi.set_mode.UPDATE : Abi.set_mode.NO_PHYSICS), "Block.setBlockData");
    }

    @Override
    public org.bukkit.block.BlockState getState() {
        return VBlockState.create(world, x, y, z, stateId());
    }

    @Override
    public org.bukkit.block.BlockState getState(boolean useSnapshot) {
        return getState();
    }

    @Override
    public boolean isEmpty() {
        return getType().isAir();
    }

    @Override
    public boolean isLiquid() {
        Material m = getType();
        return m == Material.WATER || m == Material.LAVA;
    }

    @Override
    public org.bukkit.block.Biome getBiome() {
        String key = NativeAccess.biome(world, x, y, z);
        org.bukkit.NamespacedKey k = key == null ? null : org.bukkit.NamespacedKey.fromString(key);
        org.bukkit.block.Biome b = k == null ? null : org.bukkit.Registry.BIOME.get(k);
        return b != null ? b : org.bukkit.block.Biome.PLAINS;
    }

    @Override
    public boolean isPassable() {
        return !getType().isSolid();
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getZ() {
        return z;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public Location getLocation() {
        return new Location(world, x, y, z);
    }

    @Override
    public Block getRelative(int dx, int dy, int dz) {
        return create(world, x + dx, y + dy, z + dz);
    }

    @Override
    public Block getRelative(BlockFace face) {
        return getRelative(face.getModX(), face.getModY(), face.getModZ());
    }

    @Override
    public Location getLocation(Location loc) {
        if (loc != null) {
            loc.setWorld(world);
            loc.set(x, y, z);
            loc.setYaw(0);
            loc.setPitch(0);
        }
        return loc;
    }

    @Override
    public Block getRelative(BlockFace face, int distance) {
        return getRelative(face.getModX() * distance, face.getModY() * distance, face.getModZ() * distance);
    }

    @Override
    public BlockFace getFace(Block block) {
        for (BlockFace face : BlockFace.values()) {
            if (block.getX() == x + face.getModX() && block.getY() == y + face.getModY() && block.getZ() == z + face.getModZ()) {
                return face;
            }
        }
        return null;
    }

    @Override
    public org.bukkit.Chunk getChunk() {
        return world.getChunkAt(x >> 4, z >> 4);
    }

    @Override
    @SuppressWarnings("deprecation")
    public byte getData() {
        return 0;
    }

    /** {@code {sky, block}} light here. */
    private int[] light() {
        var s = Native.scratch();
        MemorySegment sky = s.alloc(4);
        MemorySegment block = s.alloc(4);
        int status = Host.light_get(Ctx.current, world.id, pos(s), sky, block);
        if (status == Abi.status.NOT_FOUND) {
            return new int[] {0, 0};
        }
        Ctx.check(status, "Block light");
        return new int[] {sky.get(JAVA_INT, 0), block.get(JAVA_INT, 0)};
    }

    @Override
    public byte getLightFromSky() {
        return (byte) light()[0];
    }

    @Override
    public byte getLightFromBlocks() {
        return (byte) light()[1];
    }

    @Override
    public boolean isSolid() {
        return getType().isSolid();
    }

    @Override
    public boolean isBurnable() {
        return getType().isBurnable();
    }

    @Override
    public boolean isReplaceable() {
        int[] info = NativeAccess.blockType(getType().getKey().toString());
        return info != null && (info[3] & Abi.block_type_flag.REPLACEABLE) != 0;
    }

    @Override
    public String translationKey() {
        return getType().getBlockTranslationKey();
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getTranslationKey() {
        return translationKey();
    }

    @Override
    public void setBiome(org.bukkit.block.Biome biome) {
        NativeAccess.setBiome(world, x, y, z, x, y, z, biome.getKey().toString());
    }

    // ----- Metadata (Metadatable): per block position, kept for the server's lifetime as
    // Bukkit's block metadata store does.

    private static final java.util.Map<Block, java.util.Map<String, java.util.List<org.bukkit.metadata.MetadataValue>>> METADATA =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void setMetadata(String key, org.bukkit.metadata.MetadataValue value) {
        var owner = value.getOwningPlugin();
        if (owner == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }
        var values = METADATA.computeIfAbsent(this, b -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        values.removeIf(v -> v.getOwningPlugin() == owner);
        values.add(value);
    }

    @Override
    public java.util.List<org.bukkit.metadata.MetadataValue> getMetadata(String key) {
        var map = METADATA.get(this);
        var values = map == null ? null : map.get(key);
        return values == null ? java.util.List.of() : java.util.List.copyOf(values);
    }

    @Override
    public boolean hasMetadata(String key) {
        var map = METADATA.get(this);
        var values = map == null ? null : map.get(key);
        return values != null && !values.isEmpty();
    }

    @Override
    public void removeMetadata(String key, org.bukkit.plugin.Plugin owner) {
        var map = METADATA.get(this);
        var values = map == null ? null : map.get(key);
        if (values != null) {
            values.removeIf(v -> v.getOwningPlugin() == owner);
            if (values.isEmpty()) {
                map.remove(key);
                if (map.isEmpty()) {
                    METADATA.remove(this);
                }
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VBlock b && b.world == world && b.x == x && b.y == y && b.z == z;
    }

    @Override
    public int hashCode() {
        return ((y * 31 + z) * 31 + x) * 31 + world.id;
    }

    @Override
    public String toString() {
        return "VanilliteBlock{world=" + world.getName() + ",x=" + x + ",y=" + y + ",z=" + z + "}";
    }
}
