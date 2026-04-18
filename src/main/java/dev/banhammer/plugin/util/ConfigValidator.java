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

        // Validate API settings
        validateApi(config, warnings, errors);

        // Validate privacy settings
        validatePrivacy(config, warnings, errors);

        // Validate jail settings
        validateJail(config, warnings, errors);

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

        String type = config.getString("database.type", "SQLITE").toUpperCase();
        if (!type.equals("SQLITE") && !type.equals("MYSQL")) {
            errors.add("Invalid database.type: " + type + " (must be SQLITE or MYSQL)");
        }

        if (type.equals("MYSQL")) {
            String host = config.getString("database.mysql.host", "");
            if (host.isEmpty()) {
                errors.add("database.mysql.host is required when using MySQL");
            }

            String password = config.getString("database.mysql.password", "password");
            if (password.equals("password")) {
                warnings.add("database.mysql.password is set to default 'password'. Change this for security!");
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
            if (!sound.isEmpty()) {
                try {
                    org.bukkit.Sound.valueOf(sound);
                } catch (IllegalArgumentException e) {
                    warnings.add("Invalid sound in preset '" + presetId + "': " + sound);
                }
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
            if (!sound.isEmpty()) {
                try {
                    org.bukkit.Sound.valueOf(sound);
                } catch (IllegalArgumentException e) {
                    warnings.add("Invalid sound in kick/jail preset '" + presetId + "': " + sound);
                }
            }
        }
    }

    private static void validateDiscord(FileConfiguration config, List<String> warnings, List<String> errors) {
        if (!config.getBoolean("discord.enabled", false)) {
            return; // Discord disabled, skip validation
        }

        String webhookUrl = config.getString("discord.webhookUrl", "");
        if (webhookUrl.isEmpty()) {
            errors.add("discord.webhookUrl is required when Discord is enabled");
        } else if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") &&
                   !webhookUrl.startsWith("https://discordapp.com/api/webhooks/")) {
            warnings.add("discord.webhookUrl doesn't look like a valid Discord webhook URL");
        }
    }

    private static void validateApi(FileConfiguration config, List<String> warnings, List<String> errors) {
        if (!config.getBoolean("api.enabled", false)) {
            return; // API disabled, skip validation
        }

        int port = config.getInt("api.port", 8080);
        if (port < 1024 || port > 65535) {
            warnings.add("api.port " + port + " is in reserved range or invalid. Use ports 1024-65535.");
        }

        String token = config.getString("api.token", "your-secret-token");
        if (token.equals("your-secret-token") || token.length() < 16) {
            warnings.add("api.token is weak or default. Use a strong token (16+ characters) for security!");
        }

        int rateLimit = config.getInt("api.rateLimit.requestsPerMinute", 60);
        if (rateLimit < 1) {
            errors.add("api.rateLimit.requestsPerMinute must be at least 1");
        }
    }

    private static void validatePrivacy(FileConfiguration config, List<String> warnings, List<String> errors) {
        String anonymizationLevel = config.getString("privacy.ipAnonymization", "PARTIAL");
        try {
            IPAnonymizer.AnonymizationLevel.valueOf(anonymizationLevel.toUpperCase());
        } catch (IllegalArgumentException e) {
            errors.add("Invalid privacy.ipAnonymization: " + anonymizationLevel +
                    " (must be NONE, PARTIAL, FULL, or HASH)");
        }

        String salt = config.getString("privacy.ipHashSalt", "");
        if (salt.isEmpty()) {
            // This is OK - will be auto-generated
        } else if (salt.equals("change-me-to-random-salt")) {
            warnings.add("privacy.ipHashSalt is set to default value. Will be auto-generated.");
        }
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
            errors.add("punishmentTypes.jail.location.world is not set");
            return;
        }

        if (org.bukkit.Bukkit.getWorld(worldName) == null) {
            errors.add("punishmentTypes.jail.location.world '" + worldName + "' does not exist on this server");
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
