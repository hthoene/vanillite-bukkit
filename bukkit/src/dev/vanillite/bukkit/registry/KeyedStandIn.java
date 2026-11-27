// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit.registry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.bukkit.NamespacedKey;

/**
 * A registry entry of an interface type the layer does not implement yet: it knows its key and
 * translation key; default methods run, every other method throws naming itself.
 */
public final class KeyedStandIn implements InvocationHandler {
    private final NamespacedKey key;
    private final Class<?> type;
    private final String translationKey;

    KeyedStandIn(NamespacedKey key, Class<?> type, String kind) {
        this.key = key;
        this.type = type;
        this.translationKey = translationKey(kind, key);
    }

    /** An entry of an interface type outside any registry (e.g. {@code Biome.CUSTOM}). */
    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> type, NamespacedKey key, String kind) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, new KeyedStandIn(key, type, kind));
    }

    static String translationKey(String kind, NamespacedKey key) {
        return kind + "." + key.getNamespace() + "." + key.getKey().replace('/', '.');
    }

    @Override
    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        switch (m.getName()) {
            case "getKey", "getKeyOrThrow", "key":
                if (m.getParameterCount() == 0) {
                    return key;
                }
                break;
            case "getKeyOrNull":
                return key;
            case "isRegistered":
                return true;
            case "translationKey", "getTranslationKey":
                return translationKey;
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return key.hashCode();
            case "toString":
                return type.getSimpleName() + "{" + key + "}";
            case "name":
                if (m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                    return key.getKey().toUpperCase(java.util.Locale.ROOT).replace('/', '_').replace('.', '_');
                }
                break;
            default:
                break;
        }
        if (m.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, m, args);
        }
        throw new UnsupportedOperationException("Vanillite: " + type.getSimpleName() + "." + m.getName()
                + " is not implemented yet (" + type.getName() + ")");
    }

    static boolean is(Object o) {
        return Proxy.isProxyClass(o.getClass()) && Proxy.getInvocationHandler(o) instanceof KeyedStandIn;
    }
}
