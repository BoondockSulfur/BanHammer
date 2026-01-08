package dev.banhammer.plugin.command;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.util.Hex;
import dev.banhammer.plugin.util.ItemFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class BanHammerCommand implements TabExecutor {

    private final BanHammerPlugin plugin;
    private static final int ENTRIES_PER_PAGE = 10;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    public BanHammerCommand(BanHammerPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().bhUsage()));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("banhammer.reload")) {
                    sender.sendMessage(plugin.messages().noPermission());
                    return true;
                }
                plugin.getSLF4JLogger().info("Reloading BanHammer configuration...");
                plugin.reloadConfig();
                plugin.settings().reload();
                plugin.messages().load();
                plugin.getPresetManager().reload();
                plugin.reinitializeDiscord();
                plugin.reinitializeDatabase();
                sender.sendMessage(plugin.messages().reloaded());
                plugin.getSLF4JLogger().info("BanHammer configuration reloaded successfully!");
            }
            case "give" -> {
                if (!sender.hasPermission("banhammer.give")) {
                    sender.sendMessage(plugin.messages().noPermission());
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(plugin.messages().prefix()
                            .append(Component.text(" "))
                            .append(plugin.messages().giveUsage()));
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(plugin.messages().prefix()
                            .append(Component.text(" "))
                            .append(plugin.messages().playerNotFound()));
                    return true;
                }
                target.getInventory().addItem(ItemFactory.createHammer(plugin));
                sender.sendMessage(plugin.messages().given(target.getName()));
            }
            case "pack" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.messages().notPlayer());
                    return true;
                }
                plugin.resourcePackSender().sendPreferredPack(player);
                sender.sendMessage(plugin.messages().prefix()
                        .append(Component.text(" "))
                        .append(plugin.messages().resourcepackSent()));
            }
            case "history" -> handleHistory(sender, args);
            case "unban" -> handleUnban(sender, args);
            case "stats" -> handleStats(sender, args);
            case "appeals" -> handleAppeals(sender, args);
            case "approve" -> handleApprove(sender, args);
            case "deny" -> handleDeny(sender, args);
            case "gui" -> handleGUI(sender, args);
            default -> sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().unknownCommand()));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("banhammer.reload")) out.add("reload");
            if (sender.hasPermission("banhammer.give")) out.add("give");
            out.add("pack");
            if (sender.hasPermission("banhammer.history")) out.add("history");
            if (sender.hasPermission("banhammer.unban")) out.add("unban");
            if (sender.hasPermission("banhammer.stats")) out.add("stats");
            if (sender.hasPermission("banhammer.appeals")) out.add("appeals");
            if (sender.hasPermission("banhammer.appeals.review")) {
                out.add("approve");
                out.add("deny");
            }
            if (sender.hasPermission("banhammer.stats")) {
                out.add("gui");
            }
        } else if (args.length == 2) {
            if ("give".equalsIgnoreCase(args[0])) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            } else if ("history".equalsIgnoreCase(args[0]) || "unban".equalsIgnoreCase(args[0]) || "stats".equalsIgnoreCase(args[0])) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
        }
        return out;
    }

    // ===== NEW 3.0 COMMAND HANDLERS =====

    private void handleHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.history")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().historyUsage()));
            return;
        }

        String targetName = args[1];
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(plugin.messages().historyInvalidPage());
                return;
            }
        }

        // Check permission for viewing other players' history
        if (sender instanceof Player player) {
            if (!player.getName().equalsIgnoreCase(targetName) && !sender.hasPermission("banhammer.history.others")) {
                sender.sendMessage(plugin.messages().noPermission());
                return;
            }
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID targetUuid = target.getUniqueId();

        int finalPage = page;
        plugin.getPunishmentManager().getHistory(targetUuid, 1000).thenAccept(history -> {
            if (history.isEmpty()) {
                sender.sendMessage(plugin.messages().historyEmpty());
                return;
            }

            int totalPages = (int) Math.ceil(history.size() / (double) ENTRIES_PER_PAGE);
            if (finalPage < 1 || finalPage > totalPages) {
                sender.sendMessage(plugin.messages().historyInvalidPage());
                return;
            }

            int startIndex = (finalPage - 1) * ENTRIES_PER_PAGE;
            int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, history.size());

            sender.sendMessage(plugin.messages().historyHeader(targetName, finalPage, totalPages));

            for (int i = startIndex; i < endIndex; i++) {
                PunishmentRecord record = history.get(i);
                sender.sendMessage(plugin.messages().historyEntry(
                        record.getId(),
                        record.getType().name(),
                        record.getReason()
                ));
                sender.sendMessage(plugin.messages().historyEntryDate(
                        DATE_FORMAT.format(Date.from(record.getIssuedAt()))
                ));
                sender.sendMessage(plugin.messages().historyEntryStaff(record.getStaffName()));

                if (record.getExpiresAt() != null) {
                    sender.sendMessage(plugin.messages().historyEntryExpires(
                            DATE_FORMAT.format(Date.from(record.getExpiresAt()))
                    ));
                }

                if (record.isActive()) {
                    sender.sendMessage(plugin.messages().historyEntryActive());
                }
            }
        }).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to retrieve history", ex);
            sender.sendMessage(plugin.messages().errorOccurred());
            return null;
        });
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.unban")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().unbanUsage()));
            return;
        }

        String targetName = args[1];
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Entbannt durch Staff";

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        plugin.getPunishmentManager().unbanPlayer(player, targetName, reason).thenRun(() -> {
            sender.sendMessage(plugin.messages().unbanned(targetName));
            if (!reason.isEmpty()) {
                sender.sendMessage(plugin.messages().unbanReason(reason));
            }
        }).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to unban player", ex);
            sender.sendMessage(plugin.messages().errorOccurred());
            return null;
        });
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.stats")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        String targetName = args.length >= 2 ? args[1] : (sender instanceof Player ? sender.getName() : null);
        if (targetName == null) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().statsUsage()));
            return;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID targetUuid = target.getUniqueId();

        plugin.getPunishmentManager().getHistory(targetUuid, 1000).thenAccept(history -> {
            int total = history.size();
            int bans = (int) history.stream().filter(r -> r.getType() == PunishmentType.BAN || r.getType() == PunishmentType.TEMP_BAN || r.getType() == PunishmentType.IP_BAN).count();
            int kicks = (int) history.stream().filter(r -> r.getType() == PunishmentType.KICK).count();
            int mutes = (int) history.stream().filter(r -> r.getType() == PunishmentType.MUTE || r.getType() == PunishmentType.TEMP_MUTE).count();
            int warnings = (int) history.stream().filter(r -> r.getType() == PunishmentType.WARNING).count();

            sender.sendMessage(plugin.messages().statsHeader(targetName));
            sender.sendMessage(plugin.messages().statsTotal(total));
            sender.sendMessage(plugin.messages().statsBans(bans));
            sender.sendMessage(plugin.messages().statsKicks(kicks));
            sender.sendMessage(plugin.messages().statsMutes(mutes));
            sender.sendMessage(plugin.messages().statsWarnings(warnings));
        }).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to retrieve stats", ex);
            sender.sendMessage(plugin.messages().errorOccurred());
            return null;
        });
    }

    private void handleAppeals(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.appeals")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        plugin.getDatabase().getPendingAppeals().thenAccept(appeals -> {
            if (appeals.isEmpty()) {
                sender.sendMessage(plugin.messages().appealsEmpty());
                return;
            }

            sender.sendMessage(plugin.messages().appealsHeader(appeals.size()));

            for (AppealRecord appeal : appeals) {
                String shortText = appeal.getAppealText().length() > 50
                    ? appeal.getAppealText().substring(0, 50) + "..."
                    : appeal.getAppealText();

                sender.sendMessage(plugin.messages().appealsEntry(
                    appeal.getId(),
                    appeal.getPlayerName(),
                    shortText
                ));
                sender.sendMessage(plugin.messages().appealsEntryDate(
                    DATE_FORMAT.format(Date.from(appeal.getSubmittedAt()))
                ));
            }
        }).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to retrieve appeals", ex);
            sender.sendMessage(plugin.messages().errorOccurred());
            return null;
        });
    }

    private void handleApprove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.appeals.review")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().approveUsage()));
            return;
        }

        int appealId;
        try {
            appealId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.messages().appealsInvalidId());
            return;
        }

        String response = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Appeal genehmigt";

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        reviewAppeal(player, appealId, AppealRecord.AppealStatus.APPROVED, response);
    }

    private void handleDeny(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.appeals.review")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().denyUsage()));
            return;
        }

        int appealId;
        try {
            appealId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(plugin.messages().appealsInvalidId());
            return;
        }

        String response = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Appeal abgelehnt";

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        reviewAppeal(player, appealId, AppealRecord.AppealStatus.DENIED, response);
    }

    private void handleGUI(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.stats")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        plugin.getStatisticsGUI().openMainMenu(player);
    }

    private void reviewAppeal(Player staff, int appealId, AppealRecord.AppealStatus status, String response) {
        plugin.getDatabase().getAppeal(appealId).thenAccept(appeal -> {
            if (appeal == null) {
                staff.sendMessage(plugin.messages().appealsInvalidId());
                return;
            }

            if (appeal.getStatus() != AppealRecord.AppealStatus.PENDING) {
                staff.sendMessage(plugin.messages().prefix()
                        .append(Component.text(" "))
                        .append(plugin.messages().appealAlreadyProcessed()));
                return;
            }

            appeal.setStatus(status);
            appeal.setReviewedBy(staff.getUniqueId());
            appeal.setReviewerName(staff.getName());
            appeal.setReviewResponse(response);
            appeal.setReviewedAt(Instant.now());

            plugin.getDatabase().updateAppeal(appeal).thenRun(() -> {
                String statusText = status == AppealRecord.AppealStatus.APPROVED ? "APPROVED" : "DENIED";

                if (status == AppealRecord.AppealStatus.APPROVED) {
                    staff.sendMessage(plugin.messages().appealApproved(appealId));

                    // Auto-unban if approved
                    plugin.getPunishmentManager().unbanPlayer(staff, appeal.getPlayerName(), "Appeal genehmigt").thenRun(() -> {
                        // Notify player if online
                        Player target = Bukkit.getPlayer(appeal.getPlayerUuid());
                        if (target != null) {
                            target.sendMessage(plugin.messages().appealNotification("GENEHMIGT"));
                            target.sendMessage(plugin.messages().appealResponse(response));
                        }
                    });
                } else {
                    staff.sendMessage(plugin.messages().appealDenied(appealId));

                    // Notify player if online
                    Player target = Bukkit.getPlayer(appeal.getPlayerUuid());
                    if (target != null) {
                        target.sendMessage(plugin.messages().appealNotification("ABGELEHNT"));
                        target.sendMessage(plugin.messages().appealResponse(response));
                    }
                }

                // Discord notification
                if (plugin.getDiscord() != null && plugin.getConfig().getBoolean("discord.notifications.appeals", true)) {
                    plugin.getDiscord().sendAppealReview(appeal.getPlayerName(), appealId, statusText, staff.getName(), response);
                }
            }).exceptionally(ex -> {
                plugin.getSLF4JLogger().error("Failed to update appeal", ex);
                staff.sendMessage(plugin.messages().errorOccurred());
                return null;
            });
        }).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to retrieve appeal", ex);
            staff.sendMessage(plugin.messages().errorOccurred());
            return null;
        });
    }
}
