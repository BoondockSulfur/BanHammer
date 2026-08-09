package dev.banhammer.plugin.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Utility class for input validation.
 *
 * @since 3.0.0
 */
public final class ValidationUtil {

    private ValidationUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Compiled word-boundary patterns, cached because filtering runs on every punishment
     * and re-compiling the whole blocked-word list each time is needless work.
     */
    private static final Map<String, Pattern> WORD_PATTERNS = new ConcurrentHashMap<>();

    private static Pattern wordPattern(String word) {
        return WORD_PATTERNS.computeIfAbsent(word.toLowerCase(Locale.ROOT),
                w -> Pattern.compile("\\b" + Pattern.quote(w) + "\\b", Pattern.CASE_INSENSITIVE));
    }

    /**
     * The reason rules configured for this server, resolved once so every punishment path
     * (ban, kick, mute, jail, warn) applies the same limits and word filter.
     *
     * @param minLength     minimum reason length, 0 for none
     * @param maxLength     maximum reason length
     * @param requireReason whether a reason must be supplied
     * @param filterReasons whether blocked words are masked
     * @param blockedWords  the blocked-word list (may be empty)
     */
    public record ReasonPolicy(int minLength, int maxLength, boolean requireReason,
                               boolean filterReasons, List<String> blockedWords) {

        public ReasonPolicy {
            blockedWords = blockedWords == null ? List.of() : List.copyOf(blockedWords);
        }

        /** Validates a reason against this policy. */
        public ValidationResult validate(String reason) {
            return validateReason(reason, minLength, maxLength, requireReason);
        }

        /** Applies the configured word filter, if enabled. */
        public String filter(String reason) {
            return filterReasons ? filterReason(reason, blockedWords) : reason;
        }
    }

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b((?:https?|ftp)://|www\\.)[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
    );

    private static final Pattern IP_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"
    );

    /**
     * Validates a ban/kick reason.
     *
     * @param reason The reason to validate
     * @param minLength Minimum required length (0 for no minimum)
     * @param maxLength Maximum allowed length
     * @param requireReason Whether a reason is required
     * @return ValidationResult with success status and error message
     */
    public static ValidationResult validateReason(String reason, int minLength, int maxLength, boolean requireReason) {
        if (reason == null || reason.trim().isEmpty()) {
            if (requireReason) {
                return new ValidationResult(false, "A reason is required");
            }
            return new ValidationResult(true, null);
        }

        String trimmed = reason.trim();

        if (trimmed.length() < minLength) {
            return new ValidationResult(false, String.format("Reason must be at least %d characters", minLength));
        }

        if (trimmed.length() > maxLength) {
            return new ValidationResult(false, String.format("Reason cannot exceed %d characters", maxLength));
        }

        return new ValidationResult(true, null);
    }

    /**
     * Filters offensive words from a reason.
     *
     * @param reason The reason to filter
     * @param blockedWords List of blocked words
     * @return Filtered reason with blocked words replaced with asterisks
     */
    public static String filterReason(String reason, List<String> blockedWords) {
        if (reason == null || blockedWords == null || blockedWords.isEmpty()) {
            return reason;
        }

        String filtered = reason;
        for (String word : blockedWords) {
            if (word == null || word.isEmpty()) continue;

            filtered = wordPattern(word).matcher(filtered).replaceAll("*".repeat(word.length()));
        }

        return filtered;
    }

    /**
     * Checks if a reason contains blocked words.
     *
     * @param reason The reason to check
     * @param blockedWords List of blocked words
     * @return true if reason contains blocked words
     */
    public static boolean containsBlockedWords(String reason, List<String> blockedWords) {
        if (reason == null || blockedWords == null || blockedWords.isEmpty()) {
            return false;
        }

        for (String word : blockedWords) {
            if (word == null || word.isEmpty()) continue;

            // Same word-boundary semantics as filterReason, so "contains" and "filter"
            // can never disagree about whether a reason is clean.
            if (wordPattern(word).matcher(reason).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sanitizes input by removing potentially dangerous content.
     *
     * @param input The input to sanitize
     * @param removeUrls Whether to remove URLs
     * @param removeIps Whether to remove IP addresses
     * @return Sanitized input
     */
    public static String sanitizeInput(String input, boolean removeUrls, boolean removeIps) {
        if (input == null) return null;

        String sanitized = input;

        if (removeUrls) {
            sanitized = URL_PATTERN.matcher(sanitized).replaceAll("[URL removed]");
        }

        if (removeIps) {
            sanitized = IP_PATTERN.matcher(sanitized).replaceAll("[IP removed]");
        }

        return sanitized;
    }

    /**
     * Validates a player name.
     *
     * @param playerName The player name to validate
     * @return ValidationResult
     */
    public static ValidationResult validatePlayerName(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return new ValidationResult(false, "Player name cannot be empty");
        }

        if (playerName.length() < 3 || playerName.length() > 16) {
            return new ValidationResult(false, "Player name must be between 3 and 16 characters");
        }

        if (!playerName.matches("^[a-zA-Z0-9_]+$")) {
            return new ValidationResult(false, "Player name contains invalid characters");
        }

        return new ValidationResult(true, null);
    }

    /**
     * Validates an appeal text.
     *
     * @param appealText The appeal text to validate
     * @param minLength Minimum required length
     * @param maxLength Maximum allowed length
     * @return ValidationResult
     */
    public static ValidationResult validateAppeal(String appealText, int minLength, int maxLength) {
        if (appealText == null || appealText.trim().isEmpty()) {
            return new ValidationResult(false, "Appeal text cannot be empty");
        }

        String trimmed = appealText.trim();

        if (trimmed.length() < minLength) {
            return new ValidationResult(false, String.format("Appeal must be at least %d characters", minLength));
        }

        if (trimmed.length() > maxLength) {
            return new ValidationResult(false, String.format("Appeal cannot exceed %d characters", maxLength));
        }

        return new ValidationResult(true, null);
    }

    /**
     * Result of a validation operation.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        public ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getErrorMessageOrDefault(String defaultMessage) {
            return errorMessage != null ? errorMessage : defaultMessage;
        }
    }
}
