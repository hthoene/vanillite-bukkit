// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.compat.worldedit;

import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.entity.EntityType;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import dev.vanillite.bukkit.NativeAccess;
import dev.vanillite.bukkit.VBlockData;
import com.sk89q.worldedit.WorldEdit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.enginehub.linbus.format.snbt.LinStringIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinFloatTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinTagType;

/**
 * WorldEdit's Bukkit adapter for Vanillite: blocks by state id (no NMS), block entities and
 * entities as SNBT, biomes. The layer adds this class to WorldEdit's class path and names it in
 * {@code -Dworldedit.bukkit.adapter}; WorldEdit uses it in place of its own per-version adapters.
 */
public final class VanilliteAdapter implements BukkitImplAdapter {
    private static final Set<SideEffect> SIDE_EFFECTS =
            EnumSet.of(SideEffect.NEIGHBORS, SideEffect.UPDATE, SideEffect.LIGHTING, SideEffect.NETWORK);
    private static final int BLOCK_ENTITY = 64;

    /** A block type's states as the server numbers them. */
    private record TypeInfo(int first, int flags, Map<String, Property<?>> properties, Map<String, Integer> ids) {}

    private final Map<String, TypeInfo> types = new ConcurrentHashMap<>();

    public VanilliteAdapter() {
        if (!Bukkit.getServer().getClass().getName().startsWith("org.bukkit.craftbukkit.CraftServer")
                || !"Vanillite".equals(Bukkit.getServer().getName())) {
            throw new IllegalStateException("Not running on Vanillite");
        }
    }

    // ----- Block types and states

    private static Map<String, String> props(String name) {
        Map<String, String> out = new LinkedHashMap<>();
        int open = name.indexOf('[');
        if (open < 0) {
            return out;
        }
        for (String p : name.substring(open + 1, name.length() - 1).split(",")) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                out.put(p.substring(0, eq), p.substring(eq + 1));
            }
        }
        return out;
    }

    /** The properties sorted by name as {@code a=1,b=x}: one key per state, whatever the order. */
    private static String canonical(Map<String, String> props) {
        StringBuilder b = new StringBuilder();
        new TreeMap<>(props).forEach((k, v) -> b.append(k).append('=').append(v).append(','));
        return b.toString();
    }

    private TypeInfo info(String key) {
        return types.computeIfAbsent(key, k -> {
            int[] t = NativeAccess.blockType(k);
            if (t == null) {
                return new TypeInfo(-1, 0, Map.of(), Map.of());
            }
            Map<String, LinkedHashSet<String>> values = new LinkedHashMap<>();
            Map<String, Integer> ids = new HashMap<>();
            for (int id = t[1]; id < t[1] + t[2]; id++) {
                String name = NativeAccess.stateName(id);
                if (name == null) {
                    continue;
                }
                Map<String, String> p = props(name);
                p.forEach((n, v) -> values.computeIfAbsent(n, x -> new LinkedHashSet<>()).add(v));
                ids.put(canonical(p), id);
            }
            Map<String, Property<?>> properties = new LinkedHashMap<>();
            values.forEach((n, v) -> properties.put(n, property(n, new ArrayList<>(v))));
            return new TypeInfo(t[1], t[3], properties, ids);
        });
    }

    private static final Set<String> DIRECTIONS = Set.of("north", "south", "east", "west", "up", "down");

    private static Property<?> property(String name, List<String> values) {
        if (values.size() == 2 && values.contains("true") && values.contains("false")) {
            return new BooleanProperty(name, List.of(Boolean.parseBoolean(values.get(0)), Boolean.parseBoolean(values.get(1))));
        }
        if (values.stream().allMatch(v -> v.matches("-?\\d+"))) {
            return new IntegerProperty(name, values.stream().map(Integer::valueOf).toList());
        }
        if (values.stream().allMatch(DIRECTIONS::contains)) {
            return new DirectionalProperty(name, values.stream().map(v -> Direction.valueOf(v.toUpperCase(Locale.ROOT))).toList());
        }
        return new EnumProperty(name, List.copyOf(values));
    }

    @Override
    public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
        return info(blockType.id()).properties();
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockState state) {
        TypeInfo info = info(state.getBlockType().id());
        Map<String, String> p = new HashMap<>();
        state.getStates().forEach((prop, value) -> p.put(prop.name(),
                value instanceof Direction d ? d.name().toLowerCase(Locale.ROOT) : String.valueOf(value)));
        Integer id = info.ids().get(canonical(p));
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockData data) {
        return data instanceof VBlockData v ? OptionalInt.of(v.stateId()) : OptionalInt.empty();
    }

    /** The WorldEdit state of a server state id. */
    BlockState state(int id) {
        if (id < 0) {
            return BlockTypes.AIR.getDefaultState();
        }
        BlockState s = BlockStateIdAccess.getBlockStateById(id);
        if (s != null) {
            return s;
        }
        String name = NativeAccess.stateName(id);
        if (name == null) {
            return BlockTypes.AIR.getDefaultState();
        }
        ParserContext context = new ParserContext();
        context.setRestricted(false);
        context.setTryLegacy(false);
        context.setPreferringWildcard(false);
        try {
            return WorldEdit.getInstance().getBlockFactory().parseFromInput(name, context).toImmutableState();
        } catch (InputParseException e) {
            return BlockTypes.AIR.getDefaultState();
        }
    }

    /** The server state id of a WorldEdit state. */
    int id(BlockState state) {
        int id = BlockStateIdAccess.getBlockStateId(state);
        if (BlockStateIdAccess.isValidInternalId(id)) {
            return id;
        }
        return getInternalBlockStateId(state).orElse(0);
    }

    boolean hasBlockEntity(BlockType type) {
        return (info(type.id()).flags() & BLOCK_ENTITY) != 0;
    }

    @Override
    public BlockState getBlock(Location location) {
        return state(NativeAccess.state(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    @Override
    public BaseBlock getFullBlock(Location location) {
        BlockState state = getBlock(location);
        if (!hasBlockEntity(state.getBlockType())) {
            return state.toBaseBlock();
        }
        String snbt = NativeAccess.blockEntity(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (snbt == null) {
            return state.toBaseBlock();
        }
        LinCompoundTag tag = tag(snbt);
        return tag == null ? state.toBaseBlock() : state.toBaseBlock(LazyReference.computed(tag));
    }

    static LinCompoundTag tag(String snbt) {
        try {
            return LinStringIO.readFromStringUsing(snbt, LinCompoundTag::readFrom);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public WorldNativeAccess<?, ?, ?> createWorldNativeAccess(World world) {
        return new VanilliteWorldAccess(this, world);
    }

    @Override
    public BlockMaterial getBlockMaterial(BlockType blockType) {
        int[] t = NativeAccess.blockType(blockType.id());
        float[] f = NativeAccess.blockFacts(blockType.id());
        return t == null || f == null ? null : new VanilliteBlockMaterial(t[3], f[0], f[1], f[2]);
    }

    // ----- Names

    private static String translationKey(String prefix, String id) {
        int colon = id.indexOf(':');
        return prefix + "." + id.substring(0, colon) + "." + id.substring(colon + 1).replace('/', '.');
    }

    @Override
    public Component getRichBlockName(BlockType blockType) {
        return TranslatableComponent.of(translationKey("block", blockType.id()));
    }

    @Override
    public Component getRichItemName(ItemType itemType) {
        String id = itemType.id();
        return TranslatableComponent.of(translationKey(BlockType.REGISTRY.get(id) != null ? "block" : "item", id));
    }

    @Override
    public Component getRichItemName(BaseItemStack itemStack) {
        return getRichItemName(itemStack.getType());
    }

    // ----- Entities

    @Override
    public BaseEntity getEntity(Entity entity) {
        if (entity instanceof Player) {
            return null;
        }
        EntityType type = EntityType.REGISTRY.get(entity.getType().getKey().toString());
        if (type == null) {
            return null;
        }
        String snbt = NativeAccess.entityData(entity);
        LinCompoundTag tag = snbt == null ? null : tag(snbt);
        return tag == null ? new BaseEntity(type) : new BaseEntity(type, LazyReference.computed(tag));
    }

    @Override
    public Entity createEntity(Location location, BaseEntity state) {
        LinCompoundTag tag = state.getNbt();
        String snbt = "";
        if (tag != null) {
            LinCompoundTag.Builder b = tag.toBuilder();
            for (String k : List.of("UUID", "Pos", "id", "UUIDMost", "UUIDLeast", "WorldUUIDMost", "WorldUUIDLeast")) {
                b.remove(k);
            }
            b.put("Rotation", LinListTag.builder(LinTagType.floatTag())
                    .add(LinFloatTag.of(location.getYaw())).add(LinFloatTag.of(location.getPitch())).build());
            snbt = LinStringIO.writeToString(b.build());
        }
        return NativeAccess.spawn(location.getWorld(), state.getType().id(), location.getX(), location.getY(), location.getZ(), snbt);
    }

    // ----- Items

    @Override
    public ItemStack adapt(BaseItemStack item) {
        NamespacedKey key = NamespacedKey.fromString(item.getType().id());
        Material m = key == null ? null : Material.getMaterial(key.getKey().toUpperCase(Locale.ROOT));
        return new ItemStack(m == null ? Material.AIR : m, item.getAmount());
    }

    @Override
    public BaseItemStack adapt(ItemStack itemStack) {
        ItemType type = ItemType.REGISTRY.get(itemStack.getType().getKey().toString());
        return new BaseItemStack(type == null ? com.sk89q.worldedit.world.item.ItemTypes.AIR : type, itemStack.getAmount());
    }

    // ----- Biomes and the rest

    @Override
    public BiomeType getBiome(Location location) {
        String key = NativeAccess.biome(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BiomeType b = key == null ? null : BiomeType.REGISTRY.get(key);
        return b != null ? b : BiomeType.REGISTRY.get("minecraft:plains");
    }

    @Override
    public void setBiome(Location location, BiomeType biome) {
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        NativeAccess.setBiome(location.getWorld(), x, y, z, x, y, z, biome.id());
    }

    @Override
    public void initializeRegistries() {
        for (var biome : Registry.BIOME) {
            String key = biome.getKey().toString();
            if (BiomeType.REGISTRY.get(key) == null) {
                BiomeType.REGISTRY.register(key, new BiomeType(key));
            }
        }
    }

    @Override
    public DataFixer getDataFixer() {
        return null;
    }

    @Override
    public void sendFakeNBT(Player player, BlockVector3 pos, LinCompoundTag nbtData) {
        // No fake block entity data (WorldEdit CUI structure blocks).
    }

    @Override
    public void sendFakeOP(Player player) {
        // The client's operator level is the server's.
    }

    @Override
    public boolean canPlaceAt(World world, BlockVector3 position, BlockState blockState) {
        return true;
    }

    @Override
    public Set<SideEffect> getSupportedSideEffects() {
        return SIDE_EFFECTS;
    }
}
