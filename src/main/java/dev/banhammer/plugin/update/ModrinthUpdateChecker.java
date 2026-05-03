package dev.banhammer.plugin.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.util.FoliaScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
    private static final String USER_AGENT = "BanHammer/4.0.0 (GitHub)";

    private final BanHammerPlugin plugin;
    private final String projectId;
    private final String currentVersion;
    private final String gameVersion;
    private final boolean enabled;
    private final boolean checkOnStartup;
    private final boolean notifyAdmins;
    private final long checkInterval;

    private Object periodicTask;
    private String latestVersion;
    private String downloadUrl;
    private String changelogUrl;
    private long lastCheck = 0;

    public ModrinthUpdateChecker(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        this.gameVersion = Bukkit.getMinecraftVersion();
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
            periodicTask = FoliaScheduler.runAsyncRepeating(
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
        if (periodicTask != null) {
            FoliaScheduler.cancelTask(periodicTask);
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
            // First try with game version filter
            JsonArray versions = fetchVersions(true);

            // Fallback: if no versions found for this game version, fetch all and filter locally
            if (versions == null || versions.isEmpty()) {
                plugin.getSLF4JLogger().debug("No versions found for game version {}, trying unfiltered fallback", gameVersion);
                JsonArray allVersions = fetchVersions(false);
                if (allVersions == null || allVersions.isEmpty()) {
                    plugin.getSLF4JLogger().warn("No versions found on Modrinth");
                    return false;
                }
                versions = filterByGameVersion(allVersions);
                if (versions.isEmpty()) {
                    plugin.getSLF4JLogger().info("No compatible versions found for Minecraft {}", gameVersion);
                    return false;
                }
            }

            // Get latest version
            JsonObject latestVersionObj = versions.get(0).getAsJsonObject();
            JsonElement versionElement = latestVersionObj.get("version_number");
            if (versionElement == null || versionElement.isJsonNull()) {
                plugin.getSLF4JLogger().warn("Modrinth response missing version_number");
                return false;
            }
            latestVersion = versionElement.getAsString();

            // Get download URL
            JsonArray files = latestVersionObj.getAsJsonArray("files");
            if (files != null && !files.isEmpty()) {
                JsonObject primaryFile = files.get(0).getAsJsonObject();
                JsonElement urlElement = primaryFile.get("url");
                if (urlElement != null && !urlElement.isJsonNull()) {
                    downloadUrl = urlElement.getAsString();
                }
            }

            // Get changelog URL
            changelogUrl = "https://modrinth.com/plugin/" + projectId + "/version/" + latestVersion;

            plugin.getSLF4JLogger().debug("Current version: {}, Latest version: {}", currentVersion, latestVersion);

            return isNewerVersion(latestVersion);
        });
    }

    /**
     * Fetches versions from Modrinth API.
     *
     * @param filterByGameVersion whether to include the game_versions query parameter
     * @return JsonArray of versions, or null on error
     */
    private JsonArray fetchVersions(boolean filterByGameVersion) {
        HttpURLConnection connection = null;
        try {
            String apiUrl = String.format(MODRINTH_API, projectId);
            if (filterByGameVersion) {
                apiUrl += "?game_versions=" + URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8);
            }
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                plugin.getSLF4JLogger().warn("Failed to check for updates (HTTP {})", responseCode);
                return null;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            return JsonParser.parseString(response.toString()).getAsJsonArray();

        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Failed to check for updates: {}", e.getMessage());
            plugin.getSLF4JLogger().debug("Update check error", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Filters versions locally by checking if the game_versions array contains
     * the current server's game version or its major.minor prefix.
     *
     * @param allVersions all versions from Modrinth
     * @return filtered JsonArray with only compatible versions
     */
    private JsonArray filterByGameVersion(JsonArray allVersions) {
        String majorMinor = getMajorMinor(gameVersion);
        JsonArray filtered = new JsonArray();

        for (JsonElement element : allVersions) {
            JsonObject version = element.getAsJsonObject();
            JsonArray gameVersions = version.getAsJsonArray("game_versions");
            if (gameVersions == null) continue;

            for (JsonElement gv : gameVersions) {
                String tagged = gv.getAsString();
                if (tagged.equals(gameVersion) || getMajorMinor(tagged).equals(majorMinor)) {
                    filtered.add(version);
                    break;
                }
            }
        }

        return filtered;
    }

    /**
     * Extracts the major.minor portion of a version string.
     * Example: "1.21.1" -> "1.21", "26.1.2" -> "26.1"
     */
    private String getMajorMinor(String version) {
        int firstDot = version.indexOf('.');
        if (firstDot < 0) return version;
        int secondDot = version.indexOf('.', firstDot + 1);
        if (secondDot < 0) return version;
        return version.substring(0, secondDot);
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
        FoliaScheduler.runGlobal(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("banhammer.updatenotify")) {
                    sendUpdateMessage(player);
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

        sendUpdateMessage(player);
    }

    private void sendUpdateMessage(Player player) {
        Component prefix = Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("BanHammer", NamedTextColor.GOLD))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY));

        player.sendMessage(prefix
                .append(Component.text("Update available: ", NamedTextColor.GREEN))
                .append(Component.text(currentVersion, NamedTextColor.YELLOW))
                .append(Component.text(" → ", NamedTextColor.GRAY))
                .append(Component.text(latestVersion, NamedTextColor.GREEN)));

        if (downloadUrl != null) {
            player.sendMessage(prefix
                    .append(Component.text("Download: ", NamedTextColor.GRAY))
                    .append(Component.text(downloadUrl, NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.openUrl(downloadUrl))));
        }
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
