// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import io.papermc.paper.InternalAPIBridge;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.handler.configuration.LifecycleEventHandlerConfiguration;
import java.util.function.BooleanSupplier;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** The API's service for server internals (found through {@link java.util.ServiceLoader}). */
public abstract class VBridge implements InternalAPIBridge {
    public VBridge() {}

    @Override
    public LifecycleEventManager<Plugin> createPluginLifecycleEventManager(JavaPlugin plugin, BooleanSupplier valid) {
        return new LifecycleEventManager<>() {
            @Override
            public void registerEventHandler(LifecycleEventHandlerConfiguration<? super Plugin> config) {
                // Lifecycle events (Brigadier command registration, ...) are not supported yet.
            }
        };
    }
}
