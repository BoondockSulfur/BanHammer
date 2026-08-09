package dev.banhammer.plugin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationParserTest {

    @Test
    @DisplayName("keywords mean permanent")
    void keywords() {
        for (String keyword : new String[]{"permanent", "PERM", "forever", "never"}) {
            DurationParser.Result result = DurationParser.parse(keyword);
            assertTrue(result.isPermanent(), keyword);
            assertNullDuration(result);
        }
    }

    @Test
    @DisplayName("common formats parse to the expected length")
    void formats() {
        assertEquals(Duration.ofDays(7), parsed("7d"));
        assertEquals(Duration.ofMinutes(90), parsed("1h30m"));
        assertEquals(Duration.ofDays(14), parsed("2w"));
        assertEquals(Duration.ofDays(90), parsed("3mo"));
        assertEquals(Duration.ofDays(365), parsed("1y"));
        assertEquals(Duration.ofSeconds(45), parsed("45s"));
        assertEquals(Duration.ofHours(24), parsed("PT24H"));
        assertEquals(Duration.ofDays(2).plusHours(12), parsed("2d12h"));
        assertEquals(Duration.ofMinutes(90), parsed("1h 30m"), "whitespace is tolerated");
    }

    /**
     * Each of these used to be read as "permanent", which is how a typo in a mute command
     * turned into a permanent punishment.
     */
    @ParameterizedTest(name = "\"{0}\" is rejected, not treated as permanent")
    @NullSource
    @ValueSource(strings = {"", "   ", "5", "abc", "1woche", "1month", "-5m", "0m", "0s",
            "7dd", "d7", "9999999999999999d", "PT", "P7X"})
    void rejectsUnreadableInput(String input) {
        DurationParser.Result result = DurationParser.parse(input);
        assertTrue(result.isInvalid(), "expected INVALID for: " + input);
        assertFalse(result.isPermanent(), "must not be mistaken for permanent: " + input);
        assertThrows(IllegalStateException.class, result::orNullForPermanent);
    }

    @Test
    @DisplayName("negative values are rejected rather than made positive")
    void negativesAreRejected() {
        // The old parser scanned backwards over digits only, so "-5d" yielded +5 days and the
        // isNegative() guards downstream could never fire.
        assertTrue(DurationParser.parse("-5d").isInvalid());
        assertFalse(DurationParser.isValid("-5d"));
    }

    @Test
    @DisplayName("durations beyond the cap are rejected instead of overflowing")
    void overflow() {
        assertTrue(DurationParser.parse("99999y").isInvalid());
        assertTrue(DurationParser.parse("500y").isInvalid());
        assertEquals(Duration.ofDays(365 * 50L), parsed("50y"));
    }

    @Test
    void isValidAcceptsDurationsAndPermanent() {
        assertTrue(DurationParser.isValid("1w"));
        assertTrue(DurationParser.isValid("permanent"));
        assertFalse(DurationParser.isValid("1woche"));
        assertFalse(DurationParser.isValid(null));
    }

    @Test
    void formatting() {
        assertEquals("permanent", DurationParser.formatHuman(null));
        assertEquals("1h 30m", DurationParser.formatHuman(Duration.ofMinutes(90)));
        assertEquals("7d", DurationParser.formatHuman(Duration.ofDays(7)));
        assertEquals("45s", DurationParser.formatHuman(Duration.ofSeconds(45)));
        assertEquals("0s", DurationParser.formatHuman(Duration.ZERO));
        assertEquals("", DurationParser.formatDisplay(null));
        assertEquals(" (7d)", DurationParser.formatDisplay(Duration.ofDays(7)));
    }

    private static Duration parsed(String input) {
        DurationParser.Result result = DurationParser.parse(input);
        assertTrue(result.isValid(), "expected a valid duration for: " + input);
        return result.orNullForPermanent();
    }

    private static void assertNullDuration(DurationParser.Result result) {
        assertEquals(null, result.orNullForPermanent());
    }
}
