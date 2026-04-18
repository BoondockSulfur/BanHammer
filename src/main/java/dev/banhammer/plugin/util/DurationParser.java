package dev.banhammer.plugin.util;

import java.time.Duration;
import java.util.Optional;

/**
 * Utility class for parsing duration strings.
 * Supports multiple formats:
 * - Custom format: "7d", "1h30m", "2d12h", etc.
 * - ISO-8601 format: "PT24H", "P7D", etc.
 * - Keywords: "permanent", "perm"
 *
 * @since 3.0.0
 */
public final class DurationParser {

    private DurationParser() {
        // Utility class - prevent instantiation
    }

    /**
     * Parses a duration string.
     *
     * @param durationStr The duration string to parse
     * @return The parsed Duration, or null if permanent/invalid
     */
    public static Duration parse(String durationStr) {
        if (durationStr == null || durationStr.trim().isEmpty()) {
            return null;
        }

        String s = durationStr.trim();

        // Explicit handling for "permanent" keyword
        if (s.equalsIgnoreCase("permanent") || s.equalsIgnoreCase("perm")) {
            return null; // null = permanent
        }

        // Try ISO-8601 format (case-insensitive)
        if (s.toUpperCase().startsWith("P")) {
            try {
                return Duration.parse(s.toUpperCase());
            } catch (Exception e) {
                // Fall through to custom parsing
            }
        }

        // Custom format: "7d", "1h30m", "2d12h", etc.
        String tmp = s.toLowerCase();
        long days = extractTime(tmp, "d");
        long hours = extractTime(tmp, "h");
        long minutes = extractTime(tmp, "m");
        long seconds = extractTime(tmp, "s");

        // Check if any time unit was found
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            return null; // Invalid format, default to permanent
        }

        Duration duration = Duration.ZERO;
        if (days > 0) duration = duration.plusDays(days);
        if (hours > 0) duration = duration.plusHours(hours);
        if (minutes > 0) duration = duration.plusMinutes(minutes);
        if (seconds > 0) duration = duration.plusSeconds(seconds);

        return duration;
    }

    /**
     * Parses a duration string with validation.
     *
     * @param durationStr The duration string to parse
     * @return Optional containing the parsed Duration, or empty if invalid
     */
    public static Optional<Duration> parseValidated(String durationStr) {
        try {
            Duration duration = parse(durationStr);
            // null is valid (permanent), but negative durations are not
            if (duration != null && duration.isNegative()) {
                return Optional.empty();
            }
            return Optional.ofNullable(duration);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Formats a duration into a human-readable string.
     *
     * @param duration The duration to format
     * @return Human-readable string (e.g., "7d 12h 30m")
     */
    public static String formatHuman(Duration duration) {
        if (duration == null) {
            return "permanent";
        }

        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d");
        if (hours > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(hours).append("h");
        }
        if (minutes > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(minutes).append("m");
        }
        if (seconds > 0 && sb.length() == 0) {
            sb.append(seconds).append("s");
        }

        return sb.length() == 0 ? "0s" : sb.toString();
    }

    /**
     * Formats a duration with parentheses for display.
     *
     * @param duration The duration to format
     * @return Formatted string like " (7d 12h)" or "" if permanent
     */
    public static String formatDisplay(Duration duration) {
        if (duration == null || duration.isZero()) {
            return "";
        }
        return " (" + formatHuman(duration) + ")";
    }

    /**
     * Extracts a time value for a specific unit from a duration string.
     *
     * @param str The duration string
     * @param unit The unit to extract ("d", "h", "m", "s")
     * @return The extracted value, or 0 if not found
     */
    private static long extractTime(String str, String unit) {
        int index = str.toLowerCase().indexOf(unit);
        if (index < 1) return 0;

        int start = index - 1;
        while (start >= 0 && Character.isDigit(str.charAt(start))) {
            start--;
        }

        try {
            return Long.parseLong(str.substring(start + 1, index));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Validates if a duration string is valid.
     *
     * @param durationStr The duration string to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValid(String durationStr) {
        if (durationStr == null || durationStr.trim().isEmpty()) {
            return true; // null/empty is valid (permanent)
        }

        try {
            Duration duration = parse(durationStr);
            return duration == null || !duration.isNegative();
        } catch (Exception e) {
            return false;
        }
    }
}
