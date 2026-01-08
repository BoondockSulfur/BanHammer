package dev.banhammer.plugin.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * GDPR-compliant IP address anonymization utility.
 * Provides various methods to anonymize IP addresses for GDPR compliance.
 *
 * @since 3.0.0
 */
public final class IPAnonymizer {

    private IPAnonymizer() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Anonymizes an IPv4 address by removing the last octet.
     * Example: 192.168.1.123 → 192.168.1.0
     *
     * @param ip The IP address to anonymize
     * @return Anonymized IP address
     */
    public static String anonymizeIPv4(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return ip; // Not a valid IPv4 address
        }

        return parts[0] + "." + parts[1] + "." + parts[2] + ".0";
    }

    /**
     * Anonymizes an IPv6 address by removing the last 80 bits.
     * Example: 2001:0db8:85a3:0000:0000:8a2e:0370:7334 → 2001:0db8:85a3::
     *
     * @param ip The IPv6 address to anonymize
     * @return Anonymized IPv6 address
     */
    public static String anonymizeIPv6(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        // Expand IPv6 address
        String expanded = expandIPv6(ip);
        String[] parts = expanded.split(":");

        if (parts.length != 8) {
            return ip; // Not a valid IPv6 address
        }

        // Keep first 3 groups (48 bits), zero out the rest
        return parts[0] + ":" + parts[1] + ":" + parts[2] + "::";
    }

    /**
     * Anonymizes an IP address (IPv4 or IPv6) by detecting the type.
     *
     * @param ip The IP address to anonymize
     * @return Anonymized IP address
     */
    public static String anonymize(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        if (ip.contains(":")) {
            return anonymizeIPv6(ip);
        } else {
            return anonymizeIPv4(ip);
        }
    }

    /**
     * Creates a one-way hash of an IP address for identification
     * without storing the actual IP (GDPR-compliant).
     *
     * @param ip The IP address to hash
     * @param salt A salt for additional security
     * @return Base64-encoded SHA-256 hash
     */
    public static String hashIP(String ip, String salt) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = ip + salt;
            byte[] hash = digest.digest(combined.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Completely masks an IP address with asterisks (maximum anonymization).
     * Example: 192.168.1.123 → ***.***.***.***
     *
     * @param ip The IP address to mask
     * @return Fully masked IP address
     */
    public static String maskIP(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        if (ip.contains(":")) {
            // IPv6
            return "****:****:****:****:****:****:****:****";
        } else {
            // IPv4
            return "***.***.***.***";
        }
    }

    /**
     * Anonymizes an IP based on the specified level.
     *
     * @param ip The IP address to anonymize
     * @param level The anonymization level (PARTIAL, HASH, FULL)
     * @param salt Salt for hashing (only used for HASH level)
     * @return Anonymized IP address
     */
    public static String anonymize(String ip, AnonymizationLevel level, String salt) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }

        return switch (level) {
            case NONE -> ip;
            case PARTIAL -> anonymize(ip);
            case HASH -> hashIP(ip, salt != null ? salt : "default-salt");
            case FULL -> maskIP(ip);
        };
    }

    /**
     * Expands a compressed IPv6 address to its full form.
     *
     * @param ip The IPv6 address (possibly compressed)
     * @return Expanded IPv6 address
     */
    private static String expandIPv6(String ip) {
        if (!ip.contains("::")) {
            return ip;
        }

        // Count existing groups
        String[] parts = ip.split(":");
        int existingGroups = parts.length;
        if (ip.startsWith("::")) existingGroups--;
        if (ip.endsWith("::")) existingGroups--;

        // Calculate missing groups
        int missingGroups = 8 - existingGroups;

        // Build expanded address
        String[] expanded = new String[8];
        int expandedIndex = 0;

        boolean doubleColonFound = false;
        for (String part : parts) {
            if (part.isEmpty() && !doubleColonFound) {
                // This is the :: part
                doubleColonFound = true;
                for (int i = 0; i < missingGroups; i++) {
                    expanded[expandedIndex++] = "0000";
                }
            } else if (!part.isEmpty()) {
                // Pad to 4 digits
                expanded[expandedIndex++] = String.format("%4s", part).replace(' ', '0');
            }
        }

        // Fill remaining with zeros if needed
        while (expandedIndex < 8) {
            expanded[expandedIndex++] = "0000";
        }

        return String.join(":", expanded);
    }

    /**
     * Anonymization level enum.
     */
    public enum AnonymizationLevel {
        /**
         * No anonymization - store full IP address.
         * ⚠️ NOT GDPR-compliant without explicit user consent!
         */
        NONE,

        /**
         * Partial anonymization - remove last octet (IPv4) or last 80 bits (IPv6).
         * GDPR-compliant for legitimate interests.
         */
        PARTIAL,

        /**
         * Hash anonymization - one-way hash with salt.
         * GDPR-compliant, allows identification but not reversal.
         */
        HASH,

        /**
         * Full anonymization - complete masking.
         * Maximum GDPR compliance, no identification possible.
         */
        FULL
    }
}
