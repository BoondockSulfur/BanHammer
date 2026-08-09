package dev.banhammer.plugin.database;

import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.database.model.PunishmentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the shared SQL layer, run against a real SQLite file.
 *
 * <p>These cover the behaviour the plugin depends on but that cannot be observed without
 * actually executing SQL: schema migration, the compare-and-set updates that stop duplicate
 * unbans, the warning window, and statistics aggregation.
 */
class SQLiteDatabaseTest {

    @TempDir
    Path tempDir;

    private SQLiteDatabase database;

    private static final UUID VICTIM = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        database = new SQLiteDatabase(LoggerFactory.getLogger("test"), tempDir.toFile(), "test.db");
        database.initialize().join();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.shutdown().join();
        }
    }

    // ==================== Basics ====================

    @Test
    void savesAndReadsBackAPunishment() {
        PunishmentRecord record = punishment(PunishmentType.BAN, null);
        record.setReason("griefing");
        record.setVictimIp("192.168.1.0");

        int id = database.savePunishment(record).join();
        assertTrue(id > 0);

        PunishmentRecord loaded = database.getPunishment(id).join();
        assertNotNull(loaded);
        assertEquals(VICTIM, loaded.getVictimUuid());
        assertEquals("griefing", loaded.getReason());
        assertEquals("192.168.1.0", loaded.getVictimIp());
        assertNull(loaded.getExpiresAt());
        assertTrue(loaded.isActive());
    }

    @Test
    @DisplayName("expiry timestamps survive the round trip")
    void expiryRoundTrip() {
        Instant expires = Instant.now().plus(Duration.ofDays(7)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        int id = database.savePunishment(punishment(PunishmentType.TEMP_BAN, expires)).join();

        assertEquals(expires, database.getPunishment(id).join().getExpiresAt());
    }

    @Test
    void countsWithoutLoadingRows() {
        for (int i = 0; i < 15; i++) {
            database.savePunishment(punishment(PunishmentType.KICK, null)).join();
        }

        assertEquals(15, database.countPunishmentsByPlayer(VICTIM).join());
        assertEquals(10, database.getPunishmentsByPlayer(VICTIM, 10).join().size());
        assertEquals(0, database.countPunishmentsByPlayer(UUID.randomUUID()).join());
    }

    // ==================== Compare-and-set ====================

    @Test
    @DisplayName("only the first deactivation of a punishment succeeds")
    void deactivateIsCompareAndSet() {
        int id = database.savePunishment(punishment(PunishmentType.BAN, null)).join();

        // Whoever wins this is the one that performs the visible side effects; without the
        // "AND active = 1" guard, a manual unban and the expiry scheduler both announced it.
        assertTrue(database.deactivatePunishment(id, STAFF, "first").join());
        assertFalse(database.deactivatePunishment(id, STAFF, "second").join());

        PunishmentRecord loaded = database.getPunishment(id).join();
        assertFalse(loaded.isActive());
        assertEquals("first", loaded.getUnbanReason());
        assertEquals(STAFF, loaded.getUnbanStaffUuid());
        assertNotNull(loaded.getUnbannedAt());
    }

    @Test
    @DisplayName("an appeal can only be decided once")
    void appealDecisionIsCompareAndSet() {
        int punishmentId = database.savePunishment(punishment(PunishmentType.BAN, null)).join();
        AppealRecord appeal = new AppealRecord(punishmentId, VICTIM, "Victim", "please unban me");
        int appealId = database.saveAppeal(appeal).join();

        appeal.setId(appealId);
        appeal.setStatus(AppealRecord.AppealStatus.APPROVED);
        appeal.setReviewedBy(STAFF);
        appeal.setReviewerName("Staff");
        appeal.setReviewResponse("ok");
        appeal.setReviewedAt(Instant.now());

        assertTrue(database.updateAppeal(appeal).join());

        AppealRecord second = new AppealRecord(punishmentId, VICTIM, "Victim", "please unban me");
        second.setId(appealId);
        second.setStatus(AppealRecord.AppealStatus.DENIED);
        assertFalse(database.updateAppeal(second).join(), "already decided");

        assertEquals(AppealRecord.AppealStatus.APPROVED, database.getAppeal(appealId).join().getStatus());
    }

    @Test
    void deactivatesEveryActivePunishmentOfAType() {
        database.savePunishment(punishment(PunishmentType.MUTE, null)).join();
        database.savePunishment(punishment(PunishmentType.TEMP_MUTE, Instant.now().plusSeconds(600))).join();
        database.savePunishment(punishment(PunishmentType.BAN, null)).join();

        int changed = database.deactivateActivePunishments(VICTIM,
                List.of(PunishmentType.MUTE, PunishmentType.TEMP_MUTE), STAFF, "unmuted").join();

        assertEquals(2, changed);
        // The ban must be untouched.
        assertEquals(1, database.getActivePunishments(VICTIM).join().size());
    }

    // ==================== Warnings ====================

    @Test
    @DisplayName("warning count honours the time window and the active flag")
    void warningCount() {
        database.savePunishment(warning(Instant.now())).join();
        database.savePunishment(warning(Instant.now().minus(Duration.ofDays(10)))).join();
        int old = database.savePunishment(warning(Instant.now().minus(Duration.ofDays(200)))).join();

        assertEquals(3, database.getWarningCount(VICTIM).join());
        assertEquals(2, database.getWarningCount(VICTIM, Instant.now().minus(Duration.ofDays(90))).join(),
                "the 200-day-old warning is outside the window");

        // Consumed by an auto-ban: it must stop counting.
        database.deactivatePunishment(old, STAFF, "consumed").join();
        assertEquals(2, database.getWarningCount(VICTIM).join());
    }

    // ==================== Expiry ====================

    @Test
    void findsOnlyExpiredActivePunishments() {
        int expired = database.savePunishment(
                punishment(PunishmentType.TEMP_BAN, Instant.now().minusSeconds(60))).join();
        database.savePunishment(punishment(PunishmentType.TEMP_BAN, Instant.now().plusSeconds(600))).join();
        database.savePunishment(punishment(PunishmentType.BAN, null)).join();

        List<PunishmentRecord> due = database.getExpiredPunishments().join();
        assertEquals(1, due.size());
        assertEquals(expired, due.get(0).getId());

        database.deactivatePunishment(expired, null, "expired").join();
        assertTrue(database.getExpiredPunishments().join().isEmpty(),
                "a deactivated record must not be picked up again");
    }

    // ==================== Statistics ====================

    @Test
    @DisplayName("staff statistics group by UUID, not by name")
    void statisticsSurviveANameChange() {
        // Same account, two names - grouping by name split this into two rows and the
        // single-staff query then reported only the first fragment.
        database.savePunishment(byStaff("OldName", PunishmentType.BAN)).join();
        database.savePunishment(byStaff("OldName", PunishmentType.KICK)).join();
        database.savePunishment(byStaff("NewName", PunishmentType.MUTE)).join();
        database.savePunishment(byStaff("NewName", PunishmentType.JAIL)).join();

        PunishmentStatistics stats = database.getStaffStatistics(STAFF).join();
        assertEquals(4, stats.getTotalPunishments());
        assertEquals(1, stats.getBans());
        assertEquals(1, stats.getKicks());
        assertEquals(1, stats.getMutes());
        assertEquals(1, stats.getJails());

        assertEquals(1, database.getStaffStatistics(10).join().size(), "one row per account");
    }

    @Test
    @DisplayName("per-type counts add up to the total")
    void serverStatisticsAddUp() {
        database.savePunishment(punishment(PunishmentType.BAN, null)).join();
        database.savePunishment(punishment(PunishmentType.KICK, null)).join();
        database.savePunishment(punishment(PunishmentType.JAIL, null)).join();
        database.savePunishment(warning(Instant.now())).join();

        PunishmentStatistics stats = database.getServerStatistics().join();
        assertEquals(4, stats.getTotalPunishments());
        assertEquals(stats.getTotalPunishments(),
                stats.getBans() + stats.getKicks() + stats.getMutes() + stats.getJails() + stats.getWarnings(),
                "JAIL used to be missing from the buckets, so the parts never matched the whole");
    }

    // ==================== Appeals ====================

    @Test
    void appealsAreCountedPerPunishment() {
        int first = database.savePunishment(punishment(PunishmentType.BAN, null)).join();
        int second = database.savePunishment(punishment(PunishmentType.MUTE, null)).join();

        database.saveAppeal(new AppealRecord(first, VICTIM, "Victim", "one")).join();
        database.saveAppeal(new AppealRecord(first, VICTIM, "Victim", "two")).join();
        database.saveAppeal(new AppealRecord(second, VICTIM, "Victim", "three")).join();

        assertEquals(2, database.countAppealsForPunishment(first).join());
        assertEquals(1, database.countAppealsForPunishment(second).join());
        assertEquals(3, database.getPendingAppeals(10).join().size());
        assertEquals(2, database.getPendingAppeals(2).join().size(), "the limit is applied");
    }

    // ==================== Retention ====================

    @Test
    void purgeRespectsTheCutoffAndActiveRecords() {
        int oldActive = database.savePunishment(
                aged(PunishmentType.BAN, Instant.now().minus(Duration.ofDays(400)), true)).join();
        int oldInactive = database.savePunishment(
                aged(PunishmentType.KICK, Instant.now().minus(Duration.ofDays(400)), false)).join();
        int recent = database.savePunishment(punishment(PunishmentType.KICK, null)).join();
        database.saveAppeal(new AppealRecord(oldInactive, VICTIM, "Victim", "old appeal")).join();

        Instant cutoff = Instant.now().minus(Duration.ofDays(365));

        assertEquals(1, database.purgeOldPunishments(cutoff, true).join(), "keeps the active one");
        assertNotNull(database.getPunishment(oldActive).join());
        assertNull(database.getPunishment(oldInactive).join());
        assertNotNull(database.getPunishment(recent).join());
        assertEquals(0, database.countAppealsForPunishment(oldInactive).join(), "its appeal went too");

        assertEquals(1, database.purgeOldPunishments(cutoff, false).join(), "now the active one goes");
        assertNull(database.getPunishment(oldActive).join());
    }

    // ==================== Migration ====================

    @Test
    @DisplayName("a database from an older version gains the missing columns")
    void migratesLegacySchema() throws Exception {
        SQLiteDatabase legacy = null;
        try {
            Path file = tempDir.resolve("legacy.db");
            createLegacySchema(file);

            legacy = new SQLiteDatabase(LoggerFactory.getLogger("test"), tempDir.toFile(), "legacy.db");
            // Previously CREATE TABLE IF NOT EXISTS silently did nothing here, and the first
            // write failed with "no such column: server_name" - after the punishment had
            // already been applied in-game.
            legacy.initialize().join();

            PunishmentRecord record = punishment(PunishmentType.BAN, null);
            record.setServerName("Survival");
            record.setVictimIp("10.0.0.0");
            int id = legacy.savePunishment(record).join();

            PunishmentRecord loaded = legacy.getPunishment(id).join();
            assertEquals("Survival", loaded.getServerName());
            assertEquals("10.0.0.0", loaded.getVictimIp());

            // The pre-existing row must still be readable.
            assertEquals(2, legacy.countPunishmentsByPlayer(VICTIM).join());
        } finally {
            if (legacy != null) {
                legacy.shutdown().join();
            }
        }
    }

    @Test
    void migrationIsIdempotent() {
        // Re-initializing an already-current database must not fail or duplicate anything.
        database.shutdown().join();
        database = new SQLiteDatabase(LoggerFactory.getLogger("test"), tempDir.toFile(), "test.db");
        database.initialize().join();

        int id = database.savePunishment(punishment(PunishmentType.BAN, null)).join();
        assertNotNull(database.getPunishment(id).join());
    }

    /** Writes the punishments/appeals tables as an older BanHammer version created them. */
    private void createLegacySchema(Path file) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE punishments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    victim_uuid TEXT NOT NULL,
                    victim_name TEXT NOT NULL,
                    staff_uuid TEXT NOT NULL,
                    staff_name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    reason TEXT,
                    issued_at INTEGER NOT NULL,
                    expires_at INTEGER,
                    active INTEGER NOT NULL DEFAULT 1
                )
                """);
            stmt.execute("""
                CREATE TABLE appeals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    punishment_id INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    appeal_text TEXT NOT NULL,
                    submitted_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    reviewed_by TEXT
                )
                """);
            stmt.execute("INSERT INTO punishments "
                    + "(victim_uuid, victim_name, staff_uuid, staff_name, type, reason, issued_at, active) "
                    + "VALUES ('" + VICTIM + "', 'Victim', '" + STAFF + "', 'Staff', 'KICK', 'old', "
                    + System.currentTimeMillis() + ", 0)");
        }
    }

    // ==================== Fixtures ====================

    private PunishmentRecord punishment(PunishmentType type, Instant expiresAt) {
        return new PunishmentRecord(VICTIM, "Victim", null, STAFF, "Staff", type, "reason",
                Instant.now(), expiresAt);
    }

    private PunishmentRecord warning(Instant issuedAt) {
        return new PunishmentRecord(VICTIM, "Victim", null, STAFF, "Staff", PunishmentType.WARNING,
                "warned", issuedAt, null);
    }

    private PunishmentRecord byStaff(String staffName, PunishmentType type) {
        return new PunishmentRecord(UUID.randomUUID(), "Victim", null, STAFF, staffName, type,
                "reason", Instant.now(), null);
    }

    private PunishmentRecord aged(PunishmentType type, Instant issuedAt, boolean active) {
        PunishmentRecord record = new PunishmentRecord(VICTIM, "Victim", null, STAFF, "Staff", type,
                "reason", issuedAt, null);
        record.setActive(active);
        return record;
    }
}
