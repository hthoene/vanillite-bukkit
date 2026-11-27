// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.registry;

import dev.vanillite.bukkit.Impl;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemType;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** A block type with its Vanilla facts, read once from the server ({@code block_type_info}). */
public abstract class VBlockType implements BlockType.Typed<BlockData> {
    private static final MethodHandle NEW = Impl.constructor(VBlockType.class, NamespacedKey.class, MemorySegment.class);

    private final NamespacedKey key;
    private final int defaultState;
    private final int flags;
    private final float hardness;
    private final float blastResistance;
    private final float friction;

    protected VBlockType(NamespacedKey key, MemorySegment info) {
        this.key = key;
        this.defaultState = info.get(JAVA_INT, Abi.BlockTypeInfo.default_state);
        this.flags = info.get(JAVA_INT, Abi.BlockTypeInfo.flags);
        this.hardness = info.get(JAVA_FLOAT, Abi.BlockTypeInfo.hardness);
        this.blastResistance = info.get(JAVA_FLOAT, Abi.BlockTypeInfo.blast_resistance);
        this.friction = info.get(JAVA_FLOAT, Abi.BlockTypeInfo.friction);
    }

    /** The block type {@code key}, null for a block the server does not know. */
    static BlockType create(NamespacedKey key) {
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.BlockTypeInfo.SIZE);
        info.set(JAVA_INT, Abi.BlockTypeInfo.size, (int) Abi.BlockTypeInfo.SIZE);
        if (Host.block_type_info(Native.plugin, s.str(key.toString()), info) != Abi.status.OK) {
            return null;
        }
        try {
            return (VBlockType) NEW.invokeExact(key, info);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    private boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** The default block state id. */
    public int defaultState() {
        return defaultState;
    }

    @Override
    public NamespacedKey getKey() {
        return key;
    }

    @Override
    public Typed<BlockData> typed() {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <B extends BlockData> Typed<B> typed(Class<B> blockDataType) {
        return (Typed<B>) this;
    }

    @Override
    public boolean hasItemType() {
        return has(Abi.block_type_flag.ITEM);
    }

    @Override
    public ItemType getItemType() {
        ItemType item = hasItemType() ? Registry.ITEM.get(key) : null;
        if (item == null) {
            throw new IllegalStateException(key + " has no corresponding ItemType");
        }
        return item;
    }

    @Override
    public boolean isSolid() {
        return has(Abi.block_type_flag.SOLID);
    }

    @Override
    public boolean isFlammable() {
        return has(Abi.block_type_flag.FLAMMABLE);
    }

    @Override
    public boolean isBurnable() {
        return has(Abi.block_type_flag.BURNABLE);
    }

    @Override
    public boolean isOccluding() {
        return has(Abi.block_type_flag.OCCLUDING);
    }

    @Override
    public boolean hasGravity() {
        return has(Abi.block_type_flag.GRAVITY);
    }

    @Override
    public float getHardness() {
        return hardness;
    }

    @Override
    public float getBlastResistance() {
        return blastResistance;
    }

    @Override
    public float getSlipperiness() {
        return friction;
    }

    @Override
    public boolean isAir() {
        return has(Abi.block_type_flag.AIR);
    }

    @Override
    public boolean isEnabledByFeature(World world) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Material asMaterial() {
        return Material.getMaterial(key.getKey().toUpperCase(Locale.ROOT));
    }

    @Override
    public String translationKey() {
        return KeyedStandIn.translationKey("block", key);
    }

    @Override
    public String getTranslationKey() {
        return translationKey();
    }

    @Override
    public String toString() {
        return "BlockType{" + key + "}";
    }
}
