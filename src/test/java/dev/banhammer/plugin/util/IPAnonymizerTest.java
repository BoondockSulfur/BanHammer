package dev.banhammer.plugin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IPAnonymizerTest {

    @Test
    void partialIPv4DropsLastOctet() {
        assertEquals("192.168.1.0", IPAnonymizer.anonymize("192.168.1.123"));
        assertEquals("10.0.0.0", IPAnonymizer.anonymize("10.0.0.255"));
    }

    @Test
    @DisplayName("IPv4-mapped IPv6 is unwrapped before anonymizing")
    void ipv4MappedAddresses() {
        // A dual-stack server reports IPv4 clients like this. Treating them as IPv6 collapsed
        // every such client to the same value, making IP correlation worthless.
        assertEquals("192.168.1.0", IPAnonymizer.anonymize("::ffff:192.168.1.123"));
        assertEquals("192.168.1.0", IPAnonymizer.anonymize("::ffff:c0a8:017b"));
        assertEquals("192.168.1.123", IPAnonymizer.normalize("::ffff:192.168.1.123"));
    }

    @Test
    void partialIPv6KeepsFirstThreeGroups() {
        assertEquals("2001:0db8:85a3::", IPAnonymizer.anonymize("2001:db8:85a3::8a2e:370:7334"));
        assertEquals("2001:0db8:85a3::", IPAnonymizer.anonymize("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
    }

    @Test
    @DisplayName("compressed IPv6 expands without an off-by-one")
    void expandIPv6() {
        assertArrayEqualsAsString(
                new String[]{"2001", "0db8", "0000", "0000", "0000", "0000", "0000", "0001"},
                IPAnonymizer.expandIPv6("2001:db8::1"));
        assertArrayEqualsAsString(
                new String[]{"0000", "0000", "0000", "0000", "0000", "0000", "0000", "0001"},
                IPAnonymizer.expandIPv6("::1"));
        assertNull(IPAnonymizer.expandIPv6("2001::db8::1"), "two '::' are not valid");
        assertNull(IPAnonymizer.expandIPv6("2001:db8"), "uncompressed must be complete");
    }

    @Test
    void zoneIndexAndBracketsAreStripped() {
        assertEquals("fe80:0000:0000::", IPAnonymizer.anonymize("fe80::1%eth0"));
        assertEquals("2001:0db8:0000::", IPAnonymizer.anonymize("[2001:db8::1]"));
    }

    @Test
    void masking() {
        assertEquals("***.***.***.***", IPAnonymizer.maskIP("10.0.0.1"));
        assertEquals("****:****:****:****:****:****:****:****", IPAnonymizer.maskIP("2001:db8::1"));
    }

    @Test
    @DisplayName("hashing is stable and actually keyed by the salt")
    void hashing() {
        String a = IPAnonymizer.hashIP("1.2.3.4", "salt-a");
        String b = IPAnonymizer.hashIP("1.2.3.4", "salt-a");
        String c = IPAnonymizer.hashIP("1.2.3.4", "salt-b");
        String d = IPAnonymizer.hashIP("1.2.3.5", "salt-a");

        assertEquals(a, b, "same input and salt must yield the same value");
        assertNotEquals(a, c, "the salt must be part of the key, not just appended data");
        assertNotEquals(a, d);
    }

    @Test
    @DisplayName("literal detection rejects stored hashes and malformed addresses")
    void literalDetection() {
        assertTrue(IPAnonymizer.isLiteralIp("10.0.0.1"));
        assertTrue(IPAnonymizer.isLiteralIp("2001:db8::1"));
        // A stored hash must never be handed to BanList.pardon().
        assertFalse(IPAnonymizer.isLiteralIp(IPAnonymizer.hashIP("1.2.3.4", "salt")));
        assertFalse(IPAnonymizer.isLiteralIp("999.1.1.1"));
        assertFalse(IPAnonymizer.isLiteralIp("***.***.***.***"));
        assertFalse(IPAnonymizer.isLiteralIp(null));
        assertFalse(IPAnonymizer.isLiteralIp(""));
    }

    @Test
    void levelFromConfigToleratesMessyValues() {
        assertEquals(IPAnonymizer.AnonymizationLevel.PARTIAL, IPAnonymizer.levelFromConfig(" partial "));
        assertEquals(IPAnonymizer.AnonymizationLevel.HASH, IPAnonymizer.levelFromConfig("hash"));
        // An unknown value must not blow up mid-ban; it falls back instead.
        assertEquals(IPAnonymizer.AnonymizationLevel.PARTIAL, IPAnonymizer.levelFromConfig("nonsense"));
        assertEquals(IPAnonymizer.AnonymizationLevel.PARTIAL, IPAnonymizer.levelFromConfig(null));
    }

    @Test
    void anonymizeByLevel() {
        String ip = "192.168.1.123";
        assertEquals(ip, IPAnonymizer.anonymize(ip, IPAnonymizer.AnonymizationLevel.NONE, "s"));
        assertEquals("192.168.1.0", IPAnonymizer.anonymize(ip, IPAnonymizer.AnonymizationLevel.PARTIAL, "s"));
        assertEquals("***.***.***.***", IPAnonymizer.anonymize(ip, IPAnonymizer.AnonymizationLevel.FULL, "s"));
        assertNull(IPAnonymizer.anonymize(null, IPAnonymizer.AnonymizationLevel.PARTIAL, "s"));
    }

    private static void assertArrayEqualsAsString(String[] expected, String[] actual) {
        assertEquals(String.join(":", expected), actual == null ? null : String.join(":", actual));
    }
}
