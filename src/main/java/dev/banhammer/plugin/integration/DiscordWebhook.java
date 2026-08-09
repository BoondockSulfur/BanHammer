package dev.banhammer.plugin.integration;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import dev.banhammer.plugin.util.DurationParser;
import dev.banhammer.plugin.util.Messages;
import dev.banhammer.plugin.util.Settings;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import static dev.banhammer.plugin.util.Constants.DISCORD_COLOR_BAN;
import static dev.banhammer.plugin.util.Constants.DISCORD_COLOR_KICK;
import static dev.banhammer.plugin.util.Constants.DISCORD_COLOR_MUTE;
import static dev.banhammer.plugin.util.Constants.DISCORD_COLOR_UNBAN;
import static dev.banhammer.plugin.util.Constants.DISCORD_FIELD_LIMIT;

/**
 * Discord webhook integration for punishment notifications.
 *
 * <p>The library sends asynchronously on its own HTTP client with built-in rate-limit
 * handling, so nothing here blocks the server thread.
 *
 * @since 3.0.0
 */
public class DiscordWebhook {

    /** Accepts discord.com and its canary/ptb/discordapp aliases. */
    private static final Pattern WEBHOOK_URL = Pattern.compile(
            "^https://(canary\\.|ptb\\.)?discord(app)?\\.com/api(/v\\d+)?/webhooks/\\d+/[\\w-]+$");

    /** Characters Discord treats as formatting inside embed fields. */
    private static final Pattern MARKDOWN = Pattern.compile("([\\\\*_~`|>\\[\\]()])");

    private final Logger logger;
    private final Messages messages;
    private final Settings.DiscordSettings settings;
    private final WebhookClient client;
    private final boolean enabled;
    private final Thread shutdownHook;

    public DiscordWebhook(Logger logger, Messages messages, Settings.DiscordSettings settings) {
        this.logger = logger;
        this.messages = messages;
        this.settings = settings;

        WebhookClient tempClient = null;
        boolean tempEnabled = false;
        Thread tempShutdownHook = null;

        String webhookUrl = settings.webhookUrl();

        if (settings.enabled()) {
            if (webhookUrl == null || webhookUrl.isBlank()) {
                logger.error("Discord is enabled but discord.webhookUrl is empty.");
                logger.error("Set it in config.yml (Server Settings -> Integrations -> Webhooks), then run /bh reload");
            } else if (!WEBHOOK_URL.matcher(webhookUrl.trim()).matches()) {
                // Deliberately does NOT log the URL: it carries the webhook token, and a
                // rejected-but-real URL (for example a canary link) would leak posting rights
                // for the staff channel into latest.log and every pasted log excerpt.
                logger.error("Invalid Discord webhook URL format (expected "
                        + "https://discord.com/api/webhooks/ID/TOKEN). The URL is not logged for safety.");
            } else {
                try {
                    tempClient = WebhookClient.withUrl(webhookUrl.trim());
                    tempEnabled = true;

                    final WebhookClient finalClient = tempClient;
                    tempShutdownHook = new Thread(() -> {
                        try {
                            finalClient.close();
                        } catch (Exception ignored) {
                            // Nothing useful to do while the JVM is going down.
                        }
                    }, "BanHammer-Discord-Shutdown");
                    Runtime.getRuntime().addShutdownHook(tempShutdownHook);

                    logger.info("Discord webhook connected successfully");
                } catch (Exception e) {
                    logger.error("Failed to connect to the Discord webhook: {}", e.getMessage());
                }
            }
        }

        this.client = tempClient;
        this.enabled = tempEnabled;
        this.shutdownHook = tempShutdownHook;
    }

    /**
     * Sends a punishment notification to Discord.
     */
    public void sendPunishment(PunishmentRecord record) {
        if (!isUsable()) {
            return;
        }

        WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                .setTitle(new WebhookEmbed.EmbedTitle(getPunishmentTitle(record.getType()), null))
                .setColor(getPunishmentColor(record.getType()))
                .addField(field(true, text("field.player", "Player"), record.getVictimName()))
                .addField(field(false, text("field.reason", "Reason"), record.getReason()))
                .addField(field(true, text("field.duration", "Duration"), getDurationString(record)))
                .setTimestamp(Instant.now());

        if (settings.showStaffName()) {
            embed.addField(field(true, text("field.staff", "Staff"), record.getStaffName()));
        }
        if (settings.showServerName() && record.getServerName() != null) {
            embed.setFooter(new WebhookEmbed.EmbedFooter(text("field.server", "Server") + ": " + record.getServerName(), null));
        }

        send(embed, "punishment notification");
    }

    /**
     * Sends an unpunish notification to Discord.
     *
     * @param record    the punishment record that was removed
     * @param staffName the staff member who removed it, or "Automatic"
     * @param reason    the removal reason
     */
    public void sendUnpunishment(PunishmentRecord record, String staffName, String reason) {
        if (!isUsable()) {
            return;
        }

        WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                .setTitle(new WebhookEmbed.EmbedTitle(getUnpunishmentTitle(record.getType()), null))
                .setColor(DISCORD_COLOR_UNBAN)
                .addField(field(true, text("field.player", "Player"), record.getVictimName()))
                .addField(field(false, text("field.reason", "Reason"), reason))
                .setTimestamp(Instant.now());

        if (settings.showStaffName()) {
            embed.addField(field(true, text("field.removedBy", "Removed By"), staffName));
        }
        if (settings.showServerName() && record.getServerName() != null) {
            embed.setFooter(new WebhookEmbed.EmbedFooter(text("field.server", "Server") + ": " + record.getServerName(), null));
        }

        send(embed, "unpunishment notification");
    }

    /**
     * Sends an appeal notification to Discord.
     */
    public void sendAppeal(String playerName, int appealId, String appealText) {
        if (!isUsable() || !settings.appeals()) {
            return;
        }

        WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                .setTitle(new WebhookEmbed.EmbedTitle(text("title.newAppeal", "New Appeal Submitted"), null))
                .setColor(DISCORD_COLOR_KICK)
                .addField(field(true, text("field.player", "Player"), playerName))
                .addField(field(true, text("field.appealId", "Appeal ID"), String.valueOf(appealId)))
                .addField(field(false, text("field.appealText", "Appeal Text"), appealText))
                .setTimestamp(Instant.now());

        send(embed, "appeal notification");
    }

    /**
     * Sends an appeal review notification to Discord.
     *
     * @param status appeal status (APPROVED/DENIED)
     */
    public void sendAppealReview(String playerName, int appealId, String status, String reviewerName, String response) {
        if (!isUsable() || !settings.appeals()) {
            return;
        }

        boolean approved = "APPROVED".equals(status);

        WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                .setTitle(new WebhookEmbed.EmbedTitle(text(approved ? "title.appealApproved" : "title.appealDenied", approved ? "Appeal approved" : "Appeal denied"), null))
                .setColor(approved ? DISCORD_COLOR_UNBAN : DISCORD_COLOR_BAN)
                .addField(field(true, text("field.player", "Player"), playerName))
                .addField(field(true, text("field.appealId", "Appeal ID"), String.valueOf(appealId)))
                .addField(field(true, text("field.reviewedBy", "Reviewed By"), reviewerName))
                .addField(field(false, text("field.response", "Response"), response))
                .setTimestamp(Instant.now());

        send(embed, "appeal review notification");
    }

    public void shutdown() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // The JVM is already shutting down; the hook is running or has run.
            }
        }
        if (client != null) {
            client.close();
        }
    }

    // ==================== Internals ====================

    private boolean isUsable() {
        return enabled && client != null;
    }

    /**
     * Dispatches the embed and reports delivery failures.
     *
     * <p>{@code send} is asynchronous, so a deleted webhook (404) or a rejected payload (400)
     * surfaces only in the returned future - without this handler those failures vanished
     * entirely and the staff channel just stayed quiet.
     */
    private void send(WebhookEmbedBuilder embed, String description) {
        try {
            client.send(embed.build()).exceptionally(throwable -> {
                logger.warn("Failed to deliver Discord {}: {}", description, throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            logger.error("Failed to send Discord {}", description, e);
        }
    }

    /**
     * Builds an embed field with user content neutralised.
     *
     * <p>Discord renders Markdown inside embed fields, so an unescaped appeal text could
     * smuggle a masked link such as {@code [proof](https://evil.example)} into the staff
     * channel. Values are also truncated to Discord's field limit, which otherwise makes the
     * whole request fail with HTTP 400.
     */
    private static WebhookEmbed.EmbedField field(boolean inline, String name, String value) {
        String safe = value == null || value.isBlank() ? "-" : MARKDOWN.matcher(value).replaceAll("\\\\$1");
        if (safe.length() > DISCORD_FIELD_LIMIT) {
            safe = safe.substring(0, DISCORD_FIELD_LIMIT - 3) + "...";
        }
        return new WebhookEmbed.EmbedField(inline, name, safe);
    }

    private String getPunishmentTitle(PunishmentType type) {
        return switch (type) {
            case BAN, IP_BAN -> text("title.banned", "Player Banned");
            case TEMP_BAN -> text("title.tempBanned", "Player Temporarily Banned");
            case KICK -> text("title.kicked", "Player Kicked");
            case MUTE, TEMP_MUTE -> text("title.muted", "Player Muted");
            case JAIL -> text("title.jailed", "Player Jailed");
            case REGION_BAN -> text("title.regionBanned", "Player Region-Banned");
            case WARNING -> text("title.warned", "Player Warned");
        };
    }

    private String getUnpunishmentTitle(PunishmentType type) {
        return switch (type) {
            case BAN, TEMP_BAN, IP_BAN -> text("title.unbanned", "Player Unbanned");
            case MUTE, TEMP_MUTE -> text("title.unmuted", "Player Unmuted");
            case JAIL -> text("title.released", "Player Released");
            case REGION_BAN -> text("title.regionBanRemoved", "Region-Ban Removed");
            default -> text("title.punishmentRemoved", "Punishment Removed");
        };
    }

    /** Looks up a Discord-facing label; plain text, because embeds do not parse MiniMessage. */
    private String text(String key, String def) {
        return messages == null ? def : messages.discordText(key, def);
    }

    private int getPunishmentColor(PunishmentType type) {
        return switch (type) {
            case BAN, TEMP_BAN, IP_BAN -> DISCORD_COLOR_BAN;
            case KICK -> DISCORD_COLOR_KICK;
            case MUTE, TEMP_MUTE -> DISCORD_COLOR_MUTE;
            default -> DISCORD_COLOR_BAN;
        };
    }

    private String getDurationString(PunishmentRecord record) {
        if (record.getExpiresAt() == null || record.getIssuedAt() == null) {
            return "Permanent";
        }
        return DurationParser.formatHuman(Duration.between(record.getIssuedAt(), record.getExpiresAt()));
    }
}
