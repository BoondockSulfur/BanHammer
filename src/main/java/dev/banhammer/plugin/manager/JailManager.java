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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Manages jailed players and jail locations.
 *
 * <h2>Persistence</h2>
 * Jail state (who is jailed, until when, where to return them, and releases that still have
 * to be carried out) is written to {@code jails.yml}. Without it the return location was lost
 * after a restart or once the player had been offline for a while, and re-jailing on join then
 * stored the jail cell itself as the return location. Releases of offline players are kept as
 * pending and carried out on the next join, including the Essentials jail flag.
 *
 * @since 3.0.0
 */
public final class JailManager {

    private static final String STORE_FILE = "jails.yml";

    /**
     * A location stored by world name. Resolved on use, because the world may not be loaded
     * yet when the plugin starts (Multiverse) and {@link Location} only holds a weak world
     * reference.
     */
    private record SavedLocation(String world, double x, double y, double z, float yaw, float pitch) {

        static SavedLocation of(Location location) {
            return new SavedLocation(location.getWorld().getName(), location.getX(), location.getY(),
                    location.getZ(), location.getYaw(), location.getPitch());
        }

        Location resolve() {
            var loaded = world == null ? null : Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
        }

        void writeTo(ConfigurationSection section) {
            section.set("world", world);
            section.set("x", x);
            section.set("y", y);
            section.set("z", z);
            section.set("yaw", yaw);
            section.set("pitch", pitch);
        }

        static SavedLocation readFrom(ConfigurationSection section) {
            if (section == null || section.getString("world") == null) {
                return null;
            }
            return new SavedLocation(section.getString("world"), section.getDouble("x"),
                    section.getDouble("y"), section.getDouble("z"),
                    (float) section.getDouble("yaw", 0.0), (float) section.getDouble("pitch", 0.0));
        }
    }

    private final BanHammerPlugin plugin;
    private final EssentialsJailIntegration essentialsJail;

    /** Who is currently jailed. The value was never read, so this is a set. */
    private final Set<UUID> jailedPlayers = ConcurrentHashMap.newKeySet();

    /** Where to put a player back when they are released. */
    private final Map<UUID, SavedLocation> returnLocations = new ConcurrentHashMap<>();

    /** Expiry timestamps (epoch millis) for temporary jails. */
    private final Map<UUID, Long> jailExpiry = new ConcurrentHashMap<>();

    /** Players released while offline; the release is carried out on their next join. */
    private final Set<UUID> pendingReleases = ConcurrentHashMap.newKeySet();

    /**
     * Remaining time (millis) of temporary jails whose player is offline. Jail time only runs
     * while the player is online: the clock stops on quit and resumes on the next join.
     */
    private final Map<UUID, Long> pausedRemaining = new ConcurrentHashMap<>();

    private final File storeFile;

    /** Serializes file writes so an older snapshot can never overwrite a newer one. */
    private final ExecutorService fileWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BanHammer-JailStore");
        thread.setDaemon(true);
        return thread;
    });

    private volatile SavedLocation jailLocation;
    private volatile JailListener jailListener;
    private Object expiryTask;

    public JailManager(BanHammerPlugin plugin, EssentialsJailIntegration essentialsJail) {
        this.plugin = plugin;
        this.essentialsJail = essentialsJail;
        this.storeFile = new File(plugin.getDataFolder(), STORE_FILE);
        loadJailLocation();
        loadStore();
    }

    /**
     * Starts the background tasks.
     *
     * <p>Deliberately not done in the constructor: scheduling hands {@code this} to another
     * thread before construction has finished, which is exactly the publication hazard
     * {@code -Xlint:this-escape} warns about.
     */
    public void start() {
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

        SavedLocation saved = SavedLocation.readFrom(jailConfig);
        jailLocation = saved;
        if (saved == null) {
            plugin.getSLF4JLogger().warn("Jail location not configured. Use /setjail to set it.");
        } else if (saved.resolve() == null) {
            plugin.getSLF4JLogger().warn("Jail world '{}' is not loaded yet - the jail location is resolved "
                    + "again each time it is needed.", saved.world());
        } else {
            plugin.getSLF4JLogger().info("Jail location loaded: {} {},{},{}",
                    saved.world(), saved.x(), saved.y(), saved.z());
        }
    }

    // ==================== Persistence ====================

    private void loadStore() {
        if (!storeFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storeFile);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            ConfigurationSection entry = players.getConfigurationSection(key);
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("Ignoring invalid entry '{}' in {}", key, STORE_FILE);
                continue;
            }
            if (entry == null) {
                continue;
            }
            if (entry.getBoolean("jailed")) {
                jailedPlayers.add(uuid);
            }
            if (entry.getBoolean("pendingRelease")) {
                pendingReleases.add(uuid);
            }
            if (entry.isLong("expiry") || entry.isInt("expiry")) {
                jailExpiry.put(uuid, entry.getLong("expiry"));
            }
            if (entry.isLong("paused") || entry.isInt("paused")) {
                pausedRemaining.put(uuid, entry.getLong("paused"));
            }
            SavedLocation back = SavedLocation.readFrom(entry.getConfigurationSection("return"));
            if (back != null) {
                returnLocations.put(uuid, back);
            }
        }
    }

    private String snapshot() {
        YamlConfiguration yaml = new YamlConfiguration();
        Set<UUID> all = ConcurrentHashMap.newKeySet();
        all.addAll(jailedPlayers);
        all.addAll(pendingReleases);
        all.addAll(returnLocations.keySet());
        all.addAll(jailExpiry.keySet());
        all.addAll(pausedRemaining.keySet());
        for (UUID uuid : all) {
            ConfigurationSection entry = yaml.createSection("players." + uuid);
            if (jailedPlayers.contains(uuid)) {
                entry.set("jailed", true);
            }
            if (pendingReleases.contains(uuid)) {
                entry.set("pendingRelease", true);
            }
            Long expiry = jailExpiry.get(uuid);
            if (expiry != null) {
                entry.set("expiry", expiry);
            }
            Long paused = pausedRemaining.get(uuid);
            if (paused != null) {
                entry.set("paused", paused);
            }
            SavedLocation back = returnLocations.get(uuid);
            if (back != null) {
                back.writeTo(entry.createSection("return"));
            }
        }
        return yaml.saveToString();
    }

    /**
     * Writes the current state to disk off the calling thread. The snapshot is taken now, so
     * writes land in the order the changes were made.
     */
    private void persist() {
        String data = snapshot();
        try {
            fileWriter.execute(() -> writeStore(data));
        } catch (RejectedExecutionException shuttingDown) {
            writeStore(data);
        }
    }

    private void writeStore(String data) {
        writeAtomically(storeFile, data);
    }

    private void writeAtomically(File target, String data) {
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            File temp = new File(parent, target.getName() + ".tmp");
            Files.writeString(temp.toPath(), data, StandardCharsets.UTF_8);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Failed to write {}", target.getName(), e);
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

        pausedRemaining.remove(player.getUniqueId());
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
        persist();
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
            SavedLocation previous = returnLocations.putIfAbsent(uuid, SavedLocation.of(player.getLocation()));
            boolean weStoredIt = previous == null;

            if (essentialsJail.jailPlayer(player, cellName, duration)) {
                pendingReleases.remove(uuid);
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

        Location jail = getJailLocation();
        if (jail == null) {
            plugin.getSLF4JLogger().warn("Cannot jail player - jail location not set (or its world is not loaded) "
                    + "and Essentials not available");
            return false;
        }

        plugin.getSLF4JLogger().debug("Jailing {} using the built-in jail system", player.getName());

        returnLocations.putIfAbsent(uuid, SavedLocation.of(player.getLocation()));
        pendingReleases.remove(uuid);
        jailedPlayers.add(uuid);
        addToEnforcementCache(uuid);

        FoliaScheduler.teleportAsync(plugin, player, jail);
        player.sendMessage(plugin.messages().jailed());
        return true;
    }

    /**
     * Jails a player who is offline. The jail is applied on their next join, and a temporary
     * jail's time only starts running then.
     */
    public void jailOffline(UUID uuid, Duration duration) {
        pendingReleases.remove(uuid);
        jailedPlayers.add(uuid);
        jailExpiry.remove(uuid);
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            pausedRemaining.put(uuid, duration.toMillis());
        } else {
            pausedRemaining.remove(uuid);
        }
        persist();
    }

    /**
     * Stops the clock of a temporary jail when the player leaves.
     */
    public void pauseOnQuit(UUID uuid) {
        if (pause(uuid)) {
            persist();
        }
    }

    private boolean pause(UUID uuid) {
        Long expiry = jailExpiry.remove(uuid);
        if (expiry == null) {
            return false;
        }
        pausedRemaining.put(uuid, Math.max(1000L, expiry - System.currentTimeMillis()));
        return true;
    }

    /**
     * @return true if the player's temporary jail is on hold because they are offline
     */
    public boolean isPaused(UUID uuid) {
        return pausedRemaining.containsKey(uuid);
    }

    // ==================== Releasing ====================

    /**
     * Releases a player from jail. Must run on the player's region thread.
     *
     * <p>Also acts when BanHammer's own state has already been cleared - by a release while
     * the player was offline, or after a restart - as long as Essentials still holds the
     * player. Previously such a release was a no-op and Essentials re-jailed the player on
     * every join.
     */
    public void releasePlayer(Player player) {
        UUID uuid = player.getUniqueId();

        boolean tracked = jailedPlayers.remove(uuid);
        boolean pending = pendingReleases.remove(uuid);
        boolean essentialsJailed = essentialsJail != null && essentialsJail.isAvailable()
                && essentialsJail.isJailed(player);

        if (!tracked && !pending && !essentialsJailed) {
            return;
        }

        // Clear enforcement first so the teleport home is not cancelled by our own listener.
        removeFromEnforcementCache(uuid);

        SavedLocation returnLoc = returnLocations.remove(uuid);
        jailExpiry.remove(uuid);
        pausedRemaining.remove(uuid);
        persist();

        if (essentialsJailed) {
            plugin.getSLF4JLogger().debug("Releasing {} from Essentials jail...", player.getName());
            if (essentialsJail.releasePlayer(player)) {
                plugin.getSLF4JLogger().info("Released {} from Essentials jail", player.getName());
            } else {
                plugin.getSLF4JLogger().warn("Failed to release {} from Essentials; use Essentials' /unjail",
                        player.getName());
            }
        }

        if (!tracked && !essentialsJailed && returnLoc == null) {
            // Jailed and released while offline: the player never stood in the cell, so
            // there is nothing to undo and nowhere to send them.
            return;
        }

        teleportHome(player, returnLoc);
        player.sendMessage(plugin.messages().unjailed());
    }

    /**
     * Teleports a released player back where they came from.
     */
    private void teleportHome(Player player, SavedLocation returnLoc) {
        Location target = returnLoc != null ? returnLoc.resolve() : null;
        if (!isUsable(target)) {
            // Never leave a released player standing in the closed cell.
            target = player.getWorld().getSpawnLocation();
            plugin.getSLF4JLogger().warn("No usable return location for {} - sending them to the world spawn.",
                    player.getName());
        }
        FoliaScheduler.teleportAsync(plugin, player, target);
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
     * Releases an offline player. The return location is kept and the release is carried
     * out on the next join - including lifting the Essentials jail flag, which can only be
     * changed for an online player through the hook.
     */
    public void releasePlayerByUUID(UUID uuid) {
        jailedPlayers.remove(uuid);
        jailExpiry.remove(uuid);
        pausedRemaining.remove(uuid);
        pendingReleases.add(uuid);
        removeFromEnforcementCache(uuid);
        persist();
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

        Location jail = getJailLocation();
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
        this.jailLocation = SavedLocation.of(location);

        ConfigurationSection jailConfig = plugin.getConfig().createSection("punishmentTypes.jail.location");
        jailLocation.writeTo(jailConfig);

        // Serialized here, written on the writer thread: saveConfig() on another thread would
        // read the live configuration while a reload may be replacing it.
        String data = plugin.getConfig().saveToString();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        try {
            fileWriter.execute(() -> writeAtomically(configFile, data));
        } catch (RejectedExecutionException shuttingDown) {
            writeAtomically(configFile, data);
        }

        // The auto-created Essentials jail was a copy of the old location; keep it in sync.
        if (essentialsJail != null) {
            essentialsJail.updateBanHammerJail(location);
        }

        plugin.getSLF4JLogger().info("Jail location set to: {}", location);
    }

    /**
     * @return the current jail location, or {@code null} if not set or its world is not loaded
     */
    public Location getJailLocation() {
        SavedLocation saved = jailLocation;
        return saved != null ? saved.resolve() : null;
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
                        Player player = Bukkit.getPlayer(record.getVictimUuid());
                        if (player == null || !player.isOnline()) {
                            continue;
                        }
                        Duration remaining = remainingFor(record.getVictimUuid(), record, now);
                        if (remaining != null && remaining.isZero()) {
                            continue; // Already expired; the unban scheduler will clean it up.
                        }
                        resume(database, player, record, remaining);
                    }
                })
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().error("Failed to restore jailed players", throwable);
                    return null;
                });
    }

    /**
     * Restores a player's jail status when they (re)join, and carries out releases that
     * happened while they were offline.
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
            // No database: jails.yml is the source of truth.
            if (!jailedPlayers.contains(uuid)) {
                releaseIfPending(player);
                return;
            }
            Long paused = pausedRemaining.get(uuid);
            Long expiry = jailExpiry.get(uuid);
            if (paused == null && expiry != null && expiry <= System.currentTimeMillis()) {
                release(player);
                return;
            }
            addToEnforcementCache(uuid);
            reapply(player, paused != null ? Duration.ofMillis(paused) : remainingFrom(expiry));
            return;
        }

        // Block first, ask afterwards.
        addToEnforcementCache(uuid);

        database.getActivePunishmentsByType(uuid, PunishmentType.JAIL).thenAccept(punishments -> {
            Instant now = Instant.now();
            PunishmentRecord active = null;
            Duration remaining = null;
            for (PunishmentRecord candidate : punishments) {
                Duration left = remainingFor(uuid, candidate, now);
                if (left == null || !left.isZero()) {
                    active = candidate;
                    remaining = left;
                    break;
                }
            }

            if (active == null) {
                // The database is authoritative: whatever is left over locally is released.
                if (jailedPlayers.contains(uuid) || pendingReleases.contains(uuid)) {
                    release(player);
                } else {
                    removeFromEnforcementCache(uuid);
                }
                return;
            }

            addToEnforcementCache(uuid);
            resume(database, player, active, remaining);
        }).exceptionally(throwable -> {
            plugin.getSLF4JLogger().error("Failed to restore jail status for {}", player.getName(), throwable);
            // Do not leave a player blocked because of a database hiccup.
            if (!jailedPlayers.contains(uuid)) {
                removeFromEnforcementCache(uuid);
            }
            return null;
        });
    }

    /**
     * Remaining jail time for an active record, honouring a paused clock.
     *
     * @return the remaining time, {@code null} for a permanent jail, or {@link Duration#ZERO}
     *         if it has already run out
     */
    private Duration remainingFor(UUID uuid, PunishmentRecord record, Instant now) {
        if (record.getExpiresAt() == null) {
            return null;
        }
        Long paused = pausedRemaining.get(uuid);
        if (paused != null) {
            return Duration.ofMillis(paused);
        }
        Duration left = Duration.between(now, record.getExpiresAt());
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Re-applies a database jail. The record's expiry is moved to now + remaining first, so
     * the unban scheduler does not expire it on the old date in the meantime.
     */
    private void resume(Database database, Player player, PunishmentRecord record, Duration remaining) {
        if (remaining == null) {
            reapply(player, null);
            return;
        }
        database.updateExpiry(record.getId(), Instant.now().plus(remaining))
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().warn("Could not move the expiry of jail #{}: {}",
                            record.getId(), throwable.toString());
                    return false;
                })
                .thenRun(() -> reapply(player, remaining));
    }

    private void releaseIfPending(Player player) {
        if (pendingReleases.contains(player.getUniqueId())) {
            release(player);
        }
    }

    private void release(Player player) {
        FoliaScheduler.runOnEntity(plugin, player, () -> {
            if (player.isOnline()) {
                releasePlayer(player);
            }
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
            if (!player.isOnline()) {
                return;
            }
            if (!jailPlayer(player, remaining)) {
                // Leaving the player in the enforcement cache without a jail would make them
                // invulnerable and unable to teleport while walking around freely.
                removeFromEnforcementCache(player.getUniqueId());
                plugin.getSLF4JLogger().error("Could not re-jail {} - the jail location or its world is "
                        + "unavailable. The player is NOT jailed right now.", player.getName());
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
        FoliaScheduler.cancelTask(expiryTask);
        expiryTask = null;

        // Players still online at shutdown stop their clock here; the quit event does not
        // reach a plugin that is already disabled.
        for (UUID uuid : Set.copyOf(jailExpiry.keySet())) {
            pause(uuid);
        }

        // Flush the final state before the maps are cleared.
        String data = snapshot();
        fileWriter.shutdown();
        try {
            if (!fileWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getSLF4JLogger().warn("Jail store writer did not finish in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writeStore(data);

        jailedPlayers.clear();
        pendingReleases.clear();
        pausedRemaining.clear();
        returnLocations.clear();
        jailExpiry.clear();
        jailListener = null;
    }
}
