// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import java.io.File;
import java.io.InputStream;
import java.util.UUID;
import java.util.jar.JarFile;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;

/** Checks the layer without a server: every implementation class is generated and usable. */
public final class SelfTest {
    private SelfTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
        System.out.println("ok: " + what);
    }

    public static void main(String[] args) throws Throwable {
        for (Class<?> c : new Class<?>[] {VServer.class, VWorld.class, VBlock.class, VEntity.class, VPlayer.class,
                VConsole.class, VPluginManager.class, VScheduler.class, VBridge.class}) {
            Class<?> impl = Impl.of(c);
            check(c.isAssignableFrom(impl) && !java.lang.reflect.Modifier.isAbstract(impl.getModifiers()),
                    "generated " + impl.getName());
        }
        Layer.server = Impl.create(VServer.class);
        World world = (VWorld) VWorld.NEW.invokeExact(0, "minecraft:overworld");
        check(world.getName().equals("world") && world.getEnvironment() == World.Environment.NORMAL, "world name");
        Block block = world.getBlockAt(1, 2, 3);
        check(block.getRelative(org.bukkit.block.BlockFace.UP).getY() == 3, "block relative");
        Player player = VPlayer.create((VWorld) world, UUID.randomUUID(), "Alex");
        check(player.getName().equals("Alex"), "player name");
        try {
            player.getHealth();
            check(false, "unimplemented method throws");
        } catch (UnsupportedOperationException e) {
            check(e.getMessage().contains("getHealth") && e.getMessage().contains("not implemented"),
                    "unimplemented method throws: " + e.getMessage());
        }
        check(Layer.server.getPluginCommand("none") == null, "no commands");
        if (args.length > 0) {
            for (String name : new String[] {"HelloBukkit.jar", "VBench.jar"}) {
                try (JarFile jar = new JarFile(new File(args[0], name)); InputStream in = jar.getInputStream(jar.getJarEntry("plugin.yml"))) {
                    PluginDescriptionFile d = new PluginDescriptionFile(in);
                    check(!d.getCommands().isEmpty(), "plugin.yml of " + d.getFullName());
                }
            }
        }
        System.out.println("self test passed");
    }
}
