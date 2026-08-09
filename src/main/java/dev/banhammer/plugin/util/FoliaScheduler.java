package dev.banhammer.plugin.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;

/**
 * Scheduler abstraction for Paper/Folia dual-compatibility.
 * Detects Folia at runtime and delegates to the correct scheduler API.
 *
 * @since 3.1.0
 */
public final class FoliaScheduler {

    /** Written once during startup, read from async tasks - hence volatile. */
    private static volatile boolean folia;

    /** Resolved lazily; {@code ScheduledTask} only exists on Folia. */
    private static volatile java.lang.reflect.Method foliaCancelMethod;

    private FoliaScheduler() {}

    /**
     * Runs a task on the main/global thread, silently skipping it if the plugin is being
     * disabled (Bukkit rejects scheduling for a disabled plugin with an exception, which
     * would otherwise spam the log during shutdown from in-flight database callbacks).
     */
    private static void schedule(Plugin plugin, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Detects whether Folia is present. Must be called once during plugin startup.
     */
    public static void init() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
    }

    /**
     * @return true if running on Folia, false if Paper/Spigot
     */
    public static boolean isFolia() {
        return folia;
    }

    // ========== Global Region Scheduler ==========

    /**
     * Runs a task on the global region thread (Folia) or the main thread (Paper).
     * Use for non-entity, non-location work (events, global state).
     */
    public static void runGlobal(Plugin plugin, Runnable task) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            schedule(plugin, task);
        }
    }

    /**
     * Runs a delayed task on the global region thread (Folia) or the main thread (Paper).
     */
    public static void runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
        } else {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            }
        }
    }

    // ========== Entity Scheduler ==========

    /**
     * Runs a task on the entity's owning region thread (Folia) or the main thread (Paper).
     * Use for entity-specific operations: teleport, kick, openInventory, etc.
     */
    public static void runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        runOnEntity(plugin, entity, task, null);
    }

    /**
     * Runs a task on the entity's owning region thread (Folia) or the main thread (Paper).
     *
     * @param retired run instead of {@code task} if the entity is removed before the task
     *                executes (Folia only); may be {@code null}
     */
    public static void runOnEntity(Plugin plugin, Entity entity, Runnable task, Runnable retired) {
        if (folia) {
            entity.getScheduler().run(plugin, scheduledTask -> task.run(), retired);
        } else {
            schedule(plugin, task);
        }
    }

    /**
     * Runs a delayed task on the entity's owning region thread (Folia) or the main thread (Paper).
     */
    public static void runOnEntityDelayed(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (folia) {
            entity.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, delayTicks);
        } else {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            }
        }
    }

    // ========== Region Scheduler ==========

    /**
     * Runs a task on the region thread owning the given location (Folia) or the main thread (Paper).
     */
    public static void runAtLocation(Plugin plugin, Location location, Runnable task) {
        if (folia) {
            Bukkit.getRegionScheduler().execute(plugin, location, task);
        } else {
            schedule(plugin, task);
        }
    }

    // ========== Async Scheduler ==========

    /**
     * Runs a repeating async task. Returns an opaque task handle (BukkitTask on Paper,
     * ScheduledTask on Folia). Use {@link #cancelTask(Object)} to cancel.
     */
    public static Object runAsyncRepeating(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (folia) {
            long delayMs = Math.max(1, delayTicks * 50);
            long periodMs = Math.max(1, periodTicks * 50);
            return Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                    scheduledTask -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
        } else {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        }
    }

    /**
     * Runs a one-off task off the main thread.
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Cancels a task handle returned by {@link #runAsyncRepeating(Plugin, Runnable, long, long)}.
     *
     * @return true if the task was cancelled
     */
    public static boolean cancelTask(Object taskHandle) {
        if (taskHandle == null) return false;

        if (taskHandle instanceof BukkitTask bukkitTask) {
            if (!bukkitTask.isCancelled()) {
                bukkitTask.cancel();
            }
            return true;
        }

        // Folia ScheduledTask - resolved reflectively to avoid a compile-time dependency.
        try {
            java.lang.reflect.Method cancel = foliaCancelMethod;
            if (cancel == null) {
                cancel = taskHandle.getClass().getMethod("cancel");
                foliaCancelMethod = cancel;
            }
            cancel.invoke(taskHandle);
            return true;
        } catch (Exception e) {
            // A task that cannot be cancelled keeps running until the server stops, so this
            // must not be swallowed silently.
            Bukkit.getLogger().warning("[BanHammer] Failed to cancel scheduled task "
                    + taskHandle.getClass().getName() + ": " + e);
            return false;
        }
    }

    // ========== Player Operations ==========

    /**
     * Teleports a player without blocking the calling thread.
     *
     * <p>Uses Paper's {@code teleportAsync} on both platforms: a synchronous
     * {@code teleport()} into an ungenerated chunk would generate that chunk on the main
     * thread and stall the whole server for the duration.
     *
     * @return a future completing with whether the teleport succeeded
     */
    public static java.util.concurrent.CompletableFuture<Boolean> teleportAsync(
            Plugin plugin, Player player, Location destination) {
        return player.teleportAsync(destination);
    }

    /**
     * Kicks a player with a string reason. Ensures the kick runs on the correct thread.
     */
    public static void kickPlayer(Plugin plugin, Player player, String reason) {
        if (folia) {
            player.getScheduler().run(plugin, scheduledTask -> player.kick(
                    Component.text(reason != null ? reason : "")), null);
        } else {
            player.kick(Component.text(reason != null ? reason : ""));
        }
    }

    /**
     * Kicks a player with a Component reason. Ensures the kick runs on the correct thread.
     */
    public static void kickPlayer(Plugin plugin, Player player, Component reason) {
        if (folia) {
            player.getScheduler().run(plugin, scheduledTask -> player.kick(reason), null);
        } else {
            player.kick(reason);
        }
    }
}
