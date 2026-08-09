package dev.banhammer.plugin.util;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.model.PunishmentRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Writes an audit trail of punishments.
 *
 * <p>Backs the {@code logging.*} configuration section, which previously existed in
 * {@code config.yml} but was never read by any code.
 */
public final class PunishmentLogger {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final BanHammerPlugin plugin;

    public PunishmentLogger(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Records that a punishment was issued.
     */
    public void logPunishment(PunishmentRecord record) {
        if (!isEnabledFor(record)) {
            return;
        }

        String line = "%s ISSUED %s victim=%s (%s) staff=%s reason=%s expires=%s".formatted(
                TIMESTAMP.format(record.getIssuedAt()),
                record.getType(),
                record.getVictimName(),
                record.getVictimUuid(),
                record.getStaffName(),
                record.getReason() == null ? "-" : record.getReason(),
                record.getExpiresAt() == null ? "never" : TIMESTAMP.format(record.getExpiresAt()));

        write(line);
    }

    /**
     * Records that a punishment was lifted.
     */
    public void logRemoval(PunishmentRecord record, String staffName, String reason) {
        if (!plugin.settings().logging().logUnbans()) {
            return;
        }

        String line = "%s REMOVED %s victim=%s (%s) staff=%s reason=%s".formatted(
                TIMESTAMP.format(java.time.Instant.now()),
                record.getType(),
                record.getVictimName(),
                record.getVictimUuid(),
                staffName == null ? "Automatic" : staffName,
                reason == null ? "-" : reason);

        write(line);
    }

    private boolean isEnabledFor(PunishmentRecord record) {
        Settings.Logging logging = plugin.settings().logging();
        return switch (record.getType()) {
            case BAN, TEMP_BAN, IP_BAN -> logging.logBans();
            case KICK -> logging.logKicks();
            case MUTE, TEMP_MUTE -> logging.logMutes();
            case JAIL -> logging.logJails();
            case WARNING -> logging.logWarnings();
            default -> true;
        };
    }

    private void write(String line) {
        plugin.getSLF4JLogger().info(line);

        Settings.Logging logging = plugin.settings().logging();
        if (!logging.separateFile()) {
            return;
        }

        // File I/O off the main thread; a punishment must never wait on the disk.
        FoliaScheduler.runAsync(plugin, () -> {
            try {
                Path file = plugin.getDataFolder().toPath().resolve(sanitize(logging.fileName()));
                Files.createDirectories(file.getParent());
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getSLF4JLogger().warn("Failed to write punishment log: {}", e.toString());
            }
        });
    }

    /** Keeps the log inside the plugin folder even if the config contains a path. */
    private static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "banhammer-punishments.log";
        }
        return Path.of(fileName).getFileName().toString();
    }
}
