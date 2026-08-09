package dev.banhammer.plugin.database;

import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.database.model.PunishmentType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Database interface for storing punishment records.
 *
 * <p>All methods return futures completed on BanHammer's own database executor. Callers that
 * touch the Bukkit API in a continuation must hop back to the main thread first (see
 * {@code FoliaScheduler}), and should always attach an error handler - a dropped exception
 * here means a punishment silently disappears.
 *
 * @since 3.0.0
 */
public interface Database {

    /**
     * Initializes the connection pool, creates the schema and applies pending migrations.
     *
     * @return CompletableFuture that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Drains in-flight work and closes the connection pool.
     *
     * @return CompletableFuture that completes when shutdown is done
     */
    CompletableFuture<Void> shutdown();

    /**
     * Checks whether the database is believed to be usable. Cheap and non-blocking;
     * use {@link #ping()} for an authoritative check.
     *
     * @return true if connected
     */
    boolean isConnected();

    /**
     * Actively verifies the connection.
     *
     * @return CompletableFuture with true if a connection could be validated
     */
    CompletableFuture<Boolean> ping();

    // ==================== Punishments ====================

    /**
     * Saves a punishment record.
     *
     * @param record the punishment record to save
     * @return CompletableFuture with the generated ID
     */
    CompletableFuture<Integer> savePunishment(PunishmentRecord record);

    /**
     * Gets a punishment record by ID.
     *
     * @param id the punishment ID
     * @return CompletableFuture with the record, or null if not found
     */
    CompletableFuture<PunishmentRecord> getPunishment(int id);

    /**
     * Gets punishments for a player, newest first.
     *
     * @param playerUuid the player's UUID
     * @param limit      maximum number of records to return
     * @return CompletableFuture with the list of punishments
     */
    CompletableFuture<List<PunishmentRecord>> getPunishmentsByPlayer(UUID playerUuid, int limit);

    /**
     * Counts all punishments for a player, for pagination without loading the rows.
     *
     * @param playerUuid the player's UUID
     * @return CompletableFuture with the total count
     */
    CompletableFuture<Integer> countPunishmentsByPlayer(UUID playerUuid);

    /**
     * Gets all active punishments for a player.
     *
     * @param playerUuid the player's UUID
     * @return CompletableFuture with the list of active punishments
     */
    CompletableFuture<List<PunishmentRecord>> getActivePunishments(UUID playerUuid);

    /**
     * Gets all active punishments of a type for a player.
     *
     * @param playerUuid the player's UUID
     * @param type       the punishment type
     * @return CompletableFuture with the list of active punishments
     */
    CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByType(UUID playerUuid, PunishmentType type);

    /**
     * Gets all active punishments of a type across all players.
     *
     * @param type the punishment type
     * @return CompletableFuture with the list of active punishments
     */
    CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByTypeGlobal(PunishmentType type);

    /**
     * Gets all active punishments of several types across all players, in one query.
     * Used on startup so restoring state does not issue one query per online player.
     *
     * @param types the punishment types
     * @return CompletableFuture with the list of active punishments
     */
    CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByTypesGlobal(Collection<PunishmentType> types);

    /**
     * Gets punishments issued by a staff member, newest first.
     *
     * @param staffUuid the staff member's UUID
     * @param limit     maximum number of records to return
     * @return CompletableFuture with the list of punishments
     */
    CompletableFuture<List<PunishmentRecord>> getPunishmentsByStaff(UUID staffUuid, int limit);

    /**
     * Gets expired temporary punishments that are still marked active.
     *
     * @return CompletableFuture with the list of expired punishments
     */
    CompletableFuture<List<PunishmentRecord>> getExpiredPunishments();

    /**
     * Persists all mutable fields of a punishment record.
     *
     * @param record the updated record
     * @return CompletableFuture that completes when the update is done
     */
    CompletableFuture<Void> updatePunishment(PunishmentRecord record);

    /**
     * Deactivates a punishment, but only if it is still active.
     *
     * @param punishmentId the punishment ID
     * @param staffUuid    the staff member who removed the punishment, or null if automatic
     * @param reason       the reason for removal
     * @return CompletableFuture with true if this call was the one that deactivated it
     */
    CompletableFuture<Boolean> deactivatePunishment(int punishmentId, UUID staffUuid, String reason);

    /**
     * Deactivates every active punishment of the given types for a player.
     * Used to keep at most one active mute/jail per player.
     *
     * @param playerUuid the player's UUID
     * @param types      the punishment types to deactivate
     * @param staffUuid  the acting staff member, or null if automatic
     * @param reason     the reason for removal
     * @return CompletableFuture with the number of rows deactivated
     */
    CompletableFuture<Integer> deactivateActivePunishments(UUID playerUuid, Collection<PunishmentType> types,
                                                          UUID staffUuid, String reason);

    /**
     * Counts a player's active warnings.
     *
     * @param playerUuid the player's UUID
     * @return CompletableFuture with the warning count
     */
    CompletableFuture<Integer> getWarningCount(UUID playerUuid);

    /**
     * Counts a player's active warnings issued at or after a point in time.
     *
     * @param playerUuid the player's UUID
     * @param since      the earliest issue time to count, or null for no limit
     * @return CompletableFuture with the warning count
     */
    CompletableFuture<Integer> getWarningCount(UUID playerUuid, Instant since);

    /**
     * Deletes punishments (and their appeals) older than a cutoff, for data retention.
     *
     * @param cutoff     delete records issued before this instant
     * @param keepActive whether to preserve records that are still active
     * @return CompletableFuture with the number of punishments deleted
     */
    CompletableFuture<Integer> purgeOldPunishments(Instant cutoff, boolean keepActive);

    // ==================== Statistics ====================

    /**
     * Gets punishment statistics per staff member, most active first.
     *
     * @param limit maximum number of staff to return
     * @return CompletableFuture with the list of statistics
     */
    CompletableFuture<List<PunishmentStatistics>> getStaffStatistics(int limit);

    /**
     * Gets punishment statistics for a specific staff member.
     *
     * @param staffUuid the staff member's UUID
     * @return CompletableFuture with the statistics
     */
    CompletableFuture<PunishmentStatistics> getStaffStatistics(UUID staffUuid);

    /**
     * Gets server-wide punishment totals, aggregated in the database.
     *
     * @return CompletableFuture with the statistics
     */
    CompletableFuture<PunishmentStatistics> getServerStatistics();

    // ==================== Appeals ====================

    /**
     * Saves an appeal.
     *
     * @param appeal the appeal record
     * @return CompletableFuture with the generated ID
     */
    CompletableFuture<Integer> saveAppeal(AppealRecord appeal);

    /**
     * Gets an appeal by ID.
     *
     * @param id the appeal ID
     * @return CompletableFuture with the appeal, or null if not found
     */
    CompletableFuture<AppealRecord> getAppeal(int id);

    /**
     * Gets pending appeals, oldest first.
     *
     * @param limit maximum number of appeals to return
     * @return CompletableFuture with the list of pending appeals
     */
    CompletableFuture<List<AppealRecord>> getPendingAppeals(int limit);

    /**
     * Gets all appeals submitted by a player, newest first.
     *
     * @param playerUuid the player's UUID
     * @return CompletableFuture with the list of appeals
     */
    CompletableFuture<List<AppealRecord>> getAppealsByPlayer(UUID playerUuid);

    /**
     * Counts the appeals filed against a punishment.
     *
     * @param punishmentId the punishment ID
     * @return CompletableFuture with the appeal count
     */
    CompletableFuture<Integer> countAppealsForPunishment(int punishmentId);

    /**
     * Records an appeal decision, but only if the appeal is still pending.
     *
     * @param appeal the updated appeal
     * @return CompletableFuture with true if this call was the one that decided it
     */
    CompletableFuture<Boolean> updateAppeal(AppealRecord appeal);
}
