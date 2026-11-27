// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.registry;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Keyed;
import org.bukkit.Registry;

/**
 * Paper's {@link RegistryAccess} (found through {@link java.util.ServiceLoader}): one
 * {@link VRegistry} per {@link RegistryKey}, filled from the server's registry of the same name.
 */
public final class VRegistryAccess implements RegistryAccess {
    private static final Map<RegistryKey<?>, Class<?>> TYPES = new HashMap<>();
    private static final Map<Class<?>, RegistryKey<?>> BY_TYPE = new HashMap<>();

    static {
        for (Field f : RegistryKey.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !(f.getGenericType() instanceof ParameterizedType p)) {
                continue;
            }
            Type t = p.getActualTypeArguments()[0];
            Class<?> type = t instanceof Class<?> c ? c : (Class<?>) ((ParameterizedType) t).getRawType();
            try {
                RegistryKey<?> key = (RegistryKey<?>) f.get(null);
                TYPES.put(key, type);
                BY_TYPE.putIfAbsent(type, key);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final Map<RegistryKey<?>, Registry<?>> registries = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings({"unchecked", "removal"})
    public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
        RegistryKey<?> key = BY_TYPE.get(type);
        return key == null ? null : getRegistry((RegistryKey<T>) key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
        return (Registry<T>) registries.computeIfAbsent(key, k -> {
            Class<?> type = TYPES.get(k);
            if (type == null) {
                throw new java.util.NoSuchElementException("No registry " + k);
            }
            return new VRegistry<>((RegistryKey<Keyed>) k, (Class<Keyed>) type);
        });
    }
}
