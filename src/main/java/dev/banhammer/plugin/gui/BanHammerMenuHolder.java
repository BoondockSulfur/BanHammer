package dev.banhammer.plugin.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Marks an inventory as one of BanHammer's menus.
 *
 * <p>Identity is carried by the holder object, not by the window title. Matching on the title
 * meant any container a player renamed at an anvil - a shulker box called "Leaderboard", say -
 * was treated as a BanHammer menu, which both let unprivileged players walk into the
 * statistics screens and broke unrelated plugins whose GUIs happened to share a word.
 */
public final class BanHammerMenuHolder implements InventoryHolder {

    /** Which BanHammer menu an inventory represents. */
    public enum MenuType {
        MAIN,
        PLAYER_STATS,
        STAFF_LEADERBOARD,
        SERVER_STATS
    }

    /**
     * What a button does. Stored on the item itself so clicks are dispatched by identity
     * rather than by matching the display name, which breaks the moment the text is
     * translated.
     */
    public enum Action {
        OPEN_PLAYER_STATS,
        OPEN_STAFF_LEADERBOARD,
        OPEN_SERVER_STATS,
        BACK,
        CLOSE,
        PAGE_PREVIOUS,
        PAGE_NEXT
    }

    private final MenuType menuType;
    private final int page;
    private Inventory inventory;

    public BanHammerMenuHolder(MenuType menuType) {
        this(menuType, 1);
    }

    public BanHammerMenuHolder(MenuType menuType, int page) {
        this.menuType = menuType;
        this.page = page;
    }

    public MenuType menuType() {
        return menuType;
    }

    /** @return the 1-based page currently shown, for paginated menus */
    public int page() {
        return page;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @NotNull
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
