package dev.banhammer.plugin.command;

import dev.banhammer.plugin.BanHammerPlugin;
import dev.banhammer.plugin.database.model.AppealRecord;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.util.ValidationUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Command handler for /appeal
 * Allows players to submit appeals for their bans.
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

        if (!plugin.getConfig().getBoolean("appeals.enabled", true)) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(net.kyori.adventure.text.Component.text(" "))
                    .append(plugin.messages().appealsDisabled()));
            return true;
        }

        if (!plugin.getPunishmentManager().isDatabaseEnabled()) {
            sender.sendMessage(plugin.messages().databaseDisabled());
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.messages().prefix()
                    .append(net.kyori.adventure.text.Component.text(" "))
                    .append(plugin.messages().appealUsage()));
            return true;
        }

        String appealText = String.join(" ", args);

        // Validate appeal text
        int minLength = plugin.getConfig().getInt("appeals.minLength", 20);
        int maxLength = plugin.getConfig().getInt("validation.maxReasonLength", 500);
        ValidationUtil.ValidationResult validation = ValidationUtil.validateAppeal(appealText, minLength, maxLength);

        if (!validation.isValid()) {
            sender.sendMessage(plugin.messages().prefix().append(
                net.kyori.adventure.text.Component.text(" " + validation.getErrorMessage())
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED)
            ));
            return true;
        }

        // Check for active ban
        plugin.getPunishmentManager().getActivePunishments(player.getUniqueId()).thenAccept(punishments -> {
            List<PunishmentRecord> activeBans = punishments.stream()
                    .filter(p -> p.getType() == PunishmentType.BAN ||
                                 p.getType() == PunishmentType.TEMP_BAN ||
                                 p.getType() == PunishmentType.IP_BAN)
                    .toList();

            if (activeBans.isEmpty()) {
                player.sendMessage(plugin.messages().appealNoActiveBan());
                return;
            }

            PunishmentRecord ban = activeBans.get(0);

            // Check for cooldown
            plugin.getDatabase().getAppealsByPlayer(player.getUniqueId()).thenAccept(appeals -> {
                long cooldownHours = plugin.getConfig().getLong("appeals.cooldown", 24);

                if (!appeals.isEmpty()) {
                    AppealRecord lastAppeal = appeals.get(0);
                    Instant cooldownEnd = lastAppeal.getSubmittedAt().plus(Duration.ofHours(cooldownHours));

                    if (Instant.now().isBefore(cooldownEnd)) {
                        long hoursLeft = Duration.between(Instant.now(), cooldownEnd).toHours();
                        player.sendMessage(plugin.messages().appealCooldown(hoursLeft));
                        return;
                    }
                }

                // Check max appeals per punishment
                int maxAppeals = plugin.getConfig().getInt("appeals.maxAppealsPerPunishment", 3);
                long appealsForThisBan = appeals.stream()
                        .filter(a -> a.getPunishmentId() == ban.getId())
                        .count();

                if (appealsForThisBan >= maxAppeals) {
                    player.sendMessage(plugin.messages().appealMaxReached());
                    return;
                }

                // Create and save appeal
                AppealRecord appeal = new AppealRecord(
                        ban.getId(),
                        player.getUniqueId(),
                        player.getName(),
                        appealText
                );

                plugin.getDatabase().saveAppeal(appeal).thenAccept(id -> {
                    player.sendMessage(plugin.messages().appealSubmitted());

                    // Notify staff if configured
                    if (plugin.getConfig().getBoolean("appeals.notifyStaff", true)) {
                        plugin.getServer().getOnlinePlayers().stream()
                                .filter(p -> p.hasPermission("banhammer.appeals"))
                                .forEach(staff -> {
                                    staff.sendMessage(plugin.messages().prefix().append(
                                        net.kyori.adventure.text.Component.text(
                                            " Neuer Appeal von " + player.getName() + " (ID: " + id + ")"
                                        )
                                    ));
                                });
                    }

                    // Discord notification
                    if (plugin.getDiscord() != null && plugin.getConfig().getBoolean("discord.notifications.appeals", true)) {
                        plugin.getDiscord().sendAppeal(player.getName(), id, appealText);
                    }
                }).exceptionally(ex -> {
                    plugin.getSLF4JLogger().error("Failed to save appeal", ex);
                    player.sendMessage(plugin.messages().errorOccurred());
                    return null;
                });
            }).exceptionally(ex -> {
                plugin.getSLF4JLogger().error("Failed to retrieve appeals", ex);
                player.sendMessage(plugin.messages().errorOccurred());
                return null;
            });
        }).exceptionally(ex -> {
            plugin.getSLF4JLogger().error("Failed to retrieve active punishments", ex);
            player.sendMessage(plugin.messages().errorOccurred());
            return null;
        });

        return true;
    }
}
