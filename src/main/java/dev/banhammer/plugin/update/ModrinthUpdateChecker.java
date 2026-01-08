package dev.banhammer.plugin.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.banhammer.plugin.BanHammerPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Checks for updates from Modrinth API.
 *
 * @since 3.0.0
 */
public class ModrinthUpdateChecker {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/%s/version";
    private static final String USER_AGENT = "BanHammer/3.0.0 (GitHub)";

    private final BanHammerPlugin plugin;
    private final String projectId;
    private final String currentVersion;
    private final boolean enabled;
    private final boolean checkOnStartup;
    private final boolean notifyAdmins;
    private final long checkInterval;

    private BukkitTask periodicTask;
    private String latestVersion;
    private String downloadUrl;
    private String changelogUrl;
    private long lastCheck = 0;

    public ModrinthUpdateChecker(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        this.enabled = plugin.getConfig().getBoolean("updateChecker.enabled", true);
        this.checkOnStartup = plugin.getConfig().getBoolean("updateChecker.checkOnStartup", true);
        this.notifyAdmins = plugin.getConfig().getBoolean("updateChecker.notifyAdmins", true);
        this.checkInterval = plugin.getConfig().getLong("updateChecker.checkInterval", 6);
        this.projectId = plugin.getConfig().getString("updateChecker.modrinthProjectId", "bs-banhammer");
    }

    /**
     * Starts the update checker.
     */
    public void start() {
        if (!enabled) {
            plugin.getSLF4JLogger().info("Update checker is disabled");
            return;
        }

        // Check on startup
        if (checkOnStartup) {
            checkForUpdates().thenAccept(updateAvailable -> {
                if (updateAvailable) {
                    plugin.getSLF4JLogger().warn("=".repeat(60));
                    plugin.getSLF4JLogger().warn("Update available: {} -> {}", currentVersion, latestVersion);
                    plugin.getSLF4JLogger().warn("Download: {}", downloadUrl);
                    plugin.getSLF4JLogger().warn("Changelog: {}", changelogUrl);
                    plugin.getSLF4JLogger().warn("=".repeat(60));
                }
            });
        }

        // Schedule periodic checks
        if (checkInterval > 0) {
            long intervalTicks = checkInterval * 60 * 60 * 20; // Hours to ticks
            periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    () -> checkForUpdates().thenAccept(updateAvailable -> {
                        if (updateAvailable && notifyAdmins) {
                            notifyOnlineAdmins();
                        }
                    }),
                    intervalTicks,
                    intervalTicks
            );

            plugin.getSLF4JLogger().info("Update checker started (checking every {} hours)", checkInterval);
        }
    }

    /**
     * Stops the periodic update checker.
     */
    public void stop() {
        if (periodicTask != null && !periodicTask.isCancelled()) {
            periodicTask.cancel();
            plugin.getSLF4JLogger().info("Update checker stopped");
        }
    }

    /**
     * Checks for updates from Modrinth.
     *
     * @return CompletableFuture<Boolean> - true if update available
     */
    public CompletableFuture<Boolean> checkForUpdates() {
        // Rate limiting: Don't check more than once per hour
        long now = System.currentTimeMillis();
        if (now - lastCheck < TimeUnit.HOURS.toMillis(1)) {
            plugin.getSLF4JLogger().debug("Skipping update check (rate limited)");
            return CompletableFuture.completedFuture(latestVersion != null && isNewerVersion(latestVersion));
        }

        lastCheck = now;

        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiUrl = String.format(MODRINTH_API, projectId);
                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    plugin.getSLF4JLogger().warn("Failed to check for updates (HTTP {})", responseCode);
                    return false;
                }

                // Read response
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }

                // Parse JSON
                JsonArray versions = JsonParser.parseString(response.toString()).getAsJsonArray();
                if (versions.size() == 0) {
                    plugin.getSLF4JLogger().warn("No versions found on Modrinth");
                    return false;
                }

                // Get latest version
                JsonObject latestVersionObj = versions.get(0).getAsJsonObject();
                latestVersion = latestVersionObj.get("version_number").getAsString();

                // Get download URL
                JsonArray files = latestVersionObj.getAsJsonArray("files");
                if (files.size() > 0) {
                    JsonObject primaryFile = files.get(0).getAsJsonObject();
                    downloadUrl = primaryFile.get("url").getAsString();
                }

                // Get changelog URL
                changelogUrl = "https://modrinth.com/plugin/" + projectId + "/version/" + latestVersion;

                plugin.getSLF4JLogger().debug("Current version: {}, Latest version: {}", currentVersion, latestVersion);

                return isNewerVersion(latestVersion);

            } catch (Exception e) {
                plugin.getSLF4JLogger().warn("Failed to check for updates: {}", e.getMessage());
                plugin.getSLF4JLogger().debug("Update check error", e);
                return false;
            }
        });
    }

    /**
     * Compares version numbers using semantic versioning.
     *
     * @param newVersion The version to compare against
     * @return true if newVersion is newer than current version
     */
    private boolean isNewerVersion(String newVersion) {
        try {
            String[] currentParts = currentVersion.split("\\.");
            String[] newParts = newVersion.split("\\.");

            int maxLength = Math.max(currentParts.length, newParts.length);

            for (int i = 0; i < maxLength; i++) {
                int current = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
                int latest = i < newParts.length ? parseVersionPart(newParts[i]) : 0;

                if (latest > current) {
                    return true;
                } else if (latest < current) {
                    return false;
                }
            }

            return false; // Versions are equal
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to compare versions: {} vs {}", currentVersion, newVersion);
            return false;
        }
    }

    /**
     * Parses a version part, removing non-numeric suffixes.
     * Example: "3.0.0-SNAPSHOT" -> extracts "3", "0", "0"
     */
    private int parseVersionPart(String part) {
        // Remove non-numeric suffixes (like -SNAPSHOT, -alpha, etc.)
        String numericPart = part.replaceAll("[^0-9].*", "");
        return numericPart.isEmpty() ? 0 : Integer.parseInt(numericPart);
    }

    /**
     * Notifies online admins about available update.
     */
    private void notifyOnlineAdmins() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("banhammer.updatenotify")) {
                    player.sendMessage("§8[§6BanHammer§8] §aUpdate available: §e" + currentVersion + " §7→ §a" + latestVersion);
                    player.sendMessage("§8[§6BanHammer§8] §7Download: §b" + downloadUrl);
                    player.sendMessage("§8[§6BanHammer§8] §7Changelog: §9§n" + changelogUrl);
                }
            }
        });
    }

    /**
     * Sends update notification to a specific player (e.g., on join).
     */
    public void notifyPlayer(Player player) {
        if (!enabled || !notifyAdmins) return;
        if (!player.hasPermission("banhammer.updatenotify")) return;
        if (latestVersion == null || !isNewerVersion(latestVersion)) return;

        player.sendMessage("§8[§6BanHammer§8] §aUpdate available: §e" + currentVersion + " §7→ §a" + latestVersion);
        player.sendMessage("§8[§6BanHammer§8] §7Download: §b" + downloadUrl);
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public boolean isUpdateAvailable() {
        return latestVersion != null && isNewerVersion(latestVersion);
    }
}
