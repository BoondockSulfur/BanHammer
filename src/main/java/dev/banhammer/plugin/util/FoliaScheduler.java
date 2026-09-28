package dev.banhammer.plugin.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Scheduler abstraction for Paper/Folia dual-compatibility.
 * Detects Folia at runtime and delegates to the correct scheduler API.
 *
 * @since 3.1.0
 */
public final class FoliaScheduler {

    /** Written once during startup, read from async tasks - hence volatile. */
    private static volatile boolean folia;

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
        if (!plugin.isEnabled()) {
            return;
        }
        if (folia) {
            Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run());
        } else {
            schedule(plugin, task);
        }
    }

    /**
     * Runs a task on the global region thread (Folia) or the main thread (Paper) and returns
     * its result. Runs inline when already on that thread.
     *
     * @return a future that completes with the result, or exceptionally if the task threw or
     *         the plugin is being disabled
     */
    public static <T> CompletableFuture<T> callGlobal(Plugin plugin, Supplier<T> task) {
        if (Bukkit.isGlobalTickThread()) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Plugin is disabled"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        runGlobal(plugin, () -> {
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
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
        if (!plugin.isEnabled()) {
            return;
        }
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
        if (!plugin.isEnabled()) {
            return;
        }
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
        } else {
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

        // ScheduledTask is part of the Paper API, so the call goes through the public interface
        // and works for every Folia implementation class.
        if (taskHandle instanceof ScheduledTask scheduledTask) {
            scheduledTask.cancel();
            return true;
        }

        Bukkit.getLogger().warning("[BanHammer] Failed to cancel scheduled task of unknown type "
                + taskHandle.getClass().getName());
        return false;
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
        kickPlayer(plugin, player, Component.text(reason != null ? reason : ""));
    }

    /**
     * Kicks a player with a Component reason. Ensures the kick runs on the correct thread.
     */
    public static void kickPlayer(Plugin plugin, Player player, Component reason) {
        if (folia) {
            player.getScheduler().run(plugin, scheduledTask -> player.kick(reason), null);
        } else if (Bukkit.isPrimaryThread()) {
            player.kick(reason);
        } else {
            schedule(plugin, () -> player.kick(reason));
        }
    }
}
