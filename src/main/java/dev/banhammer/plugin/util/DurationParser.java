package dev.banhammer.plugin.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing duration strings.
 * Supports multiple formats:
 * <ul>
 *   <li>Custom format: {@code "7d"}, {@code "1h30m"}, {@code "2d12h"}, {@code "1w"}, {@code "3mo"}</li>
 *   <li>ISO-8601 format: {@code "PT24H"}, {@code "P7D"}</li>
 *   <li>Keywords: {@code "permanent"}, {@code "perm"}, {@code "forever"}</li>
 * </ul>
 *
 * <p><b>Important:</b> parsing distinguishes between "explicitly permanent" and
 * "could not be understood". A typo such as {@code "1woche"} yields
 * {@link Result#isInvalid()} rather than silently becoming a permanent punishment.
 *
 * @since 3.0.0
 */
public final class DurationParser {

    private DurationParser() {
        // Utility class - prevent instantiation
    }

    /** Upper bound for a temporary punishment; anything longer should be permanent. */
    public static final Duration MAX_DURATION = Duration.ofDays(365L * 100L);

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;
    private static final long SECONDS_PER_DAY = 86400L;
    private static final long SECONDS_PER_WEEK = 7L * SECONDS_PER_DAY;
    private static final long SECONDS_PER_MONTH = 30L * SECONDS_PER_DAY;
    private static final long SECONDS_PER_YEAR = 365L * SECONDS_PER_DAY;

    /**
     * A single {@code <number><unit>} pair. {@code mo} must be listed before {@code m}
     * so that "3mo" is read as months rather than "3m" followed by a stray "o".
     */
    private static final Pattern TOKEN = Pattern.compile("(\\d{1,18})\\s*(mo|[ywdhms])");

    /** The whole string must consist of nothing but such pairs. */
    private static final Pattern FULL = Pattern.compile("(?:\\d{1,18}\\s*(?:mo|[ywdhms])\\s*)+");

    /**
     * Outcome of a parse attempt.
     */
    public enum Status {
        /** A concrete, positive duration was parsed. */
        DURATION,
        /** The input explicitly asked for a permanent punishment. */
        PERMANENT,
        /** The input could not be understood. */
        INVALID
    }

    /**
     * Result of {@link DurationParser#parse(String)}.
     *
     * @param status   what kind of result this is
     * @param duration the parsed duration, or {@code null} for permanent/invalid
     */
    public record Result(Status status, Duration duration) {

        private static final Result PERMANENT = new Result(Status.PERMANENT, null);
        private static final Result INVALID = new Result(Status.INVALID, null);

        static Result permanent() {
            return PERMANENT;
        }

        static Result invalid() {
            return INVALID;
        }

        static Result of(Duration duration) {
            return new Result(Status.DURATION, duration);
        }

        /** @return true if the input was understood (either a duration or "permanent") */
        public boolean isValid() {
            return status != Status.INVALID;
        }

        /** @return true if the input could not be understood */
        public boolean isInvalid() {
            return status == Status.INVALID;
        }

        /** @return true if the input explicitly requested a permanent punishment */
        public boolean isPermanent() {
            return status == Status.PERMANENT;
        }

        /**
         * Returns the duration to hand to the punishment API, where {@code null} means permanent.
         *
         * @return the duration, or {@code null} if permanent
         * @throws IllegalStateException if this result is {@link Status#INVALID}
         */
        public Duration orNullForPermanent() {
            if (status == Status.INVALID) {
                throw new IllegalStateException("Cannot use an invalid duration result");
            }
            return duration;
        }
    }

    /**
     * Parses a duration string.
     *
     * @param durationStr the duration string to parse
     * @return the parse result; never {@code null}
     */
    public static Result parse(String durationStr) {
        if (durationStr == null) {
            return Result.invalid();
        }

        String s = durationStr.trim();
        if (s.isEmpty()) {
            return Result.invalid();
        }

        String lower = s.toLowerCase(Locale.ROOT);

        if (lower.equals("permanent") || lower.equals("perm") || lower.equals("forever") || lower.equals("never")) {
            return Result.permanent();
        }

        // ISO-8601 ("PT24H", "P7D"); Duration.parse itself rejects malformed input.
        if (lower.startsWith("p")) {
            try {
                Duration parsed = Duration.parse(s.toUpperCase(Locale.ROOT));
                return clamp(parsed);
            } catch (Exception e) {
                return Result.invalid();
            }
        }

        // The entire string must be made up of <number><unit> pairs - no leftovers,
        // so "1woche", "abc" and "-5m" are rejected instead of being misread.
        String compact = lower.replace(" ", "");
        if (!FULL.matcher(compact).matches()) {
            return Result.invalid();
        }

        long totalSeconds = 0L;
        Matcher matcher = TOKEN.matcher(compact);
        while (matcher.find()) {
            long value;
            try {
                value = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return Result.invalid();
            }

            long unitSeconds = switch (matcher.group(2)) {
                case "y" -> SECONDS_PER_YEAR;
                case "mo" -> SECONDS_PER_MONTH;
                case "w" -> SECONDS_PER_WEEK;
                case "d" -> SECONDS_PER_DAY;
                case "h" -> SECONDS_PER_HOUR;
                case "m" -> SECONDS_PER_MINUTE;
                case "s" -> 1L;
                default -> 0L;
            };

            try {
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(value, unitSeconds));
            } catch (ArithmeticException e) {
                // Overflow - far beyond MAX_DURATION anyway.
                return Result.invalid();
            }

            if (totalSeconds > MAX_DURATION.getSeconds()) {
                return Result.invalid();
            }
        }

        if (totalSeconds <= 0L) {
            // "0m" is not a meaningful punishment length.
            return Result.invalid();
        }

        return Result.of(Duration.ofSeconds(totalSeconds));
    }

    private static Result clamp(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return Result.invalid();
        }
        if (duration.compareTo(MAX_DURATION) > 0) {
            return Result.invalid();
        }
        return Result.of(duration);
    }

    /**
     * Convenience check used by configuration validation.
     *
     * @param durationStr the duration string to validate
     * @return true if the string is either a valid duration or an explicit "permanent"
     */
    public static boolean isValid(String durationStr) {
        return parse(durationStr).isValid();
    }

    /**
     * Formats a duration into a human-readable string.
     *
     * @param duration the duration to format, or {@code null} for permanent
     * @return human-readable string (e.g. {@code "7d 12h 30m"})
     */
    public static String formatHuman(Duration duration) {
        if (duration == null) {
            return "permanent";
        }

        long totalSeconds = Math.max(0L, duration.getSeconds());
        long days = totalSeconds / SECONDS_PER_DAY;
        long hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d");
        if (hours > 0) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(hours).append("h");
        }
        if (minutes > 0) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(minutes).append("m");
        }
        if (seconds > 0 && sb.isEmpty()) {
            sb.append(seconds).append("s");
        }

        return sb.isEmpty() ? "0s" : sb.toString();
    }

    /**
     * Formats a duration with parentheses for display.
     *
     * @param duration the duration to format
     * @return formatted string like {@code " (7d 12h)"}, or an empty string if permanent
     */
    public static String formatDisplay(Duration duration) {
        if (duration == null || duration.isZero()) {
            return "";
        }
        return " (" + formatHuman(duration) + ")";
    }
}
