// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.bukkit.Server;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugins, event dispatch and Bukkit's permission registry, behind the server's
 * {@link SimplePluginManager} (which delegates to it as on Paper). Permission plugins replace the
 * public maps of that manager ({@code permissions}, {@code defaultPerms}, {@code permSubs},
 * {@code defSubs}) by reflection, so every method here reads those fields anew.
 */
public abstract class VPluginManager implements org.bukkit.plugin.PluginManager {
    static final java.lang.invoke.MethodHandle NEW = Impl.constructor(VPluginManager.class, SimplePluginManager.class);

    final Map<String, Plugin> plugins = new LinkedHashMap<>();
    /** The manager plugins see. */
    final SimplePluginManager spm;

    protected VPluginManager(SimplePluginManager spm) {
        this.spm = spm;
    }

    static VPluginManager create(Server server, SimpleCommandMap commandMap) {
        SimplePluginManager spm = new SimplePluginManager(server, commandMap);
        try {
            VPluginManager pm = (VPluginManager) NEW.invokeExact(spm);
            spm.paperPluginManager = pm;
            return pm;
        } catch (Throwable t) {
            throw Native.rethrow(t);
        }
    }

    private static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("Vanillite: PluginManager." + what + " is not implemented yet");
    }

    // ----- Plugins

    @Override
    public void registerInterface(Class<? extends PluginLoader> loader) {
        throw unsupported("registerInterface");
    }

    @Override
    public Plugin[] loadPlugins(File directory) {
        throw unsupported("loadPlugins (plugins are loaded at server start)");
    }

    public Plugin[] loadPlugins(File directory, List<File> extraPluginJars) {
        throw unsupported("loadPlugins (plugins are loaded at server start)");
    }

    @Override
    public Plugin[] loadPlugins(File[] files) {
        throw unsupported("loadPlugins (plugins are loaded at server start)");
    }

    @Override
    public synchronized Plugin loadPlugin(File file) {
        throw unsupported("loadPlugin (plugins are loaded at server start)");
    }

    @Override
    public synchronized Plugin getPlugin(String name) {
        Plugin p = plugins.get(name);
        if (p == null) {
            for (Plugin q : plugins.values()) {
                if (q.getName().equalsIgnoreCase(name) || q.getPluginMeta().getProvidedPlugins().contains(name)) {
                    return q;
                }
            }
        }
        return p;
    }

    @Override
    public synchronized Plugin[] getPlugins() {
        return plugins.values().toArray(Plugin[]::new);
    }

    @Override
    public boolean isPluginEnabled(String name) {
        Plugin p = getPlugin(name);
        return p != null && p.isEnabled();
    }

    @Override
    public boolean isPluginEnabled(Plugin plugin) {
        return plugin != null && plugins.containsValue(plugin) && plugin.isEnabled();
    }

    private static final Method SET_ENABLED;

    static {
        try {
            SET_ENABLED = JavaPlugin.class.getDeclaredMethod("setEnabled", boolean.class);
            SET_ENABLED.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public void enablePlugin(Plugin plugin) {
        if (plugin.isEnabled() || !(plugin instanceof JavaPlugin java)) {
            return;
        }
        try {
            Native.log(Abi.log_level.INFO, "Enabling " + plugin.getPluginMeta().getDisplayName());
            SET_ENABLED.invoke(java, true);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Native.log(Abi.log_level.ERROR, "Error enabling " + plugin.getName() + ": " + Native.trace(e.getCause()));
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "Error enabling " + plugin.getName() + ": " + Native.trace(t));
        }
    }

    @Override
    public void disablePlugin(Plugin plugin) {
        if (!plugin.isEnabled() || !(plugin instanceof JavaPlugin java)) {
            return;
        }
        try {
            SET_ENABLED.invoke(java, false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Native.log(Abi.log_level.ERROR, "Error disabling " + plugin.getName() + ": " + Native.trace(e.getCause()));
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "Error disabling " + plugin.getName() + ": " + Native.trace(t));
        }
        Layer.server.scheduler.cancelTasks(plugin);
        Layer.server.services.unregisterAll(plugin);
        HandlerList.unregisterAll(plugin);
        Layer.server.messenger.unregisterIncomingPluginChannel(plugin);
        Layer.server.messenger.unregisterOutgoingPluginChannel(plugin);
    }

    @Override
    public void disablePlugins() {
        List<Plugin> list = new java.util.ArrayList<>(plugins.values());
        Collections.reverse(list);
        for (Plugin p : list) {
            disablePlugin(p);
        }
    }

    @Override
    public void clearPlugins() {
        disablePlugins();
        plugins.clear();
        HandlerList.unregisterAll();
        clearPermissions();
    }

    @Override
    public boolean isTransitiveDependency(PluginMeta plugin, PluginMeta depend) {
        return isTransitiveDepend(plugin, depend, new HashSet<>());
    }

    private boolean isTransitiveDepend(PluginMeta plugin, PluginMeta depend, Set<String> seen) {
        if (!seen.add(plugin.getName())) {
            return false;
        }
        List<String> direct = new java.util.ArrayList<>(plugin.getPluginDependencies());
        direct.addAll(plugin.getPluginSoftDependencies());
        for (String name : direct) {
            if (name.equals(depend.getName()) || depend.getProvidedPlugins().contains(name)) {
                return true;
            }
            Plugin p = getPlugin(name);
            if (p != null && isTransitiveDepend(p.getPluginMeta(), depend, seen)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean useTimings() {
        return false;
    }

    // ----- Events

    @Override
    public void registerEvents(Listener listener, Plugin plugin) {
        if (!plugin.isEnabled()) {
            throw new org.bukkit.plugin.IllegalPluginAccessException("Plugin attempted to register " + listener + " while not enabled");
        }
        Set<Method> methods = new HashSet<>(List.of(listener.getClass().getMethods()));
        methods.addAll(List.of(listener.getClass().getDeclaredMethods()));
        for (Method m : methods) {
            EventHandler handler = m.getAnnotation(EventHandler.class);
            if (handler == null || m.isBridge() || m.isSynthetic() || m.getParameterCount() != 1
                    || !Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                continue;
            }
            Class<? extends Event> type = m.getParameterTypes()[0].asSubclass(Event.class);
            m.setAccessible(true);
            EventExecutor executor = EventExecutor.create(m, type);
            handlers(type).register(new RegisteredListener(listener, executor, handler.priority(), plugin, handler.ignoreCancelled()));
            Events.listened(type);
        }
    }

    @Override
    public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority,
            EventExecutor executor, Plugin plugin) {
        registerEvent(event, listener, priority, executor, plugin, false);
    }

    @Override
    public void registerEvent(Class<? extends Event> event, Listener listener, EventPriority priority,
            EventExecutor executor, Plugin plugin, boolean ignoreCancelled) {
        if (!plugin.isEnabled()) {
            throw new org.bukkit.plugin.IllegalPluginAccessException("Plugin attempted to register " + event + " while not enabled");
        }
        handlers(event).register(new RegisteredListener(listener, executor, priority, plugin, ignoreCancelled));
        Events.listened(event);
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
        throw new org.bukkit.plugin.IllegalPluginAccessException(type.getName() + " has no static getHandlerList()");
    }

    @Override
    public void callEvent(Event event) {
        call(event, null);
    }

    /** Calls the listeners of one priority ({@code null}: all) of an event. */
    void call(Event event, EventPriority only) {
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
            if (only != null && listener.getPriority() != only) {
                continue;
            }
            if (!listener.getPlugin().isEnabled()) {
                continue;
            }
            try {
                listener.callEvent(event);
            } catch (Throwable t) {
                Native.log(Abi.log_level.ERROR, "Could not pass event " + event.getEventName() + " to "
                        + listener.getPlugin().getPluginMeta().getDisplayName() + ": " + Native.trace(t));
            }
        }
    }

    // ----- Permissions (Bukkit's registry semantics, on the public maps)

    @Override
    public Permission getPermission(String name) {
        return spm.permissions.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public void addPermission(Permission perm) {
        addPermission(perm, true);
    }

    void addPermission(Permission perm, boolean dirty) {
        String name = perm.getName().toLowerCase(Locale.ROOT);
        if (spm.permissions.containsKey(name)) {
            throw new IllegalArgumentException("The permission " + name + " is already defined!");
        }
        spm.permissions.put(name, perm);
        calculateDefault(perm, dirty);
    }

    @Override
    public void addPermissions(List<Permission> perms) {
        for (Permission p : perms) {
            addPermission(p, false);
        }
        dirtyPermissibles();
    }

    @Override
    public Set<Permission> getDefaultPermissions(boolean op) {
        return Set.copyOf(spm.defaultPerms.get(op));
    }

    @Override
    public void removePermission(Permission perm) {
        removePermission(perm.getName());
    }

    @Override
    public void removePermission(String name) {
        Permission removed = spm.permissions.remove(name.toLowerCase(Locale.ROOT));
        if (removed != null) {
            spm.defaultPerms.get(true).remove(removed);
            spm.defaultPerms.get(false).remove(removed);
        }
    }

    @Override
    public void recalculatePermissionDefaults(Permission perm) {
        if (perm != null && spm.permissions.containsKey(perm.getName().toLowerCase(Locale.ROOT))) {
            spm.defaultPerms.get(true).remove(perm);
            spm.defaultPerms.get(false).remove(perm);
            calculateDefault(perm, true);
        }
    }

    private void calculateDefault(Permission perm, boolean dirty) {
        PermissionDefault def = perm.getDefault();
        if (def == PermissionDefault.OP || def == PermissionDefault.TRUE) {
            spm.defaultPerms.get(true).add(perm);
            if (dirty) {
                dirty(true);
            }
        }
        if (def == PermissionDefault.NOT_OP || def == PermissionDefault.TRUE) {
            spm.defaultPerms.get(false).add(perm);
            if (dirty) {
                dirty(false);
            }
        }
    }

    void dirtyPermissibles() {
        dirty(true);
        dirty(false);
    }

    private void dirty(boolean op) {
        for (Permissible p : getDefaultPermSubscriptions(op)) {
            p.recalculatePermissions();
        }
    }

    @Override
    public void subscribeToPermission(String permission, Permissible permissible) {
        spm.permSubs.computeIfAbsent(permission.toLowerCase(Locale.ROOT), k -> new WeakHashMap<>()).put(permissible, true);
    }

    @Override
    public void unsubscribeFromPermission(String permission, Permissible permissible) {
        String name = permission.toLowerCase(Locale.ROOT);
        Map<Permissible, Boolean> map = spm.permSubs.get(name);
        if (map != null) {
            map.remove(permissible);
            if (map.isEmpty()) {
                spm.permSubs.remove(name);
            }
        }
    }

    @Override
    public Set<Permissible> getPermissionSubscriptions(String permission) {
        Map<Permissible, Boolean> map = spm.permSubs.get(permission.toLowerCase(Locale.ROOT));
        return map == null ? Set.of() : Set.copyOf(map.keySet());
    }

    @Override
    public void subscribeToDefaultPerms(boolean op, Permissible permissible) {
        spm.defSubs.computeIfAbsent(op, k -> new WeakHashMap<>()).put(permissible, true);
    }

    @Override
    public void unsubscribeFromDefaultPerms(boolean op, Permissible permissible) {
        Map<Permissible, Boolean> map = spm.defSubs.get(op);
        if (map != null) {
            map.remove(permissible);
            if (map.isEmpty()) {
                spm.defSubs.remove(op);
            }
        }
    }

    @Override
    public Set<Permissible> getDefaultPermSubscriptions(boolean op) {
        Map<Permissible, Boolean> map = spm.defSubs.get(op);
        return map == null ? Set.of() : Set.copyOf(map.keySet());
    }

    @Override
    public Set<Permission> getPermissions() {
        return new HashSet<>(spm.permissions.values());
    }

    @Override
    public void clearPermissions() {
        spm.permissions.clear();
        spm.defaultPerms.get(true).clear();
        spm.defaultPerms.get(false).clear();
    }
}
