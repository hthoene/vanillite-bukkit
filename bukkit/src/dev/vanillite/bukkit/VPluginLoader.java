// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import org.bukkit.plugin.PluginLoader;

/** The plugin loader object plugins see; loading itself is done by {@link Layer}. */
public abstract class VPluginLoader implements PluginLoader {
    public VPluginLoader() {}
}
