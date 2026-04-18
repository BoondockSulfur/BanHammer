package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces jail restrictions on jailed players.
 * Performance-optimized with local cache for fast jail checks.
 *
 * @since 3.0.0
 */
public class JailListener implements Listener {

    private final BanHammerPlugin plugin;

    // Performance optimization: Local cache for fast jail checks
    // This prevents expensive Map lookups and Reflection calls on every movement
    private final Set<UUID> jailedPlayersCache = ConcurrentHashMap.newKeySet();

    public JailListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Adds a player to the jailed cache (called by JailManager).
     */
    public void addToCache(UUID playerUuid) {
        jailedPlayersCache.add(playerUuid);
    }

    /**
     * Removes a player from the jailed cache (called by JailManager).
     */
    public void removeFromCache(UUID playerUuid) {
        jailedPlayersCache.remove(playerUuid);
    }

    /**
     * Clears the entire cache (for reload scenarios).
     */
    public void clearCache() {
        jailedPlayersCache.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // Performance optimization: Early return if not in cache
        // This prevents expensive Map lookups and Reflection calls
        if (!jailedPlayersCache.contains(player.getUniqueId())) {
            return;
        }

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventMovement", true)) {
            return;
        }

        // Only check if player moved to different block
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
            event.getFrom().getBlockY() != event.getTo().getBlockY() ||
            event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {

            plugin.getJailManager().enforceJail(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        // Performance optimization: Early return if not in cache
        if (!jailedPlayersCache.contains(player.getUniqueId())) {
            return;
        }

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventTeleport", true)) {
            return;
        }

        // Block teleportation
        event.setCancelled(true);
        player.sendMessage(plugin.messages().jailNoTeleport());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // Performance optimization: Early return if not in cache
        if (!jailedPlayersCache.contains(player.getUniqueId())) {
            return;
        }

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventDamage", true)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        // Performance optimization: Early return if not in cache
        if (!jailedPlayersCache.contains(player.getUniqueId())) {
            return;
        }

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventCommands", false)) {
            return;
        }

        String command = event.getMessage().split(" ")[0].toLowerCase();

        // Allow certain commands
        if (!command.equals("/appeal") && !command.equals("/help")) {
            event.setCancelled(true);
            player.sendMessage(plugin.messages().jailNoCommands());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Remove from cache when player quits (to save memory)
        // Player data persists in database, will be re-cached on rejoin if still jailed
        jailedPlayersCache.remove(event.getPlayer().getUniqueId());
    }
}
