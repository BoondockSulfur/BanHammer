package dev.banhammer.plugin.gui;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.database.model.PunishmentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Inventory-based GUI for viewing statistics and leaderboards.
 *
 * @since 3.0.0
 */
public class StatisticsGUI {

    private final BanHammerPlugin plugin;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    public StatisticsGUI(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the main statistics menu.
     */
    public void openMainMenu(Player player) {
        // Check if database is enabled
        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            player.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 27, Component.text("BanHammer Statistiken").color(NamedTextColor.GOLD));

        // Player Statistics
        ItemStack playerStats = createItem(Material.PLAYER_HEAD,
            Component.text("Deine Statistiken").color(NamedTextColor.GREEN),
            List.of(
                Component.text("Klicke um deine").color(NamedTextColor.GRAY),
                Component.text("Bestrafungs-Historie").color(NamedTextColor.GRAY),
                Component.text("anzuzeigen").color(NamedTextColor.GRAY)
            ));
        inv.setItem(11, playerStats);

        // Staff Leaderboard
        ItemStack staffLeaderboard = createItem(Material.DIAMOND_SWORD,
            Component.text("Staff Leaderboard").color(NamedTextColor.YELLOW),
            List.of(
                Component.text("Zeigt die aktivsten").color(NamedTextColor.GRAY),
                Component.text("Staff-Members").color(NamedTextColor.GRAY)
            ));
        inv.setItem(13, staffLeaderboard);

        // Server Statistics
        ItemStack serverStats = createItem(Material.BOOK,
            Component.text("Server Statistiken").color(NamedTextColor.AQUA),
            List.of(
                Component.text("Gesamtübersicht").color(NamedTextColor.GRAY),
                Component.text("aller Bestrafungen").color(NamedTextColor.GRAY)
            ));
        inv.setItem(15, serverStats);

        // Close button
        ItemStack close = createItem(Material.BARRIER,
            Component.text("Schließen").color(NamedTextColor.RED),
            List.of());
        inv.setItem(26, close);

        player.openInventory(inv);
    }

    /**
     * Opens player statistics GUI.
     */
    public void openPlayerStats(Player player, UUID targetUuid) {
        // Check if database is enabled
        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            player.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        plugin.getPunishmentManager().getHistory(targetUuid, 100).thenAccept(history -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Inventory inv = Bukkit.createInventory(null, 54, Component.text("Spieler Statistiken").color(NamedTextColor.GOLD));

                // Summary at top
                int total = history.size();
                int bans = (int) history.stream().filter(r -> r.getType() == PunishmentType.BAN || r.getType() == PunishmentType.TEMP_BAN).count();
                int kicks = (int) history.stream().filter(r -> r.getType() == PunishmentType.KICK).count();
                int mutes = (int) history.stream().filter(r -> r.getType() == PunishmentType.MUTE || r.getType() == PunishmentType.TEMP_MUTE).count();
                int warnings = (int) history.stream().filter(r -> r.getType() == PunishmentType.WARNING).count();
                int jails = (int) history.stream().filter(r -> r.getType() == PunishmentType.JAIL).count();

                inv.setItem(4, createItem(Material.PLAYER_HEAD,
                    Component.text("Gesamt: " + total).color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    List.of(
                        Component.text("Bans: " + bans).color(NamedTextColor.RED),
                        Component.text("Kicks: " + kicks).color(NamedTextColor.YELLOW),
                        Component.text("Mutes: " + mutes).color(NamedTextColor.GOLD),
                        Component.text("Warnings: " + warnings).color(NamedTextColor.LIGHT_PURPLE),
                        Component.text("Jails: " + jails).color(NamedTextColor.DARK_GRAY)
                    )));

                // Recent punishments (last 36)
                int slot = 9;
                for (int i = 0; i < Math.min(36, history.size()); i++) {
                    PunishmentRecord record = history.get(i);
                    Material material = getMaterialForType(record.getType());

                    List<Component> lore = new ArrayList<>();
                    lore.add(Component.text("ID: " + record.getId()).color(NamedTextColor.GRAY));
                    lore.add(Component.text("Von: " + record.getStaffName()).color(NamedTextColor.GRAY));
                    lore.add(Component.text("Grund: " + record.getReason()).color(NamedTextColor.WHITE));
                    lore.add(Component.text("Datum: " + DATE_FORMAT.format(Date.from(record.getIssuedAt()))).color(NamedTextColor.GRAY));

                    if (record.isActive()) {
                        lore.add(Component.text("✓ AKTIV").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
                    }

                    inv.setItem(slot++, createItem(material,
                        Component.text(record.getType().name()).color(getColorForType(record.getType())),
                        lore));
                }

                // Back button
                inv.setItem(45, createItem(Material.ARROW,
                    Component.text("Zurück").color(NamedTextColor.YELLOW),
                    List.of()));

                player.openInventory(inv);
            });
        });
    }

    /**
     * Opens staff leaderboard GUI.
     */
    public void openStaffLeaderboard(Player player) {
        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            player.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        plugin.getDatabase().getStaffStatistics(45).thenAccept(stats -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Inventory inv = Bukkit.createInventory(null, 54, Component.text("Staff Leaderboard").color(NamedTextColor.GOLD));

                int slot = 0;
                int rank = 1;

                for (PunishmentStatistics stat : stats) {
                    if (slot >= 45) break;

                    Material material = switch (rank) {
                        case 1 -> Material.GOLD_BLOCK;
                        case 2 -> Material.IRON_BLOCK;
                        case 3 -> Material.COPPER_BLOCK;
                        default -> Material.PLAYER_HEAD;
                    };

                    List<Component> lore = new ArrayList<>();
                    lore.add(Component.text("Rang: #" + rank).color(NamedTextColor.YELLOW));
                    lore.add(Component.text(""));
                    lore.add(Component.text("Gesamt: " + stat.getTotalPunishments()).color(NamedTextColor.GOLD));
                    lore.add(Component.text("Bans: " + stat.getBans()).color(NamedTextColor.RED));
                    lore.add(Component.text("Kicks: " + stat.getKicks()).color(NamedTextColor.YELLOW));
                    lore.add(Component.text("Mutes: " + stat.getMutes()).color(NamedTextColor.GOLD));
                    lore.add(Component.text("Warnings: " + stat.getWarnings()).color(NamedTextColor.LIGHT_PURPLE));

                    ItemStack item = createItem(material,
                        Component.text(stat.getStaffName()).color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                        lore);

                    // Set player head
                    if (material == Material.PLAYER_HEAD && item.getItemMeta() instanceof SkullMeta skullMeta) {
                        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(stat.getStaffUuid());
                        skullMeta.setOwningPlayer(offlinePlayer);
                        item.setItemMeta(skullMeta);
                    }

                    inv.setItem(slot++, item);
                    rank++;
                }

                // Back button
                inv.setItem(49, createItem(Material.ARROW,
                    Component.text("Zurück").color(NamedTextColor.YELLOW),
                    List.of()));

                player.openInventory(inv);
            });
        });
    }

    /**
     * Opens server statistics GUI.
     */
    public void openServerStats(Player player) {
        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            player.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        // Aggregate all staff statistics for server-wide stats
        plugin.getDatabase().getStaffStatistics(1000).thenAccept(staffStats -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Calculate totals
                int totalBans = 0;
                int totalKicks = 0;
                int totalMutes = 0;
                int totalWarnings = 0;
                int totalPunishments = 0;
                int totalStaff = staffStats.size();

                for (PunishmentStatistics stat : staffStats) {
                    totalBans += stat.getBans();
                    totalKicks += stat.getKicks();
                    totalMutes += stat.getMutes();
                    totalWarnings += stat.getWarnings();
                    totalPunishments += stat.getTotalPunishments();
                }

                Inventory inv = Bukkit.createInventory(null, 27, Component.text("Server Statistiken").color(NamedTextColor.GOLD));

                // Total punishments
                inv.setItem(10, createItem(Material.BOOK,
                    Component.text("Gesamt-Bestrafungen").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    List.of(
                        Component.text("Alle Bestrafungen: " + totalPunishments).color(NamedTextColor.WHITE)
                    )));

                // Bans
                inv.setItem(11, createItem(Material.IRON_BARS,
                    Component.text("Bans").color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    List.of(
                        Component.text("Anzahl: " + totalBans).color(NamedTextColor.WHITE)
                    )));

                // Kicks
                inv.setItem(12, createItem(Material.IRON_DOOR,
                    Component.text("Kicks").color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD),
                    List.of(
                        Component.text("Anzahl: " + totalKicks).color(NamedTextColor.WHITE)
                    )));

                // Mutes
                inv.setItem(13, createItem(Material.PINK_CANDLE,
                    Component.text("Mutes").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    List.of(
                        Component.text("Anzahl: " + totalMutes).color(NamedTextColor.WHITE)
                    )));

                // Warnings
                inv.setItem(14, createItem(Material.YELLOW_BANNER,
                    Component.text("Warnungen").color(NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD),
                    List.of(
                        Component.text("Anzahl: " + totalWarnings).color(NamedTextColor.WHITE)
                    )));

                // Staff count
                inv.setItem(16, createItem(Material.DIAMOND_SWORD,
                    Component.text("Aktive Staff-Members").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD),
                    List.of(
                        Component.text("Anzahl: " + totalStaff).color(NamedTextColor.WHITE)
                    )));

                // Back button
                inv.setItem(22, createItem(Material.ARROW,
                    Component.text("Zurück").color(NamedTextColor.YELLOW),
                    List.of()));

                player.openInventory(inv);
            });
        }).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to load server statistics", ex);
            player.sendMessage(plugin.messages().errorOccurred());
            player.closeInventory(); // Close any partially loaded GUI
            return null;
        });
    }

    // Helper methods

    private ItemStack createItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream()
            .map(c -> c.decoration(TextDecoration.ITALIC, false))
            .toList());
        item.setItemMeta(meta);
        return item;
    }

    private Material getMaterialForType(PunishmentType type) {
        return switch (type) {
            case BAN, TEMP_BAN, IP_BAN -> Material.IRON_BARS;
            case KICK -> Material.IRON_DOOR;
            case MUTE, TEMP_MUTE -> Material.PINK_CANDLE;
            case JAIL -> Material.CHAIN;
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
