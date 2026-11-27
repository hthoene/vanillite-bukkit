// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * An item's components as Bukkit {@link ItemMeta}: the ones Bukkit names (name, lore, enchantments,
 * flags, damage, persistent data, ...) as fields, every other component kept as it came (parsed
 * SNBT) so it survives a round trip. Kinds of meta for special items (skulls, potions, ...) are
 * generated subclasses that also implement their interface ({@link #create}).
 */
public abstract class VItemMeta implements ItemMeta, Damageable, Repairable {
    private static final String CUSTOM_NAME = "minecraft:custom_name";
    private static final String ITEM_NAME = "minecraft:item_name";
    private static final String LORE = "minecraft:lore";
    private static final String ENCHANTMENTS = "minecraft:enchantments";
    private static final String UNBREAKABLE = "minecraft:unbreakable";
    private static final String MODEL_DATA = "minecraft:custom_model_data";
    private static final String CUSTOM_DATA = "minecraft:custom_data";
    private static final String DAMAGE = "minecraft:damage";
    private static final String MAX_DAMAGE = "minecraft:max_damage";
    private static final String REPAIR_COST = "minecraft:repair_cost";
    private static final String TOOLTIP = "minecraft:tooltip_display";
    private static final String GLINT = "minecraft:enchantment_glint_override";
    private static final String MAX_STACK = "minecraft:max_stack_size";
    private static final String BUKKIT_VALUES = "PublicBukkitValues";

    Material type = Material.STONE;
    Component displayName;
    Component itemName;
    List<Component> lore;
    final Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
    final Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
    boolean unbreakable;
    Integer modelData;
    Integer damage;
    Integer maxDamage;
    Integer repairCost;
    Boolean glint;
    Integer maxStack;
    boolean hideTooltip;
    VPersistentData data = new VPersistentData();
    /** The other components of custom data (besides the persistent data). */
    Map<String, Object> customData = new LinkedHashMap<>();
    /** Components the layer does not model, as parsed SNBT. */
    Map<String, Object> other = new LinkedHashMap<>();

    protected VItemMeta() {}

    /** An empty meta of the right kind for an item type. */
    public static VItemMeta create(Material type) {
        Class<?>[] extra = kind(type);
        try {
            VItemMeta m = type == Material.PLAYER_HEAD
                    ? Impl.of(VSkullMeta.class).getDeclaredConstructor().newInstance()
                    : Impl.of(VItemMeta.class, extra).getDeclaredConstructor().newInstance();
            m.type = type;
            return m;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Class<?>[] kind(Material type) {
        String n = type.name();
        Class<?> k;
        if (n.endsWith("_SPAWN_EGG")) {
            k = org.bukkit.inventory.meta.SpawnEggMeta.class;
        } else if (n.startsWith("LEATHER_") || n.equals("WOLF_ARMOR")) {
            k = org.bukkit.inventory.meta.LeatherArmorMeta.class;
        } else if (n.endsWith("_BANNER") && !n.contains("WALL")) {
            k = org.bukkit.inventory.meta.BannerMeta.class;
        } else if (n.endsWith("SHULKER_BOX")) {
            k = org.bukkit.inventory.meta.BlockStateMeta.class;
        } else {
            k = switch (n) {
                case "PLAYER_HEAD" -> org.bukkit.inventory.meta.SkullMeta.class;
                case "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW" -> org.bukkit.inventory.meta.PotionMeta.class;
                case "ENCHANTED_BOOK" -> org.bukkit.inventory.meta.EnchantmentStorageMeta.class;
                case "WRITTEN_BOOK", "WRITABLE_BOOK" -> org.bukkit.inventory.meta.BookMeta.class;
                case "FIREWORK_ROCKET" -> org.bukkit.inventory.meta.FireworkMeta.class;
                case "FIREWORK_STAR" -> org.bukkit.inventory.meta.FireworkEffectMeta.class;
                case "FILLED_MAP" -> org.bukkit.inventory.meta.MapMeta.class;
                case "COMPASS" -> org.bukkit.inventory.meta.CompassMeta.class;
                case "CROSSBOW" -> org.bukkit.inventory.meta.CrossbowMeta.class;
                case "BUNDLE" -> org.bukkit.inventory.meta.BundleMeta.class;
                case "SUSPICIOUS_STEW" -> org.bukkit.inventory.meta.SuspiciousStewMeta.class;
                case "ARMOR_STAND" -> org.bukkit.inventory.meta.ItemMeta.class;
                default -> null;
            };
        }
        return k == null || k == ItemMeta.class ? new Class<?>[0] : new Class<?>[] {k};
    }

    // ----- Components

    /** Reads the components of an item (parsed SNBT compound). */
    @SuppressWarnings("unchecked")
    static VItemMeta fromComponents(Material type, Map<String, Object> components) {
        VItemMeta m = create(type);
        for (var e : components.entrySet()) {
            String k = e.getKey().startsWith("minecraft:") || e.getKey().startsWith("!") ? e.getKey() : "minecraft:" + e.getKey();
            Object v = e.getValue();
            switch (k) {
                case CUSTOM_NAME -> m.displayName = text(v);
                case ITEM_NAME -> m.itemName = text(v);
                case LORE -> {
                    m.lore = new ArrayList<>();
                    if (v instanceof List<?> l) {
                        l.forEach(x -> m.lore.add(text(x)));
                    }
                }
                case ENCHANTMENTS -> {
                    Object levels = v instanceof Map<?, ?> map && map.get("levels") instanceof Map<?, ?> l ? l : v;
                    if (levels instanceof Map<?, ?> map) {
                        map.forEach((id, lvl) -> {
                            NamespacedKey key = NamespacedKey.fromString(id.toString());
                            Enchantment ench = key == null ? null : Registry.ENCHANTMENT.get(key);
                            if (ench != null && lvl instanceof Number n) {
                                m.enchants.put(ench, n.intValue());
                            }
                        });
                    }
                }
                case UNBREAKABLE -> m.unbreakable = true;
                case MODEL_DATA -> {
                    if (v instanceof Map<?, ?> map && map.get("floats") instanceof List<?> f && !f.isEmpty()
                            && f.getFirst() instanceof Number n) {
                        m.modelData = n.intValue();
                    } else {
                        m.other.put(k, v);
                    }
                }
                case CUSTOM_DATA -> {
                    if (v instanceof Map<?, ?> map) {
                        Map<String, Object> cd = new LinkedHashMap<>((Map<String, Object>) map);
                        if (cd.remove(BUKKIT_VALUES) instanceof Map<?, ?> pdc) {
                            m.data = VPersistentData.fromNbt((Map<String, Object>) pdc);
                        }
                        m.customData = cd;
                    }
                }
                case DAMAGE -> m.damage = v instanceof Number n ? n.intValue() : null;
                case MAX_DAMAGE -> m.maxDamage = v instanceof Number n ? n.intValue() : null;
                case REPAIR_COST -> m.repairCost = v instanceof Number n ? n.intValue() : null;
                case GLINT -> m.glint = v instanceof Boolean b ? b : v instanceof Number n ? n.intValue() != 0 : null;
                case MAX_STACK -> m.maxStack = v instanceof Number n ? n.intValue() : null;
                case TOOLTIP -> {
                    if (v instanceof Map<?, ?> map) {
                        Object hide = map.get("hide_tooltip");
                        m.hideTooltip = Boolean.TRUE.equals(hide) || (hide instanceof Number n && n.intValue() != 0);
                        if (map.get("hidden_components") instanceof List<?> hidden) {
                            for (Object h : hidden) {
                                ItemFlag f = flagOf(h.toString());
                                if (f != null) {
                                    m.flags.add(f);
                                }
                            }
                        }
                    }
                }
                default -> m.other.put(k, v);
            }
        }
        return m;
    }

    private static ItemFlag flagOf(String component) {
        return switch (component.replace("minecraft:", "")) {
            case "enchantments" -> ItemFlag.HIDE_ENCHANTS;
            case "attribute_modifiers" -> ItemFlag.HIDE_ATTRIBUTES;
            case "unbreakable" -> ItemFlag.HIDE_UNBREAKABLE;
            case "can_break" -> ItemFlag.HIDE_DESTROYS;
            case "can_place_on" -> ItemFlag.HIDE_PLACED_ON;
            case "dyed_color" -> ItemFlag.HIDE_DYE;
            case "trim" -> ItemFlag.HIDE_ARMOR_TRIM;
            case "stored_enchantments" -> ItemFlag.HIDE_STORED_ENCHANTS;
            default -> null;
        };
    }

    private static List<String> hiddenOf(ItemFlag f) {
        return switch (f.name()) {
            case "HIDE_ENCHANTS" -> List.of("minecraft:enchantments");
            case "HIDE_ATTRIBUTES" -> List.of("minecraft:attribute_modifiers");
            case "HIDE_UNBREAKABLE" -> List.of("minecraft:unbreakable");
            case "HIDE_DESTROYS" -> List.of("minecraft:can_break");
            case "HIDE_PLACED_ON" -> List.of("minecraft:can_place_on");
            case "HIDE_DYE" -> List.of("minecraft:dyed_color");
            case "HIDE_ARMOR_TRIM" -> List.of("minecraft:trim");
            case "HIDE_STORED_ENCHANTS" -> List.of("minecraft:stored_enchantments");
            case "HIDE_ADDITIONAL_TOOLTIP" -> List.of("minecraft:potion_contents", "minecraft:written_book_content",
                    "minecraft:firework_explosion", "minecraft:fireworks", "minecraft:banner_patterns", "minecraft:jukebox_playable");
            default -> List.of();
        };
    }

    static Component text(Object snbt) {
        if (snbt instanceof String s) {
            return Component.text(s);
        }
        try {
            return GsonComponentSerializer.gson().deserializeFromTree(Snbt.json(snbt));
        } catch (RuntimeException e) {
            return Component.text(String.valueOf(snbt));
        }
    }

    private static Object snbtText(Component c) {
        return Snbt.parse(GsonComponentSerializer.gson().serialize(c));
    }

    /** The components this meta sets, as a parsed SNBT compound. */
    Map<String, Object> toComponents() {
        Map<String, Object> out = new LinkedHashMap<>(other);
        if (displayName != null) {
            out.put(CUSTOM_NAME, snbtText(displayName));
        }
        if (itemName != null) {
            out.put(ITEM_NAME, snbtText(itemName));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Object> l = new ArrayList<>();
            lore.forEach(c -> l.add(snbtText(c)));
            out.put(LORE, l);
        }
        if (!enchants.isEmpty()) {
            Map<String, Object> e = new LinkedHashMap<>();
            enchants.forEach((k, v) -> e.put(k.getKey().toString(), v));
            out.put(ENCHANTMENTS, e);
        }
        if (unbreakable) {
            out.put(UNBREAKABLE, new LinkedHashMap<>());
        }
        if (modelData != null) {
            out.put(MODEL_DATA, new LinkedHashMap<>(Map.of("floats", List.of((float) (int) modelData))));
        }
        if (!data.isEmpty() || !customData.isEmpty()) {
            Map<String, Object> cd = new LinkedHashMap<>(customData);
            if (!data.isEmpty()) {
                cd.put(BUKKIT_VALUES, data.toNbt());
            }
            out.put(CUSTOM_DATA, cd);
        }
        if (damage != null && damage > 0) {
            out.put(DAMAGE, damage);
        }
        if (maxDamage != null) {
            out.put(MAX_DAMAGE, maxDamage);
        }
        if (repairCost != null) {
            out.put(REPAIR_COST, repairCost);
        }
        if (glint != null) {
            out.put(GLINT, glint);
        }
        if (maxStack != null) {
            out.put(MAX_STACK, maxStack);
        }
        if (hideTooltip || !flags.isEmpty()) {
            Map<String, Object> t = new LinkedHashMap<>();
            if (hideTooltip) {
                t.put("hide_tooltip", true);
            }
            List<Object> hidden = new ArrayList<>();
            flags.forEach(f -> hidden.addAll(hiddenOf(f)));
            if (!hidden.isEmpty()) {
                t.put("hidden_components", hidden);
            }
            out.put(TOOLTIP, t);
        }
        return out;
    }

    // ----- Names and lore

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Override
    public boolean hasDisplayName() {
        return displayName != null;
    }

    @Override
    public Component displayName() {
        return displayName;
    }

    @Override
    public void displayName(Component name) {
        displayName = name;
    }

    @Override
    public String getDisplayName() {
        return displayName == null ? "" : LEGACY.serialize(displayName);
    }

    @Override
    public void setDisplayName(String name) {
        displayName = name == null ? null : LEGACY.deserialize(name);
    }

    @Override
    public boolean hasItemName() {
        return itemName != null;
    }

    @Override
    public Component itemName() {
        return itemName;
    }

    @Override
    public void itemName(Component name) {
        itemName = name;
    }

    @Override
    public String getItemName() {
        return itemName == null ? "" : LEGACY.serialize(itemName);
    }

    @Override
    public void setItemName(String name) {
        itemName = name == null ? null : LEGACY.deserialize(name);
    }

    @Override
    public boolean hasLore() {
        return lore != null && !lore.isEmpty();
    }

    @Override
    public List<Component> lore() {
        return lore == null ? null : new ArrayList<>(lore);
    }

    @Override
    public void lore(List<? extends Component> lines) {
        lore = lines == null ? null : new ArrayList<>(lines);
    }

    @Override
    public List<String> getLore() {
        if (lore == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        lore.forEach(c -> out.add(LEGACY.serialize(c)));
        return out;
    }

    @Override
    public void setLore(List<String> lines) {
        if (lines == null) {
            lore = null;
            return;
        }
        lore = new ArrayList<>();
        lines.forEach(l -> lore.add(LEGACY.deserialize(l)));
    }

    // ----- Enchantments and flags

    @Override
    public boolean hasEnchants() {
        return !enchants.isEmpty();
    }

    @Override
    public boolean hasEnchant(Enchantment ench) {
        return enchants.containsKey(ench);
    }

    @Override
    public int getEnchantLevel(Enchantment ench) {
        return enchants.getOrDefault(ench, 0);
    }

    @Override
    public Map<Enchantment, Integer> getEnchants() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(enchants));
    }

    @Override
    public boolean addEnchant(Enchantment ench, int level, boolean ignoreLevelRestriction) {
        if (!ignoreLevelRestriction && (level < ench.getStartLevel() || level > ench.getMaxLevel())) {
            return false;
        }
        Integer old = enchants.put(ench, level);
        return old == null || old != level;
    }

    @Override
    public boolean removeEnchant(Enchantment ench) {
        return enchants.remove(ench) != null;
    }

    @Override
    public void removeEnchantments() {
        enchants.clear();
    }

    @Override
    public boolean hasConflictingEnchant(Enchantment ench) {
        for (Enchantment e : enchants.keySet()) {
            if (!e.equals(ench) && e.conflictsWith(ench)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void addItemFlags(ItemFlag... itemFlags) {
        flags.addAll(List.of(itemFlags));
    }

    @Override
    public void removeItemFlags(ItemFlag... itemFlags) {
        List.of(itemFlags).forEach(flags::remove);
    }

    @Override
    public Set<ItemFlag> getItemFlags() {
        return Set.copyOf(flags);
    }

    @Override
    public boolean hasItemFlag(ItemFlag flag) {
        return flags.contains(flag);
    }

    @Override
    public boolean isHideTooltip() {
        return hideTooltip;
    }

    @Override
    public void setHideTooltip(boolean hide) {
        hideTooltip = hide;
    }

    @Override
    public boolean hasEnchantmentGlintOverride() {
        return glint != null;
    }

    @Override
    public Boolean getEnchantmentGlintOverride() {
        if (glint == null) {
            throw new IllegalStateException("no glint override");
        }
        return glint;
    }

    @Override
    public void setEnchantmentGlintOverride(Boolean override) {
        glint = override;
    }

    // ----- Other properties

    @Override
    public boolean isUnbreakable() {
        return unbreakable;
    }

    @Override
    public void setUnbreakable(boolean unbreakable) {
        this.unbreakable = unbreakable;
    }

    @Override
    public boolean hasCustomModelData() {
        return modelData != null;
    }

    @Override
    public int getCustomModelData() {
        if (modelData == null) {
            throw new IllegalStateException("no custom model data");
        }
        return modelData;
    }

    @Override
    public void setCustomModelData(Integer data) {
        modelData = data;
    }

    @Override
    public boolean hasMaxStackSize() {
        return maxStack != null;
    }

    @Override
    public int getMaxStackSize() {
        if (maxStack == null) {
            throw new IllegalStateException("no max stack size");
        }
        return maxStack;
    }

    @Override
    public void setMaxStackSize(Integer max) {
        maxStack = max;
    }

    @Override
    public PersistentDataContainer getPersistentDataContainer() {
        return data;
    }

    @Override
    public boolean hasAttributeModifiers() {
        return other.containsKey("minecraft:attribute_modifiers");
    }

    @Override
    public boolean hasDamage() {
        return damage != null && damage > 0;
    }

    @Override
    public int getDamage() {
        return damage == null ? 0 : damage;
    }

    @Override
    public void setDamage(int damage) {
        this.damage = damage;
    }

    @Override
    public boolean hasDamageValue() {
        return damage != null;
    }

    @Override
    public void resetDamage() {
        damage = null;
    }

    @Override
    public boolean hasMaxDamage() {
        return maxDamage != null;
    }

    @Override
    public int getMaxDamage() {
        if (maxDamage == null) {
            throw new IllegalStateException("no max damage");
        }
        return maxDamage;
    }

    @Override
    public void setMaxDamage(Integer maxDamage) {
        this.maxDamage = maxDamage;
    }

    @Override
    public boolean hasRepairCost() {
        return repairCost != null;
    }

    @Override
    public int getRepairCost() {
        return repairCost == null ? 0 : repairCost;
    }

    @Override
    public void setRepairCost(int cost) {
        repairCost = cost;
    }

    @Override
    public String getAsString() {
        return Snbt.write(toComponents());
    }

    @Override
    public String getAsComponentString() {
        return getAsString();
    }

    @Override
    public Map<String, Object> serialize() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta-type", "UNSPECIFIC");
        out.put("components", getAsString());
        return out;
    }

    @Override
    public VItemMeta clone() {
        VItemMeta m = create(type);
        m.copyFrom(this);
        return m;
    }

    void copyFrom(VItemMeta o) {
        displayName = o.displayName;
        itemName = o.itemName;
        lore = o.lore == null ? null : new ArrayList<>(o.lore);
        enchants.clear();
        enchants.putAll(o.enchants);
        flags.clear();
        flags.addAll(o.flags);
        unbreakable = o.unbreakable;
        modelData = o.modelData;
        damage = o.damage;
        maxDamage = o.maxDamage;
        repairCost = o.repairCost;
        glint = o.glint;
        maxStack = o.maxStack;
        hideTooltip = o.hideTooltip;
        data = o.data.copy();
        customData = new LinkedHashMap<>(o.customData);
        other = new LinkedHashMap<>(o.other);
    }

    /** True when it sets no component. */
    boolean isEmpty() {
        return toComponents().isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof VItemMeta m && Snbt.write(toComponents()).equals(Snbt.write(m.toComponents()));
    }

    @Override
    public int hashCode() {
        return Snbt.write(toComponents()).hashCode();
    }

    @Override
    public String toString() {
        return "VanilliteItemMeta{" + type.name().toLowerCase(Locale.ROOT) + " " + getAsString() + "}";
    }
}
