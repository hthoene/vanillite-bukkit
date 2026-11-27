// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.registry;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Fluid;
import org.bukkit.GameEvent;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;

/** A tag of the server's data packs as a Bukkit {@link Tag} (entries resolved on first use). */
public final class VTag<T extends Keyed> implements Tag<T> {
    private static final Map<String, Tag<?>> CACHE = new ConcurrentHashMap<>();

    private final String registry;
    private final NamespacedKey key;
    private final Class<T> type;
    private final Set<T> values;

    private VTag(String registry, NamespacedKey key, Class<T> type, Set<T> values) {
        this.registry = registry;
        this.key = key;
        this.type = type;
        this.values = values;
    }

    /** The server registry of a Bukkit tag registry name, null for an unknown one. */
    private static String registry(String bukkit) {
        return switch (bukkit) {
            case Tag.REGISTRY_BLOCKS -> "minecraft:block";
            case Tag.REGISTRY_ITEMS -> "minecraft:item";
            case Tag.REGISTRY_FLUIDS -> "minecraft:fluid";
            case Tag.REGISTRY_ENTITY_TYPES -> "minecraft:entity_type";
            case Tag.REGISTRY_GAME_EVENTS -> "minecraft:game_event";
            default -> null;
        };
    }

    private static List<String> lines(String registry, String tag) {
        var s = Native.scratch();
        if (Host.registry_tag(Native.plugin, s.str(registry), s.str(tag), Native.sink(s)) != Abi.status.OK) {
            return null;
        }
        String text = Native.sunk();
        return text.isEmpty() ? List.of() : List.of(text.split("\n"));
    }

    /** {@code Server.getTag}: null for an unknown registry or tag. */
    @SuppressWarnings("unchecked")
    public static <T extends Keyed> Tag<T> get(String bukkitRegistry, NamespacedKey key, Class<T> type) {
        String registry = registry(bukkitRegistry);
        if (registry == null) {
            return null;
        }
        String cacheKey = registry + "#" + key + "#" + type.getName();
        Tag<?> cached = CACHE.get(cacheKey);
        if (cached != null) {
            return (Tag<T>) cached;
        }
        List<String> entries = lines(registry, key.toString());
        if (entries == null) {
            return null;
        }
        Set<T> values = new LinkedHashSet<>();
        for (String e : entries) {
            T v = value(e, type);
            if (v != null) {
                values.add(v);
            }
        }
        Tag<T> tag = new VTag<>(registry, key, type, Collections.unmodifiableSet(values));
        CACHE.putIfAbsent(cacheKey, tag);
        return tag;
    }

    /** {@code Server.getTags}: every tag of a registry. */
    public static <T extends Keyed> Iterable<Tag<T>> all(String bukkitRegistry, Class<T> type) {
        String registry = registry(bukkitRegistry);
        List<String> names = registry == null ? null : lines(registry, "");
        List<Tag<T>> out = new ArrayList<>();
        if (names != null) {
            for (String n : names) {
                NamespacedKey k = NamespacedKey.fromString(n);
                Tag<T> tag = k == null ? null : get(bukkitRegistry, k, type);
                if (tag != null) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private static <T> T value(String name, Class<T> type) {
        NamespacedKey k = NamespacedKey.fromString(name);
        if (k == null) {
            return null;
        }
        if (type == Material.class) {
            return (T) Material.getMaterial(k.getKey().toUpperCase(Locale.ROOT));
        }
        if (type == EntityType.class) {
            return (T) Registry.ENTITY_TYPE.get(k);
        }
        if (type == Fluid.class) {
            return (T) Registry.FLUID.get(k);
        }
        if (type == GameEvent.class) {
            return (T) Registry.GAME_EVENT.get(k);
        }
        return null;
    }

    @Override
    public boolean isTagged(T item) {
        return values.contains(item);
    }

    @Override
    public Set<T> getValues() {
        return values;
    }

    @Override
    public NamespacedKey getKey() {
        return key;
    }

    @Override
    public String toString() {
        return "Tag{" + registry + " " + key + " of " + type.getSimpleName() + "}";
    }
}
