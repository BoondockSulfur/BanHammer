package dev.banhammer.plugin.integration;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.util.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;

/**
 * Integration with EssentialsX jail functionality, via reflection so Essentials stays a soft
 * dependency.
 *
 * <p>Every method BanHammer needs is resolved <b>once</b>, at construction. That serves two
 * purposes: it keeps {@code Class.getMethod} - which copies the whole declared-method array on
 * each call - out of the movement handler, and it turns an incompatible Essentials version
 * into a clear warning at startup instead of a jail that silently fails at the moment a
 * moderator uses it.
 *
 * @since 3.0.0
 */
public class EssentialsJailIntegration {

    /** Name of the jail BanHammer auto-creates in Essentials when none exists. */
    private static final String BANHAMMER_JAIL_NAME = "banhammer";

    private final BanHammerPlugin plugin;
    private final Logger logger;
    private final Plugin essentials;

    private volatile boolean available;

    // Resolved once; null when the integration is unavailable.
    private Method getUser;
    private Method getJails;
    private Method jailsGetList;
    private Method jailsGetJail;
    private Method jailsSetJail;
    private Method userSetJailed;
    private Method userIsJailed;
    private Method userSetJail;
    /** Optional: not present in every Essentials fork. */
    private Method userSetJailTimeout;

    public EssentialsJailIntegration(BanHammerPlugin plugin, Logger logger, boolean enabled) {
        this.plugin = plugin;
        this.logger = logger;

        if (!enabled) {
            this.essentials = null;
            logger.info("Essentials jail hook disabled in config "
                    + "(punishmentTypes.jail.useEssentials: false). Using the built-in jail system.");
            return;
        }

        this.essentials = Bukkit.getPluginManager().getPlugin("Essentials");

        if (essentials == null || !essentials.isEnabled()) {
            logger.info("Essentials not found. Using the built-in jail system.");
            return;
        }

        this.available = resolveApi();
        if (available) {
            logger.info("Essentials detected! Using the Essentials jail system.");
        }
    }

    /**
     * Resolves and caches every Essentials method used by this class.
     *
     * @return true if the installed Essentials exposes a compatible API
     */
    private boolean resolveApi() {
        try {
            getUser = essentials.getClass().getMethod("getUser", Player.class);
            getJails = essentials.getClass().getMethod("getJails");

            // Resolve against the declared return types (the API interfaces) rather than the
            // runtime implementation classes: those may be package-private or moved between
            // versions, which would make invoke() fail with IllegalAccessException.
            Class<?> jailsType = getJails.getReturnType();
            jailsGetList = jailsType.getMethod("getList");
            jailsGetJail = jailsType.getMethod("getJail", String.class);
            jailsSetJail = jailsType.getMethod("setJail", String.class, Location.class);

            Class<?> userType = getUser.getReturnType();
            userSetJailed = userType.getMethod("setJailed", boolean.class);
            userIsJailed = userType.getMethod("isJailed");
            userSetJail = userType.getMethod("setJail", String.class);

            try {
                userSetJailTimeout = userType.getMethod("setJailTimeout", long.class);
            } catch (NoSuchMethodException e) {
                userSetJailTimeout = null;
                logger.debug("Essentials has no setJailTimeout(long); timed jails will be tracked by BanHammer only");
            }

            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("The installed Essentials version is not compatible with BanHammer's jail hook "
                    + "({}). Falling back to the built-in jail system.", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.warn("Failed to hook into Essentials, falling back to the built-in jail system", e);
            return false;
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
     */
    public boolean jailPlayer(Player player) {
        return jailPlayer(player, null, null);
    }

    /**
     * Jails a player using Essentials in a specific cell.
     *
     * @param requestedCell the Essentials jail/cell to use, or {@code null} for the configured
     *                      default ({@code punishmentTypes.jail.essentialsDefaultJail})
     */
    public boolean jailPlayer(Player player, String requestedCell) {
        return jailPlayer(player, requestedCell, null);
    }

    /**
     * Jails a player using Essentials.
     *
     * @param requestedCell the Essentials jail/cell, or {@code null} for the configured default
     * @param duration      the jail duration, or {@code null} for permanent
     * @return true if the player was jailed
     */
    public boolean jailPlayer(Player player, String requestedCell, Duration duration) {
        if (!available) {
            return false;
        }

        try {
            Object user = getUser.invoke(essentials, player);
            if (user == null) {
                logger.warn("Could not get the Essentials user for {}", player.getName());
                return false;
            }

            Object jails = getJails.invoke(essentials);
            @SuppressWarnings("unchecked")
            Collection<String> jailNames = (Collection<String>) jailsGetList.invoke(jails);

            String jailName = resolveJailName(jails, jailNames, requestedCell);
            if (jailName == null) {
                return false; // Already logged by resolveJailName.
            }

            Object jailLocationObj = jailsGetJail.invoke(jails, jailName);
            if (!(jailLocationObj instanceof Location jailLocation)) {
                logger.error("Essentials jail '{}' has no usable location. Set it with /setjail {}.",
                        jailName, jailName);
                return false;
            }

            // Mark the player as jailed before teleporting, so Essentials' own enforcement
            // does not fight the teleport.
            userSetJailed.invoke(user, true);
            userSetJail.invoke(user, jailName);

            if (userSetJailTimeout != null) {
                // Tell Essentials when the jail ends too. Without this the jail is permanent
                // from Essentials' point of view, so removing BanHammer would strand the
                // player in jail forever.
                long timeout = duration == null ? 0L : System.currentTimeMillis() + duration.toMillis();
                userSetJailTimeout.invoke(user, timeout);
            }

            FoliaScheduler.teleportAsync(plugin, player, jailLocation);

            logger.info("Successfully jailed {} in Essentials jail '{}'", player.getName(), jailName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to jail {} using Essentials", player.getName(), e);
            return false;
        }
    }

    /**
     * Returns the names of all configured Essentials jails, or an empty list if unavailable.
     */
    public Collection<String> getJailNames() {
        if (!available) {
            return Collections.emptyList();
        }
        try {
            Object jails = getJails.invoke(essentials);
            @SuppressWarnings("unchecked")
            Collection<String> names = (Collection<String>) jailsGetList.invoke(jails);
            return names != null ? names : Collections.emptyList();
        } catch (Exception e) {
            logger.debug("Could not read the Essentials jail list", e);
            return Collections.emptyList();
        }
    }

    /**
     * Resolves which Essentials jail to use.
     *
     * @return the actual stored jail name, or {@code null} if none could be resolved
     */
    private String resolveJailName(Object jails, Collection<String> jailNames, String requestedCell) {
        if (requestedCell != null && !requestedCell.isBlank()) {
            String match = matchCell(jailNames, requestedCell);
            if (match == null) {
                logger.warn("Requested Essentials jail '{}' does not exist. Available jails: {}",
                        requestedCell, jailNames);
            }
            return match;
        }

        String configured = plugin.settings().jail().essentialsDefaultJail();
        String match = matchCell(jailNames, configured);
        if (match != null) {
            return match;
        }

        if (jailNames != null && !jailNames.isEmpty()) {
            String first = jailNames.iterator().next();
            logger.warn("Default Essentials jail '{}' not found - using '{}' instead.", configured, first);
            return first;
        }

        String created = ensureBanHammerJail(jails);
        if (created == null) {
            logger.error("No Essentials jail is configured and auto-creation failed (BanHammer has no jail "
                    + "location either). Set one with /setjail, or with Essentials' /setjail <name>.");
        }
        return created;
    }

    /** Case-insensitive lookup; returns the actual stored name or {@code null}. */
    private String matchCell(Collection<String> jailNames, String name) {
        if (jailNames == null || name == null) {
            return null;
        }
        for (String candidate : jailNames) {
            if (candidate != null && candidate.equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Registers BanHammer's configured jail location as an Essentials jail, so jailing still
     * goes through Essentials when it is hooked but nothing has been set up there yet.
     *
     * @return the jail name to use, or {@code null} if no location is available
     */
    private String ensureBanHammerJail(Object jails) {
        try {
            Location location = plugin.getJailManager().getJailLocation();
            if (location == null) {
                return null;
            }
            try {
                if (location.getWorld() == null) {
                    return null;
                }
            } catch (IllegalArgumentException worldUnloaded) {
                return null;
            }

            jailsSetJail.invoke(jails, BANHAMMER_JAIL_NAME, location);
            logger.info("No Essentials jail existed - auto-created jail '{}' from BanHammer's jail location.",
                    BANHAMMER_JAIL_NAME);
            return BANHAMMER_JAIL_NAME;
        } catch (Exception e) {
            logger.error("Failed to auto-create the Essentials jail", e);
            return null;
        }
    }

    /**
     * Releases a player from jail using Essentials.
     */
    public boolean releasePlayer(Player player) {
        if (!available) {
            return false;
        }

        try {
            Object user = getUser.invoke(essentials, player);
            if (user == null) {
                logger.warn("Could not get the Essentials user for {}", player.getName());
                return false;
            }

            userSetJailed.invoke(user, false);
            userSetJail.invoke(user, (Object) null);
            if (userSetJailTimeout != null) {
                userSetJailTimeout.invoke(user, 0L);
            }

            logger.debug("Successfully released {} from the Essentials jail", player.getName());
            return true;
        } catch (Exception e) {
            logger.error("Failed to release {} using Essentials", player.getName(), e);
            return false;
        }
    }

    /**
     * Checks if a player is jailed in Essentials.
     */
    public boolean isJailed(Player player) {
        if (!available) {
            return false;
        }

        try {
            Object user = getUser.invoke(essentials, player);
            return user != null && (boolean) userIsJailed.invoke(user);
        } catch (Exception e) {
            logger.debug("Failed to check the Essentials jail status for {}", player.getName(), e);
            return false;
        }
    }
}
