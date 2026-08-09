package dev.banhammer.plugin.util;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;
import org.bukkit.BanList;
import org.bukkit.Bukkit;

import java.util.Date;
import java.util.UUID;

/**
 * Thin wrapper around Bukkit's ban lists that bans by <em>profile</em> (UUID + name) rather
 * than by name alone.
 *
 * <h2>Why UUIDs</h2>
 * A name ban is attached to a string: the player changes their name and walks back in, while
 * whoever picks up the freed name inherits the ban. Profile bans key on the account.
 *
 * <h2>Legacy entries</h2>
 * Both views write to the same {@code banned-players.json}, so no migration is required.
 * Pardoning still clears the name entry as well, because bans created by older BanHammer
 * versions - or by vanilla {@code /ban} and other plugins - may only carry a name.
 *
 * <p>All methods touch {@code BanList}, which is an unsynchronized map backed by a JSON file:
 * call them from the main/global thread only.
 *
 * @since 4.1.0
 */
public final class BanLists {

    private BanLists() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The profile ban list seen through its generic supertype.
     *
     * <p>Obtained through the modern {@link BanListType} lookup rather than the deprecated
     * {@code BanList.Type} enum. The result is narrowed to {@code BanList<PlayerProfile>}
     * because {@code ProfileBanList} adds overloads taking
     * {@code org.bukkit.profile.PlayerProfile} alongside the inherited ones taking Paper's
     * subtype, which would make every call ambiguous.
     */
    private static BanList<PlayerProfile> profiles() {
        return Bukkit.getBanList(BanListType.PROFILE);
    }

    /**
     * The legacy name ban list, typed with a wildcard.
     *
     * <p>As {@code BanList<String>} its inherited {@code pardon(T)} and the explicit
     * {@code pardon(String)} collapse into the same signature and every call is ambiguous.
     * A wildcard makes {@code pardon(T)} inapplicable, leaving the String overload.
     */
    @SuppressWarnings("deprecation")
    private static BanList<?> names() {
        return Bukkit.getBanList(BanList.Type.NAME);
    }

    private static PlayerProfile profile(UUID uuid, String name) {
        return Bukkit.createProfile(uuid, name);
    }

    /**
     * Bans an account.
     *
     * @param uuid    the player's UUID
     * @param name    the player's current name, stored for readability
     * @param reason  the ban reason
     * @param expires when the ban expires, or {@code null} for permanent
     * @param source  who issued the ban
     */
    public static void ban(UUID uuid, String name, String reason, Date expires, String source) {
        profiles().addBan(profile(uuid, name), reason, expires, source);
    }

    /**
     * Lifts a ban for an account, covering legacy name-only entries too.
     *
     * @param uuid the player's UUID, or {@code null} if unknown
     * @param name the player's name
     * @return true if the player was banned before this call
     */
    @SuppressWarnings("deprecation")
    public static boolean pardon(UUID uuid, String name) {
        boolean wasBanned = isBanned(uuid, name);

        if (uuid != null) {
            profiles().pardon(profile(uuid, name));
        }
        if (name != null) {
            names().pardon(name);
        }

        return wasBanned;
    }

    /**
     * @param uuid the player's UUID, or {@code null} if unknown
     * @param name the player's name, or {@code null} if unknown
     * @return true if either the profile or the legacy name entry is banned
     */
    @SuppressWarnings("deprecation")
    public static boolean isBanned(UUID uuid, String name) {
        if (uuid != null && profiles().isBanned(profile(uuid, name))) {
            return true;
        }
        return name != null && names().isBanned(name);
    }

    /**
     * The IP ban list, typed with a wildcard for the same overload reason as {@link #names()}.
     */
    private static BanList<?> ips() {
        return Bukkit.getBanList(BanListType.IP);
    }

    /**
     * Bans an IP address.
     */
    @SuppressWarnings("deprecation")
    public static void banIp(String ip, String reason, Date expires, String source) {
        ips().addBan(ip, reason, expires, source);
    }

    /**
     * Lifts an IP ban.
     */
    @SuppressWarnings("deprecation")
    public static void pardonIp(String ip) {
        ips().pardon(ip);
    }
}
