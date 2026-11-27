// SPDX-License-Identifier: GPL-3.0-only
package dev.vanillite.bukkit;

import dev.vanillite.jvm.Native;
import dev.vanillite.jvm.abi.Abi;
import dev.vanillite.jvm.abi.Host;
import java.lang.foreign.MemorySegment;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Bukkit's scheduler on Vanillite's: synchronous tasks are host tasks on the owner of the main
 * world (the spike's stand-in for the "main thread"), asynchronous ones run on a JVM pool.
 */
public abstract class VScheduler implements BukkitScheduler {
    private static final MemorySegment RUN = Native.upcall(VScheduler.class, "run", dev.vanillite.jvm.abi.Abi.Fn.TaskFn);
    private static final AtomicInteger IDS = new AtomicInteger();
    static final Map<Integer, Task> TASKS = new ConcurrentHashMap<>();
    private final ExecutorService async = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Vanillite Bukkit async");
        t.setDaemon(true);
        return t;
    });

    /** A scheduled task. */
    static final class Task implements BukkitTask {
        final int id;
        final Plugin plugin;
        final Runnable body;
        final boolean sync;
        final boolean repeating;
        long nativeId;
        volatile boolean cancelled;

        Task(Plugin plugin, Runnable body, boolean sync, boolean repeating) {
            this.id = IDS.incrementAndGet();
            this.plugin = plugin;
            this.body = body;
            this.sync = sync;
            this.repeating = repeating;
        }

        @Override
        public int getTaskId() {
            return id;
        }

        @Override
        public Plugin getOwner() {
            return plugin;
        }

        @Override
        public boolean isSync() {
            return sync;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
            TASKS.remove(id);
            if (sync) {
                Host.cancel_task(Native.plugin, nativeId);
            }
        }
    }

    private static int run(MemorySegment user, MemorySegment ctx) {
        Task task = TASKS.get((int) user.address());
        if (task == null || task.cancelled) {
            return Abi.status.OK;
        }
        MemorySegment previous = Ctx.current;
        Ctx.current = ctx;
        Native.epoch++;
        try {
            task.body.run();
        } catch (Throwable t) {
            Native.log(Abi.log_level.WARN, "Task #" + task.id + " of " + (task.plugin == null ? "the layer" : task.plugin.getName()) + " threw: " + Native.trace(t));
        } finally {
            Ctx.current = previous;
            Native.epoch++;
            if (!task.repeating) {
                TASKS.remove(task.id);
            }
        }
        return Abi.status.OK;
    }

    /** Runs {@code body} in the next tick of a world, on its owner. */
    void onWorld(int world, Runnable body) {
        sync(null, body, 0, 0, world);
    }

    private Task sync(Plugin plugin, Runnable body, long delay, long period) {
        return sync(plugin, body, delay, period, 0);
    }

    private Task sync(Plugin plugin, Runnable body, long delay, long period, int world) {
        Task task = new Task(plugin, body, true, period > 0);
        TASKS.put(task.id, task);
        var s = Native.scratch();
        MemorySegment spec = s.alloc(Abi.TaskSpec.SIZE);
        spec.fill((byte) 0);
        spec.set(JAVA_INT, Abi.TaskSpec.size, (int) Abi.TaskSpec.SIZE);
        spec.set(JAVA_INT, Abi.TaskSpec.owner + Abi.Owner.kind, Abi.owner.WORLD);
        spec.set(JAVA_INT, Abi.TaskSpec.owner + Abi.Owner.world, world);
        spec.set(JAVA_LONG, Abi.TaskSpec.delay, Math.max(delay, 0));
        spec.set(JAVA_LONG, Abi.TaskSpec.period, Math.max(period, 0));
        spec.set(ADDRESS, Abi.TaskSpec.run, RUN);
        spec.set(ADDRESS, Abi.TaskSpec.user, MemorySegment.ofAddress(task.id));
        MemorySegment out = s.alloc(8);
        Ctx.check(Host.schedule(Native.plugin, spec, out), "schedule");
        task.nativeId = out.get(JAVA_LONG, 0);
        return task;
    }

    private Task async(Plugin plugin, Runnable body, long delay, long period) {
        Task task = new Task(plugin, body, false, period > 0);
        TASKS.put(task.id, task);
        async.execute(() -> {
            try {
                Thread.sleep(delay * 50);
                do {
                    if (task.cancelled) {
                        return;
                    }
                    body.run();
                    if (period > 0) {
                        Thread.sleep(period * 50);
                    }
                } while (period > 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (period <= 0) {
                    TASKS.remove(task.id);
                }
            }
        });
        return task;
    }

    @Override
    public BukkitTask runTask(Plugin plugin, Runnable task) {
        return sync(plugin, task, 0, 0);
    }

    @Override
    public BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        return sync(plugin, task, delay, 0);
    }

    @Override
    public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        return sync(plugin, task, delay, period);
    }

    @Override
    public BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        return async(plugin, task, 0, 0);
    }

    @Override
    public BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        return async(plugin, task, delay, 0);
    }

    @Override
    public BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        return async(plugin, task, delay, period);
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
        return sync(plugin, task, 0, 0).id;
    }

    @Override
    public int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        return sync(plugin, task, delay, 0).id;
    }

    @Override
    public int scheduleSyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
        return sync(plugin, task, delay, period).id;
    }

    @Override
    public void cancelTask(int id) {
        Task t = TASKS.get(id);
        if (t != null) {
            t.cancel();
        }
    }

    @Override
    public void cancelTasks(Plugin plugin) {
        for (Task t : TASKS.values()) {
            if (t.plugin == plugin) {
                t.cancel();
            }
        }
    }

    @Override
    public boolean isQueued(int id) {
        return TASKS.containsKey(id);
    }

    @Override
    public boolean isCurrentlyRunning(int id) {
        return false;
    }
}
