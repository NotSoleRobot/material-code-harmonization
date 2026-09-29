package com.sih.materialmaster.util;

import java.util.regex.Pattern;

/**
 * ISO/IEC 7064:2003 MOD 37,36 — hybrid check-character system.
 *
 * <p>Alphabet: {@code 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ} (36 characters).</p>
 *
 * <p>This standards-based implementation replaces the former hand-rolled
 * checksum and provides the error-detection guarantees required by NUMM.</p>
 *
 * <p>Reference: ISO/IEC 7064:2003 §7 — "Hybrid system MOD 37,36".</p>
 *
 * <p>WP2 — standards-based checksum remediation.</p>
 */
public final class Iso7064Mod3736 {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int M  = 36; // number of characters in alphabet
    private static final int MP1 = 37; // M + 1

    /**
     * Expected format for a complete NUMM code including check character.
     * Pattern: {@code NUMM-DD-DD-DD-DDDDDD-C} where D is a digit and C is a base-36 char.
     */
    private static final Pattern CANONICAL =
            Pattern.compile("^(NUMM-\\d{2}-\\d{2}-\\d{2}-\\d{6}-[0-9A-Z]|NUMM-[0-9A-Z]{4,8}(-[0-9A-Z]{2,6})+\\-\\d{6}-[0-9A-Z])$");

    private Iso7064Mod3736() {}

    /**
     * Converts a base-36 character to its numeric value (0–35).
     * Accepts both upper and lower case.
     */
    private static int valueOf(char c) {
        c = Character.toUpperCase(c);
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'Z') return c - 'A' + 10;
        throw new IllegalArgumentException("Character not in base-36 alphabet: " + c);
    }

    /**
     * Converts a numeric value (0–35) to its base-36 character.
     */
    private static char charOf(int val) {
        if (val < 0 || val >= M) throw new IllegalArgumentException("Value out of range: " + val);
        return ALPHABET.charAt(val);
    }

    /**
     * Strips hyphens from a code body before feeding to the algorithm.
     * e.g. {@code "NUMM-40-14-07-000042"} → {@code "NUMM4014070000042"}
     */
    public static String stripDelimiters(String s) {
        return s.replace("-", "").replace(" ", "").toUpperCase();
    }

    /**
     * Computes the single base-36 check character for {@code body}.
     *
     * <p>The body must already have delimiters stripped (call {@link #stripDelimiters} first).
     * The algorithm is the ISO 7064 "hybrid system" (§7 of the standard):
     * initialise p = M; for each character: s = (p % (M+1)) + a(c); m = s % M;
     * p = (m == 0 ? M : m) * 2; then checkValue = (M+1 - p % (M+1)) % M.</p>
     *
     * @param body Alphanumeric string, no delimiters, uppercase.
     * @return Single uppercase check character.
     */
    public static char computeCheckChar(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Body must not be null or blank");
        }
        int p = M; // start with M per ISO 7064 §7
        for (char c : body.toCharArray()) {
            int a = valueOf(c);
            int s = (p % MP1) + a;
            int m = s % M;
            p = (m == 0 ? M : m) * 2;
        }
        int checkValue = (MP1 - (p % MP1)) % M;
        return charOf(checkValue);
    }

    /**
     * Validates a complete NUMM code including its trailing check character.
     *
     * <p>Performs a format check first (fixes B-16), then verifies the check character.</p>
     *
     * @param fullCode Complete code, e.g. {@code "NUMM-40-14-07-000042-K"}
     * @return {@code true} if the code has the correct format and check character.
     */
    public static boolean validate(String fullCode) {
        if (fullCode == null) return false;
        String c = fullCode.trim().toUpperCase();
        // B-16 fix: reject malformed codes before attempting check-char verification
        if (!CANONICAL.matcher(c).matches()) return false;
        // Body is everything except the last "-K"
        String body  = c.substring(0, c.length() - 2);
        char expected = c.charAt(c.length() - 1);
        try {
            return computeCheckChar(stripDelimiters(body)) == expected;
        } catch (Exception e) {
            return false;
        }
    }
}
