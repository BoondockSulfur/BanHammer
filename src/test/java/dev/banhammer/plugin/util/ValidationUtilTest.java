package dev.banhammer.plugin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationUtilTest {

    @Test
    void reasonLengthBounds() {
        assertTrue(ValidationUtil.validateReason("griefing", 0, 500, false).isValid());
        assertFalse(ValidationUtil.validateReason("x".repeat(501), 0, 500, false).isValid());
        assertFalse(ValidationUtil.validateReason("ab", 3, 500, false).isValid());
    }

    @Test
    @DisplayName("requireReason decides whether an empty reason is acceptable")
    void requireReason() {
        assertTrue(ValidationUtil.validateReason(null, 0, 500, false).isValid());
        assertTrue(ValidationUtil.validateReason("  ", 0, 500, false).isValid());
        assertFalse(ValidationUtil.validateReason(null, 0, 500, true).isValid());
        assertFalse(ValidationUtil.validateReason("  ", 0, 500, true).isValid());
    }

    @Test
    @DisplayName("filtering and detection agree on what counts as a blocked word")
    void filterAndDetectAgree() {
        List<String> blocked = List.of("damn");

        // Both use word boundaries; previously detection used a plain substring match, so
        // "condamnation" was reported as containing a blocked word but never filtered.
        assertTrue(ValidationUtil.containsBlockedWords("oh damn", blocked));
        assertEquals("oh ****", ValidationUtil.filterReason("oh damn", blocked));

        assertFalse(ValidationUtil.containsBlockedWords("condamnation", blocked));
        assertEquals("condamnation", ValidationUtil.filterReason("condamnation", blocked));
    }

    @Test
    void filteringIsCaseInsensitive() {
        assertEquals("**** it", ValidationUtil.filterReason("DAMN it", List.of("damn")));
    }

    @Test
    void filteringToleratesEmptyInput() {
        assertEquals("hello", ValidationUtil.filterReason("hello", List.of()));
        assertEquals("hello", ValidationUtil.filterReason("hello", null));
        assertEquals(null, ValidationUtil.filterReason(null, List.of("x")));
        assertFalse(ValidationUtil.containsBlockedWords(null, List.of("x")));
    }

    @Test
    @DisplayName("the reason policy applies limits and filter together")
    void reasonPolicy() {
        ValidationUtil.ReasonPolicy policy =
                new ValidationUtil.ReasonPolicy(0, 20, true, true, List.of("badword"));

        assertTrue(policy.validate("fine").isValid());
        assertFalse(policy.validate(null).isValid(), "requireReason is honoured");
        assertFalse(policy.validate("x".repeat(21)).isValid());
        assertEquals("say *******", policy.filter("say badword"));

        ValidationUtil.ReasonPolicy unfiltered =
                new ValidationUtil.ReasonPolicy(0, 20, false, false, List.of("badword"));
        assertEquals("say badword", unfiltered.filter("say badword"));
    }

    @Test
    void appealValidation() {
        assertTrue(ValidationUtil.validateAppeal("I am very sorry about this", 20, 500).isValid());
        assertFalse(ValidationUtil.validateAppeal("sorry", 20, 500).isValid());
        assertFalse(ValidationUtil.validateAppeal("", 20, 500).isValid());
        assertFalse(ValidationUtil.validateAppeal(null, 20, 500).isValid());
        assertFalse(ValidationUtil.validateAppeal("x".repeat(501), 20, 500).isValid());
    }
}
