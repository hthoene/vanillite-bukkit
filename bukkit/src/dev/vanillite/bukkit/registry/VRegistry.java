// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.registry;

import com.google.common.collect.Iterators;
import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockType;
import org.bukkit.inventory.ItemType;

/**
 * A registry of the server, by name (e.g. {@code minecraft:enchantment}). Entries are created on
 * first use: block and item types with their Vanilla facts, enum constants by key, and for every
 * other type a keyed stand-in whose other methods throw {@link UnsupportedOperationException}.
 */
final class VRegistry<T extends Keyed> implements Registry<T> {
    private final RegistryKey<T> key;
    private final Class<T> type;
    private final Function<NamespacedKey, T> factory;
    private final Map<NamespacedKey, T> entries = new ConcurrentHashMap<>();
    private final Map<T, NamespacedKey> keys = new ConcurrentHashMap<>();
    /** Entry names of the server's registry; null when the server does not know it. */
    private volatile Set<NamespacedKey> names;

    VRegistry(RegistryKey<T> key, Class<T> type) {
        this.key = key;
        this.type = type;
        this.factory = factory(key, type);
    }

    @SuppressWarnings("unchecked")
    private static <T> Function<NamespacedKey, T> factory(RegistryKey<T> registry, Class<T> type) {
        if (type == BlockType.class) {
            return k -> (T) VBlockType.create(k);
        }
        if (type == ItemType.class) {
            return k -> (T) VItemType.create(k);
        }
        if (type.isEnum()) {
            return k -> {
                for (T c : type.getEnumConstants()) {
                    if (c instanceof Keyed keyed && k.equals(keyOf(keyed))) {
                        return c;
                    }
                }
                return null;
            };
        }
        String kind = registry.key().value();
        String prefix = kind.substring(kind.lastIndexOf('/') + 1);
        if (type.isInterface()) {
            Class<?>[][] faces = {standInInterfaces(type)};
            return k -> {
                // The type's own static constants read this registry: initialise it first, so a
                // proxy is never made while its class is half initialised.
                initialize(type);
                try {
                    return (T) Proxy.newProxyInstance(type.getClassLoader(), faces[0], new KeyedStandIn(k, type, prefix));
                } catch (RuntimeException | Error e) {
                    if (faces[0].length == 1) {
                        throw e;
                    }
                    // A sub-interface the proxy cannot implement: fall back to the type alone.
                    faces[0] = new Class<?>[] {type};
                    return (T) Proxy.newProxyInstance(type.getClassLoader(), faces[0], new KeyedStandIn(k, type, prefix));
                }
            };
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            return k -> (T) KeyedClasses.create(type, k, prefix);
        }
        return k -> null;
    }

    private static void initialize(Class<?> type) {
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The interfaces a stand-in implements: the type and its nested sub-interfaces (e.g.
     * {@code MenuType.Typed}), because API constants cast registry entries to those.
     */
    private static Class<?>[] standInInterfaces(Class<?> type) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        out.add(type);
        for (Class<?> c : type.getClasses()) {
            if (c.isInterface() && type.isAssignableFrom(c)) {
                out.add(c);
            }
        }
        return out.toArray(Class<?>[]::new);
    }

    private static NamespacedKey keyOf(Keyed keyed) {
        try {
            return keyed.getKey();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Set<NamespacedKey> names() {
        Set<NamespacedKey> n = names;
        if (n == null) {
            synchronized (this) {
                n = names;
                if (n == null) {
                    n = load();
                    names = n;
                }
            }
        }
        return n;
    }

    private Set<NamespacedKey> load() {
        var s = Native.scratch();
        if (Host.registry_entries(Native.plugin, s.str(key.key().asString()), Native.sink(s)) != Abi.status.OK) {
            return Set.of();
        }
        Set<NamespacedKey> out = new LinkedHashSet<>();
        for (String line : Native.sunk().split("\n")) {
            NamespacedKey k = line.isEmpty() ? null : NamespacedKey.fromString(line);
            if (k != null) {
                out.add(k);
            }
        }
        return out;
    }

    @Override
    public T get(NamespacedKey k) {
        T v = entries.get(k);
        if (v != null) {
            return v;
        }
        Set<NamespacedKey> known = names();
        if (!known.isEmpty() && !known.contains(k)) {
            return null;
        }
        v = factory.apply(k);
        if (v == null) {
            return null;
        }
        T prev = entries.putIfAbsent(k, v);
        if (prev != null) {
            return prev;
        }
        keys.put(v, k);
        return v;
    }

    @Override
    public NamespacedKey getKey(T value) {
        NamespacedKey k = keys.get(value);
        return k != null ? k : keyOf(value);
    }

    @Override
    public boolean hasTag(TagKey<T> tag) {
        return false;
    }

    @Override
    public Tag<T> getTag(TagKey<T> tag) {
        throw new java.util.NoSuchElementException("Vanillite: registry tags are not available yet (" + tag + ")");
    }

    @Override
    public Collection<Tag<T>> getTags() {
        return List.of();
    }

    @Override
    public Stream<T> stream() {
        return names().stream().map(this::get).filter(java.util.Objects::nonNull);
    }

    @Override
    public Stream<NamespacedKey> keyStream() {
        return names().stream();
    }

    @Override
    public int size() {
        return names().size();
    }

    @Override
    public Iterator<T> iterator() {
        return Iterators.unmodifiableIterator(stream().iterator());
    }

    @Override
    public String toString() {
        return "VanilliteRegistry{" + key.key().asString() + " of " + type.getSimpleName() + "}";
    }
}
