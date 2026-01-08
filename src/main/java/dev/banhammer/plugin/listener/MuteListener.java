package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles mute enforcement by blocking chat and optionally commands.
 *
 * @since 3.0.0
 */
public class MuteListener implements Listener {

    private final BanHammerPlugin plugin;
    private final Set<String> blockedCommands;

    public MuteListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.blockedCommands = ConcurrentHashMap.newKeySet();

        // Load blocked commands from config
        List<String> configCommands = plugin.getConfig().getStringList("punishmentTypes.mute.blockedCommands");
        if (configCommands.isEmpty()) {
            // Default blocked commands
            blockedCommands.add("msg");
            blockedCommands.add("tell");
            blockedCommands.add("w");
            blockedCommands.add("r");
            blockedCommands.add("me");
            blockedCommands.add("say");
        } else {
            blockedCommands.addAll(configCommands);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getConfig().getBoolean("punishmentTypes.mute.preventChat", true)) {
            return;
        }

        // Check if player is muted (synchronous cache check to prevent race condition)
        PunishmentRecord muteRecord = plugin.getPunishmentManager().getActiveMute(player.getUniqueId());

        if (muteRecord != null) {
            event.setCancelled(true);
            player.sendMessage(plugin.messages().muteChatBlocked(formatTimeRemaining(muteRecord)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!plugin.getConfig().getBoolean("punishmentTypes.mute.preventCommands", true)) {
            return;
        }

        String message = event.getMessage().toLowerCase();
        String command = message.split(" ")[0].replace("/", "");

        // Check if command should be blocked
        if (!blockedCommands.contains(command)) {
            return;
        }

        // Check if player is muted (synchronous cache check to prevent race condition)
        PunishmentRecord muteRecord = plugin.getPunishmentManager().getActiveMute(player.getUniqueId());

        if (muteRecord != null) {
            event.setCancelled(true);
            player.sendMessage(plugin.messages().muteCommandBlocked(formatTimeRemaining(muteRecord)));
        }
    }

    private String formatTimeRemaining(PunishmentRecord record) {
        if (record.getExpiresAt() == null) {
            return "permanent";
        }

        long secondsRemaining = Instant.now().until(record.getExpiresAt(), java.time.temporal.ChronoUnit.SECONDS);

        if (secondsRemaining <= 0) {
            return "abgelaufen";
        }

        long days = secondsRemaining / 86400;
        long hours = (secondsRemaining % 86400) / 3600;
        long minutes = (secondsRemaining % 3600) / 60;
        long seconds = secondsRemaining % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 && days == 0) sb.append(seconds).append("s");

        return sb.toString().trim();
    }
}
