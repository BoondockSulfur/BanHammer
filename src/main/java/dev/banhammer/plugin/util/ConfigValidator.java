package dev.banhammer.plugin.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates plugin configuration for common issues and errors.
 *
 * @since 3.0.0
 */
public final class ConfigValidator {

    private ConfigValidator() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates the entire configuration and logs warnings/errors.
     *
     * @param config The configuration to validate
     * @param logger The logger to use for warnings/errors
     * @return true if configuration is valid, false if critical errors exist
     */
    public static boolean validate(FileConfiguration config, Logger logger) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Validate cooldown
        validateCooldown(config, warnings, errors);

        // Validate database settings
        validateDatabase(config, warnings, errors);

        // Validate presets
        validatePresets(config, warnings, errors);

        // Validate kick/jail presets
        validateKickJailPresets(config, warnings, errors);

        // Validate Discord settings
        validateDiscord(config, warnings, errors);

        // Validate privacy settings
        validatePrivacy(config, warnings, errors);

        // Validate jail settings
        validateJail(config, warnings, errors);

        // Validate everything else that can silently misbehave at runtime
        validateMisc(config, warnings, errors);

        // Log all warnings
        if (!warnings.isEmpty()) {
            logger.warn("Configuration warnings found:");
            for (String warning : warnings) {
                logger.warn("  - {}", warning);
            }
        }

        // Log all errors
        if (!errors.isEmpty()) {
            logger.error("Configuration errors found:");
            for (String error : errors) {
                logger.error("  - {}", error);
            }
            return false;
        }

        logger.info("Configuration validation passed");
        return true;
    }

    private static void validateCooldown(FileConfiguration config, List<String> warnings, List<String> errors) {
        int cooldown = config.getInt("cooldownSeconds", 3);
        if (cooldown < 0) {
            errors.add("cooldownSeconds cannot be negative (found: " + cooldown + ")");
        } else if (cooldown > 60) {
            warnings.add("cooldownSeconds is very high (" + cooldown + " seconds). Players may find this frustrating.");
        }

        long presetSwitchCooldown = config.getLong("presetSwitchCooldown", 250);
        if (presetSwitchCooldown < 0) {
            errors.add("presetSwitchCooldown cannot be negative (found: " + presetSwitchCooldown + ")");
        } else if (presetSwitchCooldown < 50) {
            warnings.add("presetSwitchCooldown is very low (" + presetSwitchCooldown + "ms). May cause spam.");
        }
    }

    private static void validateDatabase(FileConfiguration config, List<String> warnings, List<String> errors) {
        if (!config.getBoolean("database.enabled", false)) {
            return; // Database disabled, skip validation
        }

        String type = str(config, "database.type", "SQLITE").trim().toUpperCase(java.util.Locale.ROOT);
        if (!type.equals("SQLITE") && !type.equals("MYSQL") && !type.equals("MARIADB")) {
            errors.add("Invalid database.type: " + type + " (must be SQLITE, MYSQL or MARIADB)");
        }

        if (type.equals("MYSQL") || type.equals("MARIADB")) {
            String host = str(config, "database.mysql.host", "");
            if (host.isBlank()) {
                errors.add("database.mysql.host is required when using MySQL");
            }

            int port = config.getInt("database.mysql.port", 3306);
            if (port < 1 || port > 65535) {
                errors.add("database.mysql.port must be between 1 and 65535 (found: " + port + ")");
            }

            String database = str(config, "database.mysql.database", "");
            if (database.isBlank()) {
                errors.add("database.mysql.database is required when using MySQL");
            }

            String password = str(config, "database.mysql.password", "");
            if (password.equals("password")) {
                warnings.add("database.mysql.password is still the default 'password'. Change it!");
            }
        }

        long timeout = config.getLong("database.initializationTimeoutSeconds", 60);
        if (timeout < 5) {
            warnings.add("database.initializationTimeoutSeconds is very low (" + timeout + "s). Database may fail to initialize.");
        }
    }

    private static void validatePresets(FileConfiguration config, List<String> warnings, List<String> errors) {
        var presetsSection = config.getConfigurationSection("presets");
        if (presetsSection == null || presetsSection.getKeys(false).isEmpty()) {
            warnings.add("No ban presets configured. Plugin will create a default preset.");
            return;
        }

        for (String presetId : presetsSection.getKeys(false)) {
            String durationStr = config.getString("presets." + presetId + ".duration", "permanent");
            if (!DurationParser.isValid(durationStr)) {
                errors.add("Invalid duration in preset '" + presetId + "': " + durationStr);
            }

            String sound = config.getString("presets." + presetId + ".sound", "");
            if (!sound.isEmpty() && !Sounds.isValid(sound)) {
                warnings.add("Invalid sound in preset '" + presetId + "': " + sound);
            }
        }
    }

    private static void validateKickJailPresets(FileConfiguration config, List<String> warnings, List<String> errors) {
        var presetsSection = config.getConfigurationSection("kickJailPresets");
        if (presetsSection == null || presetsSection.getKeys(false).isEmpty()) {
            warnings.add("No kick/jail presets configured. Plugin will create a default preset.");
            return;
        }

        for (String presetId : presetsSection.getKeys(false)) {
            String durationStr = config.getString("kickJailPresets." + presetId + ".duration", null);
            if (durationStr != null && !DurationParser.isValid(durationStr)) {
                errors.add("Invalid duration in kick/jail preset '" + presetId + "': " + durationStr);
            }

            String sound = config.getString("kickJailPresets." + presetId + ".sound", "");
            if (!sound.isEmpty() && !Sounds.isValid(sound)) {
                warnings.add("Invalid sound in kick/jail preset '" + presetId + "': " + sound);
            }
        }
    }

    private static void validateDiscord(FileConfiguration config, List<String> warnings, List<String> errors) {
        if (!config.getBoolean("discord.enabled", false)) {
            return; // Discord disabled, skip validation
        }

        String webhookUrl = str(config, "discord.webhookUrl", "");
        if (webhookUrl.isBlank()) {
            errors.add("discord.webhookUrl is required when Discord is enabled");
        } else if (!webhookUrl.trim().matches(
                "^https://(canary\\.|ptb\\.)?discord(app)?\\.com/api(/v\\d+)?/webhooks/\\d+/[\\w-]+$")) {
            // Never echo the value: it contains the webhook token.
            warnings.add("discord.webhookUrl does not look like a valid Discord webhook URL");
        }
    }

    private static void validatePrivacy(FileConfiguration config, List<String> warnings, List<String> errors) {
        String anonymizationLevel = str(config, "privacy.ipAnonymization", "PARTIAL");
        try {
            IPAnonymizer.AnonymizationLevel.valueOf(anonymizationLevel.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warnings.add("Invalid privacy.ipAnonymization: '" + anonymizationLevel
                    + "' (must be NONE, PARTIAL, HASH or FULL) - falling back to PARTIAL");
        }

        String salt = str(config, "privacy.ipHashSalt", "");
        if (salt.equals("change-me-to-random-salt") || salt.isBlank()) {
            warnings.add("privacy.ipHashSalt is unset - a random salt will be generated on first start.");
        }

        int retentionDays = config.getInt("privacy.dataRetention.deleteAfterDays", 365);
        if (config.getBoolean("privacy.dataRetention.enabled", false) && retentionDays < 1) {
            errors.add("privacy.dataRetention.deleteAfterDays must be at least 1 (found: " + retentionDays + ")");
        }
    }

    /**
     * Checks the remaining values that would otherwise only reveal themselves as odd
     * behaviour at runtime.
     */
    private static void validateMisc(FileConfiguration config, List<String> warnings, List<String> errors) {
        String language = str(config, "language", "en");
        if (!language.matches("[a-zA-Z0-9_-]{1,16}")) {
            warnings.add("language '" + language + "' is not a plain language code - falling back to 'en'");
        }

        String material = str(config, "item.material", "CARROT_ON_A_STICK");
        org.bukkit.Material parsed = org.bukkit.Material.matchMaterial(material);
        if (parsed == null) {
            warnings.add("item.material '" + material + "' is unknown - falling back to CARROT_ON_A_STICK");
        } else if (parsed.isAir() || !parsed.isItem()) {
            warnings.add("item.material '" + material + "' cannot exist as an item - "
                    + "falling back to CARROT_ON_A_STICK");
        }

        int maxReason = config.getInt("validation.maxReasonLength", 500);
        if (maxReason < 1) {
            errors.add("validation.maxReasonLength must be at least 1 (found: " + maxReason + ")");
        }

        int minAppeal = config.getInt("appeals.minLength", 20);
        if (minAppeal > maxReason) {
            warnings.add("appeals.minLength (" + minAppeal + ") exceeds validation.maxReasonLength ("
                    + maxReason + ") - no appeal could ever satisfy both, so minLength will be clamped.");
        }

        int checkInterval = config.getInt("tempBans.checkInterval", 60);
        if (checkInterval < 5) {
            warnings.add("tempBans.checkInterval (" + checkInterval + "s) is very low and will be raised to 5s.");
        }

        long updateInterval = config.getLong("updateChecker.checkInterval", 6);
        if (updateInterval < 0) {
            warnings.add("updateChecker.checkInterval cannot be negative - periodic checks are disabled.");
        }

        int threshold = config.getInt("punishmentTypes.warnings.autoBanThreshold", 3);
        if (threshold < 1) {
            errors.add("punishmentTypes.warnings.autoBanThreshold must be at least 1 (found: " + threshold + ")");
        }

        String autoBanDuration = str(config, "punishmentTypes.warnings.autoBanDuration", "7d");
        if (!DurationParser.isValid(autoBanDuration)) {
            errors.add("Invalid punishmentTypes.warnings.autoBanDuration: '" + autoBanDuration + "'");
        }
    }

    /** Null-safe string read; a key explicitly set to {@code null} would otherwise NPE. */
    private static String str(FileConfiguration config, String path, String def) {
        String value = config.getString(path, def);
        return value == null ? def : value;
    }

    private static void validateJail(FileConfiguration config, List<String> warnings, List<String> errors) {
        if (!config.getBoolean("punishmentTypes.jail.enabled", true)) {
            return; // Jail system disabled, skip validation
        }

        // Check if jail location is configured
        var jailLocation = config.getConfigurationSection("punishmentTypes.jail.location");
        if (jailLocation == null) {
            warnings.add("Jail location not configured. Use /setjail command to set it.");
            return;
        }

        // Validate world exists
        String worldName = jailLocation.getString("world");
        if (worldName == null || worldName.isEmpty()) {
            warnings.add("punishmentTypes.jail.location.world is not set - use /setjail");
            return;
        }

        // A warning, not an error: the built-in jail simply stays unavailable (and Essentials
        // may be providing the cells anyway), so this must not fail the whole validation.
        if (org.bukkit.Bukkit.getWorld(worldName) == null) {
            warnings.add("punishmentTypes.jail.location.world '" + worldName + "' does not exist on this server - "
                    + "the built-in jail is unavailable until /setjail is used again");
        }

        // Validate coordinates are reasonable
        double y = jailLocation.getDouble("y", 0);
        if (y < -64 || y > 320) {
            warnings.add("punishmentTypes.jail.location.y (" + y + ") is outside normal world bounds (-64 to 320)");
        }

        // Validate max distance
        double maxDistance = config.getDouble("punishmentTypes.jail.maxDistance", 10.0);
        if (maxDistance < 1) {
            warnings.add("punishmentTypes.jail.maxDistance (" + maxDistance + ") is very small. Players may be stuck.");
        } else if (maxDistance > 100) {
            warnings.add("punishmentTypes.jail.maxDistance (" + maxDistance + ") is very large. Enforcement may not work well.");
        }
    }
}
