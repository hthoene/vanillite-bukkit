// SPDX-License-Identifier: GPL-3.0-only
package io.papermc.paper.plugin.lifecycle.event.types;

import dev.vanillite.bukkit.PaperCommands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;

/**
 * The API's lifecycle event types on Vanillite (found through {@link java.util.ServiceLoader}).
 * It lives in the API's package because the provider interface is package-private; the layer's
 * class loader also loads the API, so both share the runtime package.
 */
public final class VanilliteLifecycleEventTypes implements LifecycleEventTypeProvider {
    public VanilliteLifecycleEventTypes() {}

    @Override
    public <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Monitorable<O, E> monitor(
            String name, Class<? extends O> owner) {
        return PaperCommands.monitor(name);
    }

    @Override
    public <O extends LifecycleEventOwner, E extends LifecycleEvent> LifecycleEventType.Prioritizable<O, E> prioritized(
            String name, Class<? extends O> owner) {
        return PaperCommands.prioritized(name);
    }

    @Override
    public TagEventTypeProvider tagProvider() {
        return PaperCommands.tagProvider();
    }
}
