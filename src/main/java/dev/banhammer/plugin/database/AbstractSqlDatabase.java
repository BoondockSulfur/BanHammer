package dev.banhammer.plugin.database;

import com.zaxxer.hikari.HikariDataSource;
import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.database.model.PunishmentType;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Shared JDBC implementation for all SQL backends.
 *
 * <p>Every query lives here exactly once. Previously SQLite and MySQL each carried their own
 * copy of the same twenty statements, which is how they drifted apart (different column
 * types, a foreign key with {@code ON DELETE CASCADE} on one side only, and so on).
 * Subclasses now contribute only the connection pool and the dialect-specific DDL.
 *
 * <p>Work runs on a dedicated, bounded, daemon thread pool rather than
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}: blocking JDBC calls must not
 * occupy a pool that is shared with the server and every other plugin, and on a host that
 * sets {@code -Djava.util.concurrent.ForkJoinPool.common.parallelism=0} the common pool runs
 * tasks on the calling thread - which would put database I/O on the main thread.
 */
public abstract class AbstractSqlDatabase implements Database {

    /** Bumped whenever {@link #applyMigrations(Connection)} gains a new step. */
    protected static final int SCHEMA_VERSION = 2;

    protected final Logger logger;
    protected volatile HikariDataSource dataSource;

    private volatile ExecutorService executor;
    private volatile boolean healthy;

    protected AbstractSqlDatabase(Logger logger) {
        this.logger = logger;
    }

    // ==================== Subclass contract ====================

    /** Human-readable backend name, used in log messages. */
    protected abstract String displayName();

    /** Builds and opens the connection pool. */
    protected abstract HikariDataSource createDataSource() throws Exception;

    /** Number of worker threads; should match the connection pool size. */
    protected abstract int workerThreads();

    /** {@code CREATE TABLE}/{@code CREATE INDEX} statements for a fresh database. */
    protected abstract List<String> schemaStatements();

    /** Column type used for short text values in this dialect. */
    protected abstract String textType();

    /** Column type used for epoch-millisecond timestamps in this dialect. */
    protected abstract String timestampType();

    // ==================== Lifecycle ====================

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                dataSource = createDataSource();
                executor = Executors.newFixedThreadPool(Math.max(1, workerThreads()), namedThreadFactory());

                try (Connection conn = dataSource.getConnection()) {
                    createSchema(conn);
                    applyMigrations(conn);
                }

                healthy = true;
                logger.info("{} database initialized", displayName());
            } catch (Exception e) {
                // Release anything that was already opened, otherwise a failed or timed-out
                // initialization leaks the pool and its housekeeping threads.
                closeQuietly();
                if (e instanceof ClassNotFoundException) {
                    logger.error("The {} JDBC driver is missing. It is downloaded at startup via the "
                            + "'libraries' entry in plugin.yml - check that the server could reach "
                            + "Maven Central on first launch.", displayName(), e);
                } else {
                    logger.error("Failed to initialize {} database", displayName(), e);
                }
                throw new CompletionException(e);
            }
        });
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "BanHammer-DB-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        // Runs on its own thread: the pooled executor is exactly what we are draining, and
        // the caller may want to time this out.
        return CompletableFuture.runAsync(() -> {
            healthy = false;

            ExecutorService current = executor;
            if (current != null) {
                current.shutdown();
                try {
                    if (!current.awaitTermination(10, TimeUnit.SECONDS)) {
                        logger.warn("Database tasks still running after 10s, cancelling them");
                        current.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    current.shutdownNow();
                }
                executor = null;
            }

            closeQuietly();
            logger.info("{} database connection closed", displayName());
        }, runnable -> {
            Thread thread = new Thread(runnable, "BanHammer-DB-Shutdown");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private void closeQuietly() {
        HikariDataSource source = dataSource;
        if (source != null && !source.isClosed()) {
            try {
                source.close();
            } catch (Exception e) {
                logger.warn("Failed to close the connection pool cleanly: {}", e.toString());
            }
        }
        dataSource = null;
    }

    @Override
    public boolean isConnected() {
        HikariDataSource source = dataSource;
        return source != null && !source.isClosed() && healthy;
    }

    @Override
    public CompletableFuture<Boolean> ping() {
        return run("ping", conn -> conn.isValid(5))
                .exceptionally(throwable -> false);
    }

    // ==================== Schema ====================

    private void createSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String ddl : schemaStatements()) {
                stmt.execute(ddl);
            }
        }
    }

    /**
     * Brings a database created by an older BanHammer version up to date.
     *
     * <p>{@code CREATE TABLE IF NOT EXISTS} silently does nothing when the table already
     * exists, so a plugin update that adds a column used to leave the schema stale until the
     * first write failed with "no such column" - after the punishment had already been
     * applied in-game. Columns are therefore reconciled against the live metadata.
     */
    private void applyMigrations(Connection conn) throws SQLException {
        ensureSchemaVersionTable(conn);
        int from = readSchemaVersion(conn);

        // Additive columns, safe to run against any older layout.
        ensureColumn(conn, "punishments", "victim_ip", textType());
        ensureColumn(conn, "punishments", "server_name", textType());
        ensureColumn(conn, "punishments", "unban_staff_uuid", textType());
        ensureColumn(conn, "punishments", "unban_reason", textType());
        ensureColumn(conn, "punishments", "unbanned_at", timestampType());
        ensureColumn(conn, "appeals", "reviewer_name", textType());
        ensureColumn(conn, "appeals", "review_response", textType());
        ensureColumn(conn, "appeals", "reviewed_at", timestampType());

        applyDialectMigrations(conn, from);

        if (from < SCHEMA_VERSION) {
            writeSchemaVersion(conn, SCHEMA_VERSION);
            if (from > 0) {
                logger.info("Migrated database schema from version {} to {}", from, SCHEMA_VERSION);
            }
        }
    }

    /**
     * Hook for migrations that only make sense in one dialect.
     *
     * @param conn the connection to migrate on
     * @param from the schema version found on disk, 0 for a database predating versioning
     */
    protected void applyDialectMigrations(Connection conn, int from) throws SQLException {
        // No-op by default.
    }

    private void ensureSchemaVersionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS banhammer_schema (version INTEGER NOT NULL)");
        }
    }

    private int readSchemaVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM banhammer_schema")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void writeSchemaVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM banhammer_schema");
        }
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO banhammer_schema (version) VALUES (?)")) {
            stmt.setInt(1, version);
            stmt.executeUpdate();
        }
    }

    private void ensureColumn(Connection conn, String table, String column, String type) throws SQLException {
        if (hasColumn(conn, table, column)) {
            return;
        }
        logger.info("Adding missing column {}.{} to the {} database", table, column, displayName());
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        // Column-name matching is case- and quoting-sensitive across drivers, so enumerate.
        try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== Execution helper ====================

    /** A unit of JDBC work; may throw {@link SQLException}. */
    @FunctionalInterface
    protected interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /**
     * Runs JDBC work on the database executor, with uniform logging and health tracking.
     */
    protected <T> CompletableFuture<T> run(String description, SqlWork<T> work) {
        ExecutorService current = executor;
        HikariDataSource source = dataSource;
        if (current == null || source == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Database is not initialized (" + description + ")"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = source.getConnection()) {
                T result = work.apply(conn);
                healthy = true;
                return result;
            } catch (SQLException e) {
                if (isConnectionProblem(e)) {
                    healthy = false;
                }
                logger.error("Database operation failed: {}", description, e);
                throw new CompletionException(e);
            }
        }, current);
    }

    private static boolean isConnectionProblem(SQLException e) {
        String state = e.getSQLState();
        // Class 08 is "connection exception" in the SQL standard.
        return state != null && state.startsWith("08");
    }

    // ==================== Punishments ====================

    @Override
    public CompletableFuture<Integer> savePunishment(PunishmentRecord record) {
        return run("savePunishment", conn -> {
            String sql = """
                INSERT INTO punishments (victim_uuid, victim_name, victim_ip, staff_uuid, staff_name,
                                        type, reason, issued_at, expires_at, active, server_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, record.getVictimUuid().toString());
                stmt.setString(2, record.getVictimName());
                stmt.setString(3, record.getVictimIp());
                stmt.setString(4, record.getStaffUuid().toString());
                stmt.setString(5, record.getStaffName());
                stmt.setString(6, record.getType().name());
                stmt.setString(7, record.getReason());
                stmt.setLong(8, record.getIssuedAt().toEpochMilli());
                setNullableLong(stmt, 9, record.getExpiresAt());
                stmt.setInt(10, record.isActive() ? 1 : 0);
                stmt.setString(11, record.getServerName());

                stmt.executeUpdate();

                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        int id = keys.getInt(1);
                        record.setId(id);
                        return id;
                    }
                }
                throw new SQLException("Failed to retrieve generated ID");
            }
        });
    }

    @Override
    public CompletableFuture<PunishmentRecord> getPunishment(int id) {
        return run("getPunishment", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM punishments WHERE id = ?")) {
                stmt.setInt(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? mapPunishment(rs) : null;
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getPunishmentsByPlayer(UUID playerUuid, int limit) {
        return run("getPunishmentsByPlayer", conn -> {
            String sql = "SELECT * FROM punishments WHERE victim_uuid = ? ORDER BY issued_at DESC LIMIT ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setInt(2, limit);
                return mapPunishments(stmt);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countPunishmentsByPlayer(UUID playerUuid) {
        return run("countPunishmentsByPlayer", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM punishments WHERE victim_uuid = ?")) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getActivePunishments(UUID playerUuid) {
        return run("getActivePunishments", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM punishments WHERE victim_uuid = ? AND active = 1")) {
                stmt.setString(1, playerUuid.toString());
                return mapPunishments(stmt);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByType(UUID playerUuid, PunishmentType type) {
        return run("getActivePunishmentsByType", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT * FROM punishments WHERE victim_uuid = ? AND type = ? AND active = 1")) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, type.name());
                return mapPunishments(stmt);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByTypeGlobal(PunishmentType type) {
        return getActivePunishmentsByTypesGlobal(List.of(type));
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByTypesGlobal(Collection<PunishmentType> types) {
        if (types.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return run("getActivePunishmentsByTypesGlobal", conn -> {
            String sql = "SELECT * FROM punishments WHERE active = 1 AND type IN (" + placeholders(types.size()) + ")";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                int index = 1;
                for (PunishmentType type : types) {
                    stmt.setString(index++, type.name());
                }
                return mapPunishments(stmt);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getPunishmentsByStaff(UUID staffUuid, int limit) {
        return run("getPunishmentsByStaff", conn -> {
            String sql = "SELECT * FROM punishments WHERE staff_uuid = ? ORDER BY issued_at DESC LIMIT ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, staffUuid.toString());
                stmt.setInt(2, limit);
                return mapPunishments(stmt);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getExpiredPunishments() {
        return run("getExpiredPunishments", conn -> {
            String sql = "SELECT * FROM punishments WHERE active = 1 AND expires_at IS NOT NULL AND expires_at < ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, Instant.now().toEpochMilli());
                return mapPunishments(stmt);
            }
        });
    }

    @Override
    public CompletableFuture<Void> updatePunishment(PunishmentRecord record) {
        return run("updatePunishment", conn -> {
            String sql = """
                UPDATE punishments
                SET victim_name = ?, reason = ?, expires_at = ?, active = ?,
                    unban_staff_uuid = ?, unban_reason = ?, unbanned_at = ?
                WHERE id = ?
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, record.getVictimName());
                stmt.setString(2, record.getReason());
                setNullableLong(stmt, 3, record.getExpiresAt());
                stmt.setInt(4, record.isActive() ? 1 : 0);
                stmt.setString(5, record.getUnbanStaffUuid() != null ? record.getUnbanStaffUuid().toString() : null);
                stmt.setString(6, record.getUnbanReason());
                setNullableLong(stmt, 7, record.getUnbannedAt());
                stmt.setInt(8, record.getId());
                stmt.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deactivatePunishment(int punishmentId, UUID staffUuid, String reason) {
        return run("deactivatePunishment", conn -> {
            String sql = """
                UPDATE punishments
                SET active = 0, unban_staff_uuid = ?, unban_reason = ?, unbanned_at = ?
                WHERE id = ? AND active = 1
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, staffUuid != null ? staffUuid.toString() : null);
                stmt.setString(2, reason);
                stmt.setLong(3, Instant.now().toEpochMilli());
                stmt.setInt(4, punishmentId);
                // "AND active = 1" makes this a compare-and-set: two concurrent /unban calls
                // cannot both believe they were the one that lifted the punishment.
                return stmt.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deactivateActivePunishments(UUID playerUuid, Collection<PunishmentType> types,
                                                                 UUID staffUuid, String reason) {
        if (types.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        return run("deactivateActivePunishments", conn -> {
            String sql = "UPDATE punishments SET active = 0, unban_staff_uuid = ?, unban_reason = ?, unbanned_at = ?"
                    + " WHERE victim_uuid = ? AND active = 1 AND type IN (" + placeholders(types.size()) + ")";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, staffUuid != null ? staffUuid.toString() : null);
                stmt.setString(2, reason);
                stmt.setLong(3, Instant.now().toEpochMilli());
                stmt.setString(4, playerUuid.toString());
                int index = 5;
                for (PunishmentType type : types) {
                    stmt.setString(index++, type.name());
                }
                return stmt.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> getWarningCount(UUID playerUuid) {
        return getWarningCount(playerUuid, null);
    }

    @Override
    public CompletableFuture<Integer> getWarningCount(UUID playerUuid, Instant since) {
        return run("getWarningCount", conn -> {
            String sql = "SELECT COUNT(*) FROM punishments WHERE victim_uuid = ? AND type = 'WARNING' AND active = 1"
                    + (since != null ? " AND issued_at >= ?" : "");
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                if (since != null) {
                    stmt.setLong(2, since.toEpochMilli());
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> purgeOldPunishments(Instant cutoff, boolean keepActive) {
        return run("purgeOldPunishments", conn -> {
            // Appeals reference punishments, so they have to go first regardless of whether
            // the dialect declares ON DELETE CASCADE.
            String appealSql = "DELETE FROM appeals WHERE punishment_id IN ("
                    + "SELECT id FROM punishments WHERE issued_at < ?" + (keepActive ? " AND active = 0" : "") + ")";
            try (PreparedStatement stmt = conn.prepareStatement(appealSql)) {
                stmt.setLong(1, cutoff.toEpochMilli());
                stmt.executeUpdate();
            }

            String sql = "DELETE FROM punishments WHERE issued_at < ?" + (keepActive ? " AND active = 0" : "");
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, cutoff.toEpochMilli());
                return stmt.executeUpdate();
            }
        });
    }

    // ==================== Statistics ====================

    /**
     * Grouped by UUID only. Including {@code staff_name} in the GROUP BY split a staff
     * member's record in two after a Minecraft name change, which made the single-staff
     * query report only the first fragment and duplicated them in the leaderboard.
     */
    private static final String STATS_COLUMNS = """
            staff_uuid,
            MAX(staff_name) AS staff_name,
            COUNT(*) AS total,
            SUM(CASE WHEN type IN ('BAN', 'TEMP_BAN', 'IP_BAN') THEN 1 ELSE 0 END) AS bans,
            SUM(CASE WHEN type = 'KICK' THEN 1 ELSE 0 END) AS kicks,
            SUM(CASE WHEN type IN ('MUTE', 'TEMP_MUTE') THEN 1 ELSE 0 END) AS mutes,
            SUM(CASE WHEN type = 'JAIL' THEN 1 ELSE 0 END) AS jails,
            SUM(CASE WHEN type = 'WARNING' THEN 1 ELSE 0 END) AS warnings
            """;

    @Override
    public CompletableFuture<List<PunishmentStatistics>> getStaffStatistics(int limit) {
        return run("getStaffStatistics", conn -> {
            String sql = "SELECT " + STATS_COLUMNS
                    + " FROM punishments GROUP BY staff_uuid ORDER BY total DESC LIMIT ?";
            List<PunishmentStatistics> stats = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        stats.add(mapStatistics(rs));
                    }
                }
            }
            return stats;
        });
    }

    @Override
    public CompletableFuture<PunishmentStatistics> getStaffStatistics(UUID staffUuid) {
        return run("getStaffStatistics(uuid)", conn -> {
            String sql = "SELECT " + STATS_COLUMNS
                    + " FROM punishments WHERE staff_uuid = ? GROUP BY staff_uuid";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, staffUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapStatistics(rs);
                    }
                }
            }
            return new PunishmentStatistics(staffUuid, "Unknown");
        });
    }

    @Override
    public CompletableFuture<PunishmentStatistics> getServerStatistics() {
        return run("getServerStatistics", conn -> {
            String sql = """
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN type IN ('BAN', 'TEMP_BAN', 'IP_BAN') THEN 1 ELSE 0 END) AS bans,
                       SUM(CASE WHEN type = 'KICK' THEN 1 ELSE 0 END) AS kicks,
                       SUM(CASE WHEN type IN ('MUTE', 'TEMP_MUTE') THEN 1 ELSE 0 END) AS mutes,
                       SUM(CASE WHEN type = 'JAIL' THEN 1 ELSE 0 END) AS jails,
                       SUM(CASE WHEN type = 'WARNING' THEN 1 ELSE 0 END) AS warnings
                FROM punishments
                """;
            PunishmentStatistics stats = new PunishmentStatistics(null, "Server");
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setTotalPunishments(rs.getInt("total"));
                    stats.setBans(rs.getInt("bans"));
                    stats.setKicks(rs.getInt("kicks"));
                    stats.setMutes(rs.getInt("mutes"));
                    stats.setJails(rs.getInt("jails"));
                    stats.setWarnings(rs.getInt("warnings"));
                }
            }
            return stats;
        });
    }

    // ==================== Appeals ====================

    @Override
    public CompletableFuture<Integer> saveAppeal(AppealRecord appeal) {
        return run("saveAppeal", conn -> {
            String sql = """
                INSERT INTO appeals (punishment_id, player_uuid, player_name, appeal_text, submitted_at, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, appeal.getPunishmentId());
                stmt.setString(2, appeal.getPlayerUuid().toString());
                stmt.setString(3, appeal.getPlayerName());
                stmt.setString(4, appeal.getAppealText());
                stmt.setLong(5, appeal.getSubmittedAt().toEpochMilli());
                stmt.setString(6, appeal.getStatus().name());

                stmt.executeUpdate();

                try (ResultSet keys = stmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        int id = keys.getInt(1);
                        appeal.setId(id);
                        return id;
                    }
                }
                throw new SQLException("Failed to retrieve generated ID");
            }
        });
    }

    @Override
    public CompletableFuture<AppealRecord> getAppeal(int id) {
        return run("getAppeal", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM appeals WHERE id = ?")) {
                stmt.setInt(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? mapAppeal(rs) : null;
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<AppealRecord>> getPendingAppeals(int limit) {
        return run("getPendingAppeals", conn -> {
            String sql = "SELECT * FROM appeals WHERE status = 'PENDING' ORDER BY submitted_at ASC LIMIT ?";
            List<AppealRecord> appeals = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, limit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        appeals.add(mapAppeal(rs));
                    }
                }
            }
            return appeals;
        });
    }

    @Override
    public CompletableFuture<List<AppealRecord>> getAppealsByPlayer(UUID playerUuid) {
        return run("getAppealsByPlayer", conn -> {
            String sql = "SELECT * FROM appeals WHERE player_uuid = ? ORDER BY submitted_at DESC";
            List<AppealRecord> appeals = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        appeals.add(mapAppeal(rs));
                    }
                }
            }
            return appeals;
        });
    }

    @Override
    public CompletableFuture<Integer> countAppealsForPunishment(int punishmentId) {
        return run("countAppealsForPunishment", conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM appeals WHERE punishment_id = ?")) {
                stmt.setInt(1, punishmentId);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> updateAppeal(AppealRecord appeal) {
        return run("updateAppeal", conn -> {
            String sql = """
                UPDATE appeals
                SET status = ?, reviewed_by = ?, reviewer_name = ?, review_response = ?, reviewed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, appeal.getStatus().name());
                stmt.setString(2, appeal.getReviewedBy() != null ? appeal.getReviewedBy().toString() : null);
                stmt.setString(3, appeal.getReviewerName());
                stmt.setString(4, appeal.getReviewResponse());
                setNullableLong(stmt, 5, appeal.getReviewedAt());
                stmt.setInt(6, appeal.getId());
                // Compare-and-set, so two reviewers cannot both "decide" the same appeal.
                return stmt.executeUpdate() > 0;
            }
        });
    }

    // ==================== Mapping helpers ====================

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(", "));
    }

    private static void setNullableLong(PreparedStatement stmt, int index, Instant value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, java.sql.Types.BIGINT);
        } else {
            stmt.setLong(index, value.toEpochMilli());
        }
    }

    private List<PunishmentRecord> mapPunishments(PreparedStatement stmt) throws SQLException {
        List<PunishmentRecord> records = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                records.add(mapPunishment(rs));
            }
        }
        return records;
    }

    private PunishmentStatistics mapStatistics(ResultSet rs) throws SQLException {
        PunishmentStatistics stat = new PunishmentStatistics();
        String uuid = rs.getString("staff_uuid");
        if (uuid != null) {
            stat.setStaffUuid(UUID.fromString(uuid));
        }
        stat.setStaffName(rs.getString("staff_name"));
        stat.setTotalPunishments(rs.getInt("total"));
        stat.setBans(rs.getInt("bans"));
        stat.setKicks(rs.getInt("kicks"));
        stat.setMutes(rs.getInt("mutes"));
        stat.setJails(rs.getInt("jails"));
        stat.setWarnings(rs.getInt("warnings"));
        return stat;
    }

    private PunishmentRecord mapPunishment(ResultSet rs) throws SQLException {
        PunishmentRecord record = new PunishmentRecord();
        record.setId(rs.getInt("id"));
        record.setVictimUuid(UUID.fromString(rs.getString("victim_uuid")));
        record.setVictimName(rs.getString("victim_name"));
        record.setVictimIp(rs.getString("victim_ip"));
        record.setStaffUuid(UUID.fromString(rs.getString("staff_uuid")));
        record.setStaffName(rs.getString("staff_name"));
        record.setType(PunishmentType.valueOf(rs.getString("type")));
        record.setReason(rs.getString("reason"));
        record.setIssuedAt(Instant.ofEpochMilli(rs.getLong("issued_at")));

        long expiresAt = rs.getLong("expires_at");
        if (!rs.wasNull()) {
            record.setExpiresAt(Instant.ofEpochMilli(expiresAt));
        }

        record.setActive(rs.getInt("active") == 1);

        String unbanStaff = rs.getString("unban_staff_uuid");
        if (unbanStaff != null) {
            record.setUnbanStaffUuid(UUID.fromString(unbanStaff));
        }

        record.setUnbanReason(rs.getString("unban_reason"));

        long unbannedAt = rs.getLong("unbanned_at");
        if (!rs.wasNull()) {
            record.setUnbannedAt(Instant.ofEpochMilli(unbannedAt));
        }

        record.setServerName(rs.getString("server_name"));

        return record;
    }

    private AppealRecord mapAppeal(ResultSet rs) throws SQLException {
        AppealRecord appeal = new AppealRecord();
        appeal.setId(rs.getInt("id"));
        appeal.setPunishmentId(rs.getInt("punishment_id"));
        appeal.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        appeal.setPlayerName(rs.getString("player_name"));
        appeal.setAppealText(rs.getString("appeal_text"));
        appeal.setSubmittedAt(Instant.ofEpochMilli(rs.getLong("submitted_at")));
        appeal.setStatus(AppealRecord.AppealStatus.valueOf(rs.getString("status")));

        String reviewedBy = rs.getString("reviewed_by");
        if (reviewedBy != null) {
            appeal.setReviewedBy(UUID.fromString(reviewedBy));
        }

        appeal.setReviewerName(rs.getString("reviewer_name"));
        appeal.setReviewResponse(rs.getString("review_response"));

        long reviewedAt = rs.getLong("reviewed_at");
        if (!rs.wasNull()) {
            appeal.setReviewedAt(Instant.ofEpochMilli(reviewedAt));
        }

        return appeal;
    }
}
