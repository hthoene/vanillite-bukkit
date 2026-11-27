// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.examples.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.LongUnaryOperator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** {@code /vbench}: per-call costs of common Bukkit API calls (median of 5 rounds after 3 warm-ups). */
public final class BenchPlugin extends JavaPlugin implements Listener {
    /** A custom event with one listener. */
    public static final class BenchEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private static volatile long sink;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onBench(BenchEvent event) {
        sink++;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        getServer().getScheduler().runTask(this, () -> {
            for (String line : run()) {
                sender.sendMessage(line);
                getLogger().info(line);
            }
        });
        return true;
    }

    private static double median(long n, LongUnaryOperator round) {
        double[] r = new double[5];
        for (int i = 0; i < 8; i++) {
            long start = System.nanoTime();
            round.applyAsLong(n);
            long ns = System.nanoTime() - start;
            if (i >= 3) {
                r[i - 3] = (double) ns / n;
            }
        }
        Arrays.sort(r);
        return r[2];
    }

    private List<String> run() {
        List<String> out = new ArrayList<>();
        String server = getServer().getName();
        World world = getServer().getWorlds().get(0);
        Block block = world.getBlockAt(0, 64, 0);
        out.add(server + " vbench: block at 0 64 0 is " + block.getType());
        out.add(String.format("%s vbench Block.getType (same Block): %.1f ns", server, median(1_000_000, n -> {
            long h = 0;
            for (long i = 0; i < n; i++) {
                h += block.getType().ordinal();
            }
            return sink = h;
        })));
        out.add(String.format("%s vbench World.getBlockAt(..).getType: %.1f ns", server, median(1_000_000, n -> {
            long h = 0;
            for (long i = 0; i < n; i++) {
                h += world.getBlockAt((int) (i & 15), 64, (int) ((i >> 4) & 15)).getType().ordinal();
            }
            return sink = h;
        })));
        Block high = world.getBlockAt(0, 300, 0);
        out.add(String.format("%s vbench Block.setType (physics on): %.1f ns", server, median(20_000, n -> {
            for (long i = 0; i < n; i++) {
                high.setType((i & 1) == 0 ? Material.STONE : Material.DIRT);
            }
            return n;
        })));
        high.setType(Material.AIR);
        Location center = new Location(world, 0.5, 65, 0.5);
        Collection<Entity> near = world.getNearbyEntities(center, 8, 8, 8);
        Entity entity = near.stream().filter(e -> !(e instanceof Player)).findFirst().orElse(null);
        out.add(server + " vbench: " + near.size() + " entities near 0 65 0");
        if (entity != null) {
            out.add(String.format("%s vbench Entity.getLocation: %.1f ns", server, median(1_000_000, n -> {
                double h = 0;
                for (long i = 0; i < n; i++) {
                    h += entity.getLocation().getY();
                }
                return sink = (long) h;
            })));
        }
        out.add(String.format("%s vbench World.getNearbyEntities(8): %.1f ns", server, median(100_000, n -> {
            long h = 0;
            for (long i = 0; i < n; i++) {
                h += world.getNearbyEntities(center, 8, 8, 8).size();
            }
            return sink = h;
        })));
        Player player = getServer().getOnlinePlayers().stream().findFirst().orElse(null);
        if (player != null) {
            out.add(String.format("%s vbench Player.sendMessage: %.1f ns", server, median(10_000, n -> {
                for (long i = 0; i < n; i++) {
                    player.sendMessage("vbench " + i);
                }
                return n;
            })));
        }
        out.add(String.format("%s vbench callEvent (1 listener): %.1f ns", server, median(1_000_000, n -> {
            for (long i = 0; i < n; i++) {
                getServer().getPluginManager().callEvent(new BenchEvent());
            }
            return n;
        })));
        return out;
    }
}
