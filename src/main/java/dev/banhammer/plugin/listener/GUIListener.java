package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.gui.StatisticsGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Handles GUI clicks for statistics menus.
 *
 * @since 3.0.0
 */
public class GUIListener implements Listener {

    private final BanHammerPlugin plugin;
    private final StatisticsGUI gui;

    public GUIListener(BanHammerPlugin plugin, StatisticsGUI gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Component title = event.getView().title();
        String titleText = PlainTextComponentSerializer.plainText().serialize(title);

        // Check if it's one of our GUIs
        if (!titleText.contains("BanHammer") && !titleText.contains("Statistiken") && !titleText.contains("Leaderboard")) {
            return;
        }

        event.setCancelled(true);

        if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) {
            return;
        }

        Component itemName = event.getCurrentItem().getItemMeta().displayName();
        String itemNameText = PlainTextComponentSerializer.plainText().serialize(itemName);

        // Main menu
        if (titleText.contains("BanHammer Statistiken")) {
            handleMainMenu(player, itemNameText);
        }
        // Sub menus - Back button
        else if (itemNameText.equals("Zurück")) {
            gui.openMainMenu(player);
        }
        // Close button
        else if (itemNameText.equals("Schließen")) {
            player.closeInventory();
        }
    }

    private void handleMainMenu(Player player, String itemName) {
        switch (itemName) {
            case "Deine Statistiken" -> gui.openPlayerStats(player, player.getUniqueId());
            case "Staff Leaderboard" -> gui.openStaffLeaderboard(player);
            case "Server Statistiken" -> gui.openServerStats(player);
            case "Schließen" -> player.closeInventory();
        }
    }
}
