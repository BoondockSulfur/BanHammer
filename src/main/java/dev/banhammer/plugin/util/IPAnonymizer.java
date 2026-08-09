package dev.banhammer.plugin.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * IP address anonymization utility.
 *
 * <p><b>Scope of the guarantees.</b> {@link AnonymizationLevel#PARTIAL} and
 * {@link AnonymizationLevel#FULL} discard information irreversibly.
 * {@link AnonymizationLevel#HASH} is <em>pseudonymisation</em>, not anonymisation: it is a
 * keyed digest, so anybody who knows the salt can enumerate the (only 2^32) IPv4 addresses
 * and recover the original. Treat the salt as a secret of the same rank as the database
 * password, and do not rely on HASH as a GDPR anonymisation measure on its own.
 *
 * @since 3.0.0
 */
public final class IPAnonymizer {

    private IPAnonymizer() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
                    + "(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

    /**
     * Normalizes an address before anonymization.
     *
     * <p>Dual-stack servers report IPv4 clients as IPv4-mapped IPv6 ({@code ::ffff:1.2.3.4}).
     * Without unwrapping those, every IPv4 client would collapse to the same
     * {@code 0000:0000:0000::} value and IP correlation would be worthless.
     *
     * @param ip the raw address
     * @return the address in its most specific form, or {@code null} if blank
     */
    public static String normalize(String ip) {
        if (ip == null) {
            return null;
        }
        String trimmed = ip.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        // Strip a zone index ("fe80::1%eth0") and brackets ("[::1]").
        int zone = trimmed.indexOf('%');
        if (zone >= 0) {
            trimmed = trimmed.substring(0, zone);
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);

        // IPv4-mapped / IPv4-compatible IPv6 in dotted form: ::ffff:1.2.3.4
        int lastColon = lower.lastIndexOf(':');
        if (lastColon >= 0 && lower.indexOf('.') > lastColon) {
            String tail = lower.substring(lastColon + 1);
            if (IPV4.matcher(tail).matches()) {
                return tail;
            }
        }

        // IPv4-mapped IPv6 in hex form: ::ffff:c0a8:0105
        if (lower.startsWith("::ffff:")) {
            String tail = lower.substring("::ffff:".length());
            String[] words = tail.split(":");
            if (words.length == 2) {
                try {
                    int high = Integer.parseInt(words[0], 16);
                    int low = Integer.parseInt(words[1], 16);
                    return ((high >> 8) & 0xFF) + "." + (high & 0xFF) + "."
                            + ((low >> 8) & 0xFF) + "." + (low & 0xFF);
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }

        return trimmed;
    }

    /**
     * Checks whether a string is a literal IP address.
     *
     * <p>Deliberately avoids {@code InetAddress.getByName}, which performs a blocking DNS
     * lookup for anything that is not a literal - not something to do while holding the
     * server thread, and never appropriate for a value read back out of the database.
     *
     * @param value the value to test
     * @return true if the value is a literal IPv4 or IPv6 address
     */
    public static boolean isLiteralIp(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return false;
        }
        return normalized.contains(":")
                ? expandIPv6(normalized) != null
                : IPV4.matcher(normalized).matches();
    }

    /**
     * Anonymizes an IPv4 address by removing the last octet.
     * Example: {@code 192.168.1.123} to {@code 192.168.1.0}
     *
     * @param ip the IP address to anonymize
     * @return anonymized IP address
     */
    public static String anonymizeIPv4(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        if (!IPV4.matcher(ip).matches()) {
            return ip; // Not a valid IPv4 address - leave untouched.
        }

        String[] parts = ip.split("\\.");
        return parts[0] + "." + parts[1] + "." + parts[2] + ".0";
    }

    /**
     * Anonymizes an IPv6 address by removing the last 80 bits.
     * Example: {@code 2001:0db8:85a3:0000:0000:8a2e:0370:7334} to {@code 2001:0db8:85a3::}
     *
     * @param ip the IPv6 address to anonymize
     * @return anonymized IPv6 address
     */
    public static String anonymizeIPv6(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        String[] parts = expandIPv6(ip);
        if (parts == null) {
            return ip; // Not a valid IPv6 address
        }

        // Keep first 3 groups (48 bits), zero out the rest
        return parts[0] + ":" + parts[1] + ":" + parts[2] + "::";
    }

    /**
     * Anonymizes an IP address (IPv4 or IPv6) by detecting the type.
     *
     * @param ip the IP address to anonymize
     * @return anonymized IP address
     */
    public static String anonymize(String ip) {
        String normalized = normalize(ip);
        if (normalized == null) {
            return null;
        }

        return normalized.contains(":") ? anonymizeIPv6(normalized) : anonymizeIPv4(normalized);
    }

    /**
     * Creates a keyed one-way digest of an IP address.
     *
     * <p>Uses HMAC-SHA256 with the salt as the key rather than a bare
     * {@code SHA-256(ip + salt)}: without the key an attacker cannot precompute a table
     * over the address space. See the class documentation for the residual risk.
     *
     * @param ip   the IP address to hash
     * @param salt the secret salt, used as the HMAC key
     * @return Base64-encoded digest (truncated to 128 bits)
     */
    public static String hashIP(String ip, String salt) {
        String normalized = normalize(ip);
        if (normalized == null) {
            return null;
        }

        String key = (salt == null || salt.isEmpty()) ? "banhammer-default-salt" : salt;

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));

            // 128 bits is ample for collision resistance here and keeps the column short.
            byte[] truncated = new byte[16];
            System.arraycopy(digest, 0, truncated, 0, truncated.length);
            return Base64.getEncoder().withoutPadding().encodeToString(truncated);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available on this JVM", e);
        }
    }

    /**
     * Completely masks an IP address with asterisks (maximum anonymization).
     *
     * @param ip the IP address to mask
     * @return fully masked IP address
     */
    public static String maskIP(String ip) {
        String normalized = normalize(ip);
        if (normalized == null) {
            return null;
        }

        return normalized.contains(":")
                ? "****:****:****:****:****:****:****:****"
                : "***.***.***.***";
    }

    /**
     * Anonymizes an IP based on the specified level.
     *
     * @param ip    the IP address to anonymize
     * @param level the anonymization level
     * @param salt  salt for hashing (only used for {@link AnonymizationLevel#HASH})
     * @return anonymized IP address
     */
    public static String anonymize(String ip, AnonymizationLevel level, String salt) {
        String normalized = normalize(ip);
        if (normalized == null) {
            return null;
        }

        return switch (level) {
            case NONE -> normalized;
            case PARTIAL -> anonymize(normalized);
            case HASH -> hashIP(normalized, salt);
            case FULL -> maskIP(normalized);
        };
    }

    /**
     * Resolves the configured anonymization level, tolerating stray whitespace and casing
     * and falling back to {@link AnonymizationLevel#PARTIAL} for unknown values.
     *
     * @param configValue the raw config value
     * @return the resolved level; never {@code null}
     */
    public static AnonymizationLevel levelFromConfig(String configValue) {
        if (configValue == null) {
            return AnonymizationLevel.PARTIAL;
        }
        try {
            return AnonymizationLevel.valueOf(configValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AnonymizationLevel.PARTIAL;
        }
    }

    /**
     * Expands a (possibly compressed) IPv6 address into exactly eight 4-digit groups.
     *
     * @param ip the IPv6 address
     * @return the eight groups, or {@code null} if the address is not valid IPv6
     */
    static String[] expandIPv6(String ip) {
        if (ip == null || !ip.contains(":")) {
            return null;
        }

        String head;
        String tail;
        int doubleColon = ip.indexOf("::");
        if (doubleColon >= 0) {
            if (ip.indexOf("::", doubleColon + 1) >= 0) {
                return null; // "::" may appear at most once
            }
            head = ip.substring(0, doubleColon);
            tail = ip.substring(doubleColon + 2);
        } else {
            head = ip;
            tail = null;
        }

        String[] headGroups = head.isEmpty() ? new String[0] : head.split(":", -1);
        String[] tailGroups = (tail == null || tail.isEmpty()) ? new String[0] : tail.split(":", -1);

        if (tail == null && headGroups.length != 8) {
            return null; // Uncompressed addresses must be complete.
        }
        if (headGroups.length + tailGroups.length > 8) {
            return null;
        }

        String[] expanded = new String[8];
        int index = 0;
        for (String group : headGroups) {
            String padded = pad(group);
            if (padded == null) return null;
            expanded[index++] = padded;
        }
        int fill = 8 - headGroups.length - tailGroups.length;
        for (int i = 0; i < fill; i++) {
            expanded[index++] = "0000";
        }
        for (String group : tailGroups) {
            String padded = pad(group);
            if (padded == null) return null;
            expanded[index++] = padded;
        }

        return expanded;
    }

    private static String pad(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return null;
        }
        for (int i = 0; i < group.length(); i++) {
            if (Character.digit(group.charAt(i), 16) < 0) {
                return null;
            }
        }
        return "0".repeat(4 - group.length()) + group.toLowerCase(Locale.ROOT);
    }

    /**
     * Anonymization level enum.
     */
    public enum AnonymizationLevel {
        /**
         * No anonymization - stores the full IP address.
         * Requires a lawful basis (e.g. explicit consent) under the GDPR.
         */
        NONE,

        /**
         * Partial anonymization - drops the last octet (IPv4) or the last 80 bits (IPv6).
         * Irreversible.
         */
        PARTIAL,

        /**
         * Keyed digest (HMAC-SHA256). Pseudonymisation, not anonymisation: reversible by
         * anyone who knows the salt. Lets you correlate repeat offenders without storing
         * the address in the clear.
         */
        HASH,

        /**
         * Full masking - no identification possible. Irreversible.
         */
        FULL
    }
}
