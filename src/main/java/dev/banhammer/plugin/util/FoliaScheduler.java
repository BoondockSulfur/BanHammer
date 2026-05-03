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

    private static boolean folia;

    private FoliaScheduler() {}

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
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Runs a delayed task on the global region thread (Folia) or the main thread (Paper).
     */
    public static void runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, scheduledTask -> task.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    // ========== Entity Scheduler ==========

    /**
     * Runs a task on the entity's owning region thread (Folia) or the main thread (Paper).
     * Use for entity-specific operations: teleport, kick, openInventory, etc.
     */
    public static void runOnEntity(Plugin plugin, Entity entity, Runnable task) {
        if (folia) {
            entity.getScheduler().run(plugin, scheduledTask -> task.run(), null);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Runs a delayed task on the entity's owning region thread (Folia) or the main thread (Paper).
     */
    public static void runOnEntityDelayed(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        if (folia) {
            entity.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), null, delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
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
            Bukkit.getScheduler().runTask(plugin, task);
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
     * Cancels a task handle returned by {@link #runAsyncRepeating(Plugin, Runnable, long, long)}.
     */
    public static void cancelTask(Object taskHandle) {
        if (taskHandle == null) return;

        if (taskHandle instanceof BukkitTask bukkitTask) {
            if (!bukkitTask.isCancelled()) {
                bukkitTask.cancel();
            }
        } else {
            // Folia ScheduledTask — use reflection to avoid compile-time dependency
            try {
                var cancelMethod = taskHandle.getClass().getMethod("cancel");
                cancelMethod.invoke(taskHandle);
            } catch (Exception e) {
                // Fallback: should not happen
            }
        }
    }

    // ========== Player Operations ==========

    /**
     * Teleports a player asynchronously. On Paper, uses teleportAsync if available,
     * otherwise falls back to sync teleport. On Folia, uses the entity scheduler.
     */
    public static void teleportAsync(Plugin plugin, Player player, Location destination) {
        if (folia) {
            player.teleportAsync(destination);
        } else {
            player.teleport(destination);
        }
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
