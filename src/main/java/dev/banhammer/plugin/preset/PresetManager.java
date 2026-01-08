package dev.banhammer.plugin.preset;

import dev.banhammer.plugin.BanHammerPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ban and kick/jail presets and tracks active presets per player.
 *
 * @since 3.0.0
 */
public class PresetManager {

    private final BanHammerPlugin plugin;
    private final List<BanPreset> presets = new ArrayList<>();
    private final List<KickJailPreset> kickJailPresets = new ArrayList<>();
    private final Map<UUID, Integer> activePresetIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeKickJailPresetIndex = new ConcurrentHashMap<>();

    public PresetManager(BanHammerPlugin plugin) {
        this.plugin = plugin;
        loadPresets();
        loadKickJailPresets();
    }

    /**
     * Loads presets from config.yml
     */
    public void loadPresets() {
        presets.clear();

        ConfigurationSection presetsSection = plugin.getConfig().getConfigurationSection("presets");
        if (presetsSection == null) {
            plugin.getSLF4JLogger().warn("No presets section found in config.yml! Creating default preset.");
            createDefaultPreset();
            return;
        }

        for (String presetId : presetsSection.getKeys(false)) {
            ConfigurationSection preset = presetsSection.getConfigurationSection(presetId);
            if (preset == null) continue;

            try {
                String displayName = preset.getString("displayName", presetId);
                String reason = preset.getString("reason", "Banned");
                String durationStr = preset.getString("duration", "permanent");
                boolean ipBan = preset.getBoolean("ipBan", false);
                String sound = preset.getString("sound", "BLOCK_NOTE_BLOCK_PLING");

                Duration duration = parseDuration(durationStr);

                BanPreset banPreset = new BanPreset(presetId, displayName, reason, duration, ipBan, sound);
                presets.add(banPreset);

                plugin.getSLF4JLogger().info("Loaded preset: {} ({})", displayName, banPreset.getDurationDisplay());
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Failed to load preset '{}': {}", presetId, e.getMessage());
            }
        }

        if (presets.isEmpty()) {
            plugin.getSLF4JLogger().warn("No valid presets loaded! Creating default preset.");
            createDefaultPreset();
        }
    }

    /**
     * Creates a default preset when none are configured.
     */
    private void createDefaultPreset() {
        presets.add(new BanPreset(
                "default",
                "Default Ban",
                "Banned by BanHammer",
                null, // permanent
                false,
                "BLOCK_NOTE_BLOCK_PLING"
        ));
    }

    /**
     * Gets all available presets.
     */
    public List<BanPreset> getPresets() {
        return Collections.unmodifiableList(presets);
    }

    /**
     * Gets the currently active preset for a player.
     */
    public BanPreset getActivePreset(UUID playerUuid) {
        int index = activePresetIndex.getOrDefault(playerUuid, 0);
        if (presets.isEmpty()) {
            createDefaultPreset();
        }
        // Ensure index is valid
        if (index < 0 || index >= presets.size()) {
            index = 0;
            activePresetIndex.put(playerUuid, index);
        }
        return presets.get(index);
    }

    /**
     * Cycles to the next preset for a player.
     *
     * @return The new active preset
     */
    public BanPreset cyclePreset(UUID playerUuid) {
        if (presets.isEmpty()) {
            createDefaultPreset();
        }

        int currentIndex = activePresetIndex.getOrDefault(playerUuid, 0);
        int nextIndex = (currentIndex + 1) % presets.size();
        activePresetIndex.put(playerUuid, nextIndex);

        return presets.get(nextIndex);
    }

    /**
     * Resets a player's preset to the first one (optional, for cleanup).
     */
    public void resetPreset(UUID playerUuid) {
        activePresetIndex.remove(playerUuid);
    }

    /**
     * Parses a duration string (e.g., "7d", "1h30m", "PT24H").
     *
     * @param durationStr The duration string
     * @return The parsed Duration, or null if permanent/invalid
     */
    private Duration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.trim().isEmpty()) {
            return null;
        }

        String s = durationStr.trim();

        // Explicit handling for "permanent" keyword
        if (s.equalsIgnoreCase("permanent") || s.equalsIgnoreCase("perm")) {
            return null; // null = permanent
        }

        // Try ISO-8601 format (case-insensitive)
        if (s.toUpperCase().startsWith("P")) {
            try {
                return Duration.parse(s.toUpperCase());
            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Invalid ISO-8601 duration format: {}", s);
                // Fall through to custom parsing
            }
        }

        // Custom format: "7d", "1h30m", "2d12h", etc.
        String tmp = s.toLowerCase();
        long days = extractTime(tmp, "d");
        long hours = extractTime(tmp, "h");
        long minutes = extractTime(tmp, "m");
        long seconds = extractTime(tmp, "s");

        // Check if any time unit was found
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            plugin.getSLF4JLogger().warn("Could not parse duration: '{}'. Use format like '7d', '1h30m', or 'permanent'", durationStr);
            return null; // Invalid format, default to permanent
        }

        Duration duration = Duration.ZERO;
        if (days > 0) duration = duration.plusDays(days);
        if (hours > 0) duration = duration.plusHours(hours);
        if (minutes > 0) duration = duration.plusMinutes(minutes);
        if (seconds > 0) duration = duration.plusSeconds(seconds);

        return duration;
    }

    private long extractTime(String str, String unit) {
        int index = str.toLowerCase().indexOf(unit);
        if (index < 1) return 0;

        int start = index - 1;
        while (start >= 0 && Character.isDigit(str.charAt(start))) {
            start--;
        }

        try {
            return Long.parseLong(str.substring(start + 1, index));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /* =========================
       KICK/JAIL PRESET METHODS
       ========================= */

    /**
     * Loads kick/jail presets from config.yml
     */
    public void loadKickJailPresets() {
        kickJailPresets.clear();

        ConfigurationSection presetsSection = plugin.getConfig().getConfigurationSection("kickJailPresets");
        if (presetsSection == null) {
            plugin.getSLF4JLogger().warn("No kickJailPresets section found in config.yml! Creating default preset.");
            createDefaultKickJailPreset();
            return;
        }

        for (String presetId : presetsSection.getKeys(false)) {
            ConfigurationSection preset = presetsSection.getConfigurationSection(presetId);
            if (preset == null) continue;

            try {
                String displayName = preset.getString("displayName", presetId);
                String reason = preset.getString("reason", "Kicked");
                String durationStr = preset.getString("duration", null);
                String sound = preset.getString("sound", "BLOCK_NOTE_BLOCK_PLING");

                Duration duration = parseDuration(durationStr);

                KickJailPreset kickJailPreset = new KickJailPreset(presetId, displayName, reason, duration, sound);
                kickJailPresets.add(kickJailPreset);

                plugin.getSLF4JLogger().info("Loaded kick/jail preset: {} ({})", displayName, kickJailPreset.getDurationDisplay());
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Failed to load kick/jail preset '{}': {}", presetId, e.getMessage());
            }
        }

        if (kickJailPresets.isEmpty()) {
            plugin.getSLF4JLogger().warn("No valid kick/jail presets loaded! Creating default preset.");
            createDefaultKickJailPreset();
        }
    }

    /**
     * Creates a default kick/jail preset when none are configured.
     */
    private void createDefaultKickJailPreset() {
        kickJailPresets.add(new KickJailPreset(
                "default_kick",
                "Default Kick",
                "Kicked by BanHammer",
                null, // kick
                "BLOCK_NOTE_BLOCK_PLING"
        ));
    }

    /**
     * Gets all available kick/jail presets.
     */
    public List<KickJailPreset> getKickJailPresets() {
        return Collections.unmodifiableList(kickJailPresets);
    }

    /**
     * Gets the currently active kick/jail preset for a player.
     */
    public KickJailPreset getActiveKickJailPreset(UUID playerUuid) {
        int index = activeKickJailPresetIndex.getOrDefault(playerUuid, 0);
        if (kickJailPresets.isEmpty()) {
            createDefaultKickJailPreset();
        }
        // Ensure index is valid
        if (index < 0 || index >= kickJailPresets.size()) {
            index = 0;
            activeKickJailPresetIndex.put(playerUuid, index);
        }
        return kickJailPresets.get(index);
    }

    /**
     * Cycles to the next kick/jail preset for a player.
     *
     * @return The new active kick/jail preset
     */
    public KickJailPreset cycleKickJailPreset(UUID playerUuid) {
        if (kickJailPresets.isEmpty()) {
            createDefaultKickJailPreset();
        }

        int currentIndex = activeKickJailPresetIndex.getOrDefault(playerUuid, 0);
        int nextIndex = (currentIndex + 1) % kickJailPresets.size();
        activeKickJailPresetIndex.put(playerUuid, nextIndex);

        return kickJailPresets.get(nextIndex);
    }

    /**
     * Resets a player's kick/jail preset to the first one (optional, for cleanup).
     */
    public void resetKickJailPreset(UUID playerUuid) {
        activeKickJailPresetIndex.remove(playerUuid);
    }

    /**
     * Reloads all presets from config (called on /bh reload).
     */
    public void reload() {
        plugin.getSLF4JLogger().info("Reloading presets from config...");
        loadPresets();
        loadKickJailPresets();
        plugin.getSLF4JLogger().info("Presets reloaded successfully");
    }
}
