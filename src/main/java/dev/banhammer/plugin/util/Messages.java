package dev.banhammer.plugin.util;

import dev.banhammer.plugin.BanHammerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads and renders all user-facing messages.
 *
 * <h2>Placeholder safety</h2>
 * Message templates are MiniMessage sources. Any value substituted into them
 * ({@code {player}}, {@code {reason}}, {@code {text}}, ...) is escaped with
 * {@link MiniMessage#escapeTags(String)} first, so player-supplied content such as an
 * appeal text cannot inject {@code <click:run_command:...>} or {@code <hover:...>} tags
 * into staff-facing output.
 *
 * <h2>Defaults</h2>
 * The configuration loaded from disk is backed by the corresponding resource bundled in
 * the plugin jar. Message keys added by a plugin update therefore resolve correctly on
 * servers whose {@code messages_*.yml} predates them, instead of falling back to the
 * hard-coded German strings.
 */
public final class Messages {

    private final BanHammerPlugin plugin;
    private volatile FileConfiguration cfg = new YamlConfiguration();
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Messages(BanHammerPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        try {
            String lang = sanitizeLanguage(plugin.getConfig().getString("language", "en"));
            String fileName = "messages_" + lang + ".yml";

            if (plugin.getResource(fileName) == null) {
                plugin.getSLF4JLogger().warn("Language '{}' is not bundled with BanHammer, falling back to 'en'", lang);
                lang = "en";
                fileName = "messages_en.yml";
            }

            File file = new File(plugin.getDataFolder(), fileName);
            if (!file.exists()) {
                plugin.saveResource(fileName, false);
            }

            FileConfiguration loaded = YamlConfiguration.loadConfiguration(file);
            FileConfiguration bundled = loadBundled(fileName);

            if (bundled != null) {
                // Write keys added by a plugin update into the server's file, so an admin can
                // see and translate them instead of silently getting the built-in default.
                addMissingKeys(loaded, bundled, file);
                // Still back the file with the bundle: covers anything that could not be
                // written (read-only data folder) and keys removed by hand.
                loaded.setDefaults(bundled);
            }

            cfg = loaded;
            plugin.getSLF4JLogger().info("Loaded language file: {}", fileName);
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Failed to load messages", e);
            cfg = new YamlConfiguration();
        }
    }

    /**
     * Reads the language file shipped inside the plugin jar.
     */
    private FileConfiguration loadBundled(String fileName) throws java.io.IOException {
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /**
     * Copies keys that exist in the bundled file but not in the server's file, then saves.
     *
     * <p>Values the administrator already has are never touched - only genuinely absent keys
     * are added. The file is backed up once before the first modification.
     */
    private void addMissingKeys(FileConfiguration loaded, FileConfiguration bundled, File file) {
        List<String> added = new ArrayList<>();

        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key)) {
                continue; // Sections appear implicitly once their leaves are set.
            }
            // ignoreDefault = true: ask what is really in the file, not what a default provides.
            if (loaded.contains(key, true)) {
                continue;
            }
            loaded.set(key, bundled.get(key));
            added.add(key);
        }

        if (added.isEmpty()) {
            return;
        }

        try {
            File backup = new File(file.getParentFile(), file.getName() + ".backup");
            if (!backup.exists()) {
                Files.copy(file.toPath(), backup.toPath());
            }
            loaded.save(file);
            plugin.getSLF4JLogger().info("Added {} new message key(s) to {}: {}",
                    added.size(), file.getName(), String.join(", ", added));
        } catch (Exception e) {
            // Not fatal: the bundled defaults still back the configuration in memory.
            plugin.getSLF4JLogger().warn("Could not write new message keys to {} ({}). "
                    + "The built-in texts are used for them.", file.getName(), e.toString());
        }
    }

    /**
     * Restricts the configured language to a bare identifier so it cannot be used to
     * point at an arbitrary path.
     */
    private static String sanitizeLanguage(String lang) {
        if (lang == null || lang.isBlank()) {
            return "en";
        }
        String cleaned = lang.trim().toLowerCase(Locale.ROOT);
        return cleaned.matches("[a-z0-9_-]{1,16}") ? cleaned : "en";
    }

    /**
     * Reads a raw template. Resolution order: server file, bundled resource, hard-coded default.
     */
    private String raw(String path, String def) {
        String value = cfg.getString(path);
        return value != null ? value : def;
    }

    /**
     * Escapes a value so it is inserted as literal text rather than parsed as MiniMessage.
     */
    private String esc(String value) {
        return mm.escapeTags(value == null ? "" : value);
    }

    /**
     * Deserializes a template, degrading to plain text if an administrator wrote a
     * malformed tag instead of aborting the whole command.
     */
    private Component render(String path, String template) {
        try {
            return mm.deserialize(template);
        } catch (Exception e) {
            plugin.getSLF4JLogger().warn("Message '{}' contains invalid MiniMessage and was rendered as plain text: {}",
                    path, e.getMessage());
            return Component.text(template);
        }
    }

    /** Renders a template without placeholders. */
    private Component msg(String path, String def) {
        return render(path, raw(path, def));
    }

    /** Renders a template with a single escaped placeholder. */
    private Component msg(String path, String def, String key, String value) {
        return render(path, raw(path, def).replace(key, esc(value)));
    }

    // -------- Generic accessors --------

    /**
     * Looks up a label from the {@code gui:} section.
     *
     * <p>Menu labels are numerous and purely cosmetic, so they get one generic accessor
     * instead of thirty near-identical methods. They live under their own prefix so they
     * cannot collide with message keys.
     *
     * @param key the key below {@code gui:}
     * @param def the fallback text
     */
    public Component gui(String key, String def) {
        return msg("gui." + key, def);
    }

    /**
     * Looks up a GUI label with one escaped placeholder.
     */
    public Component gui(String key, String def, String placeholder, String value) {
        return msg("gui." + key, def, placeholder, value);
    }

    /**
     * Looks up a plain (non-MiniMessage) string, for text that leaves Minecraft - Discord
     * embed titles in particular, where formatting tags would be shown literally.
     *
     * @param key the key below {@code discord:}
     * @param def the fallback text
     */
    public String discordText(String key, String def) {
        return raw("discord." + key, def);
    }

    // -------- Components --------

    public Component prefix() {
        return msg("prefix", "<gold>[BanHammer]</gold> ");
    }

    public Component noPermission() {
        return msg("noPermission", "<red>Du hast keine Berechtigung.</red>");
    }

    public Component reloaded() {
        return msg("reloaded", "<green>Konfiguration neu geladen.</green>");
    }

    public Component notPlayer() {
        return msg("notPlayer", "<red>Nur im Spiel verfügbar.</red>");
    }

    public Component noTarget() {
        return msg("noTarget", "<yellow>Kein Spieler im Visier!</yellow>");
    }

    public Component cannotBan() {
        return msg("cannotBan", "<red>Du kannst diesen Spieler nicht bannen.</red>");
    }

    public Component given(String player) {
        return msg("given", "<green>Ban Hammer an {player} gegeben.</green>", "{player}", player);
    }

    public Component cooldown(int seconds) {
        return msg("cooldown", "<yellow>Warte noch {seconds}s.</yellow>", "{seconds}", String.valueOf(seconds));
    }

    public Component bannedBroadcast(String staff, String victim, String durationText) {
        String base = raw("bannedBroadcast", "<gold>{staff}</gold> hat <red>{victim}</red>{duration} gebannt.");
        return render("bannedBroadcast", base
                .replace("{staff}", esc(staff))
                .replace("{victim}", esc(victim))
                .replace("{duration}", esc(durationText)));
    }

    public Component bannedStaff(String victim, String durationText) {
        String base = raw("bannedStaff", "<green>{victim}</green> gebannt{duration}.");
        return render("bannedStaff", base
                .replace("{victim}", esc(victim))
                .replace("{duration}", esc(durationText)));
    }

    public Component tempBannedBroadcast(String staff, String victim, String duration) {
        String base = raw("tempBannedBroadcast", "<gold>{staff}</gold> hat <red>{victim}</red> für {duration} gebannt.");
        return render("tempBannedBroadcast", base
                .replace("{staff}", esc(staff))
                .replace("{victim}", esc(victim))
                .replace("{duration}", esc(duration)));
    }

    public Component tempBannedStaff(String victim, String duration) {
        String base = raw("tempBannedStaff", "<green>{victim}</green> für {duration} gebannt.");
        return render("tempBannedStaff", base
                .replace("{victim}", esc(victim))
                .replace("{duration}", esc(duration)));
    }

    public Component kickedStaff(String victim) {
        return msg("kickedStaff", "<green>{victim}</green> gekickt.", "{victim}", victim);
    }

    // -------- New 3.0 Messages --------

    public Component databaseDisabled() {
        return msg("databaseDisabled", "<red>Datenbank ist nicht aktiviert.</red>");
    }

    public Component playerNotFound() {
        return msg("playerNotFound", "<red>Spieler nicht gefunden.</red>");
    }

    public Component unbanned(String player) {
        return msg("unbanned", "<green>{player} wurde entbannt.</green>", "{player}", player);
    }

    public Component unbanReason(String reason) {
        return msg("unbanReason", "<gray>Grund: {reason}</gray>", "{reason}", reason);
    }

    public Component notBanned(String player) {
        return msg("notBanned", "<yellow>{player} ist nicht gebannt.</yellow>", "{player}", player);
    }

    // History
    public Component historyHeader(String player, int page, int maxPages) {
        String base = raw("historyHeader", "<gold>--- Ban-Historie von {player} (Seite {page}/{maxPages}) ---</gold>");
        return render("historyHeader", base
                .replace("{player}", esc(player))
                .replace("{page}", String.valueOf(page))
                .replace("{maxPages}", String.valueOf(maxPages)));
    }

    public Component historyEntry(int id, String type, String reason) {
        String base = raw("historyEntry", "<gray>#{id}</gray> <yellow>{type}</yellow> - <white>{reason}</white>");
        return render("historyEntry", base
                .replace("{id}", String.valueOf(id))
                .replace("{type}", esc(type))
                .replace("{reason}", esc(reason)));
    }

    public Component historyEntryDate(String date) {
        return msg("historyEntryDate", "  <gray>Datum: {date}</gray>", "{date}", date);
    }

    public Component historyEntryStaff(String staff) {
        return msg("historyEntryStaff", "  <gray>Staff: {staff}</gray>", "{staff}", staff);
    }

    public Component historyEntryExpires(String expires) {
        return msg("historyEntryExpires", "  <gray>Läuft ab: {expires}</gray>", "{expires}", expires);
    }

    public Component historyEntryActive() {
        return msg("historyEntryActive", "  <red>[AKTIV]</red>");
    }

    public Component historyEmpty() {
        return msg("historyEmpty", "<yellow>Keine Einträge gefunden.</yellow>");
    }

    public Component historyInvalidPage() {
        return msg("historyInvalidPage", "<red>Ungültige Seitennummer.</red>");
    }

    // Stats
    public Component statsHeader(String target) {
        return msg("statsHeader", "<gold>--- Statistiken {target} ---</gold>", "{target}", target);
    }

    public Component statsTotal(int total) {
        return msg("statsTotal", "<gray>Gesamt:</gray> <white>{total} Bestrafungen</white>", "{total}", String.valueOf(total));
    }

    public Component statsBans(int bans) {
        return msg("statsBans", "<gray>Bans:</gray> <white>{bans}</white>", "{bans}", String.valueOf(bans));
    }

    public Component statsKicks(int kicks) {
        return msg("statsKicks", "<gray>Kicks:</gray> <white>{kicks}</white>", "{kicks}", String.valueOf(kicks));
    }

    public Component statsMutes(int mutes) {
        return msg("statsMutes", "<gray>Mutes:</gray> <white>{mutes}</white>", "{mutes}", String.valueOf(mutes));
    }

    public Component statsWarnings(int warnings) {
        return msg("statsWarnings", "<gray>Warnungen:</gray> <white>{warnings}</white>", "{warnings}", String.valueOf(warnings));
    }

    public Component statsJails(int jails) {
        return msg("statsJails", "<gray>Jails:</gray> <white>{jails}</white>", "{jails}", String.valueOf(jails));
    }

    // Appeals
    public Component appealSubmitted() {
        return msg("appealSubmitted", "<green>Dein Appeal wurde eingereicht.</green>");
    }

    public Component appealCooldown(long hours) {
        return msg("appealCooldown", "<red>Du musst noch {hours} Stunden warten.</red>", "{hours}", String.valueOf(hours));
    }

    public Component appealNoActiveBan() {
        return msg("appealNoActiveBan", "<red>Du hast keine aktive Bestrafung, gegen die du Einspruch einlegen kannst.</red>");
    }

    public Component appealTooShort() {
        return msg("appealTooShort", "<red>Der Appeal-Text muss mindestens 20 Zeichen lang sein.</red>");
    }

    public Component appealMaxReached() {
        return msg("appealMaxReached", "<red>Du hast bereits die maximale Anzahl an Appeals erreicht.</red>");
    }

    public Component appealsHeader(int count) {
        return msg("appealsHeader", "<gold>--- Offene Appeals ({count}) ---</gold>", "{count}", String.valueOf(count));
    }

    public Component appealsEntry(int id, String player, String text) {
        String base = raw("appealsEntry", "<gray>#{id}</gray> <yellow>{player}</yellow> - {text}");
        return render("appealsEntry", base
                .replace("{id}", String.valueOf(id))
                .replace("{player}", esc(player))
                .replace("{text}", esc(text)));
    }

    public Component appealsEntryDate(String date) {
        return msg("appealsEntryDate", "  <gray>{date}</gray>", "{date}", date);
    }

    public Component appealsEmpty() {
        return msg("appealsEmpty", "<yellow>Keine offenen Appeals.</yellow>");
    }

    public Component appealsInvalidId() {
        return msg("appealsInvalidId", "<red>Ungültige Appeal-ID.</red>");
    }

    public Component appealApproved(int id) {
        return msg("appealApproved", "<green>Appeal #{id} wurde genehmigt.</green>", "{id}", String.valueOf(id));
    }

    public Component appealDenied(int id) {
        return msg("appealDenied", "<red>Appeal #{id} wurde abgelehnt.</red>", "{id}", String.valueOf(id));
    }

    public Component appealNotification(String status) {
        return msg("appealNotification", "<green>Dein Appeal wurde bearbeitet: {status}</green>", "{status}", status);
    }

    public Component appealResponse(String response) {
        return msg("appealResponse", "<gray>Antwort: {response}</gray>", "{response}", response);
    }

    // Errors
    public Component errorOccurred() {
        return msg("errorOccurred", "<red>Ein Fehler ist aufgetreten.</red>");
    }

    public Component invalidDuration() {
        return msg("invalidDuration", "<red>Ungültige Dauer. Beispiele: 7d, 1h30m, 2w, permanent</red>");
    }

    // Mute Messages
    public Component muteChatBlocked(String timeRemaining) {
        return msg("muteChatBlocked", "<red>Du bist gemutet! Verbleibende Zeit: {time}</red>", "{time}", timeRemaining);
    }

    public Component muteCommandBlocked(String timeRemaining) {
        return msg("muteCommandBlocked",
                "<red>Du bist gemutet und kannst diesen Befehl nicht nutzen! Verbleibende Zeit: {time}</red>",
                "{time}", timeRemaining);
    }

    public Component mutedMessage(String duration, String reason) {
        String base = raw("mutedMessage", "<red>Du wurdest für {duration} gemutet.\nGrund: {reason}</red>");
        return render("mutedMessage", base
                .replace("{duration}", esc(duration))
                .replace("{reason}", esc(reason)));
    }

    // Jail Messages
    public Component jailed() {
        return msg("jailed", "<red>Du wurdest ins Gefängnis gesperrt!</red>");
    }

    public Component unjailed() {
        return msg("unjailed", "<green>Du wurdest aus dem Gefängnis entlassen!</green>");
    }

    public Component jailEscape() {
        return msg("jailEscape", "<red>Versuch nicht zu fliehen!</red>");
    }

    public Component jailNoTeleport() {
        return msg("jailNoTeleport", "<red>Du kannst dich nicht teleportieren, während du im Gefängnis bist!</red>");
    }

    public Component jailNoCommands() {
        return msg("jailNoCommands", "<red>Du kannst keine Befehle im Gefängnis nutzen!</red>");
    }

    public Component jailFailed() {
        return msg("jailFailed", "<red>Der Spieler konnte nicht eingesperrt werden - siehe Server-Log.</red>");
    }

    // Warning Messages
    public Component warnedMessage(String reason) {
        return msg("warnedMessage", "<yellow>Du wurdest verwarnt!\nGrund: {reason}</yellow>", "{reason}", reason);
    }

    // Command Usage Messages
    public Component unknownCommand() {
        return msg("unknownCommand", "<red>Unbekannter Befehl.</red>");
    }

    public Component muteUsage() {
        return msg("muteUsage", "Nutzung: /mute <Spieler> <Dauer> [Grund]");
    }

    public Component muteExamples() {
        return msg("muteExamples", "Beispiele: /mute Player 1h, /mute Player permanent Spam");
    }

    public Component mutedSuccess(String victim, String duration) {
        String base = raw("mutedSuccess", "{victim} wurde für {duration} gemutet.");
        return render("mutedSuccess", base
                .replace("{victim}", esc(victim))
                .replace("{duration}", esc(duration)));
    }

    public Component muteCancelled() {
        return msg("muteCancelled", "Mute wurde durch ein Event abgebrochen.");
    }

    public Component unmuteUsage() {
        return msg("unmuteUsage", "Nutzung: /unmute <Spieler> [Grund]");
    }

    public Component unmutedSuccess(String player) {
        return msg("unmutedSuccess", "{player} wurde entmutet.", "{player}", player);
    }

    public Component notMuted(String player) {
        return msg("notMuted", "<yellow>{player} ist nicht gemutet.</yellow>", "{player}", player);
    }

    public Component jailNotSet() {
        return msg("jailNotSet", "Jail-Position ist nicht gesetzt! Nutze /setjail");
    }

    public Component jailUsage() {
        return msg("jailUsage", "Nutzung: /jail <Spieler> <Dauer> [Zelle] [Grund]");
    }

    public Component jailExamples() {
        return msg("jailExamples", "Beispiele: /jail Player 30m, /jail Player 1h 2 Griefing");
    }

    public Component jailedSuccess(String victim, String duration) {
        String base = raw("jailedSuccess", "{victim} wurde für {duration} eingesperrt.");
        return render("jailedSuccess", base
                .replace("{victim}", esc(victim))
                .replace("{duration}", esc(duration)));
    }

    public Component jailCancelled() {
        return msg("jailCancelled", "Jail wurde durch ein Event abgebrochen.");
    }

    public Component unjailUsage() {
        return msg("unjailUsage", "Nutzung: /unjail <Spieler> [Grund]");
    }

    public Component playerNotOnline() {
        return msg("playerNotOnline", "Spieler nicht online.");
    }

    public Component unjailedSuccess(String player) {
        return msg("unjailedSuccess", "{player} wurde freigelassen.", "{player}", player);
    }

    public Component notJailed(String player) {
        return msg("notJailed", "<yellow>{player} ist nicht eingesperrt.</yellow>", "{player}", player);
    }

    public Component warnUsage() {
        return msg("warnUsage", "Nutzung: /warn <Spieler> <Grund>");
    }

    public Component warnedSuccess(String victim) {
        return msg("warnedSuccess", "{victim} wurde verwarnt.", "{victim}", victim);
    }

    public Component warnCount(long count, int threshold) {
        String base = raw("warnCount", "Du hast jetzt {count}/{threshold} Verwarnungen.");
        return render("warnCount", base
                .replace("{count}", String.valueOf(count))
                .replace("{threshold}", String.valueOf(threshold)));
    }

    public Component jailLocationSet() {
        return msg("jailLocationSet", "Jail-Position gesetzt!");
    }

    /**
     * Usage line for {@code /banhammer}.
     *
     * <p>The subcommand list is generated by the command itself and passed in, so it can never
     * drift out of sync with the commands that actually exist. The previous key held the whole
     * sentence including a hand-written list, which is how an already-removed "pack" subcommand
     * kept being advertised on servers whose message file predated its removal.
     *
     * @param commands the pipe-separated subcommands available to the sender
     */
    public Component usageBanHammer(String commands) {
        return msg("usageBanHammer", "Nutzung: /banhammer [{commands}]", "{commands}", commands);
    }

    public Component giveUsage() {
        return msg("giveUsage", "Nutzung: /banhammer give <Spieler>");
    }

    public Component inventoryFull(String player) {
        return msg("inventoryFull", "<red>Inventar von {player} ist voll.</red>", "{player}", player);
    }

    public Component historyUsage() {
        return msg("historyUsage", "Nutzung: /bh history <Spieler> [Seite]");
    }

    public Component unbanUsage() {
        return msg("unbanUsage", "Nutzung: /bh unban <Spieler> [Grund]");
    }

    public Component statsUsage() {
        return msg("statsUsage", "Nutzung: /bh stats <Spieler>");
    }

    public Component approveUsage() {
        return msg("approveUsage", "Nutzung: /bh approve <ID> [Antwort]");
    }

    public Component denyUsage() {
        return msg("denyUsage", "Nutzung: /bh deny <ID> [Antwort]");
    }

    public Component appealAlreadyProcessed() {
        return msg("appealAlreadyProcessed", "Dieser Appeal wurde bereits bearbeitet.");
    }

    public Component appealsDisabled() {
        return msg("appealsDisabled", "Appeals sind auf diesem Server deaktiviert.");
    }

    public Component appealUsage() {
        return msg("appealUsage", "Nutzung: /appeal <Text>");
    }
}
