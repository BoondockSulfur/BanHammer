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

    /** Name of the jail BanHammer auto-creates in Essentials when none exists. */
    private static final String BANHAMMER_JAIL_NAME = "banhammer";

    private final Logger logger;
    private final Plugin essentials;
    private boolean available = false;

    public EssentialsJailIntegration(Logger logger, boolean enabled) {
        this.logger = logger;

        if (!enabled) {
            this.essentials = null;
            logger.info("Essentials jail hook disabled in config (punishmentTypes.jail.useEssentials: false). Using built-in jail system.");
            return;
        }

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
     * Jails a player using Essentials, using the configured default cell.
     *
     * @param player The player to jail
     * @return true if successful, false otherwise
     */
    public boolean jailPlayer(Player player) {
        return jailPlayer(player, null);
    }

    /**
     * Jails a player using Essentials in a specific cell.
     *
     * @param player        The player to jail
     * @param requestedCell The Essentials jail/cell to use, or null to use the configured
     *                      default ({@code punishmentTypes.jail.essentialsDefaultJail}, default "1")
     * @return true if successful, false otherwise
     */
    public boolean jailPlayer(Player player, String requestedCell) {
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

            String jailName = resolveJailName(jails, jailNames, requestedCell);
            if (jailName == null) {
                return false; // reason already logged by resolveJailName
            }
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
            dev.banhammer.plugin.util.FoliaScheduler.teleportAsync(
                    dev.banhammer.plugin.BanHammerPlugin.get(), player, jailLocation);
            logger.debug("Teleport initiated (async-safe)");
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
     * Returns the names of all configured Essentials jails (lowercased as stored by Essentials),
     * or an empty list if unavailable or on error. Used for command validation/tab-completion.
     */
    @SuppressWarnings("unchecked")
    public java.util.Collection<String> getJailNames() {
        if (!available) {
            return java.util.Collections.emptyList();
        }
        try {
            Object jails = essentials.getClass().getMethod("getJails").invoke(essentials);
            java.util.Collection<String> names =
                    (java.util.Collection<String>) jails.getClass().getMethod("getList").invoke(jails);
            return names != null ? names : java.util.Collections.emptyList();
        } catch (Exception e) {
            logger.debug("Could not read Essentials jail list: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Resolves which Essentials jail to use.
     * <ul>
     *   <li>An explicitly requested cell must exist (case-insensitive) or this returns null.</li>
     *   <li>Otherwise the configured default cell is used; if it is missing, the first existing
     *       jail is used, or a jail is auto-created when none exist at all.</li>
     * </ul>
     *
     * @return the actual (stored) jail name to use, or null if none could be resolved
     */
    private String resolveJailName(Object jails, java.util.Collection<String> jailNames, String requestedCell) {
        // Explicit cell requested -> it must exist.
        if (requestedCell != null && !requestedCell.isBlank()) {
            String match = matchCell(jailNames, requestedCell);
            if (match == null) {
                logger.warn("Requested Essentials jail '{}' does not exist. Available jails: {}", requestedCell, jailNames);
            }
            return match;
        }

        // No cell given -> use the configured default (e.g. "1").
        String def = dev.banhammer.plugin.BanHammerPlugin.get().getConfig()
                .getString("punishmentTypes.jail.essentialsDefaultJail", "1");
        String match = matchCell(jailNames, def);
        if (match != null) {
            return match;
        }

        // Default cell missing: fall back to the first existing jail, or auto-create if none exist.
        if (jailNames != null && !jailNames.isEmpty()) {
            String first = jailNames.iterator().next();
            logger.warn("Default Essentials jail '{}' not found - using '{}' instead.", def, first);
            return first;
        }

        String created = ensureBanHammerJail(jails);
        if (created == null) {
            logger.error("No Essentials jail configured and auto-create failed "
                    + "(BanHammer jail location not set). Set one with /bh setjail or /setjail <name>.");
        }
        return created;
    }

    /** Case-insensitive lookup of a jail name; returns the actual stored name or null. */
    private String matchCell(java.util.Collection<String> jailNames, String name) {
        if (jailNames == null || name == null) {
            return null;
        }
        for (String n : jailNames) {
            if (n != null && n.equalsIgnoreCase(name)) {
                return n;
            }
        }
        return null;
    }

    /**
     * Ensures an Essentials jail exists by registering BanHammer's configured jail location
     * as an Essentials jail. Used when Essentials is hooked but no jail has been set up yet,
     * so jailing still happens through Essentials instead of the built-in system.
     *
     * @param jails the Essentials {@code Jails} object (via reflection)
     * @return the jail name to use, or null if no location is available or the call failed
     */
    private String ensureBanHammerJail(Object jails) {
        try {
            Location loc = dev.banhammer.plugin.BanHammerPlugin.get().getJailManager().getJailLocation();
            if (loc == null || loc.getWorld() == null) {
                return null;
            }

            // Essentials API: void setJail(String name, Location loc) throws Exception
            Method setJailMethod = jails.getClass().getMethod("setJail", String.class, Location.class);
            setJailMethod.invoke(jails, BANHAMMER_JAIL_NAME, loc);

            logger.info("No Essentials jail existed - auto-created jail '{}' from BanHammer's configured jail location.",
                    BANHAMMER_JAIL_NAME);
            return BANHAMMER_JAIL_NAME;
        } catch (Exception e) {
            logger.error("Failed to auto-create Essentials jail: {}", e.getMessage());
            logger.debug("Essentials setJail error", e);
            return null;
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
