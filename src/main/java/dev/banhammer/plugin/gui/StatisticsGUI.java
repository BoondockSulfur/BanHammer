package dev.banhammer.plugin.gui;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.gui.BanHammerMenuHolder.Action;
import dev.banhammer.plugin.gui.BanHammerMenuHolder.MenuType;
import dev.banhammer.plugin.util.FoliaScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inventory-based GUI for viewing statistics and leaderboards.
 *
 * <p>Every entry point re-checks {@code banhammer.stats}; buttons are tagged with a
 * {@link Action} in their persistent data rather than being recognised by their display name,
 * so the labels can be translated freely.
 *
 * @since 3.0.0
 */
public class StatisticsGUI {

    /** Permission required to view any statistics screen. */
    public static final String PERMISSION = "banhammer.stats";

    /** Entries per page in the punishment history view. */
    private static final int HISTORY_PER_PAGE = 36;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final BanHammerPlugin plugin;
    private final NamespacedKey actionKey;

    public StatisticsGUI(BanHammerPlugin plugin) {
        this.plugin = plugin;
        this.actionKey = new NamespacedKey(plugin, "menu_action");
    }

    /**
     * @return the button action stored on an item, or {@code null} if it is not a button
     */
    public Action actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return Action.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Opens the main statistics menu.
     */
    public void openMainMenu(Player player) {
        if (!canOpen(player)) {
            return;
        }

        Inventory inv = createInventory(MenuType.MAIN, 1, 27,
                plugin.messages().gui("title.main", "BanHammer Statistics"));

        inv.setItem(11, button(Material.PLAYER_HEAD, Action.OPEN_PLAYER_STATS,
                plugin.messages().gui("button.playerStats", "<green>Your statistics</green>"),
                List.of(plugin.messages().gui("button.playerStatsLore",
                        "<gray>Show your punishment history</gray>"))));

        inv.setItem(13, button(Material.DIAMOND_SWORD, Action.OPEN_STAFF_LEADERBOARD,
                plugin.messages().gui("button.leaderboard", "<yellow>Staff leaderboard</yellow>"),
                List.of(plugin.messages().gui("button.leaderboardLore",
                        "<gray>The most active staff members</gray>"))));

        inv.setItem(15, button(Material.BOOK, Action.OPEN_SERVER_STATS,
                plugin.messages().gui("button.serverStats", "<aqua>Server statistics</aqua>"),
                List.of(plugin.messages().gui("button.serverStatsLore",
                        "<gray>Totals across all punishments</gray>"))));

        inv.setItem(26, closeButton());

        player.openInventory(inv);
    }

    /**
     * Opens the punishment history of a player.
     *
     * @param page the 1-based page to show
     */
    public void openPlayerStats(Player viewer, UUID targetUuid, int page) {
        if (!canOpen(viewer)) {
            return;
        }

        Database database = plugin.getDatabase();
        if (database == null) {
            viewer.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        int requestedPage = Math.max(1, page);

        database.countPunishmentsByPlayer(targetUuid)
                .thenCompose(total -> {
                    int maxPages = Math.max(1, (int) Math.ceil(total / (double) HISTORY_PER_PAGE));
                    int effectivePage = Math.min(requestedPage, maxPages);

                    // Only the rows up to the requested page are fetched, then the page is
                    // sliced out - no need to pull the whole history to show one screen.
                    return database.getPunishmentsByPlayer(targetUuid, effectivePage * HISTORY_PER_PAGE)
                            .thenAccept(history -> FoliaScheduler.runOnEntity(plugin, viewer,
                                    () -> renderHistory(viewer, history, total, effectivePage, maxPages)));
                })
                .exceptionally(throwable -> fail(viewer, "player statistics", throwable));
    }

    private void renderHistory(Player viewer, List<PunishmentRecord> history, int total, int page, int maxPages) {
        if (!viewer.isOnline()) {
            return;
        }

        Inventory inv = createInventory(MenuType.PLAYER_STATS, page, 54,
                plugin.messages().gui("title.playerStats", "Player statistics"));

        inv.setItem(4, plain(Material.PLAYER_HEAD,
                plugin.messages().gui("header.total", "<gold><b>Total: {count}</b></gold>",
                        "{count}", String.valueOf(total)),
                List.of(
                        countLine("bans", "Bans", history, PunishmentType.BAN,
                                PunishmentType.TEMP_BAN, PunishmentType.IP_BAN),
                        countLine("kicks", "Kicks", history, PunishmentType.KICK),
                        countLine("mutes", "Mutes", history, PunishmentType.MUTE, PunishmentType.TEMP_MUTE),
                        countLine("jails", "Jails", history, PunishmentType.JAIL),
                        countLine("warnings", "Warnings", history, PunishmentType.WARNING))));

        int startIndex = (page - 1) * HISTORY_PER_PAGE;
        int slot = 9;
        for (int i = startIndex; i < history.size() && slot < 45; i++) {
            PunishmentRecord record = history.get(i);

            List<Component> lore = new ArrayList<>();
            lore.add(plugin.messages().gui("entry.id", "<gray>ID: {id}</gray>",
                    "{id}", String.valueOf(record.getId())));
            lore.add(plugin.messages().gui("entry.staff", "<gray>By: {staff}</gray>",
                    "{staff}", record.getStaffName()));
            lore.add(plugin.messages().gui("entry.reason", "<white>Reason: {reason}</white>",
                    "{reason}", record.getReason() == null ? "-" : record.getReason()));
            lore.add(plugin.messages().gui("entry.date", "<gray>Date: {date}</gray>",
                    "{date}", DATE_FORMAT.format(record.getIssuedAt())));
            if (record.isActive()) {
                lore.add(plugin.messages().gui("entry.active", "<green><b>ACTIVE</b></green>"));
            }

            inv.setItem(slot++, plain(getMaterialForType(record.getType()),
                    Component.text(record.getType().name()).color(getColorForType(record.getType())),
                    lore));
        }

        if (page > 1) {
            inv.setItem(48, button(Material.ARROW, Action.PAGE_PREVIOUS,
                    plugin.messages().gui("button.previousPage", "<yellow>Previous page</yellow>"), List.of()));
        }
        if (page < maxPages) {
            inv.setItem(50, button(Material.ARROW, Action.PAGE_NEXT,
                    plugin.messages().gui("button.nextPage", "<yellow>Next page</yellow>"), List.of()));
        }
        inv.setItem(49, plain(Material.PAPER,
                plugin.messages().gui("footer.page", "<gray>Page {page}</gray>",
                        "{page}", page + "/" + maxPages),
                List.of()));
        inv.setItem(45, backButton());

        viewer.openInventory(inv);
    }

    /**
     * Opens the staff leaderboard.
     */
    public void openStaffLeaderboard(Player player) {
        if (!canOpen(player)) {
            return;
        }

        Database database = plugin.getDatabase();
        if (database == null) {
            player.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        database.getStaffStatistics(45)
                .thenAccept(stats -> FoliaScheduler.runOnEntity(plugin, player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    Inventory inv = createInventory(MenuType.STAFF_LEADERBOARD, 1, 54,
                            plugin.messages().gui("title.leaderboard", "Staff leaderboard"));

                    int slot = 0;
                    int rank = 1;
                    for (PunishmentStatistics stat : stats) {
                        if (slot >= 45) {
                            break;
                        }

                        Material material = switch (rank) {
                            case 1 -> Material.GOLD_BLOCK;
                            case 2 -> Material.IRON_BLOCK;
                            case 3 -> Material.COPPER_BLOCK;
                            default -> Material.PLAYER_HEAD;
                        };

                        List<Component> lore = new ArrayList<>();
                        lore.add(plugin.messages().gui("entry.rank", "<yellow>Rank: #{rank}</yellow>",
                                "{rank}", String.valueOf(rank)));
                        lore.add(Component.empty());
                        lore.add(statLine("total", "Total", stat.getTotalPunishments()));
                        lore.add(statLine("bans", "Bans", stat.getBans()));
                        lore.add(statLine("kicks", "Kicks", stat.getKicks()));
                        lore.add(statLine("mutes", "Mutes", stat.getMutes()));
                        lore.add(statLine("jails", "Jails", stat.getJails()));
                        lore.add(statLine("warnings", "Warnings", stat.getWarnings()));

                        ItemStack item = plain(material,
                                Component.text(stat.getStaffName() == null ? "Unknown" : stat.getStaffName())
                                        .color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                                lore);

                        if (material == Material.PLAYER_HEAD && stat.getStaffUuid() != null
                                && item.getItemMeta() instanceof SkullMeta skullMeta) {
                            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(stat.getStaffUuid());
                            skullMeta.setOwningPlayer(offlinePlayer);
                            item.setItemMeta(skullMeta);
                        }

                        inv.setItem(slot++, item);
                        rank++;
                    }

                    inv.setItem(49, backButton());
                    player.openInventory(inv);
                }))
                .exceptionally(throwable -> fail(player, "staff leaderboard", throwable));
    }

    /**
     * Opens the server-wide statistics.
     */
    public void openServerStats(Player player) {
        if (!canOpen(player)) {
            return;
        }

        Database database = plugin.getDatabase();
        if (database == null) {
            player.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        // Aggregated in SQL rather than by summing per-staff rows in Java.
        database.getServerStatistics()
                .thenAccept(stats -> FoliaScheduler.runOnEntity(plugin, player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    Inventory inv = createInventory(MenuType.SERVER_STATS, 1, 27,
                            plugin.messages().gui("title.serverStats", "Server statistics"));

                    inv.setItem(10, statTile(Material.BOOK, "total", "Total punishments",
                            stats.getTotalPunishments()));
                    inv.setItem(11, statTile(Material.IRON_BARS, "bans", "Bans", stats.getBans()));
                    inv.setItem(12, statTile(Material.IRON_DOOR, "kicks", "Kicks", stats.getKicks()));
                    inv.setItem(13, statTile(Material.PINK_CANDLE, "mutes", "Mutes", stats.getMutes()));
                    inv.setItem(14, statTile(Material.YELLOW_BANNER, "warnings", "Warnings", stats.getWarnings()));
                    inv.setItem(15, statTile(Material.IRON_BARS, "jails", "Jails", stats.getJails()));

                    inv.setItem(22, backButton());
                    player.openInventory(inv);
                }))
                .exceptionally(throwable -> fail(player, "server statistics", throwable));
    }

    // ==================== Helpers ====================

    private boolean canOpen(Player player) {
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(plugin.messages().noPermission());
            return false;
        }
        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            player.sendMessage(plugin.messages().databaseDisabled());
            return false;
        }
        return true;
    }

    /**
     * Reports a failed load instead of leaving the player staring at a menu that never opens.
     */
    private Void fail(Player player, String what, Throwable throwable) {
        plugin.getSLF4JLogger().error("Failed to load {}", what, throwable);
        FoliaScheduler.runOnEntity(plugin, player, () -> {
            if (player.isOnline()) {
                player.sendMessage(plugin.messages().errorOccurred());
            }
        });
        return null;
    }

    private Inventory createInventory(MenuType type, int page, int size, Component title) {
        BanHammerMenuHolder holder = new BanHammerMenuHolder(type, page);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);
        return inventory;
    }

    private Component countLine(String key, String label, List<PunishmentRecord> history,
                                PunishmentType... types) {
        long count = history.stream().filter(r -> {
            for (PunishmentType type : types) {
                if (r.getType() == type) {
                    return true;
                }
            }
            return false;
        }).count();
        return statLine(key, label, (int) count);
    }

    private Component statLine(String key, String label, int value) {
        return plugin.messages().gui("stat." + key, "<gray>" + label + ": {count}</gray>",
                "{count}", String.valueOf(value));
    }

    private ItemStack statTile(Material material, String key, String label, int value) {
        return plain(material,
                plugin.messages().gui("tile." + key, "<b>" + label + "</b>"),
                List.of(statLine(key, label, value)));
    }

    private ItemStack backButton() {
        return button(Material.ARROW, Action.BACK,
                plugin.messages().gui("button.back", "<yellow>Back</yellow>"), List.of());
    }

    private ItemStack closeButton() {
        return button(Material.BARRIER, Action.CLOSE,
                plugin.messages().gui("button.close", "<red>Close</red>"), List.of());
    }

    /** Builds a clickable button, tagging it with its action. */
    private ItemStack button(Material material, Action action, Component name, List<Component> lore) {
        ItemStack item = plain(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action.name());
        item.setItemMeta(meta);
        return item;
    }

    /** Builds a decorative, non-clickable item. */
    private ItemStack plain(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Material getMaterialForType(PunishmentType type) {
        return switch (type) {
            case BAN, TEMP_BAN, IP_BAN -> Material.IRON_BARS;
            case KICK -> Material.IRON_DOOR;
            case MUTE, TEMP_MUTE -> Material.PINK_CANDLE;
            case JAIL -> Material.IRON_BARS;
            case WARNING -> Material.YELLOW_BANNER;
            default -> Material.PAPER;
        };
    }

    private NamedTextColor getColorForType(PunishmentType type) {
        return switch (type) {
            case BAN, TEMP_BAN, IP_BAN -> NamedTextColor.RED;
            case KICK -> NamedTextColor.YELLOW;
            case MUTE, TEMP_MUTE -> NamedTextColor.GOLD;
            case JAIL -> NamedTextColor.DARK_GRAY;
            case WARNING -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }
}
