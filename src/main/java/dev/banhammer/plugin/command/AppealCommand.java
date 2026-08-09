package dev.banhammer.plugin.command;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.Database;
import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.util.FoliaScheduler;
import dev.banhammer.plugin.util.Settings;
import dev.banhammer.plugin.util.ValidationUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Lets a punished player contest their punishment.
 *
 * <p>Appeals may be filed against <b>any</b> active punishment - mute, jail, warning or ban.
 * Restricting them to bans, as earlier versions did, made the feature unreachable in practice:
 * a banned player cannot log in to type {@code /appeal}, and a player who can type it is by
 * definition not banned, so the command always answered "you have no active ban".
 *
 * @since 3.0.0
 */
public class AppealCommand implements CommandExecutor {

    private final BanHammerPlugin plugin;

    public AppealCommand(BanHammerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.messages().notPlayer());
            return true;
        }

        if (!sender.hasPermission("banhammer.appeal")) {
            sender.sendMessage(plugin.messages().noPermission());
            return true;
        }

        Settings.Appeals appealSettings = plugin.settings().appeals();
        if (!appealSettings.enabled()) {
            sender.sendMessage(prefixed(plugin.messages().appealsDisabled()));
            return true;
        }

        Database database = plugin.getDatabase();
        if (database == null || !plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(prefixed(plugin.messages().appealUsage()));
            return true;
        }

        String appealText = String.join(" ", args);

        int minLength = appealSettings.minLength();
        int maxLength = plugin.settings().reasonPolicy().maxLength();
        if (minLength > maxLength) {
            // Otherwise no text can ever satisfy both bounds and every appeal is rejected.
            plugin.getSLF4JLogger().warn("appeals.minLength ({}) exceeds validation.maxReasonLength ({}); "
                    + "clamping so appeals remain possible.", minLength, maxLength);
            minLength = maxLength;
        }

        ValidationUtil.ValidationResult validation =
                ValidationUtil.validateAppeal(appealText, minLength, maxLength);
        if (!validation.isValid()) {
            sender.sendMessage(prefixed(Component
                    .text(validation.getErrorMessageOrDefault("Invalid appeal"))
                    .color(NamedTextColor.RED)));
            return true;
        }

        submitAppeal(player, database, appealSettings, appealText);
        return true;
    }

    private void submitAppeal(Player player, Database database, Settings.Appeals settings, String appealText) {
        plugin.getPunishmentManager().getActivePunishments(player.getUniqueId())
                .thenCompose(punishments -> {
                    if (punishments.isEmpty()) {
                        reply(player, plugin.messages().appealNoActiveBan());
                        return CompletableFuture.<Void>completedFuture(null);
                    }

                    // Newest first, so the appeal targets the punishment the player is most
                    // likely complaining about.
                    PunishmentRecord target = punishments.stream()
                            .max(java.util.Comparator.comparing(PunishmentRecord::getIssuedAt))
                            .orElseThrow();

                    return checkCooldownAndLimit(player, database, settings, target)
                            .thenCompose(allowed -> allowed
                                    ? save(player, database, settings, target, appealText)
                                    : CompletableFuture.<Void>completedFuture(null));
                })
                .exceptionally(throwable -> {
                    plugin.getSLF4JLogger().error("Failed to submit appeal for {}", player.getName(), throwable);
                    reply(player, plugin.messages().errorOccurred());
                    return null;
                });
    }

    private CompletableFuture<Boolean> checkCooldownAndLimit(Player player, Database database,
                                                            Settings.Appeals settings, PunishmentRecord target) {
        return database.getAppealsByPlayer(player.getUniqueId()).thenCompose(appeals -> {
            if (!appeals.isEmpty() && settings.cooldownHours() > 0) {
                AppealRecord latest = appeals.get(0);
                Instant cooldownEnd = latest.getSubmittedAt().plus(Duration.ofHours(settings.cooldownHours()));
                if (Instant.now().isBefore(cooldownEnd)) {
                    long hoursLeft = Math.max(1, Duration.between(Instant.now(), cooldownEnd).toHours());
                    reply(player, plugin.messages().appealCooldown(hoursLeft));
                    return CompletableFuture.completedFuture(false);
                }
            }

            // Counted in the database rather than from the (unbounded) list above.
            return database.countAppealsForPunishment(target.getId()).thenApply(count -> {
                if (count >= settings.maxPerPunishment()) {
                    reply(player, plugin.messages().appealMaxReached());
                    return false;
                }
                return true;
            });
        });
    }

    private CompletableFuture<Void> save(Player player, Database database, Settings.Appeals settings,
                                         PunishmentRecord target, String appealText) {
        AppealRecord appeal = new AppealRecord(target.getId(), player.getUniqueId(), player.getName(), appealText);

        return database.saveAppeal(appeal).thenAccept(id -> {
            reply(player, plugin.messages().appealSubmitted());

            if (settings.notifyStaff()) {
                notifyStaff(player.getName(), id);
            }

            if (plugin.getDiscord() != null) {
                plugin.getDiscord().sendAppeal(player.getName(), id, appealText);
            }
        });
    }

    private void notifyStaff(String playerName, int appealId) {
        // Walking the online-player list belongs on the main thread; this runs in a
        // database callback.
        FoliaScheduler.runGlobal(plugin, () -> {
            Component message = plugin.messages().prefix()
                    .append(Component.text("New appeal from " + playerName + " (ID: " + appealId + ")")
                            .color(NamedTextColor.YELLOW));

            List<Player> staff = plugin.getServer().getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("banhammer.appeals"))
                    .map(Player.class::cast)
                    .toList();

            for (Player member : staff) {
                member.sendMessage(message);
            }
        });
    }

    private Component prefixed(Component message) {
        return plugin.messages().prefix().append(message);
    }

    private void reply(Player player, Component message) {
        FoliaScheduler.runGlobal(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }
}
