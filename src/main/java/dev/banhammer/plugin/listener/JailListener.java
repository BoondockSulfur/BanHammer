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

/**
 * Enforces jail restrictions on jailed players.
 *
 * @since 3.0.0
 */
public class JailListener implements Listener {

    private final BanHammerPlugin plugin;

    public JailListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventMovement", true)) {
            return;
        }

        if (plugin.getJailManager().isJailed(player.getUniqueId())) {
            // Only check if player moved to different block
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
                event.getFrom().getBlockY() != event.getTo().getBlockY() ||
                event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {

                plugin.getJailManager().enforceJail(player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventTeleport", true)) {
            return;
        }

        if (plugin.getJailManager().isJailed(player.getUniqueId())) {
            // Block teleportation
            event.setCancelled(true);
            player.sendMessage(plugin.messages().jailNoTeleport());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventDamage", true)) {
            return;
        }

        if (plugin.getJailManager().isJailed(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getConfig().getBoolean("punishmentTypes.jail.preventCommands", false)) {
            return;
        }

        if (plugin.getJailManager().isJailed(player.getUniqueId())) {
            String command = event.getMessage().split(" ")[0].toLowerCase();

            // Allow certain commands
            if (!command.equals("/appeal") && !command.equals("/help")) {
                event.setCancelled(true);
                player.sendMessage(plugin.messages().jailNoCommands());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Player data persists in database, no action needed
    }
}
