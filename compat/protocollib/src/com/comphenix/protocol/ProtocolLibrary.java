// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol;

import dev.vanillite.bukkit.compat.protocollib.VanilliteProtocolManager;
import org.bukkit.plugin.Plugin;

/** The entry point of ProtocolLib's API. */
public class ProtocolLibrary {
    public static final String MINIMUM_MINECRAFT_VERSION = "1.8";
    public static final String MAXIMUM_MINECRAFT_VERSION = "26.3";
    public static final String MINECRAFT_LAST_RELEASE_DATE = "2026-11-01";

    private static Plugin plugin;
    private static volatile boolean updatesDisabled;

    public ProtocolLibrary() {}

    static void init(Plugin owner) {
        plugin = owner;
    }

    public static Plugin getPlugin() {
        return plugin;
    }

    public static ProtocolManager getProtocolManager() {
        return VanilliteProtocolManager.INSTANCE;
    }

    public static void disableUpdates() {
        updatesDisabled = true;
    }

    public static boolean updatesDisabled() {
        return updatesDisabled;
    }
}
