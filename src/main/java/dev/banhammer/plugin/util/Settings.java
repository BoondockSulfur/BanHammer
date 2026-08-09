package dev.banhammer.plugin.util;

import dev.banhammer.plugin.BanHammerPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed, cached view of {@code config.yml}.
 *
 * <p>Every value is read once per (re)load into an immutable {@link Snapshot} which is then
 * published through a single {@code volatile} field. Readers - including the async unban
 * scheduler and the jail cleanup task - therefore always see a complete, self-consistent
 * configuration, instead of touching Bukkit's {@code FileConfiguration} (an unsynchronized
 * map that {@code /bh reload} replaces underneath them).
 */
public final class Settings {

    private final BanHammerPlugin plugin;
    private volatile Snapshot snapshot;

    public Settings(BanHammerPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Re-reads all values from the plugin's current {@link FileConfiguration}.
     *
     * <p>Callers are responsible for calling {@link BanHammerPlugin#reloadConfig()} first;
     * this method deliberately does not do it itself so a reload does not read the file twice.
     */
    public void reload() {
        FileConfiguration c = plugin.getConfig();

        snapshot = new Snapshot(
                new Item(
                        c.getString("item.material", "CARROT_ON_A_STICK"),
                        c.getString("item.name", "<gold>Ban Hammer</gold>"),
                        List.copyOf(c.getStringList("item.lore")),
                        c.getInt("item.customModelData", 0),
                        c.getBoolean("item.unbreakable", true),
                        c.getBoolean("item.hideFlags", true),
                        c.getBoolean("item.giveOnJoin", false)),
                new Ban(c.getBoolean("ban.broadcast", true)),
                Math.max(0, c.getInt("cooldownSeconds", 3)),
                Math.max(0, c.getInt("presetSwitchCooldown", 250)),
                new Effects(
                        c.getBoolean("effects.lightning", true),
                        c.getBoolean("effects.sound", true),
                        c.getBoolean("effects.particles", true),
                        c.getBoolean("effects.knockback.enabled", false),
                        c.getDouble("effects.knockback.horizontal", 0.8),
                        c.getDouble("effects.knockback.vertical", 0.35)),
                new IpBan(
                        c.getBoolean("ipBan.enabled", false),
                        c.getBoolean("ipBan.autoIpBan", false),
                        c.getBoolean("ipBan.trackIps", true)),
                new Privacy(
                        IPAnonymizer.levelFromConfig(c.getString("privacy.ipAnonymization", "PARTIAL")),
                        c.getString("privacy.ipHashSalt", ""),
                        c.getBoolean("privacy.dataRetention.enabled", false),
                        Math.max(1, c.getInt("privacy.dataRetention.deleteAfterDays", 365)),
                        c.getBoolean("privacy.dataRetention.keepActivePunishments", true)),
                new TempBans(
                        c.getBoolean("tempBans.enabled", true),
                        Math.max(5, c.getInt("tempBans.checkInterval", 60)),
                        c.getBoolean("tempBans.notifyOnExpire", true)),
                new DiscordSettings(
                        c.getBoolean("discord.enabled", false),
                        c.getString("discord.webhookUrl", ""),
                        c.getBoolean("discord.notifications.bans", true),
                        c.getBoolean("discord.notifications.kicks", true),
                        c.getBoolean("discord.notifications.unbans", true),
                        c.getBoolean("discord.notifications.mutes", true),
                        c.getBoolean("discord.notifications.jails", true),
                        c.getBoolean("discord.notifications.warnings", true),
                        c.getBoolean("discord.notifications.appeals", true),
                        c.getBoolean("discord.showStaffName", true),
                        c.getBoolean("discord.showServerName", true)),
                new ValidationUtil.ReasonPolicy(
                        0,
                        Math.max(1, c.getInt("validation.maxReasonLength", Constants.MAX_REASON_LENGTH)),
                        c.getBoolean("validation.requireReason", false),
                        c.getBoolean("validation.filterReasons", false),
                        c.getStringList("validation.blockedWords")),
                new Logging(
                        c.getBoolean("logging.separateFile", false),
                        c.getString("logging.fileName", "banhammer-punishments.log"),
                        c.getBoolean("logging.logBans", true),
                        c.getBoolean("logging.logKicks", true),
                        c.getBoolean("logging.logUnbans", true),
                        c.getBoolean("logging.logMutes", true),
                        c.getBoolean("logging.logJails", true),
                        c.getBoolean("logging.logWarnings", true)),
                new Appeals(
                        c.getBoolean("appeals.enabled", true),
                        Math.max(1, c.getInt("appeals.minLength", 20)),
                        Math.max(0, c.getLong("appeals.cooldown", 24)),
                        Math.max(1, c.getInt("appeals.maxAppealsPerPunishment", 3)),
                        c.getBoolean("appeals.notifyStaff", true)),
                new Mute(
                        c.getBoolean("punishmentTypes.mute.enabled", true),
                        c.getString("punishmentTypes.mute.defaultReason", "Spam/Insult"),
                        c.getBoolean("punishmentTypes.mute.preventChat", true),
                        c.getBoolean("punishmentTypes.mute.preventCommands", true),
                        c.getBoolean("punishmentTypes.mute.preventSigns", true),
                        c.getBoolean("punishmentTypes.mute.preventBooks", true),
                        lowerCased(c.getStringList("punishmentTypes.mute.blockedCommands"))),
                new Jail(
                        c.getBoolean("punishmentTypes.jail.enabled", true),
                        c.getBoolean("punishmentTypes.jail.useEssentials", true),
                        c.getString("punishmentTypes.jail.essentialsDefaultJail", "1"),
                        c.getString("punishmentTypes.jail.defaultReason", "Rule violation"),
                        c.getBoolean("punishmentTypes.jail.preventMovement", true),
                        c.getBoolean("punishmentTypes.jail.preventTeleport", true),
                        c.getBoolean("punishmentTypes.jail.preventDamage", true),
                        c.getBoolean("punishmentTypes.jail.preventCommands", false),
                        c.getDouble("punishmentTypes.jail.maxDistance", 10.0)),
                new Warnings(
                        c.getBoolean("punishmentTypes.warnings.enabled", true),
                        Math.max(1, c.getInt("punishmentTypes.warnings.autoBanThreshold", 3)),
                        c.getString("punishmentTypes.warnings.autoBanDuration", "7d"),
                        Math.max(0, c.getInt("punishmentTypes.warnings.expireAfterDays", 90))),
                c.getString("database.serverName", "Unknown"),
                c.getBoolean("resourcePackHint.enabled", true),
                readLinks(c, "downloadLinks"),
                readLinks(c, "resourcePackHint.links"));
    }

    private static List<String> lowerCased(List<String> input) {
        return input.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Reads a "provider name -> URL" section, preserving the order from the file so the
     * links appear as configured. Entries without a URL are dropped.
     */
    private static Map<String, String> readLinks(FileConfiguration c, String path) {
        ConfigurationSection section = c.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }

        Map<String, String> links = new LinkedHashMap<>();
        for (String provider : section.getKeys(false)) {
            String url = section.getString(provider);
            if (url != null && !url.isBlank()) {
                links.put(provider, url.trim());
            }
        }
        return Collections.unmodifiableMap(links);
    }

    private Snapshot s() {
        return snapshot;
    }

    // -------- Nested value holders --------

    public record Item(String material, String name, List<String> lore, int customModelData,
                       boolean unbreakable, boolean hideFlags, boolean giveOnJoin) {
    }

    public record Ban(boolean broadcast) {
    }

    public record Effects(boolean lightning, boolean sound, boolean particles,
                          boolean knockbackEnabled, double knockbackHorizontal, double knockbackVertical) {
    }

    public record IpBan(boolean enabled, boolean autoIpBan, boolean trackIps) {
    }

    public record Privacy(IPAnonymizer.AnonymizationLevel anonymizationLevel, String hashSalt,
                          boolean retentionEnabled, int retentionDays, boolean retentionKeepActive) {
    }

    public record TempBans(boolean enabled, int checkIntervalSeconds, boolean notifyOnExpire) {
    }

    public record DiscordSettings(boolean enabled, String webhookUrl,
                                  boolean bans, boolean kicks, boolean unbans, boolean mutes,
                                  boolean jails, boolean warnings, boolean appeals,
                                  boolean showStaffName, boolean showServerName) {
    }

    public record Logging(boolean separateFile, String fileName, boolean logBans, boolean logKicks,
                          boolean logUnbans, boolean logMutes, boolean logJails, boolean logWarnings) {
    }

    public record Appeals(boolean enabled, int minLength, long cooldownHours,
                          int maxPerPunishment, boolean notifyStaff) {
    }

    public record Mute(boolean enabled, String defaultReason,
                       boolean preventChat, boolean preventCommands, boolean preventSigns,
                       boolean preventBooks, List<String> blockedCommands) {
    }

    public record Jail(boolean enabled, boolean useEssentials, String essentialsDefaultJail,
                       String defaultReason, boolean preventMovement,
                       boolean preventTeleport, boolean preventDamage, boolean preventCommands,
                       double maxDistance) {
    }

    public record Warnings(boolean enabled, int autoBanThreshold, String autoBanDuration, int expireAfterDays) {

        /**
         * @return how long a warning counts towards the auto-ban threshold,
         *         or {@code null} if warnings never expire
         */
        public Duration expiry() {
            return expireAfterDays <= 0 ? null : Duration.ofDays(expireAfterDays);
        }
    }

    private record Snapshot(Item item, Ban ban, int cooldownSeconds, int presetSwitchCooldownMillis,
                            Effects effects, IpBan ipBan, Privacy privacy, TempBans tempBans,
                            DiscordSettings discord, ValidationUtil.ReasonPolicy reasonPolicy, Logging logging,
                            Appeals appeals, Mute mute, Jail jail, Warnings warnings,
                            String serverName, boolean resourcePackHint,
                            Map<String, String> downloadLinks, Map<String, String> resourcePackLinks) {
    }

    // -------- Grouped accessors --------

    public Item item() {
        return s().item();
    }

    public Ban ban() {
        return s().ban();
    }

    public Effects effects() {
        return s().effects();
    }

    public IpBan ipBan() {
        return s().ipBan();
    }

    public Privacy privacy() {
        return s().privacy();
    }

    public TempBans tempBans() {
        return s().tempBans();
    }

    public DiscordSettings discord() {
        return s().discord();
    }

    public ValidationUtil.ReasonPolicy reasonPolicy() {
        return s().reasonPolicy();
    }

    public Logging logging() {
        return s().logging();
    }

    public Appeals appeals() {
        return s().appeals();
    }

    public Mute mute() {
        return s().mute();
    }

    public Jail jail() {
        return s().jail();
    }

    public Warnings warnings() {
        return s().warnings();
    }

    public String serverName() {
        return s().serverName();
    }

    public boolean resourcePackHint() {
        return s().resourcePackHint();
    }

    /**
     * @return provider name to plugin download URL, in configured order
     */
    public Map<String, String> downloadLinks() {
        return s().downloadLinks();
    }

    /**
     * @return provider name to resource pack download URL, in configured order
     */
    public Map<String, String> resourcePackLinks() {
        return s().resourcePackLinks();
    }

    public int cooldownSeconds() {
        return s().cooldownSeconds();
    }

    public int presetSwitchCooldownMillis() {
        return s().presetSwitchCooldownMillis();
    }

    // -------- Flat accessors kept for existing call sites --------

    public String itemMaterial() {
        return item().material();
    }

    public String itemName() {
        return item().name();
    }

    public List<String> itemLore() {
        return item().lore();
    }

    public int itemCustomModelData() {
        return item().customModelData();
    }

    public boolean itemUnbreakable() {
        return item().unbreakable();
    }

    public boolean itemHideFlags() {
        return item().hideFlags();
    }

    public boolean giveOnJoin() {
        return item().giveOnJoin();
    }

    public boolean broadcast() {
        return ban().broadcast();
    }

    public boolean fxLightning() {
        return effects().lightning();
    }

    public boolean fxSound() {
        return effects().sound();
    }

    public boolean fxParticles() {
        return effects().particles();
    }

    public boolean knockbackEnabled() {
        return effects().knockbackEnabled();
    }

    public double knockbackHorizontal() {
        return effects().knockbackHorizontal();
    }

    public double knockbackVertical() {
        return effects().knockbackVertical();
    }
}
