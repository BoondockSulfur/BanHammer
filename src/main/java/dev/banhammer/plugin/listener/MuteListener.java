package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.util.DurationParser;
import dev.banhammer.plugin.util.Settings;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Enforces mutes: blocks chat, configured commands, signs and books.
 *
 * <p>The listener is always registered and consults the configuration per event, so toggling
 * {@code punishmentTypes.mute.*} and running {@code /bh reload} takes effect immediately. It
 * used to read its blocked-command list once in the constructor, which meant changes needed a
 * full server restart.
 *
 * @since 3.0.0
 */
public class MuteListener implements Listener {

    private final BanHammerPlugin plugin;

    public MuteListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Settings.Mute mute = plugin.settings().mute();
        if (!mute.enabled() || !mute.preventChat()) {
            return;
        }

        PunishmentRecord record = activeMute(event.getPlayer());
        if (record != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().muteChatBlocked(formatTimeRemaining(record)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Settings.Mute mute = plugin.settings().mute();
        if (!mute.enabled() || !mute.preventCommands()) {
            return;
        }

        String command = event.getMessage().toLowerCase(Locale.ROOT).split(" ")[0].replace("/", "");

        // Strip the plugin namespace so "/minecraft:msg" is treated as "msg".
        int colon = command.indexOf(':');
        if (colon >= 0) {
            command = command.substring(colon + 1);
        }

        if (!mute.blockedCommands().contains(command)) {
            return;
        }

        PunishmentRecord record = activeMute(event.getPlayer());
        if (record != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().muteCommandBlocked(formatTimeRemaining(record)));
        }
    }

    /**
     * Blocks sign text.
     *
     * <p>A sign is a chat channel: without this a muted player just writes what they wanted to
     * say on a sign and places it in spawn.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Settings.Mute mute = plugin.settings().mute();
        if (!mute.enabled() || !mute.preventSigns()) {
            return;
        }

        PunishmentRecord record = activeMute(event.getPlayer());
        if (record != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().muteChatBlocked(formatTimeRemaining(record)));
        }
    }

    /**
     * Blocks writing and signing books, for the same reason as signs.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEditBook(PlayerEditBookEvent event) {
        Settings.Mute mute = plugin.settings().mute();
        if (!mute.enabled() || !mute.preventBooks()) {
            return;
        }

        PunishmentRecord record = activeMute(event.getPlayer());
        if (record != null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.messages().muteChatBlocked(formatTimeRemaining(record)));
        }
    }

    /**
     * Looks up the mute from the in-memory cache; never touches the database, because chat
     * events arrive on the netty threads and must not block.
     */
    private PunishmentRecord activeMute(Player player) {
        return plugin.getPunishmentManager().getActiveMute(player.getUniqueId());
    }

    private String formatTimeRemaining(PunishmentRecord record) {
        if (record.getExpiresAt() == null) {
            return "permanent";
        }

        Duration remaining = Duration.between(Instant.now(), record.getExpiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            return "0s";
        }
        return DurationParser.formatHuman(remaining);
    }
}
