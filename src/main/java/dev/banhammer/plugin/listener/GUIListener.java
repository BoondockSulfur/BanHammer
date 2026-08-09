package dev.banhammer.plugin.listener;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.gui.BanHammerMenuHolder;
import dev.banhammer.plugin.gui.BanHammerMenuHolder.Action;
import dev.banhammer.plugin.gui.StatisticsGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Handles clicks in BanHammer's statistics menus.
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
        BanHammerMenuHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }

        // Cancelled unconditionally: this also covers shift-clicking out of the player's own
        // inventory into the menu, where the clicked inventory is the player's.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Only clicks on the menu itself carry meaning.
        if (event.getClickedInventory() == null || holderOf(event.getClickedInventory()) == null) {
            return;
        }

        if (!player.hasPermission(StatisticsGUI.PERMISSION)) {
            player.closeInventory();
            player.sendMessage(plugin.messages().noPermission());
            return;
        }

        // Buttons are identified by the action stored on the item, not by their label, so
        // translating the menu cannot break navigation.
        Action action = gui.actionOf(event.getCurrentItem());
        if (action == null) {
            return;
        }

        switch (action) {
            case OPEN_PLAYER_STATS -> gui.openPlayerStats(player, player.getUniqueId(), 1);
            case OPEN_STAFF_LEADERBOARD -> gui.openStaffLeaderboard(player);
            case OPEN_SERVER_STATS -> gui.openServerStats(player);
            case BACK -> gui.openMainMenu(player);
            case CLOSE -> player.closeInventory();
            case PAGE_PREVIOUS -> gui.openPlayerStats(player, player.getUniqueId(),
                    Math.max(1, holder.page() - 1));
            case PAGE_NEXT -> gui.openPlayerStats(player, player.getUniqueId(), holder.page() + 1);
        }
    }

    /**
     * Blocks dragging items into a menu.
     *
     * <p>{@link InventoryClickEvent} does not cover drags, so without this a staff member
     * could drag a stack into the display-only menu and lose it when the window closed.
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (holderOf(event.getInventory()) != null) {
            event.setCancelled(true);
        }
    }

    private BanHammerMenuHolder holderOf(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BanHammerMenuHolder holder ? holder : null;
    }
}
