// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * The server side of a Bukkit {@link ItemStack} (Paper's API stacks delegate to one): an item type,
 * a count and its components ({@link VItemMeta}). Read from and written to the server as SNBT
 * ({@code {id:"minecraft:stone",count:3,components:{...}}}).
 */
public final class VItemStack extends ItemStack {
    private Material type;
    private int amount;
    /** Its components, null for none. */
    private VItemMeta meta;

    VItemStack(Material type, int amount) {
        this.type = type == null ? Material.AIR : type;
        this.amount = this.type.isAir() ? 0 : amount;
    }

    /** The empty stack. */
    public static VItemStack empty() {
        return new VItemStack(Material.AIR, 0);
    }

    /** A stack of the given type and count. */
    public static VItemStack of(Material type, int amount) {
        return new VItemStack(type, amount);
    }

    /** The server form of any stack (an API stack is copied). */
    public static VItemStack of(ItemStack stack) {
        if (stack == null) {
            return empty();
        }
        if (stack instanceof VItemStack v) {
            return v;
        }
        VItemStack v = new VItemStack(stack.getType(), stack.getAmount());
        if (stack.hasItemMeta()) {
            v.setItemMeta(stack.getItemMeta());
        }
        return v;
    }

    /** A stack read from SNBT ({@code {}} or malformed text: the empty stack). */
    @SuppressWarnings("unchecked")
    public static VItemStack fromSnbt(String snbt) {
        Map<String, Object> m = Snbt.compound(snbt);
        if (!(m.get("id") instanceof String id)) {
            return empty();
        }
        NamespacedKey key = NamespacedKey.fromString(id);
        Material type = key == null ? null : Material.getMaterial(key.getKey().toUpperCase(Locale.ROOT));
        int count = m.get("count") instanceof Number n ? n.intValue() : m.get("Count") instanceof Number n2 ? n2.intValue() : 1;
        VItemStack v = new VItemStack(type == null ? Material.AIR : type, count);
        if (m.get("components") instanceof Map<?, ?> c && !c.isEmpty() && !v.type.isAir()) {
            v.meta = VItemMeta.fromComponents(v.type, (Map<String, Object>) c);
        }
        return v;
    }

    /** The SNBT of a stack, {@code {}} for an empty one. */
    public static String snbt(ItemStack stack) {
        return stack == null ? "{}" : of(stack).toSnbt();
    }

    /** This stack as SNBT. */
    public String toSnbt() {
        if (isEmpty()) {
            return "{}";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", type.getKey().toString());
        m.put("count", amount);
        if (meta != null) {
            Map<String, Object> c = meta.toComponents();
            if (!c.isEmpty()) {
                m.put("components", c);
            }
        }
        return Snbt.write(m);
    }

    @Override
    public Material getType() {
        return type;
    }

    @Override
    public void setType(Material type) {
        this.type = type == null ? Material.AIR : type;
        if (meta != null) {
            VItemMeta m = VItemMeta.create(this.type);
            m.copyFrom(meta);
            meta = m;
        }
    }

    @Override
    public ItemStack withType(Material type) {
        VItemStack v = clone();
        v.setType(type);
        return v;
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public void setAmount(int amount) {
        this.amount = amount;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setDurability(short durability) {
        ItemMeta m = getItemMeta();
        if (m instanceof VItemMeta v) {
            v.setDamage(durability);
            setItemMeta(v);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public short getDurability() {
        return meta == null ? 0 : (short) meta.getDamage();
    }

    @Override
    public int getMaxStackSize() {
        if (meta != null && meta.maxStack != null) {
            return meta.maxStack;
        }
        return type.isItem() ? type.getMaxStackSize() : 64;
    }

    @Override
    public boolean isEmpty() {
        return type.isAir() || amount <= 0;
    }

    // ----- Meta

    @Override
    public ItemMeta getItemMeta() {
        if (type.isAir()) {
            return null;
        }
        return meta == null ? VItemMeta.create(type) : meta.clone();
    }

    @Override
    public boolean hasItemMeta() {
        return meta != null && !meta.isEmpty();
    }

    @Override
    public boolean setItemMeta(ItemMeta itemMeta) {
        if (itemMeta == null) {
            meta = null;
            return true;
        }
        if (!(itemMeta instanceof VItemMeta v)) {
            return false;
        }
        VItemMeta m = VItemMeta.create(type);
        m.copyFrom(v);
        meta = m;
        return true;
    }

    @Override
    public io.papermc.paper.persistence.PersistentDataContainerView getPersistentDataContainer() {
        return meta == null ? new VPersistentData() : meta.data.copy();
    }

    @Override
    public boolean editPersistentDataContainer(Consumer<PersistentDataContainer> consumer) {
        if (type.isAir()) {
            return false;
        }
        VItemMeta m = (VItemMeta) getItemMeta();
        consumer.accept(m.data);
        meta = m;
        return true;
    }

    @Override
    public boolean containsEnchantment(Enchantment enchant) {
        return meta != null && meta.hasEnchant(enchant);
    }

    @Override
    public int getEnchantmentLevel(Enchantment enchant) {
        return meta == null ? 0 : meta.getEnchantLevel(enchant);
    }

    @Override
    public Map<Enchantment, Integer> getEnchantments() {
        return meta == null ? Map.of() : meta.getEnchants();
    }

    @Override
    public void addUnsafeEnchantment(Enchantment enchant, int level) {
        VItemMeta m = (VItemMeta) getItemMeta();
        if (m != null) {
            m.addEnchant(enchant, level, true);
            meta = m;
        }
    }

    @Override
    public int removeEnchantment(Enchantment enchant) {
        if (meta == null) {
            return 0;
        }
        int level = meta.getEnchantLevel(enchant);
        meta.removeEnchant(enchant);
        return level;
    }

    @Override
    public void removeEnchantments() {
        if (meta != null) {
            meta.removeEnchantments();
        }
    }

    // ----- Comparison and copies

    @Override
    public boolean isSimilar(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        if (stack == this) {
            return true;
        }
        VItemStack o = of(stack);
        if (o.type != type) {
            return false;
        }
        boolean a = hasItemMeta();
        boolean b = o.hasItemMeta();
        return a == b && (!a || meta.equals(o.meta));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ItemStack s && getAmount() == s.getAmount() && isSimilar(s);
    }

    @Override
    public int hashCode() {
        int h = 31 + type.hashCode();
        h = h * 31 + amount;
        return h * 31 + (hasItemMeta() ? meta.hashCode() : 0);
    }

    @Override
    public VItemStack clone() {
        VItemStack v = new VItemStack(type, amount);
        v.meta = meta == null ? null : meta.clone();
        return v;
    }

    @Override
    public String toString() {
        return "ItemStack{" + type.name() + " x " + amount + (hasItemMeta() ? ", " + meta.getAsString() : "") + "}";
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("DataVersion", org.bukkit.Bukkit.getUnsafe().getDataVersion());
        m.put("id", type.getKey().toString());
        m.put("count", amount);
        if (hasItemMeta()) {
            m.put("components", meta.getAsString());
        }
        m.put("schema_version", 1);
        return m;
    }

    @Override
    public byte[] serializeAsBytes() {
        return toSnbt().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Component effectiveName() {
        if (meta != null && meta.displayName != null) {
            return meta.displayName;
        }
        if (meta != null && meta.itemName != null) {
            return meta.itemName;
        }
        return Component.translatable(translationKey());
    }

    @Override
    public String translationKey() {
        return (type.isBlock() ? "block." : "item.") + type.getKey().getNamespace() + "." + type.getKey().getKey();
    }

    @Override
    public boolean isRepairableBy(ItemStack repairMaterial) {
        return false;
    }

    @Override
    public int getMaxItemUseDuration(org.bukkit.entity.LivingEntity entity) {
        return 0;
    }
}
