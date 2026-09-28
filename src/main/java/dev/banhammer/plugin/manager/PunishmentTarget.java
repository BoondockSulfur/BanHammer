package dev.banhammer.plugin.manager;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The player a punishment is aimed at - online or offline.
 *
 * @param uuid   the player's UUID
 * @param name   the player's last known name
 * @param player the online player, or {@code null} when offline
 * @since 4.2.0
 */
public record PunishmentTarget(UUID uuid, String name, Player player) {

    public static PunishmentTarget of(Player player) {
        return new PunishmentTarget(player.getUniqueId(), player.getName(), player);
    }

    /**
     * @return true if the player is connected right now
     */
    public boolean isOnline() {
        return player != null && player.isOnline();
    }
}
