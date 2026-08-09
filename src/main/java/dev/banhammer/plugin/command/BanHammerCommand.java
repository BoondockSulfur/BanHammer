package dev.banhammer.plugin.command;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentStatistics;
import dev.banhammer.plugin.util.FoliaScheduler;
import dev.banhammer.plugin.util.ItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code /banhammer} command and its subcommands.
 *
 * @since 3.0.0
 */
public class BanHammerCommand implements TabExecutor {

    private static final int ENTRIES_PER_PAGE = 10;
    private static final int MAX_PENDING_APPEALS = 45;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private final BanHammerPlugin plugin;

    /**
     * The subcommands, declared once.
     *
     * <p>Dispatch, tab completion and the usage line all read this list. Keeping three
     * hand-written copies is what let them drift apart - the usage text still advertised a
     * "pack" subcommand that had been removed.
     */
    private enum Subcommand {
        GIVE("give", "banhammer.give", true),
        RELOAD("reload", "banhammer.reload", false),
        HISTORY("history", "banhammer.history", true),
        UNBAN("unban", "banhammer.unban", true),
        STATS("stats", "banhammer.stats", true),
        GUI("gui", "banhammer.stats", false),
        APPEALS("appeals", "banhammer.appeals", false),
        APPROVE("approve", "banhammer.appeals.review", false),
        DENY("deny", "banhammer.appeals.review", false);

        private final String label;
        private final String permission;
        /** Whether the second argument is a player name, for tab completion. */
        private final boolean takesPlayerName;

        Subcommand(String label, String permission, boolean takesPlayerName) {
            this.label = label;
            this.permission = permission;
            this.takesPlayerName = takesPlayerName;
        }

        static Subcommand byLabel(String input) {
            for (Subcommand sub : values()) {
                if (sub.label.equalsIgnoreCase(input)) {
                    return sub;
                }
            }
            return null;
        }
    }

    public BanHammerCommand(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Builds the usage line from the subcommands the sender may actually use.
     */
    private Component usage(CommandSender sender) {
        String commands = java.util.Arrays.stream(Subcommand.values())
                .filter(sub -> sender.hasPermission(sub.permission))
                .map(sub -> sub.label)
                .collect(java.util.stream.Collectors.joining("|"));
        return prefixed(plugin.messages().usageBanHammer(commands));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            reply(sender, usage(sender));
            return true;
        }

        Subcommand subcommand = Subcommand.byLabel(args[0]);
        if (subcommand == null) {
            reply(sender, prefixed(plugin.messages().unknownCommand()));
            reply(sender, usage(sender));
            return true;
        }

        switch (subcommand) {
            case RELOAD -> handleReload(sender);
            case GIVE -> handleGive(sender, args);
            case HISTORY -> handleHistory(sender, args);
            case UNBAN -> handleUnban(sender, args);
            case STATS -> handleStats(sender, args);
            case APPEALS -> handleAppeals(sender);
            case APPROVE -> handleReview(sender, args, AppealRecord.AppealStatus.APPROVED);
            case DENY -> handleReview(sender, args, AppealRecord.AppealStatus.DENIED);
            case GUI -> handleGUI(sender);
        }
        return true;
    }

    // ==================== Subcommands ====================

    private void handleReload(CommandSender sender) {
        if (!require(sender, "banhammer.reload")) {
            return;
        }

        plugin.getSLF4JLogger().info("Reloading BanHammer configuration...");
        plugin.reloadAll();
        reply(sender, plugin.messages().reloaded());
        plugin.getSLF4JLogger().info("BanHammer configuration reloaded successfully!");
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.give")) {
            return;
        }

        if (args.length < 2) {
            reply(sender, prefixed(plugin.messages().giveUsage()));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            reply(sender, prefixed(plugin.messages().playerNotFound()));
            return;
        }

        // addItem returns whatever did not fit; ignoring it reported success while the hammer
        // silently never existed.
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(ItemFactory.createHammer(plugin));
        if (leftover.isEmpty()) {
            reply(sender, plugin.messages().given(target.getName()));
        } else {
            reply(sender, prefixed(plugin.messages().inventoryFull(target.getName())));
        }
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.history") || !requireDatabase(sender)) {
            return;
        }

        if (args.length < 2) {
            reply(sender, prefixed(plugin.messages().historyUsage()));
            return;
        }

        String targetName = args[1];
        int page = 1;
        if (args.length >= 3) {
            try {
                page = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                reply(sender, plugin.messages().historyInvalidPage());
                return;
            }
        }
        if (page < 1) {
            reply(sender, plugin.messages().historyInvalidPage());
            return;
        }

        if (sender instanceof Player player
                && !player.getName().equalsIgnoreCase(targetName)
                && !sender.hasPermission("banhammer.history.others")) {
            reply(sender, plugin.messages().noPermission());
            return;
        }

        UUID targetUuid = plugin.getPunishmentManager().resolvePlayerUuid(targetName);
        if (targetUuid == null) {
            reply(sender, prefixed(plugin.messages().playerNotFound()));
            return;
        }

        Database database = plugin.getDatabase();
        if (database == null) {
            reply(sender, plugin.messages().databaseDisabled());
            return;
        }

        final int finalPage = page;
        database.countPunishmentsByPlayer(targetUuid)
                .thenCompose(total -> {
                    if (total == 0) {
                        reply(sender, plugin.messages().historyEmpty());
                        return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                    }

                    int totalPages = (int) Math.ceil(total / (double) ENTRIES_PER_PAGE);
                    if (finalPage > totalPages) {
                        reply(sender, plugin.messages().historyInvalidPage());
                        return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
                    }

                    // Fetch only up to the requested page instead of pulling 1000 rows to show ten.
                    return database.getPunishmentsByPlayer(targetUuid, finalPage * ENTRIES_PER_PAGE)
                            .thenAccept(history -> printHistoryPage(sender, targetName, history,
                                    finalPage, totalPages));
                })
                .exceptionally(throwable -> fail(sender, "history for " + targetName, throwable));
    }

    private void printHistoryPage(CommandSender sender, String targetName, List<PunishmentRecord> history,
                                  int page, int totalPages) {
        int startIndex = (page - 1) * ENTRIES_PER_PAGE;
        if (startIndex >= history.size()) {
            reply(sender, plugin.messages().historyInvalidPage());
            return;
        }
        int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, history.size());

        reply(sender, plugin.messages().historyHeader(targetName, page, totalPages));

        for (int i = startIndex; i < endIndex; i++) {
            PunishmentRecord record = history.get(i);
            reply(sender, plugin.messages().historyEntry(record.getId(), record.getType().name(),
                    record.getReason() == null ? "-" : record.getReason()));
            reply(sender, plugin.messages().historyEntryDate(DATE_FORMAT.format(record.getIssuedAt())));
            reply(sender, plugin.messages().historyEntryStaff(record.getStaffName()));

            if (record.getExpiresAt() != null) {
                reply(sender, plugin.messages().historyEntryExpires(DATE_FORMAT.format(record.getExpiresAt())));
            }
            if (record.isActive()) {
                reply(sender, plugin.messages().historyEntryActive());
            }
        }
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.unban")) {
            return;
        }

        if (args.length < 2) {
            reply(sender, prefixed(plugin.messages().unbanUsage()));
            return;
        }

        String targetName = args[1];
        String reason = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "Unbanned by staff";

        plugin.getPunishmentManager().unbanPlayer(sender, targetName, reason)
                .thenAccept(removed -> {
                    if (removed) {
                        reply(sender, plugin.messages().unbanned(targetName));
                        reply(sender, plugin.messages().unbanReason(reason));
                    } else {
                        reply(sender, plugin.messages().notBanned(targetName));
                    }
                })
                .exceptionally(throwable -> fail(sender, "unban " + targetName, throwable));
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.stats") || !requireDatabase(sender)) {
            return;
        }

        String targetName = args.length >= 2 ? args[1] : (sender instanceof Player ? sender.getName() : null);
        if (targetName == null) {
            reply(sender, prefixed(plugin.messages().statsUsage()));
            return;
        }

        // Mirrors the check in /bh history: viewing someone else's record is a separate right.
        if (sender instanceof Player player
                && !player.getName().equalsIgnoreCase(targetName)
                && !sender.hasPermission("banhammer.stats.others")) {
            reply(sender, plugin.messages().noPermission());
            return;
        }

        UUID targetUuid = plugin.getPunishmentManager().resolvePlayerUuid(targetName);
        if (targetUuid == null) {
            reply(sender, prefixed(plugin.messages().playerNotFound()));
            return;
        }

        Database database = plugin.getDatabase();
        if (database == null) {
            reply(sender, plugin.messages().databaseDisabled());
            return;
        }

        // Counted in SQL rather than by loading up to 1000 rows and filtering them in Java.
        database.getStaffStatistics(targetUuid)
                .thenAccept(stats -> printStats(sender, targetName, stats))
                .exceptionally(throwable -> fail(sender, "stats for " + targetName, throwable));
    }

    private void printStats(CommandSender sender, String targetName, PunishmentStatistics stats) {
        reply(sender, plugin.messages().statsHeader(targetName));
        reply(sender, plugin.messages().statsTotal(stats.getTotalPunishments()));
        reply(sender, plugin.messages().statsBans(stats.getBans()));
        reply(sender, plugin.messages().statsKicks(stats.getKicks()));
        reply(sender, plugin.messages().statsMutes(stats.getMutes()));
        reply(sender, plugin.messages().statsJails(stats.getJails()));
        reply(sender, plugin.messages().statsWarnings(stats.getWarnings()));
    }

    private void handleAppeals(CommandSender sender) {
        if (!require(sender, "banhammer.appeals") || !requireDatabase(sender)) {
            return;
        }

        Database database = plugin.getDatabase();
        if (database == null) {
            reply(sender, plugin.messages().databaseDisabled());
            return;
        }

        database.getPendingAppeals(MAX_PENDING_APPEALS)
                .thenAccept(appeals -> {
                    if (appeals.isEmpty()) {
                        reply(sender, plugin.messages().appealsEmpty());
                        return;
                    }

                    reply(sender, plugin.messages().appealsHeader(appeals.size()));
                    for (AppealRecord appeal : appeals) {
                        String text = appeal.getAppealText();
                        String shortText = text.length() > 50 ? text.substring(0, 50) + "..." : text;
                        reply(sender, plugin.messages().appealsEntry(appeal.getId(), appeal.getPlayerName(), shortText));
                        reply(sender, plugin.messages().appealsEntryDate(DATE_FORMAT.format(appeal.getSubmittedAt())));
                    }
                })
                .exceptionally(throwable -> fail(sender, "pending appeals", throwable));
    }

    private void handleReview(CommandSender sender, String[] args, AppealRecord.AppealStatus status) {
        if (!require(sender, "banhammer.appeals.review") || !requireDatabase(sender)) {
            return;
        }

        boolean approve = status == AppealRecord.AppealStatus.APPROVED;

        if (args.length < 2) {
            reply(sender, prefixed(approve ? plugin.messages().approveUsage() : plugin.messages().denyUsage()));
            return;
        }

        int appealId;
        try {
            appealId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            reply(sender, plugin.messages().appealsInvalidId());
            return;
        }

        String response = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : (approve ? "Appeal approved" : "Appeal denied");

        reviewAppeal(sender, appealId, status, response);
    }

    private void handleGUI(CommandSender sender) {
        if (!require(sender, "banhammer.stats")) {
            return;
        }
        if (!(sender instanceof Player player)) {
            reply(sender, plugin.messages().notPlayer());
            return;
        }
        plugin.getStatisticsGUI().openMainMenu(player);
    }

    private void reviewAppeal(CommandSender staff, int appealId, AppealRecord.AppealStatus status, String response) {
        Database database = plugin.getDatabase();
        if (database == null) {
            reply(staff, plugin.messages().databaseDisabled());
            return;
        }

        String staffName = staff instanceof Player player
                ? player.getName()
                : dev.banhammer.plugin.util.Constants.CONSOLE_NAME;
        UUID staffUuid = staff instanceof Player player
                ? player.getUniqueId()
                : dev.banhammer.plugin.util.Constants.CONSOLE_UUID;

        database.getAppeal(appealId).thenCompose(appeal -> {
            if (appeal == null) {
                reply(staff, plugin.messages().appealsInvalidId());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            if (appeal.getStatus() != AppealRecord.AppealStatus.PENDING) {
                reply(staff, prefixed(plugin.messages().appealAlreadyProcessed()));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            appeal.setStatus(status);
            appeal.setReviewedBy(staffUuid);
            appeal.setReviewerName(staffName);
            appeal.setReviewResponse(response);
            appeal.setReviewedAt(Instant.now());

            return database.updateAppeal(appeal).thenAccept(claimed -> {
                if (!claimed) {
                    // Another reviewer decided this appeal between the read and the write.
                    reply(staff, prefixed(plugin.messages().appealAlreadyProcessed()));
                    return;
                }

                boolean approved = status == AppealRecord.AppealStatus.APPROVED;
                reply(staff, approved
                        ? plugin.messages().appealApproved(appealId)
                        : plugin.messages().appealDenied(appealId));

                if (approved) {
                    plugin.getPunishmentManager()
                            .unbanPlayer(staff, appeal.getPlayerName(), "Appeal approved")
                            .exceptionally(throwable -> {
                                fail(staff, "unban after appeal " + appealId, throwable);
                                return false;
                            });
                }

                notifyAppealAuthor(appeal, approved, response);

                if (plugin.getDiscord() != null) {
                    plugin.getDiscord().sendAppealReview(appeal.getPlayerName(), appealId,
                            approved ? "APPROVED" : "DENIED", staffName, response);
                }
            });
        }).exceptionally(throwable -> fail(staff, "appeal review " + appealId, throwable));
    }

    private void notifyAppealAuthor(AppealRecord appeal, boolean approved, String response) {
        FoliaScheduler.runGlobal(plugin, () -> {
            Player target = Bukkit.getPlayer(appeal.getPlayerUuid());
            if (target != null && target.isOnline()) {
                target.sendMessage(plugin.messages().appealNotification(approved ? "APPROVED" : "DENIED"));
                target.sendMessage(plugin.messages().appealResponse(response));
            }
        });
    }

    // ==================== Tab completion ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            for (Subcommand sub : Subcommand.values()) {
                if (sender.hasPermission(sub.permission)) {
                    out.add(sub.label);
                }
            }
        } else if (args.length == 2) {
            // Only offer names for subcommands the sender may actually use, so the completer
            // does not hand a player list to someone who cannot act on it.
            Subcommand sub = Subcommand.byLabel(args[0]);
            if (sub != null && sub.takesPlayerName && sender.hasPermission(sub.permission)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    out.add(player.getName());
                }
            }
        }

        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }

    // ==================== Helpers ====================

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        reply(sender, plugin.messages().noPermission());
        return false;
    }

    private boolean requireDatabase(CommandSender sender) {
        if (plugin.getPunishmentManager().isDatabaseEnabled()) {
            return true;
        }
        reply(sender, plugin.messages().databaseDisabled());
        return false;
    }

    private Void fail(CommandSender sender, String what, Throwable throwable) {
        plugin.getSLF4JLogger().error("Command failed: {}", what, throwable);
        reply(sender, plugin.messages().errorOccurred());
        return null;
    }

    private Component prefixed(Component message) {
        return plugin.messages().prefix().append(message);
    }

    private void reply(CommandSender sender, Component message) {
        // Deliver synchronously when we are already on the main thread. Always deferring to
        // the next tick loses the reply entirely for RCON: the connection is closed as soon
        // as the command returns, so a message scheduled for the following tick goes nowhere.
        if (Bukkit.isPrimaryThread()) {
            sender.sendMessage(message);
            return;
        }

        // An RCON caller is already gone by the time a database query answers, and its
        // sendMessage() then discards the text silently. Mirror it to the server log so the
        // result is at least recoverable.
        if (sender instanceof RemoteConsoleCommandSender) {
            plugin.getSLF4JLogger().info(PlainTextComponentSerializer.plainText().serialize(message));
            return;
        }

        FoliaScheduler.runGlobal(plugin, () -> sender.sendMessage(message));
    }
}
