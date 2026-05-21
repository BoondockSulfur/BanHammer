package dev.banhammer.plugin.preset;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.util.DurationParser;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ban and kick/jail presets and tracks active presets per player.
 *
 * <p>The preset lists are held as immutable snapshots in {@code volatile} fields and
 * replaced atomically on (re)load, so concurrent readers never observe an empty or
 * partially-populated list (which previously risked a {@code % 0} ArithmeticException
 * during a reload).
 *
 * @since 3.0.0
 */
public class PresetManager {

    private final BanHammerPlugin plugin;
    private volatile List<BanPreset> presets = List.of();
    private volatile List<KickJailPreset> kickJailPresets = List.of();
    private final Map<UUID, Integer> activePresetIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> activeKickJailPresetIndex = new ConcurrentHashMap<>();

    public PresetManager(BanHammerPlugin plugin) {
        this.plugin = plugin;
        loadPresets();
        loadKickJailPresets();
    }

    /**
     * Loads ban presets from config.yml.
     */
    public void loadPresets() {
        List<BanPreset> loaded = new ArrayList<>();

        ConfigurationSection presetsSection = plugin.getConfig().getConfigurationSection("presets");
        if (presetsSection == null) {
            plugin.getSLF4JLogger().warn("No presets section found in config.yml! Creating default preset.");
            presets = List.of(defaultBanPreset());
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
                loaded.add(banPreset);

                plugin.getSLF4JLogger().info("Loaded preset: {} ({})", displayName, banPreset.getDurationDisplay());
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Failed to load preset '{}': {}", presetId, e.getMessage());
            }
        }

        if (loaded.isEmpty()) {
            plugin.getSLF4JLogger().warn("No valid presets loaded! Creating default preset.");
            loaded.add(defaultBanPreset());
        }

        // Atomic swap to an immutable snapshot
        presets = List.copyOf(loaded);
    }

    /**
     * Creates the fallback ban preset used when none are configured.
     */
    private BanPreset defaultBanPreset() {
        return new BanPreset(
                "default",
                "Default Ban",
                "Banned by BanHammer",
                null, // permanent
                false,
                "BLOCK_NOTE_BLOCK_PLING"
        );
    }

    /**
     * Gets all available presets (immutable snapshot).
     */
    public List<BanPreset> getPresets() {
        return presets;
    }

    /**
     * Gets the currently active preset for a player.
     */
    public BanPreset getActivePreset(UUID playerUuid) {
        List<BanPreset> current = presets; // snapshot the volatile reference
        if (current.isEmpty()) {
            return defaultBanPreset();
        }

        int index = activePresetIndex.getOrDefault(playerUuid, 0);
        // Ensure index is valid (e.g. after a reload shrank the list)
        if (index < 0 || index >= current.size()) {
            index = 0;
            activePresetIndex.put(playerUuid, index);
        }
        return current.get(index);
    }

    /**
     * Cycles to the next preset for a player.
     *
     * @return The new active preset
     */
    public BanPreset cyclePreset(UUID playerUuid) {
        List<BanPreset> current = presets; // snapshot the volatile reference
        if (current.isEmpty()) {
            return defaultBanPreset();
        }

        int currentIndex = activePresetIndex.getOrDefault(playerUuid, 0);
        int nextIndex = (currentIndex + 1) % current.size();
        activePresetIndex.put(playerUuid, nextIndex);

        return current.get(nextIndex);
    }

    /**
     * Resets a player's preset selection (called on quit for cleanup).
     */
    public void resetPreset(UUID playerUuid) {
        activePresetIndex.remove(playerUuid);
    }

    /**
     * Parses a duration string (e.g., "7d", "1h30m", "PT24H").
     * Uses the shared DurationParser utility.
     *
     * @param durationStr The duration string
     * @return The parsed Duration, or null if permanent/invalid
     */
    private Duration parseDuration(String durationStr) {
        Duration duration = DurationParser.parse(durationStr);
        if (duration == null && durationStr != null && !durationStr.trim().isEmpty()) {
            if (!durationStr.equalsIgnoreCase("permanent") && !durationStr.equalsIgnoreCase("perm")) {
                plugin.getSLF4JLogger().warn("Could not parse duration: '{}'. Use format like '7d', '1h30m', or 'permanent'", durationStr);
            }
        }
        return duration;
    }

    /* =========================
       KICK/JAIL PRESET METHODS
       ========================= */

    /**
     * Loads kick/jail presets from config.yml.
     */
    public void loadKickJailPresets() {
        List<KickJailPreset> loaded = new ArrayList<>();

        ConfigurationSection presetsSection = plugin.getConfig().getConfigurationSection("kickJailPresets");
        if (presetsSection == null) {
            plugin.getSLF4JLogger().warn("No kickJailPresets section found in config.yml! Creating default preset.");
            kickJailPresets = List.of(defaultKickJailPreset());
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
                loaded.add(kickJailPreset);

                plugin.getSLF4JLogger().info("Loaded kick/jail preset: {} ({})", displayName, kickJailPreset.getDurationDisplay());
            } catch (Exception e) {
                plugin.getSLF4JLogger().error("Failed to load kick/jail preset '{}': {}", presetId, e.getMessage());
            }
        }

        if (loaded.isEmpty()) {
            plugin.getSLF4JLogger().warn("No valid kick/jail presets loaded! Creating default preset.");
            loaded.add(defaultKickJailPreset());
        }

        // Atomic swap to an immutable snapshot
        kickJailPresets = List.copyOf(loaded);
    }

    /**
     * Creates the fallback kick/jail preset used when none are configured.
     */
    private KickJailPreset defaultKickJailPreset() {
        return new KickJailPreset(
                "default_kick",
                "Default Kick",
                "Kicked by BanHammer",
                null, // kick
                "BLOCK_NOTE_BLOCK_PLING"
        );
    }

    /**
     * Gets all available kick/jail presets (immutable snapshot).
     */
    public List<KickJailPreset> getKickJailPresets() {
        return kickJailPresets;
    }

    /**
     * Gets the currently active kick/jail preset for a player.
     */
    public KickJailPreset getActiveKickJailPreset(UUID playerUuid) {
        List<KickJailPreset> current = kickJailPresets; // snapshot the volatile reference
        if (current.isEmpty()) {
            return defaultKickJailPreset();
        }

        int index = activeKickJailPresetIndex.getOrDefault(playerUuid, 0);
        // Ensure index is valid (e.g. after a reload shrank the list)
        if (index < 0 || index >= current.size()) {
            index = 0;
            activeKickJailPresetIndex.put(playerUuid, index);
        }
        return current.get(index);
    }

    /**
     * Cycles to the next kick/jail preset for a player.
     *
     * @return The new active kick/jail preset
     */
    public KickJailPreset cycleKickJailPreset(UUID playerUuid) {
        List<KickJailPreset> current = kickJailPresets; // snapshot the volatile reference
        if (current.isEmpty()) {
            return defaultKickJailPreset();
        }

        int currentIndex = activeKickJailPresetIndex.getOrDefault(playerUuid, 0);
        int nextIndex = (currentIndex + 1) % current.size();
        activeKickJailPresetIndex.put(playerUuid, nextIndex);

        return current.get(nextIndex);
    }

    /**
     * Resets a player's kick/jail preset selection (called on quit for cleanup).
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
