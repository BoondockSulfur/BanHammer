package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.util.Links;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Sends join-time notifications to staff (resource pack hint, update check).
 *
 * @since 4.0.0
 */
public final class JoinNotificationListener implements Listener {

    private static final String RESOURCE_PACK_URL = "https://modrinth.com/resourcepack/bs-banhammer-resource-pack";

    private final BanHammerPlugin plugin;

    public JoinNotificationListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Update notification
        if (plugin.getUpdateChecker() != null) {
            plugin.getUpdateChecker().notifyPlayer(player);
        }

        giveHammerIfConfigured(player);

        // Resource pack hint (only for staff with banhammer.use permission)
        if (!plugin.settings().resourcePackHint()) return;
        if (!player.hasPermission("banhammer.use")) return;

        Component links = resourcePackLinks();
        if (links == null) {
            return;
        }

        dev.banhammer.plugin.util.FoliaScheduler.runOnEntityDelayed(plugin, player, () -> {
            if (!player.isOnline()) {
                return;
            }

            Component prefix = Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(Component.text("BanHammer", NamedTextColor.GOLD))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY));

            player.sendMessage(prefix
                    .append(Component.text("Resource pack available - download here: ", NamedTextColor.GRAY))
                    .append(links));
        }, 60); // 3 seconds after join
    }

    /**
     * Clickable provider row for the resource pack, falling back to the Modrinth page when
     * nothing is configured.
     */
    private Component resourcePackLinks() {
        Component configured = Links.row(plugin.settings().resourcePackLinks());
        return configured != null ? configured : Links.label("Modrinth", RESOURCE_PACK_URL);
    }

    /**
     * Hands the hammer to staff on join when {@code item.giveOnJoin} is enabled.
     *
     * <p>The option was documented in {@code config.yml} but no code ever read it.
     */
    private void giveHammerIfConfigured(Player player) {
        if (!plugin.settings().item().giveOnJoin() || !player.hasPermission("banhammer.use")) {
            return;
        }

        // Do not stack up a new hammer on every login.
        for (var stack : player.getInventory().getContents()) {
            if (dev.banhammer.plugin.util.ItemFactory.isHammer(plugin, stack)) {
                return;
            }
        }

        if (!player.getInventory().addItem(dev.banhammer.plugin.util.ItemFactory.createHammer(plugin)).isEmpty()) {
            plugin.getSLF4JLogger().debug("Could not give the hammer to {}: inventory full", player.getName());
        }
    }
}
