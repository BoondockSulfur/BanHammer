package dev.banhammer.plugin.scheduler;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.event.PlayerUnpunishedEvent;
import dev.banhammer.plugin.integration.DiscordWebhook;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.List;

import static dev.banhammer.plugin.util.Constants.*;

/**
 * Scheduler that automatically removes expired temporary punishments.
 *
 * @since 3.0.0
 */
public class UnbanScheduler {

    private final BanHammerPlugin plugin;
    private final Database database;
    private final DiscordWebhook discord;
    private BukkitTask task;
    private final boolean enabled;

    public UnbanScheduler(BanHammerPlugin plugin, Database database, DiscordWebhook discord) {
        this.plugin = plugin;
        this.database = database;
        this.discord = discord;
        this.enabled = plugin.getConfig().getBoolean("tempBans.enabled", true);
    }

    /**
     * Starts the scheduler.
     */
    public void start() {
        if (!enabled || database == null) {
            plugin.getSLF4JLogger().info("Auto-unban scheduler is disabled");
            return;
        }

        long checkInterval = plugin.getConfig().getLong("tempBans.checkInterval", 60) * 20L; // Convert to ticks

        task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::checkExpiredPunishments,
                SCHEDULER_BAN_CHECK_DELAY,
                checkInterval
        );

        plugin.getSLF4JLogger().info("Auto-unban scheduler started (checking every {} seconds)", checkInterval / 20);
    }

    /**
     * Stops the scheduler.
     */
    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            plugin.getSLF4JLogger().info("Auto-unban scheduler stopped");
        }
    }

    /**
     * Checks for and removes expired punishments.
     */
    private void checkExpiredPunishments() {
        database.getExpiredPunishments().thenAccept(expired -> {
            if (expired.isEmpty()) return;

            plugin.getSLF4JLogger().debug("Found {} expired punishment(s), processing...", expired.size());

            for (PunishmentRecord record : expired) {
                processExpiredPunishment(record);
            }
        }).exceptionally(throwable -> {
            plugin.getSLF4JLogger().error("Failed to check expired punishments", throwable);
            return null;
        });
    }

    /**
     * Processes a single expired punishment.
     *
     * @param record The expired punishment record
     */
    private void processExpiredPunishment(PunishmentRecord record) {
        try {
            // Remove from Minecraft ban system
            switch (record.getType()) {
                case TEMP_BAN, BAN -> {
                    BanList banList = Bukkit.getBanList(BanList.Type.NAME);
                    banList.pardon(record.getVictimName());
                }
                case IP_BAN -> {
                    // Only pardon if victimIp is a valid IP address (not hashed)
                    if (record.getVictimIp() != null && isValidIP(record.getVictimIp())) {
                        BanList ipBanList = Bukkit.getBanList(BanList.Type.IP);
                        ipBanList.pardon(record.getVictimIp());
                    }
                }
                case TEMP_MUTE, MUTE -> {
                    // Remove from mute cache in PunishmentManager
                    plugin.getPunishmentManager().removeMuteFromCache(record.getVictimUuid());
                }
                case JAIL -> {
                    // Release from jail
                    plugin.getJailManager().releasePlayerByUUID(record.getVictimUuid());
                    plugin.getSLF4JLogger().info("Automatically released {} from jail (punishment expired)", record.getVictimName());
                }
            }

            // Deactivate in database
            database.deactivatePunishment(record.getId(), null, "Automatic expiration")
                    .thenRun(() -> {
                        record.setActive(false);
                        record.setUnbanReason("Expired automatically");
                        record.setUnbannedAt(Instant.now());

                        // Fire event on main thread
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            PlayerUnpunishedEvent event = new PlayerUnpunishedEvent(
                                    null,
                                    record,
                                    "Expired automatically",
                                    true
                            );
                            Bukkit.getPluginManager().callEvent(event);
                        });

                        // Discord notification
                        if (discord != null) {
                            discord.sendUnpunishment(record, "Automatic", "Punishment expired");
                        }

                        plugin.getSLF4JLogger().debug("Automatically removed expired {} for player {}",
                                record.getType(), record.getVictimName());
                    })
                    .exceptionally(throwable -> {
                        plugin.getSLF4JLogger().error("Failed to deactivate punishment {} for {}",
                                record.getId(), record.getVictimName(), throwable);
                        return null;
                    });

        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Failed to process expired punishment for {}", record.getVictimName(), e);
        }
    }

    /**
     * Validates if a string is a valid IP address (IPv4 or IPv6).
     * Uses InetAddress for proper validation including compressed IPv6 notation.
     *
     * @param ip The IP address string to validate
     * @return true if valid IP address, false otherwise
     */
    private boolean isValidIP(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }

        // Use Java's InetAddress for robust IP validation
        // This handles both IPv4 and all IPv6 formats (including ::1, fe80::, etc.)
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
            // Ensure it's a valid IP and not a hostname
            return addr.getHostAddress().equals(ip) ||
                   // IPv6 addresses might be normalized differently
                   (addr instanceof java.net.Inet6Address);
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
