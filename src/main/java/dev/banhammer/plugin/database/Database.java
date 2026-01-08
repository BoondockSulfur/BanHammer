package dev.banhammer.plugin.database;

import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.database.model.PunishmentType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Database interface for storing punishment records.
 *
 * @since 3.0.0
 */
public interface Database {

    /**
     * Initializes the database connection and creates tables if needed.
     *
     * @return CompletableFuture that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Closes the database connection.
     *
     * @return CompletableFuture that completes when shutdown is done
     */
    CompletableFuture<Void> shutdown();

    /**
     * Saves a punishment record to the database.
     *
     * @param record The punishment record to save
     * @return CompletableFuture with the generated ID
     */
    CompletableFuture<Integer> savePunishment(PunishmentRecord record);

    /**
     * Gets a punishment record by ID.
     *
     * @param id The punishment ID
     * @return CompletableFuture with the record, or null if not found
     */
    CompletableFuture<PunishmentRecord> getPunishment(int id);

    /**
     * Gets all punishments for a specific player.
     *
     * @param playerUuid The player's UUID
     * @param limit Maximum number of records to return
     * @return CompletableFuture with the list of punishments
     */
    CompletableFuture<List<PunishmentRecord>> getPunishmentsByPlayer(UUID playerUuid, int limit);

    /**
     * Gets all active punishments for a specific player.
     *
     * @param playerUuid The player's UUID
     * @return CompletableFuture with the list of active punishments
     */
    CompletableFuture<List<PunishmentRecord>> getActivePunishments(UUID playerUuid);

    /**
     * Gets all active punishments of a specific type for a player.
     *
     * @param playerUuid The player's UUID
     * @param type The punishment type
     * @return CompletableFuture with the list of active punishments
     */
    CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByType(UUID playerUuid, PunishmentType type);

    /**
     * Gets all active punishments of a specific type across all players.
     * Used for loading caches on startup.
     *
     * @param type The punishment type
     * @return CompletableFuture with the list of active punishments
     */
    CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByTypeGlobal(PunishmentType type);

    /**
     * Gets all punishments issued by a specific staff member.
     *
     * @param staffUuid The staff member's UUID
     * @param limit Maximum number of records to return
     * @return CompletableFuture with the list of punishments
     */
    CompletableFuture<List<PunishmentRecord>> getPunishmentsByStaff(UUID staffUuid, int limit);

    /**
     * Gets all expired temporary punishments that are still marked as active.
     *
     * @return CompletableFuture with the list of expired punishments
     */
    CompletableFuture<List<PunishmentRecord>> getExpiredPunishments();

    /**
     * Updates a punishment record (e.g., to mark it as inactive).
     *
     * @param record The updated record
     * @return CompletableFuture that completes when update is done
     */
    CompletableFuture<Void> updatePunishment(PunishmentRecord record);

    /**
     * Deactivates a punishment (unban/unmute).
     *
     * @param punishmentId The punishment ID
     * @param staffUuid The staff member who removed the punishment
     * @param reason The reason for removal
     * @return CompletableFuture that completes when deactivation is done
     */
    CompletableFuture<Void> deactivatePunishment(int punishmentId, UUID staffUuid, String reason);

    /**
     * Gets punishment statistics for all staff members.
     *
     * @param limit Maximum number of staff to return
     * @return CompletableFuture with the list of statistics
     */
    CompletableFuture<List<PunishmentStatistics>> getStaffStatistics(int limit);

    /**
     * Gets punishment statistics for a specific staff member.
     *
     * @param staffUuid The staff member's UUID
     * @return CompletableFuture with the statistics
     */
    CompletableFuture<PunishmentStatistics> getStaffStatistics(UUID staffUuid);

    /**
     * Saves an appeal to the database.
     *
     * @param appeal The appeal record
     * @return CompletableFuture with the generated ID
     */
    CompletableFuture<Integer> saveAppeal(AppealRecord appeal);

    /**
     * Gets an appeal by ID.
     *
     * @param id The appeal ID
     * @return CompletableFuture with the appeal, or null if not found
     */
    CompletableFuture<AppealRecord> getAppeal(int id);

    /**
     * Gets all pending appeals.
     *
     * @return CompletableFuture with the list of pending appeals
     */
    CompletableFuture<List<AppealRecord>> getPendingAppeals();

    /**
     * Gets all appeals submitted by a specific player.
     *
     * @param playerUuid The player's UUID
     * @return CompletableFuture with the list of appeals
     */
    CompletableFuture<List<AppealRecord>> getAppealsByPlayer(UUID playerUuid);

    /**
     * Updates an appeal record.
     *
     * @param appeal The updated appeal
     * @return CompletableFuture that completes when update is done
     */
    CompletableFuture<Void> updateAppeal(AppealRecord appeal);

    /**
     * Checks if the database is currently connected.
     *
     * @return true if connected, false otherwise
     */
    boolean isConnected();

    /**
     * Counts the number of warnings for a specific player.
     *
     * @param playerUuid The player's UUID
     * @return CompletableFuture with the warning count
     */
    CompletableFuture<Integer> getWarningCount(UUID playerUuid);
}
