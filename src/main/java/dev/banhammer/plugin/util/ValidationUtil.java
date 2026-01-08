package dev.banhammer.plugin.util;

import java.util.List;
import java.util.regex.Pattern;

import static dev.banhammer.plugin.util.Constants.MAX_REASON_LENGTH;

/**
 * Utility class for input validation.
 *
 * @since 3.0.0
 */
public final class ValidationUtil {

    private ValidationUtil() {
        throw new UnsupportedOperationException("Utility class");
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
     * Validates a reason with default settings.
     *
     * @param reason The reason to validate
     * @return ValidationResult
     */
    public static ValidationResult validateReason(String reason) {
        return validateReason(reason, 0, MAX_REASON_LENGTH, false);
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

            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
            filtered = pattern.matcher(filtered).replaceAll("*".repeat(word.length()));
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

        String lowerReason = reason.toLowerCase();
        for (String word : blockedWords) {
            if (word == null || word.isEmpty()) continue;

            if (lowerReason.contains(word.toLowerCase())) {
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
