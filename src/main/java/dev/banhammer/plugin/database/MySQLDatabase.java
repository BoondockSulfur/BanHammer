package dev.banhammer.plugin.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.util.List;

import static dev.banhammer.plugin.util.Constants.DB_CONNECTION_TIMEOUT;
import static dev.banhammer.plugin.util.Constants.DB_MAX_LIFETIME;
import static dev.banhammer.plugin.util.Constants.DB_MAX_POOL_SIZE;
import static dev.banhammer.plugin.util.Constants.DB_MIN_IDLE;

/**
 * MySQL/MariaDB backend. All query logic lives in {@link AbstractSqlDatabase}.
 *
 * @since 3.0.0
 */
public class MySQLDatabase extends AbstractSqlDatabase {

    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";

    /** Long enough for Bedrock/Geyser names, which carry a prefix and exceed 16 characters. */
    private static final String NAME_TYPE = "VARCHAR(64)";

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean useSsl;

    public MySQLDatabase(Logger logger, String host, int port, String database,
                         String username, String password, boolean useSsl) {
        super(logger);
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.useSsl = useSsl;
    }

    @Override
    protected String displayName() {
        return "MySQL";
    }

    @Override
    protected HikariDataSource createDataSource() throws Exception {
        // Fails fast with a clear error if Paper could not resolve the library declared in
        // plugin.yml. Setting driverClassName below is what actually matters: it makes Hikari
        // instantiate the driver directly instead of asking DriverManager, which cannot see a
        // driver loaded into the plugin's isolated library class loader.
        Class.forName(DRIVER);

        HikariConfig config = new HikariConfig();
        config.setPoolName("BanHammer-MySQL");
        config.setDriverClassName(DRIVER);
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?characterEncoding=utf8&useUnicode=true"
                        + "&allowPublicKeyRetrieval=%s&sslMode=%s",
                host, port, database, useSsl ? "false" : "true", useSsl ? "REQUIRED" : "DISABLED"));
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

        return new HikariDataSource(config);
    }

    @Override
    protected int workerThreads() {
        return DB_MAX_POOL_SIZE;
    }

    @Override
    protected String textType() {
        return "VARCHAR(255)";
    }

    @Override
    protected String timestampType() {
        return "BIGINT";
    }

    @Override
    protected List<String> schemaStatements() {
        return List.of(
                """
                CREATE TABLE IF NOT EXISTS punishments (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    victim_uuid VARCHAR(36) NOT NULL,
                    victim_name VARCHAR(64) NOT NULL,
                    victim_ip VARCHAR(255),
                    staff_uuid VARCHAR(36) NOT NULL,
                    staff_name VARCHAR(64) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    reason TEXT,
                    issued_at BIGINT NOT NULL,
                    expires_at BIGINT,
                    active TINYINT NOT NULL DEFAULT 1,
                    unban_staff_uuid VARCHAR(36),
                    unban_reason TEXT,
                    unbanned_at BIGINT,
                    server_name VARCHAR(64),
                    INDEX idx_victim_issued (victim_uuid, issued_at),
                    INDEX idx_staff_issued (staff_uuid, issued_at),
                    INDEX idx_type_active (type, active),
                    INDEX idx_active_expires (active, expires_at),
                    INDEX idx_victim_type_active (victim_uuid, type, active)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,

                """
                CREATE TABLE IF NOT EXISTS appeals (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    punishment_id INT NOT NULL,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(64) NOT NULL,
                    appeal_text TEXT NOT NULL,
                    submitted_at BIGINT NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    reviewed_by VARCHAR(36),
                    reviewer_name VARCHAR(64),
                    review_response TEXT,
                    reviewed_at BIGINT,
                    INDEX idx_appeal_player_uuid (player_uuid),
                    INDEX idx_appeal_status (status, submitted_at),
                    CONSTRAINT fk_appeal_punishment FOREIGN KEY (punishment_id)
                        REFERENCES punishments(id) ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
        );
    }

    /**
     * Widens the name columns on databases created before schema version 2, where
     * {@code VARCHAR(16)} made MySQL reject Geyser/Bedrock names outright (with
     * "Data too long for column") while SQLite accepted the very same punishment.
     */
    @Override
    protected void applyDialectMigrations(java.sql.Connection conn, int from) throws java.sql.SQLException {
        if (from >= 2) {
            return;
        }

        logger.info("Widening name columns to {} for Bedrock/Geyser compatibility", NAME_TYPE);
        try (java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE punishments MODIFY COLUMN victim_name " + NAME_TYPE + " NOT NULL");
            stmt.execute("ALTER TABLE punishments MODIFY COLUMN staff_name " + NAME_TYPE + " NOT NULL");
            stmt.execute("ALTER TABLE punishments MODIFY COLUMN server_name " + NAME_TYPE);
            stmt.execute("ALTER TABLE appeals MODIFY COLUMN player_name " + NAME_TYPE + " NOT NULL");
            stmt.execute("ALTER TABLE appeals MODIFY COLUMN reviewer_name " + NAME_TYPE);
        }
    }
}
