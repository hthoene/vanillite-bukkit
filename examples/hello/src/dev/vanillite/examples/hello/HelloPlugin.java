// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.examples.hello;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Greets joining players, comments broken blocks, protects bedrock and answers /hellob. */
public final class HelloPlugin extends JavaPlugin implements Listener {
    private int greeted;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Hello from a Bukkit plugin on " + getServer().getName() + " " + getServer().getVersion()
                + ", worlds " + getServer().getWorlds().stream().map(w -> w.getName()).toList());
    }

    @Override
    public void onDisable() {
        getLogger().info("Greeted " + greeted + " player(s), bye.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        greeted++;
        getLogger().info("Greeting " + event.getPlayer().getName());
        event.getPlayer().sendMessage(ChatColor.GOLD + "Welcome, " + event.getPlayer().getName() + "! (HelloBukkit)");
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        getLogger().info(event.getPlayer().getName() + " left");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType().name().equals("BEDROCK")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "HelloBukkit protects bedrock.");
            return;
        }
        event.getPlayer().sendMessage("You broke " + event.getBlock().getType() + " at "
                + event.getBlock().getX() + " " + event.getBlock().getY() + " " + event.getBlock().getZ());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(ChatColor.GREEN + "Hello, " + sender.getName() + "!");
        getServer().getScheduler().runTaskLater(this, () -> sender.sendMessage("...and hello again, one second later."), 20);
        return true;
    }
}
