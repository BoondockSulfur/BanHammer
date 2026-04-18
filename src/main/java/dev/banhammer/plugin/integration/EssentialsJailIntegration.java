package dev.banhammer.plugin.integration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Integration with Essentials plugin for jail functionality.
 * Uses reflection to avoid hard dependency on Essentials.
 *
 * @since 3.0.0
 */
public class EssentialsJailIntegration {

    private final Logger logger;
    private final Plugin essentials;
    private boolean available = false;

    public EssentialsJailIntegration(Logger logger) {
        this.logger = logger;
        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials");

        if (essentials != null && essentials.isEnabled()) {
            try {
                // Try to access Essentials API to verify it's available
                Class.forName("com.earth2me.essentials.Essentials");
                available = true;
                logger.info("Essentials detected! Using Essentials jail system.");
            } catch (ClassNotFoundException e) {
                logger.warn("Essentials plugin found but API not accessible. Using built-in jail system.");
            }
        } else {
            logger.info("Essentials not found. Using built-in jail system.");
        }
    }

    /**
     * Checks if Essentials jail integration is available.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Jails a player using Essentials.
     *
     * @param player The player to jail
     * @return true if successful, false otherwise
     */
    public boolean jailPlayer(Player player) {
        if (!available) {
            return false;
        }

        try {
            logger.debug("Jailing {} using Essentials...", player.getName());

            // Use Essentials API via reflection
            Object essentialsObj = essentials;
            Method getUserMethod = essentials.getClass().getMethod("getUser", Player.class);
            Object user = getUserMethod.invoke(essentialsObj, player);

            if (user == null) {
                logger.warn("Could not get Essentials user for {}", player.getName());
                return false;
            }

            // Get the Jails object from Essentials
            Method getJailsMethod = essentials.getClass().getMethod("getJails");
            Object jails = getJailsMethod.invoke(essentialsObj);

            // Get jail names
            Method getJailNamesMethod = jails.getClass().getMethod("getList");
            @SuppressWarnings("unchecked")
            java.util.Collection<String> jailNames = (java.util.Collection<String>) getJailNamesMethod.invoke(jails);

            if (jailNames == null || jailNames.isEmpty()) {
                logger.error("No jails configured in Essentials! Please create at least one jail with /setjail <name>");
                return false;
            }

            // Use the first available jail
            String jailName = jailNames.iterator().next();
            logger.debug("Using Essentials jail: {}", jailName);

            // Get the jail location for teleportation
            // The getJail method returns a Location directly, not a Jail object
            Method getJailMethod = jails.getClass().getMethod("getJail", String.class);
            Object jailLocationObj = getJailMethod.invoke(jails, jailName);

            if (jailLocationObj == null) {
                logger.error("Jail '{}' has no location set! Use /setjail {} to set it.", jailName, jailName);
                return false;
            }

            if (!(jailLocationObj instanceof Location)) {
                logger.error("Jail location is not a valid Location object! Got: {}", jailLocationObj.getClass().getName());
                return false;
            }

            Location jailLocation = (Location) jailLocationObj;

            // Log jail location details for debugging
            logger.debug("Attempting to teleport {} to jail", player.getName());
            logger.debug("Jail location: World={}, X={}, Y={}, Z={}",
                jailLocation.getWorld() != null ? jailLocation.getWorld().getName() : "null",
                jailLocation.getX(), jailLocation.getY(), jailLocation.getZ());
            logger.debug("Player current location: World={}, X={}, Y={}, Z={}",
                player.getLocation().getWorld().getName(),
                player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());

            // FIXED: Set jailed status FIRST to prevent race conditions
            // This ensures Essentials knows the player is jailed before teleportation
            Method setJailedMethod = user.getClass().getMethod("setJailed", boolean.class);
            setJailedMethod.invoke(user, true);
            logger.debug("Set jailed status to true");

            // Set jail name
            Method setJailMethod = user.getClass().getMethod("setJail", String.class);
            setJailMethod.invoke(user, jailName);
            logger.debug("Set jail name to '{}'", jailName);

            // Then teleport player to jail (after status is set)
            boolean teleportSuccess = player.teleport(jailLocation);
            logger.debug("Teleport result: {}", teleportSuccess);
            logger.debug("Player location after teleport: World={}, X={}, Y={}, Z={}",
                player.getLocation().getWorld().getName(),
                player.getLocation().getX(), player.getLocation().getY(), player.getLocation().getZ());

            logger.info("Successfully jailed {} in Essentials jail '{}'", player.getName(), jailName);
            return true;

        } catch (Exception e) {
            logger.error("Failed to jail player using Essentials: {}", e.getMessage());
            logger.debug("Essentials jail error", e);
            return false;
        }
    }

    /**
     * Releases a player from jail using Essentials.
     *
     * @param player The player to release
     * @return true if successful, false otherwise
     */
    public boolean releasePlayer(Player player) {
        if (!available) {
            return false;
        }

        try {
            logger.debug("Releasing {} from jail using Essentials...", player.getName());

            // Use Essentials API via reflection
            Object essentialsObj = essentials;
            Method getUserMethod = essentials.getClass().getMethod("getUser", Player.class);
            Object user = getUserMethod.invoke(essentialsObj, player);

            if (user == null) {
                logger.warn("Could not get Essentials user for {}", player.getName());
                return false;
            }

            // Set jailed status to false
            Method setJailedMethod = user.getClass().getMethod("setJailed", boolean.class);
            setJailedMethod.invoke(user, false);

            logger.debug("Successfully released {} from jail using Essentials", player.getName());
            return true;

        } catch (Exception e) {
            logger.error("Failed to release player using Essentials: {}", e.getMessage());
            logger.debug("Essentials unjail error", e);
            return false;
        }
    }

    /**
     * Checks if a player is jailed in Essentials.
     *
     * @param player The player to check
     * @return true if jailed, false otherwise
     */
    public boolean isJailed(Player player) {
        if (!available) {
            return false;
        }

        try {
            Object essentialsObj = essentials;
            Method getUserMethod = essentials.getClass().getMethod("getUser", Player.class);
            Object user = getUserMethod.invoke(essentialsObj, player);

            if (user == null) {
                return false;
            }

            Method isJailedMethod = user.getClass().getMethod("isJailed");
            return (boolean) isJailedMethod.invoke(user);

        } catch (Exception e) {
            logger.debug("Failed to check jail status: {}", e.getMessage());
            return false;
        }
    }
}
