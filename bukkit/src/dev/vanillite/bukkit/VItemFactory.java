// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Item metas and stacks of the server ({@link VItemMeta}, {@link VItemStack}). */
public abstract class VItemFactory implements ItemFactory {
    protected VItemFactory() {}

    @Override
    public ItemMeta getItemMeta(Material material) {
        return material == null || material.isAir() ? null : VItemMeta.create(material);
    }

    @Override
    public boolean isApplicable(ItemMeta meta, ItemStack stack) {
        return stack != null && isApplicable(meta, stack.getType());
    }

    @Override
    public boolean isApplicable(ItemMeta meta, Material material) {
        return meta instanceof VItemMeta && material != null && !material.isAir();
    }

    @Override
    public boolean equals(ItemMeta meta1, ItemMeta meta2) {
        boolean e1 = meta1 == null || ((VItemMeta) meta1).isEmpty();
        boolean e2 = meta2 == null || ((VItemMeta) meta2).isEmpty();
        return e1 && e2 || (!e1 && !e2 && meta1.equals(meta2));
    }

    @Override
    public ItemMeta asMetaFor(ItemMeta meta, ItemStack stack) {
        return asMetaFor(meta, stack.getType());
    }

    @Override
    public ItemMeta asMetaFor(ItemMeta meta, Material material) {
        if (meta == null) {
            return null;
        }
        VItemMeta m = VItemMeta.create(material);
        m.copyFrom((VItemMeta) meta);
        return m;
    }

    @Override
    public Color getDefaultLeatherColor() {
        return Color.fromRGB(0xA06540);
    }

    /** Reads {@code minecraft:stone}, {@code stone[components]} or SNBT {@code {id:...}}. */
    @Override
    public ItemStack createItemStack(String input) {
        String s = input.strip();
        if (s.startsWith("{")) {
            VItemStack v = VItemStack.fromSnbt(s);
            if (v.isEmpty()) {
                throw new IllegalArgumentException("Could not parse item: " + input);
            }
            return v;
        }
        int bracket = s.indexOf('[');
        String id = (bracket < 0 ? s : s.substring(0, bracket)).toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        String components = "";
        if (bracket >= 0 && s.endsWith("]")) {
            components = ",components:{" + s.substring(bracket + 1, s.length() - 1)
                    .replaceAll("(^|,)\\s*([a-z0-9_:.]+)\\s*=", "$1\"$2\":") + "}";
        }
        VItemStack v = VItemStack.fromSnbt("{id:" + Snbt.quote(id) + ",count:1" + components + "}");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Could not parse item: " + input);
        }
        return v;
    }

    @Override
    public Material getSpawnEgg(EntityType type) {
        return type == null ? null : Material.getMaterial(type.name() + "_SPAWN_EGG");
    }

    @Override
    public Component displayName(ItemStack itemStack) {
        return VItemStack.of(itemStack).effectiveName();
    }

    @Override
    public ItemStack ensureServerConversions(ItemStack item) {
        return VItemStack.of(item).clone();
    }
}
