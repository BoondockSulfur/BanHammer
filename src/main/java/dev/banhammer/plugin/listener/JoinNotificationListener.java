package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

        // Resource pack hint (only for staff with banhammer.use permission)
        if (!plugin.getConfig().getBoolean("resourcePackHint.enabled", true)) return;
        if (!player.hasPermission("banhammer.use")) return;

        dev.banhammer.plugin.util.FoliaScheduler.runOnEntityDelayed(plugin, player, () -> {
            Component prefix = Component.text("[", NamedTextColor.DARK_GRAY)
                    .append(Component.text("BanHammer", NamedTextColor.GOLD))
                    .append(Component.text("] ", NamedTextColor.DARK_GRAY));

            player.sendMessage(prefix
                    .append(Component.text("Resource Pack verfügbar: ", NamedTextColor.GRAY))
                    .append(Component.text("Hier herunterladen", NamedTextColor.YELLOW, TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl(RESOURCE_PACK_URL))));
        }, 60); // 3 Sekunden nach Join
    }
}
