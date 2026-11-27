// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.registry;

import dev.vanillite.bukkit.Impl;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import org.bukkit.inventory.ItemRarity;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.meta.ItemMeta;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/** An item type with its Vanilla facts, read once from the server ({@code item_type_info}). */
public abstract class VItemType implements ItemType.Typed<ItemMeta> {
    private static final MethodHandle NEW = Impl.constructor(VItemType.class, NamespacedKey.class, MemorySegment.class);

    private final NamespacedKey key;
    private final int maxStack;
    private final int maxDamage;
    private final int blockState;
    private final int flags;
    private final int rarity;

    protected VItemType(NamespacedKey key, MemorySegment info) {
        this.key = key;
        this.maxStack = info.get(JAVA_INT, Abi.ItemTypeInfo.max_stack_size);
        this.maxDamage = info.get(JAVA_INT, Abi.ItemTypeInfo.max_damage);
        this.blockState = info.get(JAVA_INT, Abi.ItemTypeInfo.block_state);
        this.flags = info.get(JAVA_INT, Abi.ItemTypeInfo.flags);
        this.rarity = info.get(JAVA_INT, Abi.ItemTypeInfo.rarity);
    }

    /** The item type {@code key}, null for an item the server does not know. */
    static ItemType create(NamespacedKey key) {
        var s = Native.scratch();
        MemorySegment info = s.alloc(Abi.ItemTypeInfo.SIZE);
        info.set(JAVA_INT, Abi.ItemTypeInfo.size, (int) Abi.ItemTypeInfo.SIZE);
        if (Host.item_type_info(Native.plugin, s.str(key.toString()), info) != Abi.status.OK) {
            return null;
        }
        try {
            return (VItemType) NEW.invokeExact(key, info);
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    @Override
    public NamespacedKey getKey() {
        return key;
    }

    @Override
    public Typed<ItemMeta> typed() {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <M extends ItemMeta> Typed<M> typed(Class<M> metaType) {
        return (Typed<M>) this;
    }

    @Override
    public boolean hasBlockType() {
        return blockState != -1;
    }

    @Override
    public BlockType getBlockType() {
        if (!hasBlockType()) {
            throw new IllegalStateException(key + " has no corresponding BlockType");
        }
        var s = Native.scratch();
        MemorySegment buf = s.strBuf(256);
        if (Host.block_state_name(Native.plugin, blockState, buf) != Abi.status.OK) {
            throw new IllegalStateException(key + " has no corresponding BlockType");
        }
        String name = Native.string(buf, true);
        int props = name.indexOf('[');
        return Registry.BLOCK.get(NamespacedKey.fromString(props < 0 ? name : name.substring(0, props)));
    }

    @Override
    public int getMaxStackSize() {
        return maxStack;
    }

    @Override
    public short getMaxDurability() {
        return (short) maxDamage;
    }

    @Override
    public boolean isEdible() {
        return (flags & Abi.item_type_flag.EDIBLE) != 0;
    }

    @Override
    public boolean isRecord() {
        return (flags & Abi.item_type_flag.RECORD) != 0;
    }

    @Override
    public boolean isCompostable() {
        return (flags & Abi.item_type_flag.COMPOSTABLE) != 0;
    }

    @Override
    public ItemRarity getItemRarity() {
        return switch (rarity) {
            case 1 -> ItemRarity.UNCOMMON;
            case 2 -> ItemRarity.RARE;
            case 3 -> ItemRarity.EPIC;
            default -> ItemRarity.COMMON;
        };
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
        return KeyedStandIn.translationKey(hasBlockType() ? "block" : "item", key);
    }

    @Override
    public String getTranslationKey() {
        return translationKey();
    }

    @Override
    public String toString() {
        return "ItemType{" + key + "}";
    }
}
