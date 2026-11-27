// SPDX-License-Identifier: Apache-2.0
package dev.vanillite.jvm;

import dev.vanillite.jvm.abi.Abi;
import java.io.File;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Entry point called by the native library once the JVM is up. */
public final class Boot {
    private Boot() {}

    private static JvmPlugin plugin;

    /**
     * Called through JNI by the native library.
     *
     * @param host the host's function table ({@code HostApi})
     * @param id the plugin id
     * @param calls the native call table: enable and disable (written here), then two
     *     functions for measurements
     * @return a status of {@link Abi.status}
     */
    public static int load(long host, int id, long calls) {
        try {
            Native.init(host, id, calls);
            MemorySegment table = MemorySegment.ofAddress(calls).reinterpret(4 * 8);
            var lookup = MethodHandles.lookup();
            var type = MethodType.methodType(int.class, MemorySegment.class);
            table.set(ADDRESS, 0, Native.upcall(lookup.findStatic(Boot.class, "enable", type), Abi.Fn.LifecycleFn));
            table.set(ADDRESS, 8, Native.upcall(lookup.findStatic(Boot.class, "disable", type), Abi.Fn.LifecycleFn));
            Native.noop = table.get(JAVA_LONG, 16);
            Native.upcallLoop = table.get(JAVA_LONG, 24);
            // The expanded class path, e.g. for the training run of an AOT cache.
            java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("vanillite.jvm.dir"), "class-path.txt"),
                    System.getProperty("java.class.path"));
            String jar = System.getProperty("vanillite.jvm.main-jar");
            String main = System.getProperty("vanillite.jvm.main-class");
            ClassLoader loader = new URLClassLoader(
                    "vanillite-jvm-main", new URL[] {new File(jar).toURI().toURL()}, Boot.class.getClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
            plugin = (JvmPlugin) Class.forName(main, true, loader).getConstructor().newInstance();
            plugin.load();
            return Abi.status.OK;
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "vanillite-jvm: the Java plugin failed to load: " + Native.trace(t));
            return Abi.status.ERROR;
        }
    }

    private static int enable(MemorySegment ctx) {
        try {
            return plugin.enable(ctx);
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "enable failed: " + Native.trace(t));
            return Abi.status.ERROR;
        }
    }

    private static int disable(MemorySegment ctx) {
        try {
            return plugin.disable(ctx);
        } catch (Throwable t) {
            Native.log(Abi.log_level.ERROR, "disable failed: " + Native.trace(t));
            return Abi.status.ERROR;
        }
    }
}
