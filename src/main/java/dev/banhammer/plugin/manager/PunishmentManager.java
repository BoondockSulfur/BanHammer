package dev.banhammer.plugin.manager;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.event.PlayerPunishEvent;
import dev.banhammer.plugin.event.PlayerPunishedEvent;
import dev.banhammer.plugin.event.PlayerUnpunishedEvent;
import dev.banhammer.plugin.integration.DiscordWebhook;
import dev.banhammer.plugin.util.IPAnonymizer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for all punishment operations.
 * Handles bans, kicks, mutes, and other punishments.
 *
 * @since 3.0.0
 */
public class PunishmentManager {

    private final BanHammerPlugin plugin;
    private final Database database;
    private final DiscordWebhook discord;
    private final boolean databaseEnabled;
    private final String serverName;

    // Cache for active mutes to prevent race conditions in async checks
    private final Map<UUID, PunishmentRecord> activeMutes = new ConcurrentHashMap<>();

    public PunishmentManager(BanHammerPlugin plugin, Database database, DiscordWebhook discord) {
        this.plugin = plugin;
        this.database = database;
        this.discord = discord;
        this.databaseEnabled = plugin.getConfig().getBoolean("database.enabled", false);
        this.serverName = plugin.getConfig().getString("database.serverName", "Unknown");
    }

    /**
     * Bans a player permanently or temporarily.
     *
     * @param staff The staff member issuing the ban
     * @param victim The player to ban
     * @param reason The ban reason
     * @param duration The ban duration (null for permanent)
     * @param ipBan Whether to also IP ban
     * @return CompletableFuture with the punishment record ID
     */
    public CompletableFuture<Integer> banPlayer(Player staff, Player victim, String reason, Duration duration, boolean ipBan) {
        // Fire pre-event
        PunishmentType type = ipBan ? PunishmentType.IP_BAN : (duration == null ? PunishmentType.BAN : PunishmentType.TEMP_BAN);
        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, type, reason, duration);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(-1);
        }

        // Use updated values from event
        String finalReason = event.getReason();
        Duration finalDuration = event.getDuration();

        // Calculate expiration
        Instant expiresAt = finalDuration != null ? Instant.now().plus(finalDuration) : null;

        // Debug logging
        if (expiresAt == null) {
            plugin.getSLF4JLogger().info("Applying PERMANENT ban to {}", victim.getName());
        } else {
            long seconds = java.time.Duration.between(Instant.now(), expiresAt).getSeconds();
            plugin.getSLF4JLogger().info("Applying TEMPORARY ban to {} for {} seconds (expires at {})",
                    victim.getName(), seconds, expiresAt);
        }

        // Apply Minecraft ban
        try {
            BanList banList = Bukkit.getBanList(BanList.Type.NAME);
            banList.addBan(
                    victim.getName(),
                    finalReason,
                    expiresAt != null ? java.util.Date.from(expiresAt) : null,
                    staff.getName()
            );

            // IP ban if requested
            if (ipBan && victim.getAddress() != null) {
                String ip = victim.getAddress().getAddress().getHostAddress();
                BanList ipBanList = Bukkit.getBanList(BanList.Type.IP);
                ipBanList.addBan(ip, finalReason, expiresAt != null ? java.util.Date.from(expiresAt) : null, staff.getName());
            }

            // Kick player
            victim.kickPlayer(finalReason);
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Failed to ban player", e);
            return CompletableFuture.failedFuture(e);
        }

        // Create punishment record (for database and/or Discord)
        String ipAddress = null;
        if (ipBan && plugin.getConfig().getBoolean("ipBan.trackIps", true) && victim.getAddress() != null) {
            String rawIP = victim.getAddress().getAddress().getHostAddress();
            String levelStr = plugin.getConfig().getString("privacy.ipAnonymization", "PARTIAL");
            IPAnonymizer.AnonymizationLevel level = IPAnonymizer.AnonymizationLevel.valueOf(levelStr.toUpperCase());
            String salt = plugin.getConfig().getString("privacy.ipHashSalt", "banhammer-secret-salt");
            ipAddress = IPAnonymizer.anonymize(rawIP, level, salt);
        }

        PunishmentRecord record = new PunishmentRecord(
                victim.getUniqueId(),
                victim.getName(),
                ipAddress,
                staff.getUniqueId(),
                staff.getName(),
                type,
                finalReason,
                Instant.now(),
                expiresAt
        );
        record.setServerName(serverName);

        // Save to database if enabled
        if (databaseEnabled && database != null) {
            return database.savePunishment(record).thenApply(id -> {
                record.setId(id);

                // Fire post-event
                PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

                // Discord notification
                if (discord != null) {
                    discord.sendPunishment(record);
                }

                return id;
            });
        } else {
            // Database disabled, still send Discord notification and fire event
            PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

            if (discord != null) {
                discord.sendPunishment(record);
            }

            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Kicks a player.
     *
     * @param staff The staff member issuing the kick
     * @param victim The player to kick
     * @param reason The kick reason
     * @return CompletableFuture with the punishment record ID
     */
    public CompletableFuture<Integer> kickPlayer(Player staff, Player victim, String reason) {
        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, PunishmentType.KICK, reason, null);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(-1);
        }

        String finalReason = event.getReason();

        try {
            victim.kickPlayer(finalReason);
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Failed to kick player", e);
            return CompletableFuture.failedFuture(e);
        }

        // Create punishment record
        PunishmentRecord record = new PunishmentRecord(
                victim.getUniqueId(),
                victim.getName(),
                null,
                staff.getUniqueId(),
                staff.getName(),
                PunishmentType.KICK,
                finalReason,
                Instant.now(),
                null
        );
        record.setServerName(serverName);
        record.setActive(false); // Kicks are not "active" punishments

        if (databaseEnabled && database != null) {
            return database.savePunishment(record).thenApply(id -> {
                record.setId(id);

                PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

                if (discord != null) {
                    discord.sendPunishment(record);
                }

                return id;
            });
        } else {
            // Database disabled, still send Discord notification and fire event
            PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

            if (discord != null) {
                discord.sendPunishment(record);
            }

            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Unbans a player.
     *
     * @param staff The staff member removing the ban
     * @param playerName The player name to unban
     * @param reason The reason for unbanning
     * @return CompletableFuture that completes when unbanning is done
     */
    public CompletableFuture<Void> unbanPlayer(Player staff, String playerName, String reason) {
        // Remove from Minecraft ban list
        BanList banList = Bukkit.getBanList(BanList.Type.NAME);
        banList.pardon(playerName);

        if (databaseEnabled && database != null) {
            // Find active ban in database
            UUID playerUuid = Bukkit.getOfflinePlayer(playerName).getUniqueId();

            return database.getActivePunishmentsByType(playerUuid, PunishmentType.BAN)
                    .thenCompose(punishments -> {
                        if (!punishments.isEmpty()) {
                            PunishmentRecord record = punishments.get(0);
                            return database.deactivatePunishment(record.getId(), staff.getUniqueId(), reason)
                                    .thenRun(() -> {
                                        record.setActive(false);
                                        record.setUnbanStaffUuid(staff.getUniqueId());
                                        record.setUnbanReason(reason);
                                        record.setUnbannedAt(Instant.now());

                                        PlayerUnpunishedEvent event = new PlayerUnpunishedEvent(staff, record, reason, false);
                                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));

                                        if (discord != null) {
                                            discord.sendUnpunishment(record, staff.getName(), reason);
                                        }
                                    });
                        }
                        return CompletableFuture.completedFuture(null);
                    });
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Gets punishment history for a player.
     *
     * @param playerUuid The player's UUID
     * @param limit Maximum number of records
     * @return CompletableFuture with the list of punishments
     */
    public CompletableFuture<List<PunishmentRecord>> getHistory(UUID playerUuid, int limit) {
        if (databaseEnabled && database != null) {
            return database.getPunishmentsByPlayer(playerUuid, limit);
        }
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Gets active punishments for a player.
     *
     * @param playerUuid The player's UUID
     * @return CompletableFuture with the list of active punishments
     */
    public CompletableFuture<List<PunishmentRecord>> getActivePunishments(UUID playerUuid) {
        if (databaseEnabled && database != null) {
            return database.getActivePunishments(playerUuid);
        }
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Checks if database is enabled.
     *
     * @return true if database is enabled
     */
    public boolean isDatabaseEnabled() {
        return databaseEnabled && database != null && database.isConnected();
    }

    /**
     * Mutes a player permanently or temporarily.
     *
     * @param staff The staff member issuing the mute
     * @param victim The player to mute
     * @param reason The mute reason
     * @param duration The mute duration (null for permanent)
     * @return CompletableFuture with the punishment record ID
     */
    public CompletableFuture<Integer> mutePlayer(Player staff, Player victim, String reason, Duration duration) {
        PunishmentType type = duration == null ? PunishmentType.MUTE : PunishmentType.TEMP_MUTE;
        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, type, reason, duration);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(-1);
        }

        String finalReason = event.getReason();
        Duration finalDuration = event.getDuration();
        Instant expiresAt = finalDuration != null ? Instant.now().plus(finalDuration) : null;

        // Create punishment record
        PunishmentRecord record = new PunishmentRecord(
                victim.getUniqueId(),
                victim.getName(),
                null,
                staff.getUniqueId(),
                staff.getName(),
                type,
                finalReason,
                Instant.now(),
                expiresAt
        );
        record.setServerName(serverName);

        if (databaseEnabled && database != null) {
            return database.savePunishment(record).thenApply(id -> {
                record.setId(id);

                // Add to active mutes cache
                activeMutes.put(victim.getUniqueId(), record);

                PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

                if (discord != null) {
                    discord.sendPunishment(record);
                }

                return id;
            });
        } else {
            // Database disabled, still send Discord notification and fire event
            // Note: Without database, mutes won't persist across restarts
            activeMutes.put(victim.getUniqueId(), record);

            PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

            if (discord != null) {
                discord.sendPunishment(record);
            }

            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Unmutes a player.
     *
     * @param staff The staff member removing the mute
     * @param playerName The player name to unmute
     * @param reason The reason for unmuting
     * @return CompletableFuture that completes when unmuting is done
     */
    public CompletableFuture<Void> unmutePlayer(Player staff, String playerName, String reason) {
        if (databaseEnabled && database != null) {
            UUID playerUuid = Bukkit.getOfflinePlayer(playerName).getUniqueId();

            return database.getActivePunishmentsByType(playerUuid, PunishmentType.MUTE)
                    .thenCompose(punishments -> {
                        if (!punishments.isEmpty()) {
                            PunishmentRecord record = punishments.get(0);
                            return database.deactivatePunishment(record.getId(), staff.getUniqueId(), reason)
                                    .thenRun(() -> {
                                        record.setActive(false);
                                        record.setUnbanStaffUuid(staff.getUniqueId());
                                        record.setUnbanReason(reason);
                                        record.setUnbannedAt(Instant.now());

                                        // Remove from active mutes cache
                                        activeMutes.remove(playerUuid);

                                        PlayerUnpunishedEvent event = new PlayerUnpunishedEvent(staff, record, reason, false);
                                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));

                                        if (discord != null) {
                                            discord.sendUnpunishment(record, staff.getName(), reason);
                                        }
                                    });
                        }
                        return CompletableFuture.completedFuture(null);
                    });
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Jails a player.
     *
     * @param staff The staff member issuing the jail
     * @param victim The player to jail
     * @param reason The jail reason
     * @param duration The jail duration (null for permanent)
     * @return CompletableFuture with the punishment record ID
     */
    public CompletableFuture<Integer> jailPlayer(Player staff, Player victim, String reason, Duration duration) {
        plugin.getSLF4JLogger().info("===== JAIL PLAYER CALLED =====");
        plugin.getSLF4JLogger().info("Staff: {}, Victim: {}, Reason: {}, Duration: {}",
            staff.getName(), victim.getName(), reason, duration);

        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, PunishmentType.JAIL, reason, duration);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            plugin.getSLF4JLogger().warn("Jail event was cancelled!");
            return CompletableFuture.completedFuture(-1);
        }

        String finalReason = event.getReason();
        Duration finalDuration = event.getDuration();
        Instant expiresAt = finalDuration != null ? Instant.now().plus(finalDuration) : null;

        // Actually jail the player
        plugin.getSLF4JLogger().info("Calling JailManager.jailPlayer()...");
        boolean jailed = plugin.getJailManager().jailPlayer(victim);
        plugin.getSLF4JLogger().info("JailManager.jailPlayer() returned: {}", jailed);

        if (!jailed) {
            plugin.getSLF4JLogger().error("Failed to jail player - jail location not set!");
        }

        // Create punishment record
        PunishmentRecord record = new PunishmentRecord(
                victim.getUniqueId(),
                victim.getName(),
                null,
                staff.getUniqueId(),
                staff.getName(),
                PunishmentType.JAIL,
                finalReason,
                Instant.now(),
                expiresAt
        );
        record.setServerName(serverName);

        if (databaseEnabled && database != null) {
            return database.savePunishment(record).thenApply(id -> {
                record.setId(id);

                PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

                if (discord != null) {
                    discord.sendPunishment(record);
                }

                return id;
            });
        } else {
            // Database disabled, still send Discord notification and fire event
            PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

            if (discord != null) {
                discord.sendPunishment(record);
            }

            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Unjails a player.
     *
     * @param staff The staff member releasing the player
     * @param playerName The player name to unjail
     * @param reason The reason for unjailing
     * @return CompletableFuture that completes when unjailing is done
     */
    public CompletableFuture<Void> unjailPlayer(Player staff, String playerName, String reason) {
        UUID playerUuid = Bukkit.getOfflinePlayer(playerName).getUniqueId();

        // Release player from jail (works for both online and offline)
        Player player = Bukkit.getPlayer(playerName);
        if (player != null) {
            plugin.getJailManager().releasePlayer(player);
        } else {
            // Player is offline, clean up jail tracking to prevent memory leak
            plugin.getJailManager().releasePlayerByUUID(playerUuid);
        }

        if (databaseEnabled && database != null) {
            return database.getActivePunishmentsByType(playerUuid, PunishmentType.JAIL)
                    .thenCompose(punishments -> {
                        if (!punishments.isEmpty()) {
                            PunishmentRecord record = punishments.get(0);
                            return database.deactivatePunishment(record.getId(), staff.getUniqueId(), reason)
                                    .thenRun(() -> {
                                        record.setActive(false);
                                        record.setUnbanStaffUuid(staff.getUniqueId());
                                        record.setUnbanReason(reason);
                                        record.setUnbannedAt(Instant.now());

                                        PlayerUnpunishedEvent event = new PlayerUnpunishedEvent(staff, record, reason, false);
                                        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));

                                        if (discord != null) {
                                            discord.sendUnpunishment(record, staff.getName(), reason);
                                        }
                                    });
                        }
                        return CompletableFuture.completedFuture(null);
                    });
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Warns a player and auto-bans if threshold is reached.
     *
     * @param staff The staff member issuing the warning
     * @param victim The player to warn
     * @param reason The warning reason
     * @return CompletableFuture with the punishment record ID
     */
    public CompletableFuture<Integer> warnPlayer(Player staff, Player victim, String reason) {
        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, PunishmentType.WARNING, reason, null);
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(-1);
        }

        String finalReason = event.getReason();

        // Create punishment record
        PunishmentRecord record = new PunishmentRecord(
                victim.getUniqueId(),
                victim.getName(),
                null,
                staff.getUniqueId(),
                staff.getName(),
                PunishmentType.WARNING,
                finalReason,
                Instant.now(),
                null
        );
        record.setServerName(serverName);
        record.setActive(false); // Warnings are not "active" in the traditional sense

        if (databaseEnabled && database != null) {
            return database.savePunishment(record).thenCompose(id -> {
                record.setId(id);

                PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

                if (discord != null) {
                    discord.sendPunishment(record);
                }

                // Check for auto-ban threshold (requires database)
                return checkWarningThreshold(staff, victim).thenApply(v -> id);
            });
        } else {
            // Database disabled, still send Discord notification and fire event
            // Note: Auto-ban threshold won't work without database
            PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victim.getName(), record);
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

            if (discord != null) {
                discord.sendPunishment(record);
            }

            return CompletableFuture.completedFuture(0);
        }
    }

    /**
     * Checks if a player has reached the warning threshold and auto-bans them.
     *
     * @param staff The staff member who issued the warning
     * @param victim The warned player
     * @return CompletableFuture that completes when check is done
     */
    private CompletableFuture<Void> checkWarningThreshold(Player staff, Player victim) {
        if (!plugin.getConfig().getBoolean("punishmentTypes.warnings.enabled", true)) {
            return CompletableFuture.completedFuture(null);
        }

        int threshold = plugin.getConfig().getInt("punishmentTypes.warnings.autoBanThreshold", 3);
        String autoBanDuration = plugin.getConfig().getString("punishmentTypes.warnings.autoBanDuration", "7d");

        // Use optimized COUNT query instead of loading all records
        return database.getWarningCount(victim.getUniqueId()).thenAccept(warningCount -> {
            if (warningCount >= threshold) {
                // Auto-ban - must run on main thread because banPlayer uses Bukkit API
                Duration duration = parseDuration(autoBanDuration);
                String reason = "Automatischer Ban: " + warningCount + " Verwarnungen erreicht";

                Bukkit.getScheduler().runTask(plugin, () -> {
                    banPlayer(staff, victim, reason, duration, false).thenAccept(banId -> {
                        plugin.getSLF4JLogger().info("Player {} auto-banned after {} warnings", victim.getName(), warningCount);
                    });
                });
            }
        });
    }

    /**
     * Parses a duration string (e.g., "7d", "1h30m", "PT24H").
     *
     * @param durationStr The duration string
     * @return The parsed Duration, or null if permanent/invalid
     */
    private Duration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.trim().isEmpty()) {
            return null;
        }

        String s = durationStr.trim();

        // Explicit handling for "permanent" keyword
        if (s.equalsIgnoreCase("permanent") || s.equalsIgnoreCase("perm")) {
            return null; // null = permanent
        }

        // Try ISO-8601 format (case-insensitive)
        if (s.toUpperCase().startsWith("P")) {
            try {
                return Duration.parse(s.toUpperCase());
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Invalid ISO-8601 duration format: {}", s);
                // Fall through to custom parsing
            }
        }

        // Custom format: "7d", "1h30m", "2d12h", etc.
        String tmp = s.toLowerCase();
        long days = extractTime(tmp, "d");
        long hours = extractTime(tmp, "h");
        long minutes = extractTime(tmp, "m");
        long seconds = extractTime(tmp, "s");

        // Check if any time unit was found
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            plugin.getSLF4JLogger().warn("Could not parse duration: '{}'. Use format like '7d', '1h30m', or 'permanent'", durationStr);
            return null; // Invalid format, default to permanent
        }

        Duration duration = Duration.ZERO;
        if (days > 0) duration = duration.plusDays(days);
        if (hours > 0) duration = duration.plusHours(hours);
        if (minutes > 0) duration = duration.plusMinutes(minutes);
        if (seconds > 0) duration = duration.plusSeconds(seconds);

        return duration;
    }

    private long extractTime(String str, String unit) {
        int index = str.toLowerCase().indexOf(unit);
        if (index < 1) return 0; // Fixed: Changed from <= to < to handle position 0 correctly

        int start = index - 1;
        while (start >= 0 && Character.isDigit(str.charAt(start))) {
            start--;
        }

        try {
            return Long.parseLong(str.substring(start + 1, index));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Checks if a player is currently muted (synchronous cache check).
     * Thread-safe implementation using computeIfPresent to avoid race conditions.
     *
     * @param playerUuid The player's UUID
     * @return The active mute record, or null if not muted
     */
    public PunishmentRecord getActiveMute(UUID playerUuid) {
        // Use computeIfPresent for atomic check-and-remove operation
        return activeMutes.computeIfPresent(playerUuid, (uuid, record) -> {
            // Check if expired
            if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(Instant.now())) {
                // Return null to atomically remove from map
                return null;
            }
            // Return record to keep it in map
            return record;
        });
    }

    /**
     * Loads all active mutes into cache from database.
     * Should be called on plugin startup.
     */
    public void loadActiveMutes() {
        if (!databaseEnabled || database == null) {
            return;
        }

        // Query for all active mutes from database
        CompletableFuture.allOf(
            database.getActivePunishmentsByTypeGlobal(PunishmentType.MUTE),
            database.getActivePunishmentsByTypeGlobal(PunishmentType.TEMP_MUTE)
        ).thenAccept(v -> {
            // This will be handled by the individual futures below
        });

        // Load permanent mutes
        database.getActivePunishmentsByTypeGlobal(PunishmentType.MUTE)
            .thenAccept(mutes -> {
                for (PunishmentRecord mute : mutes) {
                    activeMutes.put(mute.getVictimUuid(), mute);
                }
                plugin.getSLF4JLogger().info("Loaded {} active permanent mute(s)", mutes.size());
            })
            .exceptionally(throwable -> {
                plugin.getSLF4JLogger().error("Failed to load permanent mutes", throwable);
                return null;
            });

        // Load temporary mutes (only those not yet expired)
        database.getActivePunishmentsByTypeGlobal(PunishmentType.TEMP_MUTE)
            .thenAccept(tempMutes -> {
                int loaded = 0;
                for (PunishmentRecord mute : tempMutes) {
                    // Double-check expiration (in case DB query doesn't filter)
                    if (mute.getExpiresAt() == null || mute.getExpiresAt().isAfter(java.time.Instant.now())) {
                        activeMutes.put(mute.getVictimUuid(), mute);
                        loaded++;
                    }
                }
                plugin.getSLF4JLogger().info("Loaded {} active temporary mute(s)", loaded);
            })
            .exceptionally(throwable -> {
                plugin.getSLF4JLogger().error("Failed to load temporary mutes", throwable);
                return null;
            });
    }

    /**
     * Removes a mute from the cache (called by UnbanScheduler on expiration).
     *
     * @param playerUuid The player UUID
     */
    public void removeMuteFromCache(UUID playerUuid) {
        activeMutes.remove(playerUuid);
    }
}
