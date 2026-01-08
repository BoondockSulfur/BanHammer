package dev.banhammer.plugin.command;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.util.ValidationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Commands for extended punishment types: mute, jail, warn.
 *
 * @since 3.0.0
 */
public class PunishmentCommands implements CommandExecutor, TabCompleter {

    private final BanHammerPlugin plugin;

    public PunishmentCommands(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase();

        switch (cmdName) {
            case "mute" -> handleMute(sender, args);
            case "unmute" -> handleUnmute(sender, args);
            case "jail" -> handleJail(sender, args);
            case "unjail" -> handleUnjail(sender, args);
            case "warn" -> handleWarn(sender, args);
            case "setjail" -> handleSetJail(sender, args);
            default -> sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().unknownCommand()));
        }

        return true;
    }

    private void handleMute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.mute")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!(sender instanceof Player staff)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().muteUsage()));
            sender.sendMessage(plugin.messages().muteExamples());
            return;
        }

        Player victim = Bukkit.getPlayerExact(args[0]);
        if (victim == null) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().playerNotFound()));
            return;
        }

        String durationStr = args[1];
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : plugin.getConfig().getString("punishmentTypes.mute.defaultReason", "Kein Grund angegeben");

        // Validate reason
        int maxReasonLength = plugin.getConfig().getInt("validation.maxReasonLength", 500);
        boolean requireReason = plugin.getConfig().getBoolean("validation.requireReason", false);
        ValidationUtil.ValidationResult validation = ValidationUtil.validateReason(reason, 0, maxReasonLength, requireReason);

        if (!validation.isValid()) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" " + validation.getErrorMessage()))
                    .color(NamedTextColor.RED));
            return;
        }

        // Filter offensive words if enabled
        if (plugin.getConfig().getBoolean("validation.filterReasons", false)) {
            List<String> blockedWords = plugin.getConfig().getStringList("validation.blockedWords");
            reason = ValidationUtil.filterReason(reason, blockedWords);
        }

        final String finalReason = reason; // Make effectively final for lambda
        Duration duration = parseDuration(durationStr);

        plugin.getPunishmentManager().mutePlayer(staff, victim, finalReason, duration).thenAccept(id -> {
            if (id > 0) {
                String durText = duration == null ? "permanent" : formatDuration(duration);
                sender.sendMessage(plugin.messages().prefix()
                        .append(Component.text(" "))
                        .append(plugin.messages().mutedSuccess(victim.getName(), durText))
                        .color(NamedTextColor.GREEN));
                victim.sendMessage(plugin.messages().mutedMessage(durText, finalReason));
            } else if (id == -1) {
                sender.sendMessage(plugin.messages().prefix()
                        .append(Component.text(" "))
                        .append(plugin.messages().muteCancelled())
                        .color(NamedTextColor.RED));
            }
        });
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.mute")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!(sender instanceof Player staff)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().unmuteUsage()));
            return;
        }

        String playerName = args[0];
        String reason = args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "Entmutet durch Staff";

        plugin.getPunishmentManager().unmutePlayer(staff, playerName, reason).thenRun(() -> {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().unmutedSuccess(playerName))
                    .color(NamedTextColor.GREEN));
        });
    }

    private void handleJail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.jail")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!(sender instanceof Player staff)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (plugin.getJailManager().getJailLocation() == null) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().jailNotSet())
                    .color(NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().jailUsage()));
            sender.sendMessage(plugin.messages().jailExamples());
            return;
        }

        Player victim = Bukkit.getPlayerExact(args[0]);
        if (victim == null) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().playerNotFound()));
            return;
        }

        String durationStr = args[1];
        String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : plugin.getConfig().getString("punishmentTypes.jail.defaultReason", "Kein Grund angegeben");

        // Validate reason
        ValidationUtil.ValidationResult validation = ValidationUtil.validateReason(reason);
        if (!validation.isValid()) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" " + validation.getErrorMessage()))
                    .color(NamedTextColor.RED));
            return;
        }

        Duration duration = parseDuration(durationStr);

        plugin.getPunishmentManager().jailPlayer(staff, victim, reason, duration).thenAccept(id -> {
            if (id > 0) {
                String durText = duration == null ? "permanent" : formatDuration(duration);
                sender.sendMessage(plugin.messages().prefix()
                        .append(Component.text(" "))
                        .append(plugin.messages().jailedSuccess(victim.getName(), durText))
                        .color(NamedTextColor.GREEN));
            } else if (id == -1) {
                sender.sendMessage(plugin.messages().prefix()
                        .append(Component.text(" "))
                        .append(plugin.messages().jailCancelled())
                        .color(NamedTextColor.RED));
            }
        });
    }

    private void handleUnjail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.jail")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!(sender instanceof Player staff)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().unjailUsage()));
            return;
        }

        String playerName = args[0];
        Player target = plugin.getServer().getPlayer(playerName);

        if (target == null) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().playerNotOnline())
                    .color(NamedTextColor.RED));
            return;
        }

        // Release from jail (works with both Essentials and built-in)
        plugin.getJailManager().releasePlayer(target);

        sender.sendMessage(plugin.messages().prefix()
                .append(Component.text(" "))
                .append(plugin.messages().unjailedSuccess(playerName))
                .color(NamedTextColor.GREEN));

        // If database is enabled, also update punishment record
        if (plugin.getPunishmentManager().isDatabaseEnabled()) {
            String reason = args.length >= 2 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                    : "Freigelassen durch Staff";

            plugin.getPunishmentManager().unjailPlayer(staff, playerName, reason);
        }
    }

    private void handleWarn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.warn")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!(sender instanceof Player staff)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().warnUsage()));
            return;
        }

        Player victim = Bukkit.getPlayerExact(args[0]);
        if (victim == null) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" "))
                    .append(plugin.messages().playerNotFound()));
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        // Validate reason
        ValidationUtil.ValidationResult validation = ValidationUtil.validateReason(reason, 3, 500, true);
        if (!validation.isValid()) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(Component.text(" " + validation.getErrorMessage()))
                    .color(NamedTextColor.RED));
            return;
        }

        plugin.getPunishmentManager().warnPlayer(staff, victim, reason).thenAccept(id -> {
            if (id > 0) {
                sender.sendMessage(plugin.messages().prefix()
                        .append(Component.text(" "))
                        .append(plugin.messages().warnedSuccess(victim.getName()))
                        .color(NamedTextColor.YELLOW));
                victim.sendMessage(plugin.messages().warnedMessage(reason));

                // Get warning count
                plugin.getPunishmentManager().getHistory(victim.getUniqueId(), 1000).thenAccept(history -> {
                    long warnings = history.stream()
                            .filter(r -> r.getType() == dev.banhammer.plugin.database.model.PunishmentType.WARNING)
                            .count();

                    int threshold = plugin.getConfig().getInt("punishmentTypes.warnings.autoBanThreshold", 3);
                    victim.sendMessage(plugin.messages().warnCount((int) warnings, threshold));
                });
            }
        });
    }

    private void handleSetJail(CommandSender sender, String[] args) {
        if (!sender.hasPermission("banhammer.setjail")) {
            sender.sendMessage(plugin.messages().noPermission());
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return;
        }

        plugin.getJailManager().setJailLocation(player.getLocation());
        sender.sendMessage(plugin.messages().prefix()
                .append(Component.text(" "))
                .append(plugin.messages().jailLocationSet())
                .color(NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            // Suggest online player names
            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(player.getName());
            }
        } else if (args.length == 2 && (command.getName().equalsIgnoreCase("mute") ||
                command.getName().equalsIgnoreCase("jail"))) {
            // Suggest durations
            suggestions.add("permanent");
            suggestions.add("1h");
            suggestions.add("30m");
            suggestions.add("1d");
            suggestions.add("7d");
        }

        return suggestions;
    }

    // Helper methods

    private Duration parseDuration(String durationStr) {
        if (durationStr == null || durationStr.equalsIgnoreCase("permanent") || durationStr.equalsIgnoreCase("perm")) {
            return null;
        }

        String s = durationStr.trim().toLowerCase();

        // ISO-8601 format
        if (s.startsWith("p")) {
            try {
                return Duration.parse(s);
            } catch (Exception e) {
                return null;
            }
        }

        // Custom format
        long days = extractTime(s, "d");
        long hours = extractTime(s, "h");
        long minutes = extractTime(s, "m");
        long seconds = extractTime(s, "s");

        Duration duration = Duration.ZERO;
        if (days > 0) duration = duration.plusDays(days);
        if (hours > 0) duration = duration.plusHours(hours);
        if (minutes > 0) duration = duration.plusMinutes(minutes);
        if (seconds > 0) duration = duration.plusSeconds(seconds);

        return duration.isZero() ? null : duration;
    }

    private long extractTime(String str, String unit) {
        int index = str.indexOf(unit);
        if (index <= 0) return 0;

        int start = index - 1;
        while (start >= 0 && Character.isDigit(str.charAt(start))) {
            start--;
        }

        try {
            return Long.parseLong(str.substring(start + 1, index));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String formatDuration(Duration duration) {
        if (duration == null) return "permanent";

        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m");

        return sb.toString().trim();
    }
}
