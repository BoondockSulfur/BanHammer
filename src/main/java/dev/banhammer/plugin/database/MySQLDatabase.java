package dev.banhammer.plugin.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.database.model.PunishmentType;
import org.slf4j.Logger;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static dev.banhammer.plugin.util.Constants.*;

/**
 * MySQL database implementation for storing punishment records.
 * Suitable for multi-server networks with shared ban database.
 *
 * @since 3.0.0
 */
public class MySQLDatabase implements Database {

    private final Logger logger;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private HikariDataSource dataSource;

    public MySQLDatabase(Logger logger, String host, int port, String database, String username, String password) {
        this.logger = logger;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s", host, port, database));
                config.setUsername(username);
                config.setPassword(password);
                config.setMaximumPoolSize(DB_MAX_POOL_SIZE);
                config.setMinimumIdle(DB_MIN_IDLE);
                config.setConnectionTimeout(DB_CONNECTION_TIMEOUT);
                config.setMaxLifetime(DB_MAX_LIFETIME);
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
                config.addDataSourceProperty("useServerPrepStmts", "true");
                config.addDataSourceProperty("useLocalSessionState", "true");
                config.addDataSourceProperty("rewriteBatchedStatements", "true");
                config.addDataSourceProperty("cacheResultSetMetadata", "true");
                config.addDataSourceProperty("cacheServerConfiguration", "true");
                config.addDataSourceProperty("elideSetAutoCommits", "true");
                config.addDataSourceProperty("maintainTimeStats", "false");

                dataSource = new HikariDataSource(config);
                createTables();

                logger.info("MySQL database initialized at: {}:{}/{}", host, port, database);
            } catch (Exception e) {
                logger.error("Failed to initialize MySQL database", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                logger.info("MySQL database connection closed");
            }
        });
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Punishments table
            // Note: MySQL supports inline INDEX definitions in CREATE TABLE IF NOT EXISTS
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS punishments (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    victim_uuid VARCHAR(36) NOT NULL,
                    victim_name VARCHAR(16) NOT NULL,
                    victim_ip VARCHAR(45),
                    staff_uuid VARCHAR(36) NOT NULL,
                    staff_name VARCHAR(16) NOT NULL,
                    type VARCHAR(20) NOT NULL,
                    reason TEXT,
                    issued_at BIGINT NOT NULL,
                    expires_at BIGINT,
                    active TINYINT(1) NOT NULL DEFAULT 1,
                    unban_staff_uuid VARCHAR(36),
                    unban_reason TEXT,
                    unbanned_at BIGINT,
                    server_name VARCHAR(64),
                    INDEX idx_victim_uuid (victim_uuid),
                    INDEX idx_staff_uuid (staff_uuid),
                    INDEX idx_active (active),
                    INDEX idx_expires (expires_at),
                    INDEX idx_type (type),
                    INDEX idx_victim_type_active (victim_uuid, type, active)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            // Appeals table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS appeals (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    punishment_id INT NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(16) NOT NULL,
                    appeal_text TEXT NOT NULL,
                    submitted_at BIGINT NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    reviewed_by VARCHAR(36),
                    reviewer_name VARCHAR(16),
                    review_response TEXT,
                    reviewed_at BIGINT,
                    INDEX idx_punishment_id (punishment_id),
                    INDEX idx_player_uuid (player_uuid),
                    INDEX idx_status (status),
                    FOREIGN KEY (punishment_id) REFERENCES punishments(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);

            logger.info("MySQL database tables created successfully");
        }
    }

    @Override
    public CompletableFuture<Integer> savePunishment(PunishmentRecord record) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                INSERT INTO punishments (victim_uuid, victim_name, victim_ip, staff_uuid, staff_name,
                                        type, reason, issued_at, expires_at, active, server_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

                stmt.setString(1, record.getVictimUuid().toString());
                stmt.setString(2, record.getVictimName());
                stmt.setString(3, record.getVictimIp());
                stmt.setString(4, record.getStaffUuid().toString());
                stmt.setString(5, record.getStaffName());
                stmt.setString(6, record.getType().name());
                stmt.setString(7, record.getReason());
                stmt.setLong(8, record.getIssuedAt().toEpochMilli());
                stmt.setObject(9, record.getExpiresAt() != null ? record.getExpiresAt().toEpochMilli() : null);
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
            } catch (SQLException e) {
                logger.error("Failed to save punishment", e);
                throw new RuntimeException(e);
            }
        });
    }

    // The following methods are identical to SQLite implementation
    // (MySQL and SQLite use the same SQL for these queries)

    @Override
    public CompletableFuture<PunishmentRecord> getPunishment(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM punishments WHERE id = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setInt(1, id);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapPunishment(rs);
                    }
                }

                return null;
            } catch (SQLException e) {
                logger.error("Failed to get punishment", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getPunishmentsByPlayer(UUID playerUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM punishments WHERE victim_uuid = ? ORDER BY issued_at DESC LIMIT ?";
            List<PunishmentRecord> records = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, playerUuid.toString());
                stmt.setInt(2, limit);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        records.add(mapPunishment(rs));
                    }
                }

                return records;
            } catch (SQLException e) {
                logger.error("Failed to get punishments by player", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getActivePunishments(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM punishments WHERE victim_uuid = ? AND active = 1";
            List<PunishmentRecord> records = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, playerUuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        records.add(mapPunishment(rs));
                    }
                }

                return records;
            } catch (SQLException e) {
                logger.error("Failed to get active punishments", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByType(UUID playerUuid, PunishmentType type) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM punishments WHERE victim_uuid = ? AND type = ? AND active = 1";
            List<PunishmentRecord> records = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, type.name());

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        records.add(mapPunishment(rs));
                    }
                }

                return records;
            } catch (SQLException e) {
                logger.error("Failed to get active punishments by type", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getActivePunishmentsByTypeGlobal(PunishmentType type) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM punishments WHERE type = ? AND active = 1";
            List<PunishmentRecord> records = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, type.name());

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        records.add(mapPunishment(rs));
                    }
                }

                return records;
            } catch (SQLException e) {
                logger.error("Failed to get active punishments by type (global)", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getPunishmentsByStaff(UUID staffUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM punishments WHERE staff_uuid = ? ORDER BY issued_at DESC LIMIT ?";
            List<PunishmentRecord> records = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, staffUuid.toString());
                stmt.setInt(2, limit);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        records.add(mapPunishment(rs));
                    }
                }

                return records;
            } catch (SQLException e) {
                logger.error("Failed to get punishments by staff", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentRecord>> getExpiredPunishments() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM punishments WHERE active = 1 AND expires_at IS NOT NULL AND expires_at < ?";
            List<PunishmentRecord> records = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setLong(1, Instant.now().toEpochMilli());

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        records.add(mapPunishment(rs));
                    }
                }

                return records;
            } catch (SQLException e) {
                logger.error("Failed to get expired punishments", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> updatePunishment(PunishmentRecord record) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                UPDATE punishments
                SET active = ?, unban_staff_uuid = ?, unban_reason = ?, unbanned_at = ?
                WHERE id = ?
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setInt(1, record.isActive() ? 1 : 0);
                stmt.setString(2, record.getUnbanStaffUuid() != null ? record.getUnbanStaffUuid().toString() : null);
                stmt.setString(3, record.getUnbanReason());
                stmt.setObject(4, record.getUnbannedAt() != null ? record.getUnbannedAt().toEpochMilli() : null);
                stmt.setInt(5, record.getId());

                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to update punishment", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deactivatePunishment(int punishmentId, UUID staffUuid, String reason) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                UPDATE punishments
                SET active = 0, unban_staff_uuid = ?, unban_reason = ?, unbanned_at = ?
                WHERE id = ?
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, staffUuid != null ? staffUuid.toString() : null);
                stmt.setString(2, reason);
                stmt.setLong(3, Instant.now().toEpochMilli());
                stmt.setInt(4, punishmentId);

                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to deactivate punishment", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<PunishmentStatistics>> getStaffStatistics(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT staff_uuid, staff_name,
                       COUNT(*) as total,
                       SUM(CASE WHEN type IN ('BAN', 'TEMP_BAN', 'IP_BAN') THEN 1 ELSE 0 END) as bans,
                       SUM(CASE WHEN type = 'KICK' THEN 1 ELSE 0 END) as kicks,
                       SUM(CASE WHEN type IN ('MUTE', 'TEMP_MUTE') THEN 1 ELSE 0 END) as mutes,
                       SUM(CASE WHEN type = 'WARNING' THEN 1 ELSE 0 END) as warnings
                FROM punishments
                GROUP BY staff_uuid, staff_name
                ORDER BY total DESC
                LIMIT ?
            """;

            List<PunishmentStatistics> stats = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setInt(1, limit);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        PunishmentStatistics stat = new PunishmentStatistics();
                        stat.setStaffUuid(UUID.fromString(rs.getString("staff_uuid")));
                        stat.setStaffName(rs.getString("staff_name"));
                        stat.setTotalPunishments(rs.getInt("total"));
                        stat.setBans(rs.getInt("bans"));
                        stat.setKicks(rs.getInt("kicks"));
                        stat.setMutes(rs.getInt("mutes"));
                        stat.setWarnings(rs.getInt("warnings"));
                        stats.add(stat);
                    }
                }

                return stats;
            } catch (SQLException e) {
                logger.error("Failed to get staff statistics", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<PunishmentStatistics> getStaffStatistics(UUID staffUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                SELECT staff_uuid, staff_name,
                       COUNT(*) as total,
                       SUM(CASE WHEN type IN ('BAN', 'TEMP_BAN', 'IP_BAN') THEN 1 ELSE 0 END) as bans,
                       SUM(CASE WHEN type = 'KICK' THEN 1 ELSE 0 END) as kicks,
                       SUM(CASE WHEN type IN ('MUTE', 'TEMP_MUTE') THEN 1 ELSE 0 END) as mutes,
                       SUM(CASE WHEN type = 'WARNING' THEN 1 ELSE 0 END) as warnings
                FROM punishments
                WHERE staff_uuid = ?
                GROUP BY staff_uuid, staff_name
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, staffUuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        PunishmentStatistics stat = new PunishmentStatistics();
                        stat.setStaffUuid(UUID.fromString(rs.getString("staff_uuid")));
                        stat.setStaffName(rs.getString("staff_name"));
                        stat.setTotalPunishments(rs.getInt("total"));
                        stat.setBans(rs.getInt("bans"));
                        stat.setKicks(rs.getInt("kicks"));
                        stat.setMutes(rs.getInt("mutes"));
                        stat.setWarnings(rs.getInt("warnings"));
                        return stat;
                    }
                }

                return new PunishmentStatistics(staffUuid, "Unknown");
            } catch (SQLException e) {
                logger.error("Failed to get staff statistics", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> saveAppeal(AppealRecord appeal) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = """
                INSERT INTO appeals (punishment_id, player_uuid, player_name, appeal_text, submitted_at, status)
                VALUES (?, ?, ?, ?, ?, ?)
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

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
            } catch (SQLException e) {
                logger.error("Failed to save appeal", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<AppealRecord> getAppeal(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM appeals WHERE id = ?";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setInt(1, id);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapAppeal(rs);
                    }
                }

                return null;
            } catch (SQLException e) {
                logger.error("Failed to get appeal", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<AppealRecord>> getPendingAppeals() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM appeals WHERE status = 'PENDING' ORDER BY submitted_at ASC";
            List<AppealRecord> appeals = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    appeals.add(mapAppeal(rs));
                }

                return appeals;
            } catch (SQLException e) {
                logger.error("Failed to get pending appeals", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<AppealRecord>> getAppealsByPlayer(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM appeals WHERE player_uuid = ? ORDER BY submitted_at DESC";
            List<AppealRecord> appeals = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, playerUuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        appeals.add(mapAppeal(rs));
                    }
                }

                return appeals;
            } catch (SQLException e) {
                logger.error("Failed to get appeals by player", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateAppeal(AppealRecord appeal) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                UPDATE appeals
                SET status = ?, reviewed_by = ?, reviewer_name = ?, review_response = ?, reviewed_at = ?
                WHERE id = ?
            """;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, appeal.getStatus().name());
                stmt.setString(2, appeal.getReviewedBy() != null ? appeal.getReviewedBy().toString() : null);
                stmt.setString(3, appeal.getReviewerName());
                stmt.setString(4, appeal.getReviewResponse());
                stmt.setObject(5, appeal.getReviewedAt() != null ? appeal.getReviewedAt().toEpochMilli() : null);
                stmt.setInt(6, appeal.getId());

                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to update appeal", e);
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    @Override
    public CompletableFuture<Integer> getWarningCount(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT COUNT(*) as count FROM punishments WHERE victim_uuid = ? AND type = 'WARNING'";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, playerUuid.toString());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("count");
                    }
                }

                return 0;
            } catch (SQLException e) {
                logger.error("Failed to get warning count", e);
                throw new RuntimeException(e);
            }
        });
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
