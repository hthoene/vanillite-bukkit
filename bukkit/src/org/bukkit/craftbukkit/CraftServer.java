// SPDX-License-Identifier: GPL-3.0-only
package org.bukkit.craftbukkit;

import dev.vanillite.bukkit.VServer;

/**
 * Compatibility name of the server object. Plugins find the server implementation's package
 * ({@code org.bukkit.craftbukkit}, unversioned as on current Paper) through this class; it holds
 * no code of its own.
 */
public abstract class CraftServer extends VServer {
    protected CraftServer() {}
}
