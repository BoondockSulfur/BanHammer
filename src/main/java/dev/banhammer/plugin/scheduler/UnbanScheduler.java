package dev.banhammer.plugin.scheduler;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.event.PlayerUnpunishedEvent;
import dev.banhammer.plugin.integration.DiscordWebhook;
import dev.banhammer.plugin.util.BanLists;
import dev.banhammer.plugin.util.FoliaScheduler;
import dev.banhammer.plugin.util.IPAnonymizer;
import dev.banhammer.plugin.util.Settings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.banhammer.plugin.util.Constants.SCHEDULER_BAN_CHECK_DELAY;

/**
 * Periodic maintenance: releases expired temporary punishments and enforces data retention.
 *
 * <h2>Ordering</h2>
 * Each expired record is deactivated <em>first</em>, using a compare-and-set update that only
 * succeeds while the row is still active. All visible side effects - lifting the ban, firing
 * the event, notifying Discord - happen only for the caller that won that update. Previously
 * the side effects came first, so a failing database write meant the same record was
 * reprocessed every 60 seconds forever, re-sending the Discord message each time.
 *
 * @since 3.0.0
 */
public class UnbanScheduler {

    /** Reason text written to the database and reported everywhere else. */
    private static final String EXPIRY_REASON = "Expired automatically";

    private final BanHammerPlugin plugin;
    private final Database database;
    private volatile DiscordWebhook discord;

    private Object task;
    private Object retentionTask;

    /** Guards against a slow run overlapping with the next tick. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public UnbanScheduler(BanHammerPlugin plugin, Database database, DiscordWebhook discord) {
        this.plugin = plugin;
        this.database = database;
        this.discord = discord;
    }

    /**
     * Updates the Discord webhook reference (called on reload).
     */
    public void updateDiscord(DiscordWebhook discord) {
        this.discord = discord;
    }

    /**
     * Starts the scheduler.
     */
    public void start() {
        if (database == null) {
            plugin.getSLF4JLogger().info("Auto-unban scheduler is disabled (no database)");
            return;
        }

        Settings.TempBans tempBans = plugin.settings().tempBans();
        if (!tempBans.enabled()) {
            plugin.getSLF4JLogger().info("Auto-unban scheduler is disabled in config");
            return;
        }

        long checkIntervalTicks = tempBans.checkIntervalSeconds() * 20L;
        task = FoliaScheduler.runAsyncRepeating(
                plugin,
                this::checkExpiredPunishments,
                SCHEDULER_BAN_CHECK_DELAY,
                checkIntervalTicks
        );
        plugin.getSLF4JLogger().info("Auto-unban scheduler started (checking every {} seconds)",
                tempBans.checkIntervalSeconds());

        Settings.Privacy privacy = plugin.settings().privacy();
        if (privacy.retentionEnabled()) {
            // Once per hour is frequent enough for a day-granularity retention window.
            retentionTask = FoliaScheduler.runAsyncRepeating(
                    plugin, this::purgeExpiredData, SCHEDULER_BAN_CHECK_DELAY, 20L * 3600L);
            plugin.getSLF4JLogger().info("Data retention enabled: deleting punishments older than {} days",
                    privacy.retentionDays());
        }
    }

    /**
     * Stops the scheduler.
     */
    public void stop() {
        if (task != null) {
            FoliaScheduler.cancelTask(task);
            task = null;
        }
        if (retentionTask != null) {
            FoliaScheduler.cancelTask(retentionTask);
            retentionTask = null;
        }
        plugin.getSLF4JLogger().info("Auto-unban scheduler stopped");
    }

    /**
     * Checks for and removes expired punishments.
     */
    private void checkExpiredPunishments() {
        if (!running.compareAndSet(false, true)) {
            plugin.getSLF4JLogger().debug("Previous expiry check still running, skipping this tick");
            return;
        }

        database.getExpiredPunishments()
                .thenAccept(expired -> {
                    if (!expired.isEmpty()) {
                        plugin.getSLF4JLogger().debug("Found {} expired punishment(s), processing...", expired.size());
                        for (PunishmentRecord record : expired) {
                            processExpiredPunishment(record);
                        }
                    }
                })
                .whenComplete((ignored, throwable) -> {
                    running.set(false);
                    if (throwable != null) {
                        plugin.getSLF4JLogger().error("Failed to check expired punishments", throwable);
                    }
                });
    }

    /**
     * Processes a single expired punishment.
     *
     * @param record the expired punishment record
     */
    private void processExpiredPunishment(PunishmentRecord record) {
        database.deactivatePunishment(record.getId(), null, EXPIRY_REASON)
                .thenAccept(claimed -> {
                    if (!claimed) {
                        // Somebody else (a manual /unban, or another server sharing the
                        // database) already handled this record.
                        return;
                    }

                    record.setActive(false);
                    record.setUnbanReason(EXPIRY_REASON);
                    record.setUnbannedAt(Instant.now());

                    lift(record);

                    if (discord != null) {
                        discord.sendUnpunishment(record, "Automatic", EXPIRY_REASON);
                    }

                    plugin.getSLF4JLogger().debug("Automatically removed expired {} for player {}",
                            record.getType(), record.getVictimName());
                })
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().error("Failed to deactivate punishment {} for {}",
                            record.getId(), record.getVictimName(), throwable);
                    return null;
                });
    }

    /**
     * Undoes the in-game effect of a punishment.
     *
     * <p>Everything here touches the Bukkit API, so it is dispatched to the main/global
     * thread: {@code BanList} is an unsynchronized map backed by {@code banned-players.json},
     * and on Folia these calls throw outright when made from an async thread.
     */
    private void lift(PunishmentRecord record) {
        switch (record.getType()) {
            case TEMP_BAN, BAN -> FoliaScheduler.runGlobal(plugin, () -> {
                BanLists.pardon(record.getVictimUuid(), record.getVictimName());
                fireEvent(record);
                notifyIfOnline(record);
            });

            case IP_BAN -> FoliaScheduler.runGlobal(plugin, () -> {
                // Only the real IP can be pardoned. With anonymization enabled (the default)
                // the stored value is masked or hashed; such bans expire through the ban
                // list's own expiry date instead.
                String storedIp = record.getVictimIp();
                if (plugin.settings().privacy().anonymizationLevel() == IPAnonymizer.AnonymizationLevel.NONE
                        && storedIp != null && IPAnonymizer.isLiteralIp(storedIp)) {
                    BanLists.pardonIp(storedIp);
                }
                BanLists.pardon(record.getVictimUuid(), record.getVictimName());
                fireEvent(record);
                notifyIfOnline(record);
            });

            case TEMP_MUTE, MUTE -> {
                plugin.getPunishmentManager().removeMuteFromCache(record.getVictimUuid());
                FoliaScheduler.runGlobal(plugin, () -> {
                    fireEvent(record);
                    notifyIfOnline(record);
                });
            }

            case JAIL -> {
                Player jailed = Bukkit.getPlayer(record.getVictimUuid());
                if (jailed != null && jailed.isOnline()) {
                    FoliaScheduler.runOnEntity(plugin, jailed,
                            () -> plugin.getJailManager().releasePlayer(jailed),
                            () -> plugin.getJailManager().releasePlayerByUUID(record.getVictimUuid()));
                } else {
                    plugin.getJailManager().releasePlayerByUUID(record.getVictimUuid());
                }
                plugin.getSLF4JLogger().info("Automatically released {} from jail (punishment expired)",
                        record.getVictimName());
                FoliaScheduler.runGlobal(plugin, () -> fireEvent(record));
            }

            default -> {
                // WARNING never expires (it is stored inactive), so reaching here means a new
                // punishment type was added without teaching the scheduler how to lift it.
                plugin.getSLF4JLogger().warn("No expiry handling for punishment type {} (record #{}); "
                        + "the database row was deactivated but nothing was undone in-game",
                        record.getType(), record.getId());
                FoliaScheduler.runGlobal(plugin, () -> fireEvent(record));
            }
        }
    }

    private void fireEvent(PunishmentRecord record) {
        Bukkit.getPluginManager().callEvent(new PlayerUnpunishedEvent(null, record, EXPIRY_REASON, true));
    }

    private void notifyIfOnline(PunishmentRecord record) {
        if (!plugin.settings().tempBans().notifyOnExpire()) {
            return;
        }
        Player player = Bukkit.getPlayer(record.getVictimUuid());
        if (player != null && player.isOnline()) {
            player.sendMessage(plugin.messages().prefix().append(plugin.messages().unbanned(record.getVictimName())));
        }
    }

    /**
     * Deletes punishment history older than the configured retention window.
     */
    private void purgeExpiredData() {
        Settings.Privacy privacy = plugin.settings().privacy();
        if (!privacy.retentionEnabled()) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(privacy.retentionDays()));
        database.purgeOldPunishments(cutoff, privacy.retentionKeepActive())
                .thenAccept(deleted -> {
                    if (deleted > 0) {
                        plugin.getSLF4JLogger().info("Data retention: deleted {} punishment(s) older than {} days",
                                deleted, privacy.retentionDays());
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().error("Data retention purge failed", throwable);
                    return null;
                });
    }
}
