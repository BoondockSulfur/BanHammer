package dev.banhammer.plugin.manager;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.integration.EssentialsJailIntegration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

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

    public JailManager(BanHammerPlugin plugin, EssentialsJailIntegration essentialsJail) {
        this.plugin = plugin;
        this.essentialsJail = essentialsJail;
        loadJailLocation();
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
     * Jails a player by teleporting them to the jail location.
     * Prefers Essentials jail if available, otherwise uses built-in jail system.
     *
     * @param player The player to jail
     * @return true if successful, false if jail location not set
     */
    public boolean jailPlayer(Player player) {
        // Try Essentials first if available
        if (essentialsJail != null && essentialsJail.isAvailable()) {
            plugin.getSLF4JLogger().debug("Attempting to jail {} using Essentials...", player.getName());
            boolean success = essentialsJail.jailPlayer(player);

            if (success) {
                // Track player as jailed (for our own management)
                jailedPlayers.put(player.getUniqueId(), player.getLocation());
                player.sendMessage(plugin.messages().jailed());
                plugin.getSLF4JLogger().info("Jailed {} using Essentials", player.getName());
                return true;
            } else {
                plugin.getSLF4JLogger().warn("Essentials jail failed for {}, falling back to built-in jail", player.getName());
            }
        }

        // Fallback to built-in jail system
        if (jailLocation == null) {
            plugin.getSLF4JLogger().warn("Cannot jail player - jail location not set and Essentials not available");
            return false;
        }

        plugin.getSLF4JLogger().debug("Jailing {} using built-in jail system", player.getName());

        // Save return location
        returnLocations.put(player.getUniqueId(), player.getLocation().clone());

        // Teleport to jail
        player.teleport(jailLocation);
        jailedPlayers.put(player.getUniqueId(), jailLocation);

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

        // Try to release from Essentials if available and player is jailed there
        if (essentialsJail != null && essentialsJail.isAvailable() && essentialsJail.isJailed(player)) {
            plugin.getSLF4JLogger().debug("Releasing {} from Essentials jail...", player.getName());
            boolean success = essentialsJail.releasePlayer(player);

            if (success) {
                jailedPlayers.remove(uuid);
                returnLocations.remove(uuid);
                player.sendMessage(plugin.messages().unjailed());
                plugin.getSLF4JLogger().info("Released {} from Essentials jail", player.getName());
                return;
            } else {
                plugin.getSLF4JLogger().warn("Failed to release {} from Essentials, trying built-in system", player.getName());
            }
        }

        // Handle built-in jail release
        Location returnLoc = returnLocations.remove(uuid);
        if (returnLoc != null && returnLoc.getWorld() != null) {
            player.teleport(returnLoc);
        }

        jailedPlayers.remove(uuid);
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
            player.teleport(jailLocation);
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
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) {
                                jailPlayer(player);
                            }
                        });
                    });
            });
        }
    }
}
