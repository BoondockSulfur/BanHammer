package dev.banhammer.plugin.command;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.manager.PunishmentManager.PunishmentResult;
import dev.banhammer.plugin.util.DurationParser;
import dev.banhammer.plugin.util.FoliaScheduler;
import dev.banhammer.plugin.util.ValidationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Commands for the extended punishment types: mute, jail, warn.
 *
 * <p>All of these work from the console and RCON as well; only {@code /setjail} needs a player,
 * because it takes the location from them.
 *
 * @since 3.0.0
 */
public class PunishmentCommands implements CommandExecutor, TabCompleter {

    private static final List<String> DURATION_SUGGESTIONS =
            List.of("permanent", "30m", "1h", "6h", "1d", "7d", "30d");

    private final BanHammerPlugin plugin;

    public PunishmentCommands(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "mute" -> handleMute(sender, args);
            case "unmute" -> handleUnmute(sender, args);
            case "jail" -> handleJail(sender, args);
            case "unjail" -> handleUnjail(sender, args);
            case "warn" -> handleWarn(sender, args);
            case "setjail" -> handleSetJail(sender);
            default -> reply(sender, plugin.messages().unknownCommand());
        }
        return true;
    }

    // ==================== Mute ====================

    private void handleMute(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.mute") || !requireDatabase(sender)) {
            return;
        }

        if (!plugin.settings().mute().enabled()) {
            reply(sender, error("The mute system is disabled in config.yml"));
            return;
        }

        if (args.length < 2) {
            reply(sender, prefixed(plugin.messages().muteUsage()));
            reply(sender, plugin.messages().muteExamples());
            return;
        }

        Player victim = Bukkit.getPlayerExact(args[0]);
        if (victim == null) {
            reply(sender, prefixed(plugin.messages().playerNotFound()));
            return;
        }

        Duration duration = parseDurationOrComplain(sender, args[1]);
        if (duration == INVALID) {
            return;
        }

        String reason = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : plugin.settings().mute().defaultReason();

        String checked = checkReason(sender, reason);
        if (checked == null) {
            return;
        }

        final String finalReason = checked;
        final String durationText = DurationParser.formatHuman(duration);

        plugin.getPunishmentManager().mutePlayer(sender, victim, finalReason, duration)
                .thenAccept(result -> FoliaScheduler.runGlobal(plugin, () -> {
                    if (report(sender, result, victim.getName(), plugin.messages().errorOccurred())) {
                        reply(sender, prefixed(plugin.messages().mutedSuccess(victim.getName(), durationText)));
                        if (victim.isOnline()) {
                            victim.sendMessage(plugin.messages().mutedMessage(durationText, finalReason));
                        }
                    }
                }))
                .exceptionally(throwable -> fail(sender, "mute " + victim.getName(), throwable));
    }

    private void handleUnmute(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.mute")) {
            return;
        }

        if (args.length < 1) {
            reply(sender, prefixed(plugin.messages().unmuteUsage()));
            return;
        }

        String playerName = args[0];
        String reason = args.length >= 2
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "Unmuted by staff";

        plugin.getPunishmentManager().unmutePlayer(sender, playerName, reason)
                .thenAccept(removed -> reply(sender, removed
                        ? prefixed(plugin.messages().unmutedSuccess(playerName))
                        : prefixed(plugin.messages().notMuted(playerName))))
                .exceptionally(throwable -> fail(sender, "unmute " + playerName, throwable));
    }

    // ==================== Jail ====================

    private void handleJail(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.jail") || !requireDatabase(sender)) {
            return;
        }

        if (!plugin.settings().jail().enabled()) {
            reply(sender, error("The jail system is disabled in config.yml"));
            return;
        }

        // Usage is checked before the jail-location check, so a bare "/jail" explains the
        // syntax rather than complaining about an unrelated setup problem.
        if (args.length < 2) {
            reply(sender, prefixed(plugin.messages().jailUsage()));
            reply(sender, plugin.messages().jailExamples());
            return;
        }

        boolean essentials = plugin.getJailManager().isEssentialsAvailable();
        if (!essentials && plugin.getJailManager().getJailLocation() == null) {
            reply(sender, prefixed(plugin.messages().jailNotSet()));
            return;
        }

        Player victim = Bukkit.getPlayerExact(args[0]);
        if (victim == null) {
            reply(sender, prefixed(plugin.messages().playerNotFound()));
            return;
        }

        Duration duration = parseDurationOrComplain(sender, args[1]);
        if (duration == INVALID) {
            return;
        }

        // With Essentials the third argument is the cell; without it the reason starts there.
        String cellName = null;
        int reasonStart = 2;
        if (essentials && args.length >= 3) {
            String requested = args[2];
            var cells = plugin.getJailManager().getEssentialsJailNames();
            if (cells.stream().noneMatch(c -> c.equalsIgnoreCase(requested))) {
                reply(sender, error("Essentials jail '" + requested + "' does not exist. Available: "
                        + (cells.isEmpty() ? "(none)" : String.join(", ", cells))));
                return;
            }
            cellName = requested;
            reasonStart = 3;
        }

        String reason = args.length > reasonStart
                ? String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length))
                : plugin.settings().jail().defaultReason();

        String checked = checkReason(sender, reason);
        if (checked == null) {
            return;
        }

        final String durationText = DurationParser.formatHuman(duration);

        plugin.getPunishmentManager().jailPlayer(sender, victim, checked, duration, cellName)
                .thenAccept(result -> FoliaScheduler.runGlobal(plugin, () -> {
                    if (report(sender, result, victim.getName(), plugin.messages().jailFailed())) {
                        reply(sender, prefixed(plugin.messages().jailedSuccess(victim.getName(), durationText)));
                    }
                }))
                .exceptionally(throwable -> fail(sender, "jail " + victim.getName(), throwable));
    }

    private void handleUnjail(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.jail")) {
            return;
        }

        if (args.length < 1) {
            reply(sender, prefixed(plugin.messages().unjailUsage()));
            return;
        }

        String playerName = args[0];
        Player target = Bukkit.getPlayerExact(playerName);
        boolean databaseEnabled = plugin.getPunishmentManager().isDatabaseEnabled();

        if (target == null && !databaseEnabled) {
            // Without a database an offline player's jail exists only in memory, keyed by a
            // UUID we can no longer resolve reliably.
            reply(sender, prefixed(plugin.messages().playerNotOnline()));
            return;
        }

        if (target != null) {
            FoliaScheduler.runOnEntity(plugin, target, () -> plugin.getJailManager().releasePlayer(target));
        } else {
            UUID uuid = plugin.getPunishmentManager().resolvePlayerUuid(playerName);
            if (uuid == null) {
                reply(sender, prefixed(plugin.messages().playerNotFound()));
                return;
            }
            plugin.getJailManager().releasePlayerByUUID(uuid);
        }

        String reason = args.length >= 2
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "Released by staff";

        if (!databaseEnabled) {
            reply(sender, prefixed(plugin.messages().unjailedSuccess(playerName)));
            return;
        }

        plugin.getPunishmentManager().unjailPlayer(sender, playerName, reason)
                .thenAccept(removed -> reply(sender, removed
                        ? prefixed(plugin.messages().unjailedSuccess(playerName))
                        : prefixed(plugin.messages().notJailed(playerName))))
                .exceptionally(throwable -> fail(sender, "unjail " + playerName, throwable));
    }

    // ==================== Warn ====================

    private void handleWarn(CommandSender sender, String[] args) {
        if (!require(sender, "banhammer.warn") || !requireDatabase(sender)) {
            return;
        }

        if (!plugin.settings().warnings().enabled()) {
            reply(sender, error("The warning system is disabled in config.yml"));
            return;
        }

        if (args.length < 2) {
            reply(sender, prefixed(plugin.messages().warnUsage()));
            return;
        }

        Player victim = Bukkit.getPlayerExact(args[0]);
        if (victim == null) {
            reply(sender, prefixed(plugin.messages().playerNotFound()));
            return;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String checked = checkReason(sender, reason);
        if (checked == null) {
            return;
        }

        final String finalReason = checked;
        int threshold = plugin.settings().warnings().autoBanThreshold();

        plugin.getPunishmentManager().warnPlayer(sender, victim, finalReason)
                .thenAccept(result -> {
                    if (!result.isSuccess()) {
                        FoliaScheduler.runGlobal(plugin, () ->
                                report(sender, result, victim.getName(), plugin.messages().errorOccurred()));
                        return;
                    }

                    // A dedicated COUNT query rather than pulling the whole history and
                    // filtering it in memory, which also disagreed with the auto-ban counter.
                    plugin.getPunishmentManager().getWarningCount(victim.getUniqueId())
                            .thenAccept(count -> FoliaScheduler.runGlobal(plugin, () -> {
                                reply(sender, prefixed(plugin.messages().warnedSuccess(victim.getName())));
                                if (victim.isOnline()) {
                                    victim.sendMessage(plugin.messages().warnedMessage(finalReason));
                                    victim.sendMessage(plugin.messages().warnCount(count, threshold));
                                }
                            }));
                })
                .exceptionally(throwable -> fail(sender, "warn " + victim.getName(), throwable));
    }

    // ==================== Setjail ====================

    private void handleSetJail(CommandSender sender) {
        if (!require(sender, "banhammer.setjail")) {
            return;
        }

        if (!(sender instanceof Player player)) {
            reply(sender, plugin.messages().notPlayer());
            return;
        }

        plugin.getJailManager().setJailLocation(player.getLocation());
        reply(sender, prefixed(plugin.messages().jailLocationSet()));
    }

    // ==================== Tab completion ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("setjail")) {
            return List.of();
        }

        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                suggestions.add(player.getName());
            }
        } else if (args.length == 2 && (name.equals("mute") || name.equals("jail"))) {
            suggestions.addAll(DURATION_SUGGESTIONS);
        } else if (args.length == 3 && name.equals("jail") && plugin.getJailManager().isEssentialsAvailable()) {
            suggestions.addAll(plugin.getJailManager().getEssentialsJailNames());
        }

        // Filter by what has been typed so far; sending the full list on every keystroke is
        // wasteful with a few hundred players online.
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .toList();
    }

    // ==================== Helpers ====================

    /** Sentinel telling the caller that parsing failed and the user was already told. */
    private static final Duration INVALID = Duration.ofSeconds(Long.MIN_VALUE);

    /**
     * Parses a duration argument.
     *
     * @return the duration, {@code null} for permanent, or {@link #INVALID} if unparseable
     */
    private Duration parseDurationOrComplain(CommandSender sender, String input) {
        DurationParser.Result result = DurationParser.parse(input);
        if (result.isInvalid()) {
            // An unreadable duration used to be treated as "permanent", so a typo like
            // "/mute Steve 1w" silently issued a permanent mute.
            reply(sender, prefixed(plugin.messages().invalidDuration()));
            return INVALID;
        }
        return result.orNullForPermanent();
    }

    /**
     * Validates and filters a reason against the configured policy.
     *
     * @return the (possibly filtered) reason, or {@code null} if it was rejected
     */
    private String checkReason(CommandSender sender, String reason) {
        ValidationUtil.ReasonPolicy policy = plugin.settings().reasonPolicy();
        ValidationUtil.ValidationResult validation = policy.validate(reason);
        if (!validation.isValid()) {
            reply(sender, error(validation.getErrorMessageOrDefault("Invalid reason")));
            return null;
        }
        return policy.filter(reason);
    }

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

    /**
     * Reports non-success outcomes.
     *
     * @param onFailure message for {@link PunishmentResult.Status#FAILED}; the reason a
     *                  punishment could not be applied is type-specific
     * @return true if the punishment succeeded and the caller should print its own message
     */
    private boolean report(CommandSender sender, PunishmentResult result, String victimName,
                           Component onFailure) {
        switch (result.status()) {
            case SUCCESS -> {
                return true;
            }
            case CANCELLED -> reply(sender, prefixed(plugin.messages().muteCancelled()));
            case NOT_PERMITTED -> reply(sender, prefixed(plugin.messages().cannotBan()));
            case FAILED -> reply(sender, prefixed(onFailure));
        }
        plugin.getSLF4JLogger().debug("Punishment of {} ended as {}", victimName, result.status());
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

    private Component error(String text) {
        return prefixed(Component.text(text).color(NamedTextColor.RED));
    }

    /**
     * Sends a message, hopping to the main thread because replies are often produced in a
     * database callback.
     */
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
