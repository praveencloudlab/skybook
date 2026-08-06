package com.skybook.praveen.bookingservice.security;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The surname comparison rule for guest lookup (GUEST_CHECKIN_MODULE.md §6),
 * defined once and tested as a table. Both sides - the stored passenger name
 * and the typed one - are NFD-decomposed with combining marks stripped
 * (é → e), then everything that is not a letter is removed (spaces, hyphens,
 * apostrophes, dots), then uppercased with a fixed locale.
 *
 * <p>So {@code O'Brien}, {@code o brien}, {@code OBRIEN} and {@code Óbrien}
 * all match a stored {@code O'Brien}, and {@code García-López} matches
 * {@code Garcia Lopez} - the same equivalence the industry's ASCII-uppercase
 * ticketing has always effectively applied. A name that normalizes to empty
 * matches nothing.
 */
public final class SurnameNormalizer {

    private SurnameNormalizer() {
    }

    public static String normalize(String surname) {
        if (surname == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(surname, Normalizer.Form.NFD);
        return decomposed
                .replaceAll("\\p{M}", "")
                .replaceAll("[^\\p{L}]", "")
                .toUpperCase(Locale.ROOT);
    }

    public static boolean matches(String stored, String typed) {
        String normalizedStored = normalize(stored);
        return !normalizedStored.isEmpty() && normalizedStored.equals(normalize(typed));
    }
}
