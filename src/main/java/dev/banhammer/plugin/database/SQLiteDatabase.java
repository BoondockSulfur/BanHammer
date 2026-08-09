package dev.banhammer.plugin.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.io.File;
import java.util.List;

import static dev.banhammer.plugin.util.Constants.DB_CONNECTION_TIMEOUT;
import static dev.banhammer.plugin.util.Constants.DB_MAX_LIFETIME;

/**
 * SQLite backend. All query logic lives in {@link AbstractSqlDatabase}.
 *
 * @since 3.0.0
 */
public class SQLiteDatabase extends AbstractSqlDatabase {

    private final File dbFile;

    /**
     * @param dataFolder the plugin data folder
     * @param fileName   the database file name from {@code database.sqlite.file}
     */
    public SQLiteDatabase(Logger logger, File dataFolder, String fileName) {
        super(logger);
        String safeName = (fileName == null || fileName.isBlank()) ? "banhammer.db" : fileName.trim();
        // Keep the file inside the plugin folder even if someone puts a path in the config.
        safeName = new File(safeName).getName();
        this.dbFile = new File(dataFolder, safeName);
    }

    @Override
    protected String displayName() {
        return "SQLite";
    }

    @Override
    protected HikariDataSource createDataSource() throws Exception {
        // Fails fast with a clear error if Paper could not resolve the library declared in
        // plugin.yml. Hikari does not go through DriverManager (see setDriverClassName below),
        // because a runtime-loaded driver is not visible to it.
        Class.forName("org.sqlite.JDBC");

        HikariConfig config = new HikariConfig();
        config.setPoolName("BanHammer-SQLite");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        // SQLite supports a single writer, so a larger pool would only queue.
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(DB_CONNECTION_TIMEOUT);
        config.setMaxLifetime(DB_MAX_LIFETIME);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");

        return new HikariDataSource(config);
    }

    @Override
    protected int workerThreads() {
        return 1;
    }

    @Override
    protected String textType() {
        return "TEXT";
    }

    @Override
    protected String timestampType() {
        return "INTEGER";
    }

    @Override
    protected List<String> schemaStatements() {
        return List.of(
                // Applied explicitly rather than as a pool property, so a typo fails loudly
                // instead of being silently ignored.
                "PRAGMA journal_mode = WAL",

                """
                CREATE TABLE IF NOT EXISTS punishments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    victim_uuid TEXT NOT NULL,
                    victim_name TEXT NOT NULL,
                    victim_ip TEXT,
                    staff_uuid TEXT NOT NULL,
                    staff_name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    reason TEXT,
                    issued_at INTEGER NOT NULL,
                    expires_at INTEGER,
                    active INTEGER NOT NULL DEFAULT 1,
                    unban_staff_uuid TEXT,
                    unban_reason TEXT,
                    unbanned_at INTEGER,
                    server_name TEXT
                )
                """,

                """
                CREATE TABLE IF NOT EXISTS appeals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    punishment_id INTEGER NOT NULL,
                    player_uuid TEXT NOT NULL,
                    player_name TEXT NOT NULL,
                    appeal_text TEXT NOT NULL,
                    submitted_at INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    reviewed_by TEXT,
                    reviewer_name TEXT,
                    review_response TEXT,
                    reviewed_at INTEGER,
                    FOREIGN KEY (punishment_id) REFERENCES punishments(id) ON DELETE CASCADE
                )
                """,

                // Covering the ORDER BY as well as the predicate keeps history lookups off a filesort.
                "CREATE INDEX IF NOT EXISTS idx_victim_issued ON punishments(victim_uuid, issued_at DESC)",
                "CREATE INDEX IF NOT EXISTS idx_staff_issued ON punishments(staff_uuid, issued_at DESC)",
                "CREATE INDEX IF NOT EXISTS idx_type_active ON punishments(type, active)",
                "CREATE INDEX IF NOT EXISTS idx_active_expires ON punishments(active, expires_at)",
                "CREATE INDEX IF NOT EXISTS idx_victim_type_active ON punishments(victim_uuid, type, active)",

                "CREATE INDEX IF NOT EXISTS idx_appeal_punishment_id ON appeals(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_appeal_player_uuid ON appeals(player_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_appeal_status ON appeals(status, submitted_at)"
        );
    }
}
