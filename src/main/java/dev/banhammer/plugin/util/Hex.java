package dev.banhammer.plugin.util;

public final class Hex {
    private Hex() {}

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static String encodeHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hex[i * 2] = HEX_CHARS[v >>> 4];
            hex[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(hex);
    }

    public static byte[] decodeHex(String hex) {
        String s = hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if ((s.length() & 1) == 1) throw new IllegalArgumentException("Odd length hex");
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = toNibble(s.charAt(i * 2));
            int lo = toNibble(s.charAt(i * 2 + 1));
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int toNibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("Invalid hex char: " + c);
    }
}
