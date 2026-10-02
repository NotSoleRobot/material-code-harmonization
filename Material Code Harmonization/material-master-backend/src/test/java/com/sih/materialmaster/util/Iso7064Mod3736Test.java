package com.sih.materialmaster.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for ISO 7064 MOD 37,36.
 *
 * Gate criteria (WP2):
 *   G1 — round-trip: for any valid body the check char appended passes validate()
 *   G2 — single substitution always detected (100% detection rate)
 *   G3 — adjacent transposition always detected (100% detection rate)
 *   G4 — format guard rejects malformed codes before check-char verification
 */
class Iso7064Mod3736Test {

    private static final String SAMPLE_BASE = "NUMM-401407-CS-050-S40-000042";
    private static final String ALPHABET     = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // -----------------------------------------------------------------------
    // G1: Round-trip
    // -----------------------------------------------------------------------

    @Test
    void roundTrip_validCodePassesValidation() {
        char check = Iso7064Mod3736.computeCheckChar(
                Iso7064Mod3736.stripDelimiters(SAMPLE_BASE));
        String fullCode = SAMPLE_BASE + "-" + check;
        assertTrue(Iso7064Mod3736.validate(fullCode),
                "Round-trip code must pass validate(): " + fullCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "NUMM-401407-CS-050-S40-000001",
            "NUMM-401407-SS-025-150-000099",
            "NUMM-100201-XX-000-STD-123456",
            "NUMM-999999-PV-999-S80-999999",
    })
    void roundTrip_multipleBaseCodes(String base) {
        char check = Iso7064Mod3736.computeCheckChar(
                Iso7064Mod3736.stripDelimiters(base));
        String full = base + "-" + check;
        assertTrue(Iso7064Mod3736.validate(full),
                "Round-trip must pass for: " + full);
    }

    // -----------------------------------------------------------------------
    // G2: 100% single-character substitution detection
    // -----------------------------------------------------------------------

    @Test
    void singleSubstitution_alwaysDetected() {
        char orig = Iso7064Mod3736.computeCheckChar(
                Iso7064Mod3736.stripDelimiters(SAMPLE_BASE));
        String valid = SAMPLE_BASE + "-" + orig;

        // Mutate every position of the full code string except the separator chars
        String stripped = Iso7064Mod3736.stripDelimiters(valid);
        int failures = 0;
        for (int pos = 0; pos < stripped.length(); pos++) {
            for (char sub : ALPHABET.toCharArray()) {
                if (sub == stripped.charAt(pos)) continue; // skip original
                String mutated = stripped.substring(0, pos) + sub + stripped.substring(pos + 1);
                // Re-format as NUMM-DD-DD-DD-DDDDDD-C (16 alphanumeric chars → 22 with hyphens)
                // Strip is reversible only for check verification; we test the stripped form directly
                // by checking that the mutated stripped code does NOT satisfy the round-trip property.
                // We verify by computing the check char on the body and confirming it differs from the tail.
                String mutBody = mutated.substring(0, mutated.length() - 1);
                char mutCheck = mutated.charAt(mutated.length() - 1);
                char expected = Iso7064Mod3736.computeCheckChar(mutBody);
                if (expected == mutCheck) failures++;
            }
        }
        assertEquals(0, failures,
                "ISO 7064 MOD 37,36 must detect 100% of single-character substitutions; " +
                "found " + failures + " undetected errors");
    }

    // -----------------------------------------------------------------------
    // G3: 100% adjacent transposition detection
    // -----------------------------------------------------------------------

    @Test
    void adjacentTransposition_alwaysDetected() {
        char orig = Iso7064Mod3736.computeCheckChar(
                Iso7064Mod3736.stripDelimiters(SAMPLE_BASE));
        String stripped = Iso7064Mod3736.stripDelimiters(SAMPLE_BASE + "-" + orig);

        int failures = 0;
        for (int i = 0; i < stripped.length() - 1; i++) {
            if (stripped.charAt(i) == stripped.charAt(i + 1)) continue; // identical swap is undetectable by definition
            StringBuilder sb = new StringBuilder(stripped);
            char tmp = sb.charAt(i);
            sb.setCharAt(i, sb.charAt(i + 1));
            sb.setCharAt(i + 1, tmp);
            String transposed = sb.toString();

            String tBody  = transposed.substring(0, transposed.length() - 1);
            char   tCheck = transposed.charAt(transposed.length() - 1);
            if (Iso7064Mod3736.computeCheckChar(tBody) == tCheck) failures++;
        }
        assertEquals(0, failures,
                "ISO 7064 MOD 37,36 must detect 100% of adjacent transpositions; " +
                "found " + failures + " undetected errors");
    }

    // -----------------------------------------------------------------------
    // G4: Format guard
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "",                          // empty
            "NUMM-401407-CS-050-S40-0042-A",    // serial too short
            "NUMM-401407-CS-050-S40-0000042-A", // serial too long
            "OTHER-401407-CS-050-S40-000042-A", // wrong prefix
            "NUMM-401407-CS-050-S40-000042",    // missing check char
            "NUMM-40-14-07-000042-A",           // obsolete structure
    })
    void malformedCodes_rejectBeforeCheckVerification(String code) {
        assertFalse(Iso7064Mod3736.validate(code),
                "Malformed code must be rejected: '" + code + "'");
    }

    // -----------------------------------------------------------------------
    // Additional: check char is unique per body (no collisions across serials)
    // -----------------------------------------------------------------------

    @Test
    void checkChars_areNotAllTheSame() {
        Set<Character> seen = new HashSet<>();
        for (int i = 1; i <= 36; i++) {
            String base = String.format("NUMM-401407-CS-050-S40-%06d", i);
            seen.add(Iso7064Mod3736.computeCheckChar(
                    Iso7064Mod3736.stripDelimiters(base)));
        }
        assertTrue(seen.size() > 1, "Check characters should vary across serials");
    }
}
