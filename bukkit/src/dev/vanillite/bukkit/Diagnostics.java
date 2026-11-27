// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@code /vjvm bench|mem|gc <seconds>}: measurements of the layer itself (call costs between
 * native code and Java, memory of the JVM, effect of garbage collection).
 */
final class Diagnostics {
    private Diagnostics() {}

    /** An event with one empty listener, for the round-trip measurement. */
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

    private static boolean listening;
    private static volatile int sink;

    static boolean run(CommandSender sender, String[] args) {
        String what = args.length > 0 ? args[0] : "mem";
        switch (what) {
            case "bench" -> server().scheduler.runTask(null, () -> report(sender, bench()));
            case "mem" -> report(sender, memory());
            case "gc" -> gc(sender, args.length > 1 ? Integer.parseInt(args[1]) : 30);
            default -> sender.sendMessage("/vjvm bench | mem | gc <seconds>");
        }
        return true;
    }

    private static VServer server() {
        return Layer.server;
    }

    private static void report(CommandSender sender, List<String> lines) {
        for (String line : lines) {
            sender.sendMessage(line);
            if (sender != server().console) {
                Native.log(Abi.log_level.INFO, line);
            }
        }
    }

    private static int up(int x) {
        return x + 1;
    }

    private static int fire(int x) {
        server().pluginManager.callEvent(new BenchEvent());
        return x + 1;
    }

    /** Median of 5 rounds after 3 warm-up rounds, in ns per call. */
    private static double measure(long n, java.util.function.LongUnaryOperator round) {
        double[] r = new double[5];
        for (int i = 0; i < 8; i++) {
            long ns = round.applyAsLong(n);
            if (i >= 3) {
                r[i - 3] = (double) ns / n;
            }
        }
        Arrays.sort(r);
        return r[2];
    }

    private static long timed(long n, java.util.function.LongConsumer body) {
        long start = System.nanoTime();
        body.accept(n);
        return System.nanoTime() - start;
    }

    static List<String> bench() {
        List<String> out = new ArrayList<>();
        try {
            if (!listening) {
                listening = true;
                BenchEvent.getHandlerList().register(new RegisteredListener(new Listener() {}, (l, e) -> {},
                        EventPriority.NORMAL, null, false) {
                    @Override
                    public org.bukkit.plugin.Plugin getPlugin() {
                        return server().pluginManager.getPlugins().length > 0 ? server().pluginManager.getPlugins()[0] : null;
                    }
                });
            }
            FunctionDescriptor intInt = FunctionDescriptor.of(JAVA_INT, JAVA_INT);
            MemorySegment noop = MemorySegment.ofAddress(Native.noop);
            MethodHandle normal = Native.LINKER.downcallHandle(noop, intInt);
            MethodHandle critical = Native.LINKER.downcallHandle(noop, intInt, Linker.Option.critical(false));
            MethodHandle loop = Native.LINKER.downcallHandle(MemorySegment.ofAddress(Native.upcallLoop),
                    FunctionDescriptor.of(JAVA_LONG, java.lang.foreign.ValueLayout.ADDRESS, JAVA_LONG));
            MemorySegment upStub = Native.upcall(Diagnostics.class, "up", intInt);
            MemorySegment fireStub = Native.upcall(Diagnostics.class, "fire", intInt);

            out.add(String.format("downcall Java->native (no-op), normal: %.1f ns", measure(20_000_000, n -> timed(n, k -> {
                int x = 0;
                try {
                    for (long i = 0; i < k; i++) {
                        x = (int) normal.invokeExact(x);
                    }
                } catch (Throwable t) {
                    throw Native.rethrow(t);
                }
                sink = x;
            }))));
            out.add(String.format("downcall Java->native (no-op), critical: %.1f ns", measure(20_000_000, n -> timed(n, k -> {
                int x = 0;
                try {
                    for (long i = 0; i < k; i++) {
                        x = (int) critical.invokeExact(x);
                    }
                } catch (Throwable t) {
                    throw Native.rethrow(t);
                }
                sink = x;
            }))));
            out.add(String.format("upcall native->Java (empty): %.1f ns", measure(5_000_000, n -> {
                try {
                    return (long) loop.invokeExact(upStub, n);
                } catch (Throwable t) {
                    throw Native.rethrow(t);
                }
            })));
            out.add(String.format("event round trip native->Java + Bukkit event, 1 empty listener: %.1f ns",
                    measure(2_000_000, n -> {
                        try {
                            return (long) loop.invokeExact(fireStub, n);
                        } catch (Throwable t) {
                            throw Native.rethrow(t);
                        }
                    })));
            out.add(String.format("Bukkit callEvent in Java only, 1 empty listener: %.1f ns", measure(5_000_000, n -> timed(n, k -> {
                for (long i = 0; i < k; i++) {
                    server().pluginManager.callEvent(new BenchEvent());
                }
            }))));
            VWorld world = server().world(0);
            VBlock block = (VBlock) world.getBlockAt(0, 64, 0);
            var s = Native.scratch();
            MemorySegment pos = s.alloc(Abi.BlockPos.SIZE);
            pos.set(JAVA_INT, Abi.BlockPos.x, 0);
            pos.set(JAVA_INT, Abi.BlockPos.y, 64);
            pos.set(JAVA_INT, Abi.BlockPos.z, 0);
            MemorySegment id = s.alloc(4);
            int status = Host.block_get_id(Ctx.current, 0, pos, id);
            out.add("block_get_id status " + status + " (0 = loaded, 3 = not loaded)");
            out.add(String.format("host block_get_id, critical: %.1f ns", measure(5_000_000, n -> timed(n, k -> {
                for (long i = 0; i < k; i++) {
                    Host.block_get_id(Ctx.current, 0, pos, id);
                }
            }))));
            out.add(String.format("host block_get_id, with thread transition: %.1f ns", measure(5_000_000, n -> timed(n, k -> {
                for (long i = 0; i < k; i++) {
                    Host.block_get_id_transition(Ctx.current, 0, pos, id);
                }
            }))));
            out.add(String.format("Block.getType (Bukkit, cached Block): %.1f ns", measure(5_000_000, n -> timed(n, k -> {
                int h = 0;
                for (long i = 0; i < k; i++) {
                    h += block.getType().ordinal();
                }
                sink = h;
            }))));
        } catch (Throwable t) {
            out.add("bench failed: " + t);
            Native.log(Abi.log_level.ERROR, Native.trace(t));
        }
        return out;
    }

    static List<String> memory() {
        List<String> out = new ArrayList<>();
        Runtime rt = Runtime.getRuntime();
        out.add(String.format("heap used %.1f MiB, committed %.1f MiB, max %.1f MiB",
                mib(rt.totalMemory() - rt.freeMemory()), mib(rt.totalMemory()), mib(rt.maxMemory())));
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.NON_HEAP) {
                out.add(String.format("%s: used %.1f MiB, committed %.1f MiB", pool.getName(),
                        mib(pool.getUsage().getUsed()), mib(pool.getUsage().getCommitted())));
            }
        }
        out.add("loaded classes " + ManagementFactory.getClassLoadingMXBean().getLoadedClassCount()
                + ", JVM threads " + ManagementFactory.getThreadMXBean().getThreadCount()
                + ", GC " + ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList()
                + ", JVM up " + ManagementFactory.getRuntimeMXBean().getUptime() + " ms");
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS") || line.startsWith("VmHWM") || line.startsWith("Threads")) {
                    out.add("process " + line.replaceAll("\\s+", " "));
                }
            }
        } catch (java.io.IOException e) {
            out.add("no /proc/self/status");
        }
        return out;
    }

    private static double mib(long bytes) {
        return bytes / 1048576.0;
    }

    /** Collects garbage on a JVM thread for some seconds, then reports the pauses. */
    private static void gc(CommandSender sender, int seconds) {
        // A task on every tick, so ticks call into Java while the collector runs.
        var ticker = server().scheduler.runTaskTimer(null, () -> sink++, 1, 1);
        Thread t = new Thread(() -> {
            long before = gcTime();
            long count = gcCount();
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            List<byte[]> keep = new ArrayList<>();
            while (System.nanoTime() < end) {
                for (int i = 0; i < 1000; i++) {
                    keep.add(new byte[1024]);
                }
                if (keep.size() > 20_000) {
                    keep.clear();
                    System.gc();
                }
            }
            ticker.cancel();
            Native.log(Abi.log_level.INFO, "gc stress done: " + (gcCount() - count) + " collections, "
                    + (gcTime() - before) + " ms total pause");
        }, "vjvm-gc-stress");
        t.setDaemon(true);
        t.start();
        sender.sendMessage("gc stress for " + seconds + " s started");
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }
}
