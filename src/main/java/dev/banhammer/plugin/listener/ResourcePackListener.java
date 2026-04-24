package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.util.ResourcePackSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class ResourcePackListener implements Listener {

    private final BanHammerPlugin plugin;

    public ResourcePackListener(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        var settings = plugin.settings();
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Player join - rpEnabled: {}, rpSendOnJoin: {}",
            settings.rpEnabled(), settings.rpSendOnJoin());

        if (!settings.rpEnabled() || !settings.rpSendOnJoin()) return;

        Player player = event.getPlayer();
        int delay = Math.max(0, settings.rpDelayTicks());
        plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Scheduling resourcepack send for {} with delay {} ticks",
            player.getName(), delay);

        dev.banhammer.plugin.util.FoliaScheduler.runOnEntityDelayed(plugin, player, () -> {
            ResourcePackSender.Result result = plugin.resourcePackSender().sendPreferredPack(player);
            if (result == ResourcePackSender.Result.NONE) {
                plugin.getSLF4JLogger().warn("DEBUG RESOURCEPACK: No resourcepack available for {}", player.getName());
            } else {
                plugin.getSLF4JLogger().info("DEBUG RESOURCEPACK: Sent {} pack to {}", result, player.getName());
            }
        }, delay);
    }
}
