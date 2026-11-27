// SPDX-License-Identifier: GPL-3.0-only
package com.comphenix.protocol;

import dev.vanillite.bukkit.compat.protocollib.Packets;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ProtocolLib on Vanillite: the API on the server's own packet interface instead of Minecraft's
 * internals. Listeners of a plugin that is disabled are removed.
 */
public class ProtocolLib extends JavaPlugin implements Listener {
    public ProtocolLib() {}

    @Override
    public void onLoad() {
        ProtocolLibrary.init(this);
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ProtocolLib API " + getPluginMeta().getVersion() + " on Vanillite's packet interface (protocol "
                + Packets.protocolVersion() + ")");
    }

    @Override
    public void onDisable() {
        for (var listener : Packets.listeners()) {
            Packets.remove(listener);
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() != this) {
            Packets.removeAll(event.getPlugin());
        }
    }
}
