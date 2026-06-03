package dev.banhammer.plugin.manager;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.integration.EssentialsJailIntegration;
import dev.banhammer.plugin.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages jailed players and jail locations.
 *
 * @since 3.0.0
 */
public class JailManager {

    private final BanHammerPlugin plugin;
    private final EssentialsJailIntegration essentialsJail;
    private final Map<UUID, Location> jailedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();
    private Location jailLocation;
    private Object cleanupTask;
    private Object expiryTask;
    // Expiry timestamps (epoch millis) for temporary jails — enables auto-release
    // even without a database (the UnbanScheduler only runs when a DB is enabled).
    private final Map<UUID, Long> jailExpiry = new ConcurrentHashMap<>();

    public JailManager(BanHammerPlugin plugin, EssentialsJailIntegration essentialsJail) {
        this.plugin = plugin;
        this.essentialsJail = essentialsJail;
        loadJailLocation();
        startCleanupTask();
        startExpiryTask();
    }

    private void loadJailLocation() {
        ConfigurationSection jailConfig = plugin.getConfig().getConfigurationSection("punishmentTypes.jail.location");

        if (jailConfig != null) {
            String worldName = jailConfig.getString("world");
            double x = jailConfig.getDouble("x");
            double y = jailConfig.getDouble("y");
            double z = jailConfig.getDouble("z");
            float yaw = (float) jailConfig.getDouble("yaw", 0.0);
            float pitch = (float) jailConfig.getDouble("pitch", 0.0);

            if (worldName != null && Bukkit.getWorld(worldName) != null) {
                jailLocation = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
                plugin.getSLF4JLogger().info("Jail location loaded: " + worldName + " " + x + "," + y + "," + z);
            } else {
                plugin.getSLF4JLogger().warn("Jail location world not found or not configured. Jail system disabled.");
            }
        } else {
            plugin.getSLF4JLogger().warn("Jail location not configured. Use /bh setjail to set it.");
        }
    }

    /**
     * Jails a player and registers an in-memory expiry for temporary jails.
     * This makes timed jails auto-release even when no database is configured.
     *
     * @param player   the player to jail
     * @param duration the jail duration (null = permanent)
     * @return true if successful, false if jail location not set
     */
    public boolean jailPlayer(Player player, Duration duration) {
        return jailPlayer(player, duration, null);
    }

    /**
     * Jails a player into a specific Essentials cell and registers an in-memory expiry
     * for temporary jails.
     *
     * @param player   the player to jail
     * @param duration the jail duration (null = permanent)
     * @param cellName the Essentials cell to use, or null for the configured default
     * @return true if successful
     */
    public boolean jailPlayer(Player player, Duration duration, String cellName) {
        boolean ok = jailPlayer(player, cellName);
        if (ok) {
            // The in-memory expiry is only a fallback for setups WITHOUT a database.
            // With a database the UnbanScheduler is the sole authority for expiry, so tracking
            // it here too would never be consumed (checkInMemoryExpiry skips when a DB is on)
            // and the entry would leak. Only register it in the no-database case.
            boolean timed = duration != null && !duration.isZero() && !duration.isNegative();
            if (timed && !plugin.getPunishmentManager().isDatabaseEnabled()) {
                jailExpiry.put(player.getUniqueId(), System.currentTimeMillis() + duration.toMillis());
            } else {
                jailExpiry.remove(player.getUniqueId()); // permanent or DB-managed: no in-memory expiry
            }
        }
        return ok;
    }

    /**
     * Jails a player by teleporting them to the jail location.
     * Prefers Essentials jail if available, otherwise uses built-in jail system.
     *
     * @param player The player to jail
     * @return true if successful, false if jail location not set
     */
    public boolean jailPlayer(Player player) {
        return jailPlayer(player, (String) null);
    }

    /**
     * Jails a player, optionally into a specific Essentials cell.
     * Prefers Essentials jail if available, otherwise uses the built-in jail system.
     *
     * @param player   The player to jail
     * @param cellName The Essentials cell to use, or null for the configured default
     * @return true if successful, false if jailing failed
     */
    public boolean jailPlayer(Player player, String cellName) {
        // Try Essentials first if available
        if (essentialsJail != null && essentialsJail.isAvailable()) {
            plugin.getSLF4JLogger().debug("Attempting to jail {} using Essentials...", player.getName());

            // FIXED: Save return location BEFORE jailing (for both Essentials and built-in)
            // putIfAbsent: keep the ORIGINAL pre-jail location. When a jail is re-applied
            // (relog/restart restore) the player is already at/near the jail, so a plain put
            // would clobber the real return location and release would drop them at the jail.
            returnLocations.putIfAbsent(player.getUniqueId(), player.getLocation().clone());

            boolean success = essentialsJail.jailPlayer(player, cellName);

            if (success) {
                // Track player as jailed (for our own management)
                jailedPlayers.put(player.getUniqueId(), player.getLocation());

                // Add to JailListener cache for performance
                notifyJailListenerCacheAdd(player.getUniqueId());

                player.sendMessage(plugin.messages().jailed());
                plugin.getSLF4JLogger().info("Jailed {} using Essentials", player.getName());
                return true;
            } else {
                // Essentials is hooked but jailing failed (and auto-create did not succeed).
                // By design we do NOT fall back to the built-in jail while Essentials is present -
                // when Essentials is hooked, jails must be created in Essentials.
                returnLocations.remove(player.getUniqueId());
                plugin.getSLF4JLogger().warn("Essentials is hooked but jailing {} failed - not using built-in jail. "
                        + "Check the Essentials jail configuration or set BanHammer's jail location with /bh setjail.",
                        player.getName());
                return false;
            }
        }

        // Built-in jail system (only used when Essentials is NOT hooked)
        if (jailLocation == null) {
            plugin.getSLF4JLogger().warn("Cannot jail player - jail location not set and Essentials not available");
            return false;
        }

        plugin.getSLF4JLogger().debug("Jailing {} using built-in jail system", player.getName());

        // Save return location
        returnLocations.put(player.getUniqueId(), player.getLocation().clone());

        // Teleport to jail
        FoliaScheduler.teleportAsync(plugin, player, jailLocation);
        jailedPlayers.put(player.getUniqueId(), jailLocation);

        // Add to JailListener cache for performance
        notifyJailListenerCacheAdd(player.getUniqueId());

        player.sendMessage(plugin.messages().jailed());
        return true;
    }

    /**
     * Releases a player from jail.
     * Handles both Essentials and built-in jail systems.
     *
     * @param player The player to release
     */
    public void releasePlayer(Player player) {
        UUID uuid = player.getUniqueId();

        if (!jailedPlayers.containsKey(uuid)) {
            return;
        }

        // Remove from JailListener cache FIRST to prevent teleport cancellation
        notifyJailListenerCacheRemove(uuid);

        // Get return location before removing from maps
        Location returnLoc = returnLocations.remove(uuid);
        jailedPlayers.remove(uuid);
        jailExpiry.remove(uuid);

        // Try to release from Essentials if available and player is jailed there
        if (essentialsJail != null && essentialsJail.isAvailable() && essentialsJail.isJailed(player)) {
            plugin.getSLF4JLogger().debug("Releasing {} from Essentials jail...", player.getName());
            boolean success = essentialsJail.releasePlayer(player);

            if (success) {
                // Teleport back to original location (Essentials doesn't do this)
                if (returnLoc != null && returnLoc.getWorld() != null) {
                    FoliaScheduler.teleportAsync(plugin, player, returnLoc);
                }

                player.sendMessage(plugin.messages().unjailed());
                plugin.getSLF4JLogger().info("Released {} from Essentials jail", player.getName());
                return;
            } else {
                plugin.getSLF4JLogger().warn("Failed to release {} from Essentials, trying built-in system", player.getName());
            }
        }

        // Handle built-in jail release - teleport back to original location
        if (returnLoc != null && returnLoc.getWorld() != null) {
            FoliaScheduler.teleportAsync(plugin, player, returnLoc);
        }

        player.sendMessage(plugin.messages().unjailed());
    }

    /**
     * Releases a player from jail by UUID (for offline players).
     *
     * @param uuid The player's UUID
     */
    public void releasePlayerByUUID(UUID uuid) {
        // Remove from jail tracking
        jailedPlayers.remove(uuid);
        returnLocations.remove(uuid); // Fix memory leak - clean up return location
        jailExpiry.remove(uuid);

        // Remove from JailListener cache
        notifyJailListenerCacheRemove(uuid);
    }

    /**
     * Checks if a player is currently jailed.
     * Checks both Essentials and built-in jail systems.
     *
     * @param playerUuid The player's UUID
     * @return true if jailed
     */
    public boolean isJailed(UUID playerUuid) {
        // Check if player is online and in Essentials jail
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null && essentialsJail != null && essentialsJail.isAvailable()) {
            if (essentialsJail.isJailed(player)) {
                return true;
            }
        }

        // Check built-in jail system
        return jailedPlayers.containsKey(playerUuid);
    }

    /**
     * Returns a player to jail if they leave the jail area.
     * Only enforces for built-in jail system (Essentials handles its own enforcement).
     *
     * @param player The player to check
     */
    public void enforceJail(Player player) {
        if (!isJailed(player.getUniqueId())) {
            return;
        }

        // Skip enforcement if player is in Essentials jail (Essentials handles its own enforcement)
        if (essentialsJail != null && essentialsJail.isAvailable() && essentialsJail.isJailed(player)) {
            return;
        }

        // Only enforce for built-in jail system
        if (jailLocation == null) {
            return;
        }

        Location playerLoc = player.getLocation();
        double maxDistance = plugin.getConfig().getDouble("punishmentTypes.jail.maxDistance", 10.0);

        if (playerLoc.getWorld() != jailLocation.getWorld() ||
            playerLoc.distance(jailLocation) > maxDistance) {

            // Teleport back to jail
            FoliaScheduler.teleportAsync(plugin, player, jailLocation);
            player.sendMessage(plugin.messages().jailEscape());
        }
    }

    /**
     * Sets the jail location.
     *
     * @param location The new jail location
     */
    public void setJailLocation(Location location) {
        this.jailLocation = location;

        // Save to config
        ConfigurationSection jailConfig = plugin.getConfig().createSection("punishmentTypes.jail.location");
        jailConfig.set("world", location.getWorld().getName());
        jailConfig.set("x", location.getX());
        jailConfig.set("y", location.getY());
        jailConfig.set("z", location.getZ());
        jailConfig.set("yaw", location.getYaw());
        jailConfig.set("pitch", location.getPitch());
        plugin.saveConfig();

        plugin.getSLF4JLogger().info("Jail location set to: " + location);
    }

    /**
     * Gets the current jail location.
     *
     * @return The jail location, or null if not set
     */
    public Location getJailLocation() {
        return jailLocation;
    }

    /**
     * @return true if the Essentials jail hook is active (installed, enabled and available)
     */
    public boolean isEssentialsAvailable() {
        return essentialsJail != null && essentialsJail.isAvailable();
    }

    /**
     * @return the configured Essentials jail/cell names, or an empty collection if Essentials
     *         is not hooked. Used by the /jail command to validate a requested cell.
     */
    public java.util.Collection<String> getEssentialsJailNames() {
        return essentialsJail != null ? essentialsJail.getJailNames() : java.util.Collections.emptyList();
    }

    /**
     * Loads jailed players from database on server start.
     */
    public void loadJailedPlayers() {
        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.getPunishmentManager().getActivePunishments(player.getUniqueId()).thenAccept(punishments -> {
                punishments.stream()
                    .filter(p -> p.getType() == PunishmentType.JAIL)
                    .findFirst()
                    .ifPresent(p -> {
                        // Check if player is still online before jailing to prevent concurrent modification
                        FoliaScheduler.runOnEntity(plugin, player, () -> {
                            if (player.isOnline()) {
                                jailPlayer(player);
                            }
                        });
                    });
            });
        }
    }

    /**
     * Restores a player's jail status when they (re)join the server.
     * Without this, a jailed player escapes enforcement simply by relogging
     * (their enforcement cache entry is cleared on quit), and jails do not
     * survive a server restart.
     *
     * @param player The joining player
     */
    public void restoreJailOnJoin(Player player) {
        UUID uuid = player.getUniqueId();

        // --- No database: in-memory tracking is the source of truth ---
        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            if (!jailedPlayers.containsKey(uuid)) {
                return; // not jailed
            }
            Long expiry = jailExpiry.get(uuid);
            if (expiry != null && expiry <= System.currentTimeMillis()) {
                releasePlayerByUUID(uuid); // temporary jail expired while the player was offline
                return;
            }
            // Re-establish enforcement: the JailListener cache is cleared on quit, so it must
            // be rebuilt (and the player pulled back into jail) or a relog escapes the jail.
            FoliaScheduler.runOnEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    jailPlayer(player);
                }
            });
            return;
        }

        // --- Database enabled: the database is the source of truth ---
        Database database = plugin.getDatabase();
        if (database == null) {
            return;
        }

        database.getActivePunishmentsByType(uuid, PunishmentType.JAIL).thenAccept(punishments -> {
            PunishmentRecord active = punishments.stream()
                    .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(Instant.now()))
                    .findFirst()
                    .orElse(null);

            // Not jailed, or jail already expired (the UnbanScheduler will clean it up)
            if (active == null) {
                return;
            }

            // Re-apply the jail on the player's region/main thread. We re-run this even if
            // jailedPlayers still holds the entry (fast relog within the cleanup window),
            // because the enforcement cache was cleared on quit and must be rebuilt.
            FoliaScheduler.runOnEntity(plugin, player, () -> {
                if (player.isOnline()) {
                    jailPlayer(player);
                }
            });
        }).exceptionally(throwable -> {
            plugin.getSLF4JLogger().error("Failed to restore jail status for {}", player.getName(), throwable);
            return null;
        });
    }

    /**
     * Notifies JailListener to add player to cache for performance optimization.
     */
    private void notifyJailListenerCacheAdd(UUID playerUuid) {
        try {
            // Get JailListener from the plugin's HandlerList
            for (var listener : org.bukkit.event.HandlerList.getRegisteredListeners(plugin)) {
                if (listener.getListener() instanceof dev.banhammer.plugin.listener.JailListener jailListener) {
                    jailListener.addToCache(playerUuid);
                    break;
                }
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().debug("Could not update JailListener cache: {}", e.getMessage());
        }
    }

    /**
     * Notifies JailListener to remove player from cache.
     */
    private void notifyJailListenerCacheRemove(UUID playerUuid) {
        try {
            // Get JailListener from the plugin's HandlerList
            for (var listener : org.bukkit.event.HandlerList.getRegisteredListeners(plugin)) {
                if (listener.getListener() instanceof dev.banhammer.plugin.listener.JailListener jailListener) {
                    jailListener.removeFromCache(playerUuid);
                    break;
                }
            }
        } catch (Exception e) {
            plugin.getSLF4JLogger().debug("Could not update JailListener cache: {}", e.getMessage());
        }
    }

    /**
     * Starts a periodic cleanup task to remove offline players from jail tracking.
     * Prevents memory leaks from players who logged out while jailed.
     */
    private void startCleanupTask() {
        // Run cleanup every 5 minutes (6000 ticks)
        cleanupTask = FoliaScheduler.runAsyncRepeating(plugin, () -> {
            int removed = cleanupOfflineJails();
            if (removed > 0) {
                plugin.getSLF4JLogger().debug("Cleaned up {} offline jailed players from memory", removed);
            }
        }, 6000L, 6000L);
    }

    /**
     * Cleans up offline players from jail tracking maps.
     * With a database, offline players are freed from memory (the jail persists in the DB and
     * is restored on rejoin). Without a database, memory IS the source of truth, so only expired
     * temporary jails are purged here and active/permanent jails are kept so they survive a relog.
     *
     * @return Number of entries removed
     */
    public int cleanupOfflineJails() {
        int removed = 0;
        boolean dbEnabled = plugin.getPunishmentManager().isDatabaseEnabled();
        long now = System.currentTimeMillis();

        for (UUID uuid : jailedPlayers.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                continue;
            }

            if (dbEnabled) {
                // Jail persists in the DB and is re-applied from there on rejoin.
                jailedPlayers.remove(uuid);
                returnLocations.remove(uuid);
                jailExpiry.remove(uuid);
                removed++;
            } else {
                // No DB: dropping an active jail would let the player escape by relogging,
                // so only purge temporary jails that have already expired.
                Long expiry = jailExpiry.get(uuid);
                if (expiry != null && expiry <= now) {
                    releasePlayerByUUID(uuid);
                    removed++;
                }
            }
        }

        return removed;
    }

    /**
     * Starts a fine-grained task that auto-releases expired temporary jails.
     * Acts as a fallback when no database is configured; with a database the
     * UnbanScheduler is the authority, so this task stays passive then.
     */
    private void startExpiryTask() {
        // Check once per second (20 ticks)
        expiryTask = FoliaScheduler.runAsyncRepeating(plugin, this::checkInMemoryExpiry, 20L, 20L);
    }

    private void checkInMemoryExpiry() {
        if (jailExpiry.isEmpty()) {
            return;
        }
        // With a database, the UnbanScheduler handles expiry (and DB deactivation).
        if (plugin.getPunishmentManager().isDatabaseEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : jailExpiry.entrySet()) {
            if (entry.getValue() > now) {
                continue;
            }
            UUID uuid = entry.getKey();
            jailExpiry.remove(uuid); // remove first to avoid double-firing on the next tick

            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                plugin.getSLF4JLogger().info("Automatically released {} from jail (temporary jail expired)", player.getName());
                FoliaScheduler.runOnEntity(plugin, player, () -> releasePlayer(player));
            } else {
                releasePlayerByUUID(uuid);
            }
        }
    }

    /**
     * Stops the cleanup and expiry tasks (called on plugin disable).
     */
    public void shutdown() {
        FoliaScheduler.cancelTask(cleanupTask);
        FoliaScheduler.cancelTask(expiryTask);
    }
}
