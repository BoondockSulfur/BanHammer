package dev.banhammer.plugin.util;

import dev.banhammer.plugin.BanHammerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public final class Messages {

    private final BanHammerPlugin plugin;
    private File file;
    private FileConfiguration cfg;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public Messages(BanHammerPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        try {
            // Get language from config
            String lang = plugin.getConfig().getString("language", "de");
            String fileName = "messages_" + lang + ".yml";

            file = new File(plugin.getDataFolder(), fileName);

            // If language-specific file doesn't exist, save it from resources
            if (!file.exists()) {
                try {
                    plugin.saveResource(fileName, false);
                } catch (IllegalArgumentException e) {
                    // Language file not in resources, fall back to default
                    plugin.getSLF4JLogger().warn("Language file {} not found, using messages.yml", fileName);
                    fileName = "messages.yml";
                    file = new File(plugin.getDataFolder(), fileName);
                    if (!file.exists()) {
                        plugin.saveResource(fileName, false);
                    }
                }
            }

            cfg = YamlConfiguration.loadConfiguration(file);
            plugin.getSLF4JLogger().info("Loaded language file: {}", fileName);
        } catch (Exception e) {
            plugin.getSLF4JLogger().error("Failed to load messages", e);
            cfg = new YamlConfiguration();
        }
    }

    private String raw(String path, String def) {
        return cfg.getString(path, def);
    }

    // -------- Components --------

    public Component prefix() {
        return mm.deserialize(raw("prefix", "<gold>[BanHammer]</gold> "));
    }

    public Component noPermission() {
        return mm.deserialize(raw("noPermission", "<red>Du hast keine Berechtigung.</red>"));
    }

    public Component reloaded() {
        return mm.deserialize(raw("reloaded", "<green>Konfiguration neu geladen.</green>"));
    }

    public Component notPlayer() {
        return mm.deserialize(raw("notPlayer", "<red>Nur im Spiel verfügbar.</red>"));
    }

    public Component noTarget() {
        return mm.deserialize(raw("noTarget", "<yellow>Kein Spieler im Visier!</yellow>"));
    }

    public Component cannotBan() {
        return mm.deserialize(raw("cannotBan", "<red>Du kannst diesen Spieler nicht bannen.</red>"));
    }

    public Component given(String player) {
        String base = raw("given", "<green>Ban Hammer an {player} gegeben.</green>");
        return mm.deserialize(base.replace("{player}", player));
    }

    public Component cooldown(int seconds) {
        String base = raw("cooldown", "<yellow>Warte noch {seconds}s.</yellow>");
        return mm.deserialize(base.replace("{seconds}", String.valueOf(seconds)));
    }

    public Component bannedBroadcast(String staff, String victim, String durationText) {
        String base = raw("bannedBroadcast", "<gold>{staff}</gold> hat <red>{victim}</red>{duration} gebannt.");
        return mm.deserialize(
                base.replace("{staff}", staff)
                        .replace("{victim}", victim)
                        .replace("{duration}", durationText)
        );
    }

    public Component bannedStaff(String victim, String durationText) {
        String base = raw("bannedStaff", "<green>{victim}</green> gebannt{duration}.");
        return mm.deserialize(
                base.replace("{victim}", victim)
                        .replace("{duration}", durationText)
        );
    }

    public Component tempBannedBroadcast(String staff, String victim, String duration) {
        String base = raw("tempBannedBroadcast", "<gold>{staff}</gold> hat <red>{victim}</red> für {duration} gebannt.");
        return mm.deserialize(
                base.replace("{staff}", staff)
                        .replace("{victim}", victim)
                        .replace("{duration}", duration)
        );
    }

    public Component tempBannedStaff(String victim, String duration) {
        String base = raw("tempBannedStaff", "<green>{victim}</green> für {duration} gebannt.");
        return mm.deserialize(
                base.replace("{victim}", victim)
                        .replace("{duration}", duration)
        );
    }

    public Component kickedStaff(String victim) {
        String base = raw("kickedStaff", "<green>{victim}</green> gekickt.");
        return mm.deserialize(base.replace("{victim}", victim));
    }

    // -------- New 3.0 Messages --------

    public Component databaseDisabled() {
        return mm.deserialize(raw("databaseDisabled", "<red>Datenbank ist nicht aktiviert.</red>"));
    }

    public Component playerNotFound() {
        return mm.deserialize(raw("playerNotFound", "<red>Spieler nicht gefunden.</red>"));
    }

    public Component unbanned(String player) {
        String base = raw("unbanned", "<green>{player} wurde entbannt.</green>");
        return mm.deserialize(base.replace("{player}", player));
    }

    public Component unbanReason(String reason) {
        String base = raw("unbanReason", "<gray>Grund: {reason}</gray>");
        return mm.deserialize(base.replace("{reason}", reason));
    }

    public Component notBanned(String player) {
        String base = raw("notBanned", "<yellow>{player} ist nicht gebannt.</yellow>");
        return mm.deserialize(base.replace("{player}", player));
    }

    // History
    public Component historyHeader(String player, int page, int maxPages) {
        String base = raw("historyHeader", "<gold>--- Ban-Historie von {player} (Seite {page}/{maxPages}) ---</gold>");
        return mm.deserialize(base
                .replace("{player}", player)
                .replace("{page}", String.valueOf(page))
                .replace("{maxPages}", String.valueOf(maxPages)));
    }

    public Component historyEntry(int id, String type, String reason) {
        String base = raw("historyEntry", "<gray>#{id}</gray> <yellow>{type}</yellow> - <white>{reason}</white>");
        return mm.deserialize(base
                .replace("{id}", String.valueOf(id))
                .replace("{type}", type)
                .replace("{reason}", reason));
    }

    public Component historyEntryDate(String date) {
        String base = raw("historyEntryDate", "  <gray>Datum: {date}</gray>");
        return mm.deserialize(base.replace("{date}", date));
    }

    public Component historyEntryStaff(String staff) {
        String base = raw("historyEntryStaff", "  <gray>Staff: {staff}</gray>");
        return mm.deserialize(base.replace("{staff}", staff));
    }

    public Component historyEntryExpires(String expires) {
        String base = raw("historyEntryExpires", "  <gray>Läuft ab: {expires}</gray>");
        return mm.deserialize(base.replace("{expires}", expires));
    }

    public Component historyEntryActive() {
        return mm.deserialize(raw("historyEntryActive", "  <red>[AKTIV]</red>"));
    }

    public Component historyEmpty() {
        return mm.deserialize(raw("historyEmpty", "<yellow>Keine Einträge gefunden.</yellow>"));
    }

    public Component historyInvalidPage() {
        return mm.deserialize(raw("historyInvalidPage", "<red>Ungültige Seitennummer.</red>"));
    }

    // Stats
    public Component statsHeader(String target) {
        String base = raw("statsHeader", "<gold>--- Statistiken {target} ---</gold>");
        return mm.deserialize(base.replace("{target}", target));
    }

    public Component statsTotal(int total) {
        String base = raw("statsTotal", "<gray>Gesamt:</gray> <white>{total} Bestrafungen</white>");
        return mm.deserialize(base.replace("{total}", String.valueOf(total)));
    }

    public Component statsBans(int bans) {
        String base = raw("statsBans", "<gray>Bans:</gray> <white>{bans}</white>");
        return mm.deserialize(base.replace("{bans}", String.valueOf(bans)));
    }

    public Component statsKicks(int kicks) {
        String base = raw("statsKicks", "<gray>Kicks:</gray> <white>{kicks}</white>");
        return mm.deserialize(base.replace("{kicks}", String.valueOf(kicks)));
    }

    public Component statsMutes(int mutes) {
        String base = raw("statsMutes", "<gray>Mutes:</gray> <white>{mutes}</white>");
        return mm.deserialize(base.replace("{mutes}", String.valueOf(mutes)));
    }

    public Component statsWarnings(int warnings) {
        String base = raw("statsWarnings", "<gray>Warnungen:</gray> <white>{warnings}</white>");
        return mm.deserialize(base.replace("{warnings}", String.valueOf(warnings)));
    }

    // Appeals
    public Component appealSubmitted() {
        return mm.deserialize(raw("appealSubmitted", "<green>Dein Appeal wurde eingereicht.</green>"));
    }

    public Component appealCooldown(long hours) {
        String base = raw("appealCooldown", "<red>Du musst noch {hours} Stunden warten.</red>");
        return mm.deserialize(base.replace("{hours}", String.valueOf(hours)));
    }

    public Component appealNoActiveBan() {
        return mm.deserialize(raw("appealNoActiveBan", "<red>Du hast keinen aktiven Ban.</red>"));
    }

    public Component appealTooShort() {
        return mm.deserialize(raw("appealTooShort", "<red>Der Appeal-Text muss mindestens 20 Zeichen lang sein.</red>"));
    }

    public Component appealMaxReached() {
        return mm.deserialize(raw("appealMaxReached", "<red>Du hast bereits die maximale Anzahl an Appeals erreicht.</red>"));
    }

    public Component appealsHeader(int count) {
        String base = raw("appealsHeader", "<gold>--- Offene Appeals ({count}) ---</gold>");
        return mm.deserialize(base.replace("{count}", String.valueOf(count)));
    }

    public Component appealsEntry(int id, String player, String text) {
        String base = raw("appealsEntry", "<gray>#{id}</gray> <yellow>{player}</yellow> - {text}");
        return mm.deserialize(base
                .replace("{id}", String.valueOf(id))
                .replace("{player}", player)
                .replace("{text}", text));
    }

    public Component appealsEntryDate(String date) {
        String base = raw("appealsEntryDate", "  <gray>{date}</gray>");
        return mm.deserialize(base.replace("{date}", date));
    }

    public Component appealsEmpty() {
        return mm.deserialize(raw("appealsEmpty", "<yellow>Keine offenen Appeals.</yellow>"));
    }

    public Component appealsInvalidId() {
        return mm.deserialize(raw("appealsInvalidId", "<red>Ungültige Appeal-ID.</red>"));
    }

    public Component appealApproved(int id) {
        String base = raw("appealApproved", "<green>Appeal #{id} wurde genehmigt.</green>");
        return mm.deserialize(base.replace("{id}", String.valueOf(id)));
    }

    public Component appealDenied(int id) {
        String base = raw("appealDenied", "<red>Appeal #{id} wurde abgelehnt.</red>");
        return mm.deserialize(base.replace("{id}", String.valueOf(id)));
    }

    public Component appealNotification(String status) {
        String base = raw("appealNotification", "<green>Dein Appeal wurde bearbeitet: {status}</green>");
        return mm.deserialize(base.replace("{status}", status));
    }

    public Component appealResponse(String response) {
        String base = raw("appealResponse", "<gray>Antwort: {response}</gray>");
        return mm.deserialize(base.replace("{response}", response));
    }

    // Errors
    public Component errorOccurred() {
        return mm.deserialize(raw("errorOccurred", "<red>Ein Fehler ist aufgetreten.</red>"));
    }

    public Component invalidDuration() {
        return mm.deserialize(raw("invalidDuration", "<red>Ungültige Dauer. Beispiele: 7d, 1h30m, permanent</red>"));
    }

    // Mute Messages
    public Component muteChatBlocked(String timeRemaining) {
        String base = raw("muteChatBlocked", "<red>Du bist gemutet! Verbleibende Zeit: {time}</red>");
        return mm.deserialize(base.replace("{time}", timeRemaining));
    }

    public Component muteCommandBlocked(String timeRemaining) {
        String base = raw("muteCommandBlocked", "<red>Du bist gemutet und kannst diesen Befehl nicht nutzen! Verbleibende Zeit: {time}</red>");
        return mm.deserialize(base.replace("{time}", timeRemaining));
    }

    public Component mutedMessage(String duration, String reason) {
        String base = raw("mutedMessage", "<red>Du wurdest für {duration} gemutet.\nGrund: {reason}</red>");
        return mm.deserialize(base
                .replace("{duration}", duration)
                .replace("{reason}", reason));
    }

    // Jail Messages
    public Component jailed() {
        return mm.deserialize(raw("jailed", "<red>Du wurdest ins Gefängnis gesperrt!</red>"));
    }

    public Component unjailed() {
        return mm.deserialize(raw("unjailed", "<green>Du wurdest aus dem Gefängnis entlassen!</green>"));
    }

    public Component jailEscape() {
        return mm.deserialize(raw("jailEscape", "<red>Versuch nicht zu fliehen!</red>"));
    }

    public Component jailNoTeleport() {
        return mm.deserialize(raw("jailNoTeleport", "<red>Du kannst dich nicht teleportieren, während du im Gefängnis bist!</red>"));
    }

    public Component jailNoCommands() {
        return mm.deserialize(raw("jailNoCommands", "<red>Du kannst keine Befehle im Gefängnis nutzen!</red>"));
    }

    // Warning Messages
    public Component warnedMessage(String reason) {
        String base = raw("warnedMessage", "<yellow>Du wurdest verwarnt!\nGrund: {reason}</yellow>");
        return mm.deserialize(base.replace("{reason}", reason));
    }

    // Command Usage Messages
    public Component unknownCommand() {
        return mm.deserialize(raw("unknownCommand", "<red>Unbekannter Befehl.</red>"));
    }

    public Component muteUsage() {
        return mm.deserialize(raw("muteUsage", "Nutzung: /mute <Spieler> <Dauer> [Grund]"));
    }

    public Component muteExamples() {
        return mm.deserialize(raw("muteExamples", "Beispiele: /mute Player 1h, /mute Player permanent Spam"));
    }

    public Component mutedSuccess(String victim, String duration) {
        String base = raw("mutedSuccess", "{victim} wurde für {duration} gemutet.");
        return mm.deserialize(base
                .replace("{victim}", victim)
                .replace("{duration}", duration));
    }

    public Component muteCancelled() {
        return mm.deserialize(raw("muteCancelled", "Mute wurde durch ein Event abgebrochen."));
    }

    public Component unmuteUsage() {
        return mm.deserialize(raw("unmuteUsage", "Nutzung: /unmute <Spieler> [Grund]"));
    }

    public Component unmutedSuccess(String player) {
        String base = raw("unmutedSuccess", "{player} wurde entmutet.");
        return mm.deserialize(base.replace("{player}", player));
    }

    public Component jailNotSet() {
        return mm.deserialize(raw("jailNotSet", "Jail-Position ist nicht gesetzt! Nutze /setjail"));
    }

    public Component jailUsage() {
        return mm.deserialize(raw("jailUsage", "Nutzung: /jail <Spieler> <Dauer> [Grund]"));
    }

    public Component jailExamples() {
        return mm.deserialize(raw("jailExamples", "Beispiele: /jail Player 30m, /jail Player permanent Griefing"));
    }

    public Component jailedSuccess(String victim, String duration) {
        String base = raw("jailedSuccess", "{victim} wurde für {duration} eingesperrt.");
        return mm.deserialize(base
                .replace("{victim}", victim)
                .replace("{duration}", duration));
    }

    public Component jailCancelled() {
        return mm.deserialize(raw("jailCancelled", "Jail wurde durch ein Event abgebrochen."));
    }

    public Component unjailUsage() {
        return mm.deserialize(raw("unjailUsage", "Nutzung: /unjail <Spieler> [Grund]"));
    }

    public Component playerNotOnline() {
        return mm.deserialize(raw("playerNotOnline", "Spieler nicht online."));
    }

    public Component unjailedSuccess(String player) {
        String base = raw("unjailedSuccess", "{player} wurde freigelassen.");
        return mm.deserialize(base.replace("{player}", player));
    }

    public Component warnUsage() {
        return mm.deserialize(raw("warnUsage", "Nutzung: /warn <Spieler> <Grund>"));
    }

    public Component warnedSuccess(String victim) {
        String base = raw("warnedSuccess", "{victim} wurde verwarnt.");
        return mm.deserialize(base.replace("{victim}", victim));
    }

    public Component warnCount(long count, int threshold) {
        String base = raw("warnCount", "Du hast jetzt {count}/{threshold} Verwarnungen.");
        return mm.deserialize(base
                .replace("{count}", String.valueOf(count))
                .replace("{threshold}", String.valueOf(threshold)));
    }

    public Component jailLocationSet() {
        return mm.deserialize(raw("jailLocationSet", "Jail-Position gesetzt!"));
    }

    public Component bhUsage() {
        return mm.deserialize(raw("bhUsage", "Nutzung: /banhammer [give|reload|pack|history|unban|stats|appeals|approve|deny]"));
    }

    public Component giveUsage() {
        return mm.deserialize(raw("giveUsage", "Nutzung: /banhammer give <Spieler>"));
    }

    public Component resourcepackSent() {
        return mm.deserialize(raw("resourcepackSent", "Resourcepack gesendet."));
    }

    public Component historyUsage() {
        return mm.deserialize(raw("historyUsage", "Nutzung: /bh history <Spieler> [Seite]"));
    }

    public Component unbanUsage() {
        return mm.deserialize(raw("unbanUsage", "Nutzung: /bh unban <Spieler> [Grund]"));
    }

    public Component statsUsage() {
        return mm.deserialize(raw("statsUsage", "Nutzung: /bh stats <Spieler>"));
    }

    public Component approveUsage() {
        return mm.deserialize(raw("approveUsage", "Nutzung: /bh approve <ID> [Antwort]"));
    }

    public Component denyUsage() {
        return mm.deserialize(raw("denyUsage", "Nutzung: /bh deny <ID> [Antwort]"));
    }

    public Component appealAlreadyProcessed() {
        return mm.deserialize(raw("appealAlreadyProcessed", "Dieser Appeal wurde bereits bearbeitet."));
    }

    public Component appealsDisabled() {
        return mm.deserialize(raw("appealsDisabled", "Appeals sind auf diesem Server deaktiviert."));
    }

    public Component appealUsage() {
        return mm.deserialize(raw("appealUsage", "Nutzung: /appeal <Text>"));
    }
}
