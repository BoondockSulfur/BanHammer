package dev.banhammer.plugin.manager;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.integration.EssentialsJailIntegration;
import dev.banhammer.plugin.listener.JailListener;
import dev.banhammer.plugin.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages jailed players and jail locations.
 *
 * @since 3.0.0
 */
public final class JailManager {

    private final BanHammerPlugin plugin;
    private final EssentialsJailIntegration essentialsJail;

    /** Who is currently jailed. The value was never read, so this is a set. */
    private final Set<UUID> jailedPlayers = ConcurrentHashMap.newKeySet();

    /** Where to put a player back when they are released. */
    private final Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();

    /** Expiry timestamps (epoch millis) for temporary jails. */
    private final Map<UUID, Long> jailExpiry = new ConcurrentHashMap<>();

    private volatile Location jailLocation;
    private volatile JailListener jailListener;
    private Object cleanupTask;
    private Object expiryTask;

    public JailManager(BanHammerPlugin plugin, EssentialsJailIntegration essentialsJail) {
        this.plugin = plugin;
        this.essentialsJail = essentialsJail;
        loadJailLocation();
    }

    /**
     * Starts the background tasks.
     *
     * <p>Deliberately not done in the constructor: scheduling hands {@code this} to another
     * thread before construction has finished, which is exactly the publication hazard
     * {@code -Xlint:this-escape} warns about.
     */
    public void start() {
        startCleanupTask();
        startExpiryTask();
    }

    /**
     * Injects the enforcement listener.
     *
     * <p>Replaces walking the plugin's {@code HandlerList} on every jail and release, which
     * was both wasteful and fragile - and silently did nothing when the listener had not been
     * registered because the jail system was disabled.
     */
    public void setJailListener(JailListener jailListener) {
        this.jailListener = jailListener;
    }

    /**
     * Re-reads the jail location from the configuration (called on {@code /bh reload}).
     */
    public void reload() {
        loadJailLocation();
    }

    private void loadJailLocation() {
        ConfigurationSection jailConfig = plugin.getConfig().getConfigurationSection("punishmentTypes.jail.location");

        if (jailConfig == null) {
            plugin.getSLF4JLogger().warn("Jail location not configured. Use /setjail to set it.");
            jailLocation = null;
            return;
        }

        String worldName = jailConfig.getString("world");
        double x = jailConfig.getDouble("x");
        double y = jailConfig.getDouble("y");
        double z = jailConfig.getDouble("z");
        float yaw = (float) jailConfig.getDouble("yaw", 0.0);
        float pitch = (float) jailConfig.getDouble("pitch", 0.0);

        if (worldName != null && Bukkit.getWorld(worldName) != null) {
            jailLocation = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
            plugin.getSLF4JLogger().info("Jail location loaded: {} {},{},{}", worldName, x, y, z);
        } else {
            jailLocation = null;
            plugin.getSLF4JLogger().warn("Jail world '{}' not found - the built-in jail is unavailable "
                    + "until /setjail is used again.", worldName);
        }
    }

    // ==================== Jailing ====================

    /**
     * Jails a player and registers an expiry for temporary jails.
     *
     * @param player   the player to jail
     * @param duration the jail duration, or {@code null} for permanent
     * @return true if the player was jailed
     */
    public boolean jailPlayer(Player player, Duration duration) {
        return jailPlayer(player, duration, null);
    }

    /**
     * Jails a player into a specific Essentials cell.
     *
     * @param player   the player to jail
     * @param duration the jail duration, or {@code null} for permanent
     * @param cellName the Essentials cell to use, or {@code null} for the configured default
     * @return true if the player was jailed
     */
    public boolean jailPlayer(Player player, Duration duration, String cellName) {
        boolean ok = jailPlayer(player, cellName, duration);
        if (!ok) {
            return false;
        }

        boolean timed = duration != null && !duration.isZero() && !duration.isNegative();
        if (timed) {
            // Tracked unconditionally. Making this conditional on the database being reachable
            // at this exact moment meant a brief outage produced a timed jail that nothing
            // would ever release. The unban scheduler stays authoritative when a database is
            // present; this is a harmless second safety net, and releases are idempotent.
            jailExpiry.put(player.getUniqueId(), System.currentTimeMillis() + duration.toMillis());
        } else {
            jailExpiry.remove(player.getUniqueId());
        }
        return true;
    }

    /**
     * Jails a player using the configured default cell.
     *
     * @return true if the player was jailed
     */
    public boolean jailPlayer(Player player) {
        return jailPlayer(player, null, (String) null);
    }

    /**
     * Jails a player, optionally into a specific Essentials cell.
     *
     * @param player   the player to jail
     * @param cellName the Essentials cell to use, or {@code null} for the configured default
     * @param duration the jail duration, passed on to Essentials so its own timeout matches
     * @return true if the player was jailed
     */
    private boolean jailPlayer(Player player, String cellName, Duration duration) {
        UUID uuid = player.getUniqueId();

        if (essentialsJail != null && essentialsJail.isAvailable()) {
            plugin.getSLF4JLogger().debug("Attempting to jail {} using Essentials...", player.getName());

            // putIfAbsent keeps the ORIGINAL pre-jail location: when a jail is re-applied
            // (relog or restart restore) the player already stands in the jail, and a plain
            // put would overwrite the real return location with the jail itself.
            Location previous = returnLocations.putIfAbsent(uuid, player.getLocation().clone());
            boolean weStoredIt = previous == null;

            if (essentialsJail.jailPlayer(player, cellName, duration)) {
                jailedPlayers.add(uuid);
                addToEnforcementCache(uuid);
                player.sendMessage(plugin.messages().jailed());
                plugin.getSLF4JLogger().info("Jailed {} using Essentials", player.getName());
                return true;
            }

            // Only drop the return location if this call is what created it - otherwise a
            // failed re-jail would erase the location saved by the original jail.
            if (weStoredIt) {
                returnLocations.remove(uuid);
            }
            // When Essentials is hooked, jails are managed there by design; falling back to
            // the built-in jail would put the player somewhere Essentials knows nothing about.
            plugin.getSLF4JLogger().warn("Essentials is hooked but jailing {} failed - not using the built-in jail. "
                    + "Check the Essentials jail configuration or set BanHammer's jail location with /setjail.",
                    player.getName());
            return false;
        }

        if (jailLocation == null) {
            plugin.getSLF4JLogger().warn("Cannot jail player - jail location not set and Essentials not available");
            return false;
        }

        plugin.getSLF4JLogger().debug("Jailing {} using the built-in jail system", player.getName());

        returnLocations.putIfAbsent(uuid, player.getLocation().clone());
        jailedPlayers.add(uuid);
        addToEnforcementCache(uuid);

        FoliaScheduler.teleportAsync(plugin, player, jailLocation);
        player.sendMessage(plugin.messages().jailed());
        return true;
    }

    // ==================== Releasing ====================

    /**
     * Releases a player from jail.
     */
    public void releasePlayer(Player player) {
        UUID uuid = player.getUniqueId();

        if (!jailedPlayers.contains(uuid)) {
            return;
        }

        // Clear enforcement first so the teleport home is not cancelled by our own listener.
        removeFromEnforcementCache(uuid);

        Location returnLoc = returnLocations.remove(uuid);
        jailedPlayers.remove(uuid);
        jailExpiry.remove(uuid);

        if (essentialsJail != null && essentialsJail.isAvailable() && essentialsJail.isJailed(player)) {
            plugin.getSLF4JLogger().debug("Releasing {} from Essentials jail...", player.getName());
            if (essentialsJail.releasePlayer(player)) {
                teleportHome(player, returnLoc);
                player.sendMessage(plugin.messages().unjailed());
                plugin.getSLF4JLogger().info("Released {} from Essentials jail", player.getName());
                return;
            }
            plugin.getSLF4JLogger().warn("Failed to release {} from Essentials, falling back to the built-in system",
                    player.getName());
        }

        teleportHome(player, returnLoc);
        player.sendMessage(plugin.messages().unjailed());
    }

    /**
     * Teleports a released player back where they came from.
     */
    private void teleportHome(Player player, Location returnLoc) {
        if (!isUsable(returnLoc)) {
            plugin.getSLF4JLogger().warn("No usable return location for {} - leaving them where they are. "
                    + "The world they were jailed from may have been unloaded.", player.getName());
            return;
        }
        FoliaScheduler.teleportAsync(plugin, player, returnLoc);
    }

    /**
     * Checks whether a stored location can still be used.
     *
     * <p>{@link Location#getWorld()} holds a weak reference and <em>throws</em>
     * {@code IllegalArgumentException("World unloaded")} rather than returning {@code null}
     * once the world is gone, so a plain null check is not enough. Getting this wrong meant an
     * unloaded jail world aborted the whole release, leaving the player stuck in jail with the
     * tracking maps already cleared.
     */
    private static boolean isUsable(Location location) {
        if (location == null) {
            return false;
        }
        try {
            return location.getWorld() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Releases a player from jail by UUID (for offline players).
     */
    public void releasePlayerByUUID(UUID uuid) {
        jailedPlayers.remove(uuid);
        returnLocations.remove(uuid);
        jailExpiry.remove(uuid);
        removeFromEnforcementCache(uuid);
    }

    // ==================== Queries and enforcement ====================

    /**
     * Checks whether a player is currently jailed.
     */
    public boolean isJailed(UUID playerUuid) {
        if (jailedPlayers.contains(playerUuid)) {
            return true;
        }

        Player player = Bukkit.getPlayer(playerUuid);
        return player != null && essentialsJail != null && essentialsJail.isAvailable()
                && essentialsJail.isJailed(player);
    }

    /**
     * Returns a player to the jail area if they left it.
     *
     * <p>Only the built-in jail is enforced here; Essentials enforces its own.
     */
    public void enforceJail(Player player) {
        if (!jailedPlayers.contains(player.getUniqueId())) {
            return;
        }

        // Resolved once - this runs on every movement packet, and the Essentials lookup goes
        // through reflection.
        if (essentialsJail != null && essentialsJail.isAvailable() && essentialsJail.isJailed(player)) {
            return;
        }

        Location jail = jailLocation;
        if (!isUsable(jail)) {
            return;
        }

        Location playerLoc = player.getLocation();
        double maxDistance = plugin.settings().jail().maxDistance();

        if (!playerLoc.getWorld().equals(jail.getWorld())
                || playerLoc.distanceSquared(jail) > maxDistance * maxDistance) {
            FoliaScheduler.teleportAsync(plugin, player, jail);
            player.sendMessage(plugin.messages().jailEscape());
        }
    }

    /**
     * Sets the jail location and persists it.
     */
    public void setJailLocation(Location location) {
        this.jailLocation = location;

        ConfigurationSection jailConfig = plugin.getConfig().createSection("punishmentTypes.jail.location");
        jailConfig.set("world", location.getWorld().getName());
        jailConfig.set("x", location.getX());
        jailConfig.set("y", location.getY());
        jailConfig.set("z", location.getZ());
        jailConfig.set("yaw", location.getYaw());
        jailConfig.set("pitch", location.getPitch());

        // Written off the main thread; saveConfig() serializes the whole file.
        FoliaScheduler.runAsync(plugin, plugin::saveConfig);

        plugin.getSLF4JLogger().info("Jail location set to: {}", location);
    }

    /**
     * @return the current jail location, or {@code null} if not set
     */
    public Location getJailLocation() {
        return jailLocation;
    }

    /**
     * @return true if the Essentials jail hook is active
     */
    public boolean isEssentialsAvailable() {
        return essentialsJail != null && essentialsJail.isAvailable();
    }

    /**
     * @return the configured Essentials jail/cell names, or an empty collection
     */
    public Collection<String> getEssentialsJailNames() {
        return essentialsJail != null ? essentialsJail.getJailNames() : Collections.emptyList();
    }

    // ==================== Restore ====================

    /**
     * Re-applies jails to players who are already online, once the database is ready.
     */
    public void loadJailedPlayers() {
        Database database = plugin.getDatabase();
        if (database == null) {
            return;
        }

        // One query for everybody. Asking per online player meant 150 queries on a busy
        // server, which on SQLite (a single connection) ran the last ones into the connection
        // timeout - and those players were then never re-jailed.
        database.getActivePunishmentsByTypeGlobal(PunishmentType.JAIL)
                .thenAccept(records -> {
                    Instant now = Instant.now();
                    for (PunishmentRecord record : records) {
                        if (record.getExpiresAt() != null && !record.getExpiresAt().isAfter(now)) {
                            continue; // Already expired; the unban scheduler will clean it up.
                        }
                        Player player = Bukkit.getPlayer(record.getVictimUuid());
                        if (player == null || !player.isOnline()) {
                            continue;
                        }
                        Duration remaining = record.getExpiresAt() == null
                                ? null
                                : Duration.between(now, record.getExpiresAt());
                        FoliaScheduler.runOnEntity(plugin, player, () -> {
                            if (player.isOnline()) {
                                jailPlayer(player, remaining);
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().error("Failed to restore jailed players", throwable);
                    return null;
                });
    }

    /**
     * Restores a player's jail status when they (re)join.
     *
     * <p>The player is added to the enforcement cache immediately and only removed again if
     * the lookup says they are not jailed. Waiting for the database first left a window -
     * hundreds of milliseconds on MySQL - in which movement and teleport handlers saw an
     * empty cache and a jailed player could {@code /spawn} away.
     */
    public void restoreJailOnJoin(Player player) {
        UUID uuid = player.getUniqueId();

        Database database = plugin.getDatabase();
        if (database == null) {
            // No database: memory is the source of truth.
            if (!jailedPlayers.contains(uuid)) {
                return;
            }
            Long expiry = jailExpiry.get(uuid);
            if (expiry != null && expiry <= System.currentTimeMillis()) {
                releasePlayerByUUID(uuid);
                return;
            }
            reapply(player, remainingFrom(jailExpiry.get(uuid)));
            return;
        }

        // Block first, ask afterwards.
        addToEnforcementCache(uuid);

        database.getActivePunishmentsByType(uuid, PunishmentType.JAIL).thenAccept(punishments -> {
            Instant now = Instant.now();
            PunishmentRecord active = punishments.stream()
                    .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now))
                    .findFirst()
                    .orElse(null);

            if (active == null) {
                if (!jailedPlayers.contains(uuid)) {
                    removeFromEnforcementCache(uuid);
                }
                return;
            }

            Duration remaining = active.getExpiresAt() == null ? null : Duration.between(now, active.getExpiresAt());
            reapply(player, remaining);
        }).exceptionally(throwable -> {
            plugin.getSLF4JLogger().error("Failed to restore jail status for {}", player.getName(), throwable);
            // Do not leave a player blocked because of a database hiccup.
            if (!jailedPlayers.contains(uuid)) {
                removeFromEnforcementCache(uuid);
            }
            return null;
        });
    }

    private Duration remainingFrom(Long expiryMillis) {
        if (expiryMillis == null) {
            return null;
        }
        long remaining = expiryMillis - System.currentTimeMillis();
        return remaining > 0 ? Duration.ofMillis(remaining) : Duration.ofSeconds(1);
    }

    private void reapply(Player player, Duration remaining) {
        FoliaScheduler.runOnEntity(plugin, player, () -> {
            if (player.isOnline()) {
                jailPlayer(player, remaining);
            }
        });
    }

    // ==================== Enforcement cache ====================

    private void addToEnforcementCache(UUID playerUuid) {
        JailListener listener = jailListener;
        if (listener != null) {
            listener.addToCache(playerUuid);
        }
    }

    private void removeFromEnforcementCache(UUID playerUuid) {
        JailListener listener = jailListener;
        if (listener != null) {
            listener.removeFromCache(playerUuid);
        }
    }

    // ==================== Background tasks ====================

    private void startCleanupTask() {
        cleanupTask = FoliaScheduler.runAsyncRepeating(plugin, () -> {
            int removed = cleanupOfflineJails();
            if (removed > 0) {
                plugin.getSLF4JLogger().debug("Cleaned up {} offline jailed player(s) from memory", removed);
            }
        }, 6000L, 6000L);
    }

    /**
     * Frees memory held for offline jailed players.
     *
     * <p>With a database the jail persists there and is restored on rejoin, so entries can be
     * dropped. Without one, memory <em>is</em> the record, so only expired temporary jails go.
     *
     * @return number of entries removed
     */
    public int cleanupOfflineJails() {
        int removed = 0;
        boolean hasDatabase = plugin.getDatabase() != null;
        long now = System.currentTimeMillis();

        for (UUID uuid : List.copyOf(jailedPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                continue;
            }

            if (hasDatabase) {
                jailedPlayers.remove(uuid);
                returnLocations.remove(uuid);
                jailExpiry.remove(uuid);
                removeFromEnforcementCache(uuid);
                removed++;
            } else {
                Long expiry = jailExpiry.get(uuid);
                if (expiry != null && expiry <= now) {
                    releasePlayerByUUID(uuid);
                    removed++;
                }
            }
        }

        return removed;
    }

    private void startExpiryTask() {
        expiryTask = FoliaScheduler.runAsyncRepeating(plugin, this::checkInMemoryExpiry, 20L, 20L);
    }

    private void checkInMemoryExpiry() {
        if (jailExpiry.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> entry : jailExpiry.entrySet()) {
            if (entry.getValue() > now) {
                continue;
            }
            UUID uuid = entry.getKey();
            // Removed first so a slow release cannot fire twice on the next tick.
            jailExpiry.remove(uuid);

            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                plugin.getSLF4JLogger().info("Automatically released {} from jail (temporary jail expired)",
                        player.getName());
                FoliaScheduler.runOnEntity(plugin, player, () -> releasePlayer(player),
                        () -> releasePlayerByUUID(uuid));
            } else {
                releasePlayerByUUID(uuid);
            }
        }
    }

    /**
     * Stops the background tasks and clears all state (called on plugin disable).
     */
    public void shutdown() {
        FoliaScheduler.cancelTask(cleanupTask);
        FoliaScheduler.cancelTask(expiryTask);
        cleanupTask = null;
        expiryTask = null;

        jailedPlayers.clear();
        returnLocations.clear();
        jailExpiry.clear();
        jailListener = null;
    }
}
