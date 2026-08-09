package dev.banhammer.plugin.manager;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.event.PlayerPunishEvent;
import dev.banhammer.plugin.event.PlayerPunishedEvent;
import dev.banhammer.plugin.event.PlayerUnpunishedEvent;
import dev.banhammer.plugin.integration.DiscordWebhook;
import dev.banhammer.plugin.util.BanLists;
import dev.banhammer.plugin.util.Constants;
import dev.banhammer.plugin.util.DurationParser;
import dev.banhammer.plugin.util.FoliaScheduler;
import dev.banhammer.plugin.util.IPAnonymizer;
import dev.banhammer.plugin.util.PunishmentLogger;
import dev.banhammer.plugin.util.Settings;
import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
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
 *
 * <p>Every entry point takes a {@link CommandSender} rather than a {@link Player}, so
 * punishments can also be issued from the console, RCON or a command block. Console actions
 * are attributed to {@link Constants#CONSOLE_UUID}.
 *
 * @since 3.0.0
 */
public class PunishmentManager {

    /** Punishment types that count as "banned". */
    private static final List<PunishmentType> BAN_TYPES =
            List.of(PunishmentType.BAN, PunishmentType.TEMP_BAN, PunishmentType.IP_BAN);

    /** Punishment types that count as "muted". */
    private static final List<PunishmentType> MUTE_TYPES =
            List.of(PunishmentType.MUTE, PunishmentType.TEMP_MUTE);

    private final BanHammerPlugin plugin;
    private final PunishmentLogger auditLog;
    private volatile Database database;
    private volatile DiscordWebhook discord;

    /** Cache for active mutes so the chat handler never has to hit the database. */
    private final Map<UUID, PunishmentRecord> activeMutes = new ConcurrentHashMap<>();

    public PunishmentManager(BanHammerPlugin plugin, Database database, DiscordWebhook discord) {
        this.plugin = plugin;
        this.database = database;
        this.discord = discord;
        this.auditLog = new PunishmentLogger(plugin);
    }

    /**
     * Outcome of a punishment attempt.
     *
     * @param status   what happened
     * @param recordId the database ID, or 0 when running without a database
     */
    public record PunishmentResult(Status status, int recordId) {

        public enum Status {
            /** The punishment was applied. */
            SUCCESS,
            /** Another plugin cancelled {@link PlayerPunishEvent}. */
            CANCELLED,
            /** The target is protected (bypass permission, or self-punishment). */
            NOT_PERMITTED,
            /** The punishment could not be applied; see the server log. */
            FAILED
        }

        public static PunishmentResult success(int recordId) {
            return new PunishmentResult(Status.SUCCESS, recordId);
        }

        public static PunishmentResult cancelled() {
            return new PunishmentResult(Status.CANCELLED, -1);
        }

        public static PunishmentResult notPermitted() {
            return new PunishmentResult(Status.NOT_PERMITTED, -1);
        }

        public static PunishmentResult failed() {
            return new PunishmentResult(Status.FAILED, -1);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }
    }

    // ==================== Wiring ====================

    /**
     * Updates the database reference (called after async DB initialization and on reload).
     */
    public void updateDatabase(Database database) {
        this.database = database;
    }

    /**
     * Updates the Discord webhook reference (called on reload).
     */
    public void updateDiscord(DiscordWebhook discord) {
        this.discord = discord;
    }

    /**
     * @return true if a database is configured and currently usable
     */
    public boolean isDatabaseEnabled() {
        Database current = database;
        return current != null && current.isConnected();
    }

    // ==================== Guards and identity ====================

    /**
     * Checks whether a staff member may punish a target.
     *
     * <p>Applies to every path - hammer and command alike. Previously only the hammer
     * consulted the bypass permission, so a moderator could still mute or jail a protected
     * player through the commands.
     *
     * @return true if the punishment may proceed
     */
    public boolean canPunish(CommandSender staff, Player victim) {
        if (!(staff instanceof Player staffPlayer)) {
            return true; // Console is unrestricted.
        }
        if (staffPlayer.getUniqueId().equals(victim.getUniqueId())) {
            return false; // No self-punishment.
        }
        return !victim.hasPermission("banhammer.bypass") && !victim.isOp();
    }

    private static UUID staffUuid(CommandSender staff) {
        return staff instanceof Player player ? player.getUniqueId() : Constants.CONSOLE_UUID;
    }

    private static String staffName(CommandSender staff) {
        return staff instanceof Player player ? player.getName() : Constants.CONSOLE_NAME;
    }

    // ==================== Bans ====================

    /**
     * Bans a player permanently or temporarily.
     *
     * @param staff    the staff member issuing the ban
     * @param victim   the player to ban
     * @param reason   the ban reason
     * @param duration the ban duration, or {@code null} for permanent
     * @param ipBan    whether to also IP ban
     * @return the outcome
     */
    public CompletableFuture<PunishmentResult> banPlayer(CommandSender staff, Player victim, String reason,
                                                         Duration duration, boolean ipBan) {
        if (!canPunish(staff, victim)) {
            return CompletableFuture.completedFuture(PunishmentResult.notPermitted());
        }

        PunishmentType requestedType = ipBan
                ? PunishmentType.IP_BAN
                : (duration == null ? PunishmentType.BAN : PunishmentType.TEMP_BAN);

        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, requestedType, reason, duration);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(PunishmentResult.cancelled());
        }

        String finalReason = event.getReason();
        Duration finalDuration = event.getDuration();
        Instant expiresAt = finalDuration != null ? Instant.now().plus(finalDuration) : null;

        // Re-derive the type: a listener may have turned a temp ban into a permanent one, and
        // storing TEMP_BAN with no expiry would leave a row the unban scheduler never lifts.
        PunishmentType type = ipBan
                ? PunishmentType.IP_BAN
                : (finalDuration == null ? PunishmentType.BAN : PunishmentType.TEMP_BAN);

        // Read the address BEFORE kicking - once the connection is closed getAddress()
        // returns null and the IP ban record would end up without an IP.
        String rawIp = rawAddress(victim);
        String storedIp = null;
        Settings.IpBan ipSettings = plugin.settings().ipBan();
        if (ipBan && ipSettings.trackIps() && rawIp != null) {
            Settings.Privacy privacy = plugin.settings().privacy();
            storedIp = IPAnonymizer.anonymize(rawIp, privacy.anonymizationLevel(), privacy.hashSalt());
        }

        try {
            java.util.Date expiryDate = expiresAt != null ? java.util.Date.from(expiresAt) : null;

            // Banned by account, not by name: a name ban is shed by simply renaming, and
            // whoever later claims that name inherits it.
            BanLists.ban(victim.getUniqueId(), victim.getName(), finalReason, expiryDate, staffName(staff));

            if (ipBan && rawIp != null) {
                BanLists.banIp(rawIp, finalReason, expiryDate, staffName(staff));
            }

            victim.kick(Component.text(finalReason != null ? finalReason : ""));
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Failed to ban player {}", victim.getName(), e);
            return CompletableFuture.completedFuture(PunishmentResult.failed());
        }

        PunishmentRecord record = newRecord(staff, victim, type, finalReason, expiresAt);
        record.setVictimIp(storedIp);

        // Supersede any ban that is still marked active, so history has one active row.
        return supersede(victim.getUniqueId(), BAN_TYPES, staff, "Replaced by a new ban")
                .thenCompose(ignored -> persistAndAnnounce(staff, victim.getName(), record));
    }

    /**
     * Kicks a player.
     */
    public CompletableFuture<PunishmentResult> kickPlayer(CommandSender staff, Player victim, String reason) {
        if (!canPunish(staff, victim)) {
            return CompletableFuture.completedFuture(PunishmentResult.notPermitted());
        }

        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, PunishmentType.KICK, reason, null);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(PunishmentResult.cancelled());
        }

        String finalReason = event.getReason();

        try {
            victim.kick(Component.text(finalReason != null ? finalReason : ""));
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Failed to kick player {}", victim.getName(), e);
            return CompletableFuture.completedFuture(PunishmentResult.failed());
        }

        PunishmentRecord record = newRecord(staff, victim, PunishmentType.KICK, finalReason, null);
        record.setActive(false); // A kick is instantaneous, never "active".

        return persistAndAnnounce(staff, victim.getName(), record);
    }

    /**
     * Removes a ban.
     *
     * @return true if an active ban was found and lifted
     */
    public CompletableFuture<Boolean> unbanPlayer(CommandSender staff, String playerName, String reason) {
        UUID playerUuid = resolvePlayerUuid(playerName);

        // BanList is not thread-safe and this can be reached from a database callback
        // (for instance when an appeal is approved), so always go through the main thread.
        CompletableFuture<Boolean> vanillaPardon = new CompletableFuture<>();
        FoliaScheduler.runGlobal(plugin, () -> vanillaPardon.complete(
                BanLists.pardon(playerUuid, playerName)));

        Database current = database;
        if (current == null || playerUuid == null) {
            // Nothing to reconcile in the database, so the vanilla ban list decides whether
            // anything was actually lifted.
            return vanillaPardon;
        }

        return current.getActivePunishments(playerUuid).thenCompose(active -> {
            PunishmentRecord record = active.stream()
                    .filter(p -> BAN_TYPES.contains(p.getType()))
                    .findFirst()
                    .orElse(null);

            if (record == null) {
                // No database record, but the player may still have been on the ban list.
                return vanillaPardon;
            }

            pardonIpIfPossible(record);

            return current.deactivatePunishment(record.getId(), staffUuid(staff), reason)
                    .thenApply(claimed -> {
                        if (claimed) {
                            announceRemoval(staff, record, reason);
                        }
                        return claimed;
                    });
        });
    }

    /**
     * Lifts the IP ban belonging to a record, when that is possible at all.
     */
    private void pardonIpIfPossible(PunishmentRecord record) {
        if (record.getType() != PunishmentType.IP_BAN || record.getVictimIp() == null) {
            return;
        }

        // The stored value is only the real address when anonymization is off; otherwise it
        // is masked or hashed and there is nothing to hand to pardon().
        if (plugin.settings().privacy().anonymizationLevel() == IPAnonymizer.AnonymizationLevel.NONE
                && IPAnonymizer.isLiteralIp(record.getVictimIp())) {
            FoliaScheduler.runGlobal(plugin, () -> BanLists.pardonIp(record.getVictimIp()));
        } else {
            plugin.getSLF4JLogger().warn("The IP ban for {} cannot be lifted automatically because the stored "
                    + "address is anonymized ({}). Use /pardon-ip manually.",
                    record.getVictimName(), plugin.settings().privacy().anonymizationLevel());
        }
    }

    // ==================== Mutes ====================

    /**
     * Mutes a player permanently or temporarily.
     */
    public CompletableFuture<PunishmentResult> mutePlayer(CommandSender staff, Player victim, String reason,
                                                          Duration duration) {
        if (!canPunish(staff, victim)) {
            return CompletableFuture.completedFuture(PunishmentResult.notPermitted());
        }

        PunishmentType requestedType = duration == null ? PunishmentType.MUTE : PunishmentType.TEMP_MUTE;
        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, requestedType, reason, duration);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(PunishmentResult.cancelled());
        }

        String finalReason = event.getReason();
        Duration finalDuration = event.getDuration();
        Instant expiresAt = finalDuration != null ? Instant.now().plus(finalDuration) : null;
        PunishmentType type = finalDuration == null ? PunishmentType.MUTE : PunishmentType.TEMP_MUTE;

        PunishmentRecord record = newRecord(staff, victim, type, finalReason, expiresAt);

        // Cache immediately: waiting for the database round-trip leaves a window in which the
        // player is officially muted but can still talk.
        activeMutes.put(victim.getUniqueId(), record);

        return supersede(victim.getUniqueId(), MUTE_TYPES, staff, "Replaced by a new mute")
                .thenCompose(ignored -> persistAndAnnounce(staff, victim.getName(), record));
    }

    /**
     * Removes a mute.
     *
     * @return true if an active mute was found and lifted
     */
    public CompletableFuture<Boolean> unmutePlayer(CommandSender staff, String playerName, String reason) {
        UUID playerUuid = resolvePlayerUuid(playerName);
        if (playerUuid == null) {
            return CompletableFuture.completedFuture(false);
        }

        // Always clear the cache, database or not. Without this a server running without a
        // database could mute a player but never unmute them again.
        boolean wasCached = activeMutes.remove(playerUuid) != null;

        Database current = database;
        if (current == null) {
            return CompletableFuture.completedFuture(wasCached);
        }

        return current.getActivePunishments(playerUuid).thenCompose(active -> {
            PunishmentRecord record = active.stream()
                    .filter(p -> MUTE_TYPES.contains(p.getType()))
                    .findFirst()
                    .orElse(null);

            if (record == null) {
                return CompletableFuture.completedFuture(wasCached);
            }

            return current.deactivateActivePunishments(playerUuid, MUTE_TYPES, staffUuid(staff), reason)
                    .thenApply(count -> {
                        if (count > 0) {
                            announceRemoval(staff, record, reason);
                        }
                        return count > 0 || wasCached;
                    });
        });
    }

    /**
     * Checks whether a player is currently muted, without touching the database.
     *
     * @return the active mute record, or {@code null} if not muted
     */
    public PunishmentRecord getActiveMute(UUID playerUuid) {
        return activeMutes.computeIfPresent(playerUuid, (uuid, record) -> {
            if (record.getExpiresAt() != null && record.getExpiresAt().isBefore(Instant.now())) {
                return null; // Atomically drops the expired entry.
            }
            return record;
        });
    }

    /**
     * Loads all active mutes into the cache. Called once the database is ready.
     */
    public void loadActiveMutes() {
        Database current = database;
        if (current == null) {
            return;
        }

        current.getActivePunishmentsByTypesGlobal(MUTE_TYPES)
                .thenAccept(mutes -> {
                    Instant now = Instant.now();
                    int loaded = 0;
                    for (PunishmentRecord mute : mutes) {
                        if (mute.getExpiresAt() == null || mute.getExpiresAt().isAfter(now)) {
                            activeMutes.put(mute.getVictimUuid(), mute);
                            loaded++;
                        }
                    }
                    plugin.getSLF4JLogger().info("Loaded {} active mute(s)", loaded);
                })
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().error("Failed to load active mutes", throwable);
                    return null;
                });
    }

    /**
     * Removes a mute from the cache (called by the unban scheduler on expiry).
     */
    public void removeMuteFromCache(UUID playerUuid) {
        activeMutes.remove(playerUuid);
    }

    /**
     * Drops every cached mute (called when the database is swapped out on reload).
     */
    public void clearMuteCache() {
        activeMutes.clear();
    }

    // ==================== Jail ====================

    /**
     * Jails a player.
     */
    public CompletableFuture<PunishmentResult> jailPlayer(CommandSender staff, Player victim, String reason,
                                                          Duration duration) {
        return jailPlayer(staff, victim, reason, duration, null);
    }

    /**
     * Jails a player into a specific Essentials cell.
     *
     * @param cellName the Essentials cell to use, or {@code null} for the configured default
     */
    public CompletableFuture<PunishmentResult> jailPlayer(CommandSender staff, Player victim, String reason,
                                                          Duration duration, String cellName) {
        if (!canPunish(staff, victim)) {
            return CompletableFuture.completedFuture(PunishmentResult.notPermitted());
        }

        if (!plugin.settings().jail().enabled()) {
            plugin.getSLF4JLogger().warn("Refusing to jail {}: the jail system is disabled in config",
                    victim.getName());
            return CompletableFuture.completedFuture(PunishmentResult.failed());
        }

        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, PunishmentType.JAIL, reason, duration);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(PunishmentResult.cancelled());
        }

        String finalReason = event.getReason();
        Duration finalDuration = event.getDuration();
        Instant expiresAt = finalDuration != null ? Instant.now().plus(finalDuration) : null;

        // Apply the jail first and abort if it fails. Storing an active JAIL record for a
        // player who is still walking around free would leave the database lying, and the
        // staff member would have been told the jail succeeded.
        if (!plugin.getJailManager().jailPlayer(victim, finalDuration, cellName)) {
            plugin.getSLF4JLogger().warn("Failed to jail {} - no jail location configured, or the "
                    + "Essentials integration rejected the request", victim.getName());
            return CompletableFuture.completedFuture(PunishmentResult.failed());
        }

        PunishmentRecord record = newRecord(staff, victim, PunishmentType.JAIL, finalReason, expiresAt);

        return supersede(victim.getUniqueId(), List.of(PunishmentType.JAIL), staff, "Replaced by a new jail")
                .thenCompose(ignored -> persistAndAnnounce(staff, victim.getName(), record));
    }

    /**
     * Removes a jail record.
     *
     * <p>The caller is responsible for calling {@code JailManager.releasePlayer} first;
     * this method only reconciles the database and fires the event.
     *
     * @return true if an active jail record was found and lifted
     */
    public CompletableFuture<Boolean> unjailPlayer(CommandSender staff, String playerName, String reason) {
        UUID playerUuid = resolvePlayerUuid(playerName);
        Database current = database;
        if (current == null || playerUuid == null) {
            return CompletableFuture.completedFuture(false);
        }

        return current.getActivePunishmentsByType(playerUuid, PunishmentType.JAIL)
                .thenCompose(punishments -> {
                    if (punishments.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    PunishmentRecord record = punishments.get(0);
                    return current.deactivateActivePunishments(playerUuid, List.of(PunishmentType.JAIL),
                                    staffUuid(staff), reason)
                            .thenApply(count -> {
                                if (count > 0) {
                                    announceRemoval(staff, record, reason);
                                }
                                return count > 0;
                            });
                });
    }

    // ==================== Warnings ====================

    /**
     * Warns a player and auto-bans once the threshold is reached.
     */
    public CompletableFuture<PunishmentResult> warnPlayer(CommandSender staff, Player victim, String reason) {
        if (!canPunish(staff, victim)) {
            return CompletableFuture.completedFuture(PunishmentResult.notPermitted());
        }

        PlayerPunishEvent event = new PlayerPunishEvent(staff, victim, PunishmentType.WARNING, reason, null);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return CompletableFuture.completedFuture(PunishmentResult.cancelled());
        }

        PunishmentRecord record = newRecord(staff, victim, PunishmentType.WARNING, event.getReason(), null);

        return persistAndAnnounce(staff, victim.getName(), record)
                .thenCompose(result -> {
                    if (!result.isSuccess() || database == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    return checkWarningThreshold(staff, victim).thenApply(ignored -> result);
                });
    }

    /**
     * Counts a player's warnings that still count towards the auto-ban threshold.
     */
    public CompletableFuture<Integer> getWarningCount(UUID playerUuid) {
        Database current = database;
        if (current == null) {
            return CompletableFuture.completedFuture(0);
        }
        Duration expiry = plugin.settings().warnings().expiry();
        return current.getWarningCount(playerUuid, expiry == null ? null : Instant.now().minus(expiry));
    }

    /**
     * Auto-bans a player once they reach the configured number of warnings.
     *
     * <p>The counted warnings are deactivated afterwards. Without that reset the count only
     * ever grows, so every single warning past the threshold triggered another ban.
     */
    private CompletableFuture<Void> checkWarningThreshold(CommandSender staff, Player victim) {
        Settings.Warnings warnings = plugin.settings().warnings();
        if (!warnings.enabled()) {
            return CompletableFuture.completedFuture(null);
        }

        Database current = database;
        if (current == null) {
            return CompletableFuture.completedFuture(null);
        }

        return getWarningCount(victim.getUniqueId()).thenCompose(count -> {
            if (count < warnings.autoBanThreshold()) {
                return CompletableFuture.completedFuture(null);
            }

            Duration duration = parseDuration(warnings.autoBanDuration());
            String reason = "Automatic ban: reached " + count + " warnings";

            // Consume the warnings first, so a failure to ban does not leave the player
            // primed to be banned again by their next warning.
            return current.deactivateActivePunishments(victim.getUniqueId(), List.of(PunishmentType.WARNING),
                            staffUuid(staff), "Consumed by automatic ban")
                    .thenAccept(ignored -> FoliaScheduler.runGlobal(plugin, () -> {
                        if (!victim.isOnline()) {
                            plugin.getSLF4JLogger().info("Skipping auto-ban of {}: player went offline",
                                    victim.getName());
                            return;
                        }
                        banPlayer(staff, victim, reason, duration, false).thenAccept(result ->
                                plugin.getSLF4JLogger().info("Player {} auto-banned after {} warnings ({})",
                                        victim.getName(), count, result.status()));
                    }));
        });
    }

    // ==================== Queries ====================

    /**
     * Gets punishment history for a player.
     */
    public CompletableFuture<List<PunishmentRecord>> getHistory(UUID playerUuid, int limit) {
        Database current = database;
        return current == null
                ? CompletableFuture.completedFuture(List.of())
                : current.getPunishmentsByPlayer(playerUuid, limit);
    }

    /**
     * Gets active punishments for a player.
     */
    public CompletableFuture<List<PunishmentRecord>> getActivePunishments(UUID playerUuid) {
        Database current = database;
        return current == null
                ? CompletableFuture.completedFuture(List.of())
                : current.getActivePunishments(playerUuid);
    }

    // ==================== Internals ====================

    private PunishmentRecord newRecord(CommandSender staff, Player victim, PunishmentType type,
                                       String reason, Instant expiresAt) {
        PunishmentRecord record = new PunishmentRecord(
                victim.getUniqueId(),
                victim.getName(),
                null,
                staffUuid(staff),
                staffName(staff),
                type,
                reason,
                Instant.now(),
                expiresAt);
        record.setServerName(plugin.settings().serverName());
        if (type == PunishmentType.WARNING) {
            // Warnings stay active until they are consumed by an auto-ban or expire.
            record.setActive(true);
        }
        return record;
    }

    /**
     * Deactivates punishments of the given types that are still marked active, so a player
     * never accumulates several active mutes or jails that have to be lifted one by one.
     */
    private CompletableFuture<Integer> supersede(UUID victimUuid, List<PunishmentType> types,
                                                 CommandSender staff, String reason) {
        Database current = database;
        if (current == null) {
            return CompletableFuture.completedFuture(0);
        }
        return current.deactivateActivePunishments(victimUuid, types, staffUuid(staff), reason)
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().warn("Failed to supersede previous punishments: {}", throwable.toString());
                    return 0;
                });
    }

    /**
     * Persists a record (when a database is configured), then fires the event, writes the
     * audit log and notifies Discord.
     */
    private CompletableFuture<PunishmentResult> persistAndAnnounce(CommandSender staff, String victimName,
                                                                   PunishmentRecord record) {
        Database current = database;
        if (current == null) {
            announce(staff, victimName, record);
            return CompletableFuture.completedFuture(PunishmentResult.success(0));
        }

        return current.savePunishment(record)
                .thenApply(id -> {
                    record.setId(id);
                    announce(staff, victimName, record);
                    return PunishmentResult.success(id);
                })
                .exceptionally(throwable -> {
                    // The punishment is already in effect in-game; make very sure this is not
                    // swallowed, because the record is now missing from the history.
                    plugin.getSLF4JLogger().error("Punishment for {} was applied but could NOT be saved to the "
                            + "database. It will not appear in the history.", victimName, throwable);
                    announce(staff, victimName, record);
                    return PunishmentResult.failed();
                });
    }

    private void announce(CommandSender staff, String victimName, PunishmentRecord record) {
        auditLog.logPunishment(record);

        PlayerPunishedEvent punishedEvent = new PlayerPunishedEvent(staff, victimName, record);
        FoliaScheduler.runGlobal(plugin, () -> Bukkit.getPluginManager().callEvent(punishedEvent));

        DiscordWebhook webhook = discord;
        if (webhook != null && isDiscordEnabledFor(record.getType())) {
            webhook.sendPunishment(record);
        }
    }

    private void announceRemoval(CommandSender staff, PunishmentRecord record, String reason) {
        record.setActive(false);
        record.setUnbanStaffUuid(staffUuid(staff));
        record.setUnbanReason(reason);
        record.setUnbannedAt(Instant.now());

        auditLog.logRemoval(record, staffName(staff), reason);

        FoliaScheduler.runGlobal(plugin, () ->
                Bukkit.getPluginManager().callEvent(new PlayerUnpunishedEvent(staff, record, reason, false)));

        DiscordWebhook webhook = discord;
        if (webhook != null && plugin.settings().discord().unbans()) {
            webhook.sendUnpunishment(record, staffName(staff), reason);
        }
    }

    /**
     * Honours the {@code discord.notifications.*} toggles, which were previously ignored for
     * everything except appeals.
     */
    private boolean isDiscordEnabledFor(PunishmentType type) {
        Settings.DiscordSettings settings = plugin.settings().discord();
        return switch (type) {
            case BAN, TEMP_BAN, IP_BAN -> settings.bans();
            case KICK -> settings.kicks();
            case MUTE, TEMP_MUTE -> settings.mutes();
            case JAIL -> settings.jails();
            case WARNING -> settings.warnings();
            default -> true;
        };
    }

    private static String rawAddress(Player player) {
        var address = player.getAddress();
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    private Duration parseDuration(String durationStr) {
        DurationParser.Result result = DurationParser.parse(durationStr);
        if (result.isInvalid()) {
            // Never fall back to "permanent" here - a config typo must not silently turn an
            // auto-ban into a permanent ban.
            plugin.getSLF4JLogger().warn("Could not parse duration '{}' - falling back to 7d. "
                    + "Use a format like '7d', '1h30m' or 'permanent'.", durationStr);
            return Duration.ofDays(7);
        }
        return result.orNullForPermanent();
    }

    /**
     * Resolves a player name to a UUID using only local knowledge.
     *
     * <p>Deliberately does not fall back to {@code Bukkit.getOfflinePlayer(String)}: that call
     * may block on a web request and, worse, invents an offline-mode UUID for unknown names,
     * which would silently address the wrong player's records.
     *
     * @return the UUID, or {@code null} if the name is unknown to this server
     */
    public UUID resolvePlayerUuid(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return online.getUniqueId();
        }

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(playerName);
        return cached != null ? cached.getUniqueId() : null;
    }
}
