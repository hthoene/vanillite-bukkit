// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;

/** Plugins and event dispatch. */
public abstract class VPluginManager implements PluginManager {
    final Map<String, Plugin> plugins = new LinkedHashMap<>();

    @Override
    public Plugin getPlugin(String name) {
        return plugins.get(name);
    }

    @Override
    public Plugin[] getPlugins() {
        return plugins.values().toArray(Plugin[]::new);
    }

    @Override
    public boolean isPluginEnabled(String name) {
        Plugin p = plugins.get(name);
        return p != null && p.isEnabled();
    }

    @Override
    public boolean isPluginEnabled(Plugin plugin) {
        return plugin != null && plugin.isEnabled();
    }

    @Override
    public void registerEvents(Listener listener, Plugin plugin) {
        for (Method m : listener.getClass().getMethods()) {
            register(listener, plugin, m);
        }
        for (Method m : listener.getClass().getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                register(listener, plugin, m);
            }
        }
    }

    private void register(Listener listener, Plugin plugin, Method m) {
        EventHandler handler = m.getAnnotation(EventHandler.class);
        if (handler == null || m.getParameterCount() != 1 || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
            return;
        }
        Class<? extends Event> type = m.getParameterTypes()[0].asSubclass(Event.class);
        m.setAccessible(true);
        EventExecutor executor = EventExecutor.create(m, type);
        handlers(type).register(new RegisteredListener(listener, executor, handler.priority(), plugin, handler.ignoreCancelled()));
    }

    @Override
    public void registerEvent(Class<? extends Event> event, Listener listener, org.bukkit.event.EventPriority priority,
            EventExecutor executor, Plugin plugin) {
        registerEvent(event, listener, priority, executor, plugin, false);
    }

    @Override
    public void registerEvent(Class<? extends Event> event, Listener listener, org.bukkit.event.EventPriority priority,
            EventExecutor executor, Plugin plugin, boolean ignoreCancelled) {
        handlers(event).register(new RegisteredListener(listener, executor, priority, plugin, ignoreCancelled));
    }

    /** The handler list of an event class (its static {@code getHandlerList()}). */
    static HandlerList handlers(Class<?> type) {
        for (Class<?> c = type; c != null && Event.class.isAssignableFrom(c); c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("getHandlerList");
                m.setAccessible(true);
                return (HandlerList) m.invoke(null);
            } catch (NoSuchMethodException e) {
                // try the superclass
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException(type.getName() + " has no static getHandlerList()");
    }

    @Override
    public void callEvent(Event event) {
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
            if (!listener.getPlugin().isEnabled()) {
                continue;
            }
            try {
                listener.callEvent(event);
            } catch (Throwable t) {
                Native.log(Abi.log_level.ERROR, "Could not pass event " + event.getEventName() + " to "
                        + listener.getPlugin().getName() + ": " + Native.trace(t));
            }
        }
    }
}
