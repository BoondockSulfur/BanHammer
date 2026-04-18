package dev.banhammer.plugin.integration;

import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import dev.banhammer.plugin.database.model.PunishmentRecord;
import dev.banhammer.plugin.database.model.PunishmentType;
import org.slf4j.Logger;

import java.time.Instant;

import static dev.banhammer.plugin.util.Constants.*;

/**
 * Discord webhook integration for punishment notifications.
 *
 * @since 3.0.0
 */
public class DiscordWebhook {

    private final Logger logger;
    private final WebhookClient client;
    private final boolean enabled;
    private final Thread shutdownHook;

    public DiscordWebhook(Logger logger, String webhookUrl, boolean enabled) {
        this.logger = logger;

        // Use temporary variables for validation logic
        WebhookClient tempClient = null;
        boolean tempEnabled = false;
        Thread tempShutdownHook = null;

        // Validate webhook URL if enabled
        if (enabled) {
            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                logger.error("Discord is enabled but webhookUrl is EMPTY!");
                logger.error("Please set discord.webhookUrl in config.yml, then run /bh reload");
                logger.error("Get a webhook URL from Discord: Server Settings -> Integrations -> Webhooks");
            } else if (!webhookUrl.startsWith("https://discord.com/api/webhooks/") &&
                       !webhookUrl.startsWith("https://discordapp.com/api/webhooks/")) {
                logger.error("Invalid Discord webhook URL format: {}", webhookUrl);
                logger.error("Expected format: https://discord.com/api/webhooks/ID/TOKEN");
                logger.error("Get a valid webhook URL from Discord: Server Settings -> Integrations -> Webhooks");
            } else {
                try {
                    tempClient = WebhookClient.withUrl(webhookUrl);
                    tempEnabled = true;

                    // Add shutdown hook to ensure cleanup even if plugin crashes
                    final WebhookClient finalClient = tempClient;
                    tempShutdownHook = new Thread(() -> {
                        try {
                            finalClient.close();
                        } catch (Exception e) {
                            // Ignore exceptions during shutdown
                        }
                    });
                    Runtime.getRuntime().addShutdownHook(tempShutdownHook);

                    logger.info("✓ Discord webhook connected successfully!");
                } catch (Exception e) {
                    logger.error("Failed to connect to Discord webhook: {}", e.getMessage());
                    logger.error("Check your webhook URL in config.yml and run /bh reload");
                }
            }
        }

        // Assign final fields once at the end
        this.client = tempClient;
        this.enabled = tempEnabled;
        this.shutdownHook = tempShutdownHook;
    }

    /**
     * Sends a punishment notification to Discord.
     *
     * @param record The punishment record
     */
    public void sendPunishment(PunishmentRecord record) {
        logger.info("sendPunishment called - enabled: {}, client: {}", enabled, (client != null ? "present" : "null"));

        if (!enabled || client == null) {
            logger.warn("Discord webhook not available - enabled: {}, client: {}", enabled, (client != null ? "present" : "null"));
            return;
        }

        logger.info("Sending Discord notification for punishment: {} -> {}", record.getStaffName(), record.getVictimName());

        try {
            WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                    .setTitle(new WebhookEmbed.EmbedTitle(getPunishmentTitle(record.getType()), null))
                    .setColor(getPunishmentColor(record.getType()))
                    .addField(new WebhookEmbed.EmbedField(true, "Player", record.getVictimName()))
                    .addField(new WebhookEmbed.EmbedField(true, "Staff", record.getStaffName()))
                    .addField(new WebhookEmbed.EmbedField(false, "Reason", record.getReason() != null ? record.getReason() : "No reason provided"))
                    .setTimestamp(Instant.now());

            if (record.getExpiresAt() != null) {
                embed.addField(new WebhookEmbed.EmbedField(true, "Duration", getDurationString(record)));
            } else {
                embed.addField(new WebhookEmbed.EmbedField(true, "Duration", "Permanent"));
            }

            if (record.getServerName() != null) {
                embed.setFooter(new WebhookEmbed.EmbedFooter("Server: " + record.getServerName(), null));
            }

            client.send(embed.build());
        } catch (Exception e) {
            logger.error("Failed to send Discord webhook", e);
        }
    }

    /**
     * Sends an unpunish notification to Discord.
     *
     * @param record The punishment record that was removed
     * @param staffName The staff member who removed it (or "Automatic")
     * @param reason The removal reason
     */
    public void sendUnpunishment(PunishmentRecord record, String staffName, String reason) {
        if (!enabled || client == null) return;

        try {
            WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                    .setTitle(new WebhookEmbed.EmbedTitle(getUnpunishmentTitle(record.getType()), null))
                    .setColor(DISCORD_COLOR_UNBAN)
                    .addField(new WebhookEmbed.EmbedField(true, "Player", record.getVictimName()))
                    .addField(new WebhookEmbed.EmbedField(true, "Removed By", staffName))
                    .addField(new WebhookEmbed.EmbedField(false, "Reason", reason != null ? reason : "No reason provided"))
                    .setTimestamp(Instant.now());

            if (record.getServerName() != null) {
                embed.setFooter(new WebhookEmbed.EmbedFooter("Server: " + record.getServerName(), null));
            }

            client.send(embed.build());
        } catch (Exception e) {
            logger.error("Failed to send Discord webhook", e);
        }
    }

    /**
     * Sends an appeal notification to Discord.
     *
     * @param playerName The player who submitted the appeal
     * @param appealId The appeal ID
     * @param appealText The appeal text (shortened if needed)
     */
    public void sendAppeal(String playerName, int appealId, String appealText) {
        if (!enabled || client == null) return;

        try {
            // Shorten text if too long
            String shortText = appealText.length() > 200
                ? appealText.substring(0, 200) + "..."
                : appealText;

            WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                    .setTitle(new WebhookEmbed.EmbedTitle("📝 New Appeal Submitted", null))
                    .setColor(0xFFA500) // Orange
                    .addField(new WebhookEmbed.EmbedField(true, "Player", playerName))
                    .addField(new WebhookEmbed.EmbedField(true, "Appeal ID", String.valueOf(appealId)))
                    .addField(new WebhookEmbed.EmbedField(false, "Appeal Text", shortText))
                    .setTimestamp(Instant.now());

            client.send(embed.build());
        } catch (Exception e) {
            logger.error("Failed to send Discord appeal notification", e);
        }
    }

    /**
     * Sends an appeal review notification to Discord.
     *
     * @param playerName The player whose appeal was reviewed
     * @param appealId The appeal ID
     * @param status Appeal status (APPROVED/DENIED)
     * @param reviewerName The staff member who reviewed it
     * @param response The review response
     */
    public void sendAppealReview(String playerName, int appealId, String status, String reviewerName, String response) {
        if (!enabled || client == null) return;

        try {
            int color = status.equals("APPROVED") ? 0x00FF00 : 0xFF0000; // Green or Red
            String emoji = status.equals("APPROVED") ? "✅" : "❌";

            WebhookEmbedBuilder embed = new WebhookEmbedBuilder()
                    .setTitle(new WebhookEmbed.EmbedTitle(emoji + " Appeal " + status, null))
                    .setColor(color)
                    .addField(new WebhookEmbed.EmbedField(true, "Player", playerName))
                    .addField(new WebhookEmbed.EmbedField(true, "Appeal ID", String.valueOf(appealId)))
                    .addField(new WebhookEmbed.EmbedField(true, "Reviewed By", reviewerName))
                    .addField(new WebhookEmbed.EmbedField(false, "Response", response != null ? response : "No response provided"))
                    .setTimestamp(Instant.now());

            client.send(embed.build());
        } catch (Exception e) {
            logger.error("Failed to send Discord appeal review notification", e);
        }
    }

    public void shutdown() {
        // Remove shutdown hook to prevent leak on reload
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM is already shutting down, ignore
            }
        }
        if (client != null) {
            client.close();
        }
    }

    private String getPunishmentTitle(PunishmentType type) {
        return switch (type) {
            case BAN, IP_BAN -> "\uD83D\uDD28 Player Banned";
            case TEMP_BAN -> "\u23F0 Player Temporarily Banned";
            case KICK -> "\uD83D\uDC62 Player Kicked";
            case MUTE, TEMP_MUTE -> "\uD83D\uDD07 Player Muted";
            case JAIL -> "\uD83D\uDD12 Player Jailed";
            case REGION_BAN -> "\uD83D\uDEAB Player Region-Banned";
            case WARNING -> "\u26A0\uFE0F Player Warned";
        };
    }

    private String getUnpunishmentTitle(PunishmentType type) {
        return switch (type) {
            case BAN, TEMP_BAN, IP_BAN -> "\u2705 Player Unbanned";
            case MUTE, TEMP_MUTE -> "\uD83D\uDD0A Player Unmuted";
            case JAIL -> "\uD83D\uDD13 Player Released";
            case REGION_BAN -> "\u2705 Region-Ban Removed";
            default -> "\u2705 Punishment Removed";
        };
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
        if (record.getExpiresAt() == null) return "Permanent";

        long seconds = java.time.Duration.between(record.getIssuedAt(), record.getExpiresAt()).getSeconds();
        long days = seconds / SECONDS_PER_DAY;
        long hours = (seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
        long minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m");

        return sb.toString().trim();
    }
}
