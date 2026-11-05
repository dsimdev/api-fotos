package com.axum.fotos.core;

import java.util.Optional;

/**
 * A validated barcode (GTIN): 8, 12, 13 or 14 digits with a correct GS1 check digit.
 *
 * <p>The EAN is external input: it ends up in a request to a third party, in a cache key and in
 * logs. So it is validated once, here, and the rest of the code only ever sees an {@code Ean}.
 *
 * <p>Digits are checked one by one as ASCII. {@link Character#isDigit(char)} would also accept
 * digits from other scripts (Arabic-Indic, fullwidth), which are not an EAN and must not reach a URL.
 */
public record Ean(String value) {

    private static final int[] VALID_LENGTHS = {8, 12, 13, 14};

    public Ean {
        if (!isValid(value)) {
            // The value is not echoed back: it came from outside and this message goes to logs.
            throw new IllegalArgumentException("Not a valid EAN/GTIN");
        }
    }

    public static Ean parse(String raw) {
        return new Ean(raw == null ? null : raw.trim());
    }

    public static Optional<Ean> tryParse(String raw) {
        var trimmed = raw == null ? null : raw.trim();
        return isValid(trimmed) ? Optional.of(new Ean(trimmed)) : Optional.empty();
    }

    public static boolean isValid(String value) {
        if (value == null || !hasValidLength(value.length())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return checkDigit(value) == value.charAt(value.length() - 1) - '0';
    }

    /** GS1: from the right, skipping the check digit, weights 3, 1, 3, 1... */
    private static int checkDigit(String value) {
        int sum = 0;
        int weight = 3;
        for (int i = value.length() - 2; i >= 0; i--) {
            sum += (value.charAt(i) - '0') * weight;
            weight = weight == 3 ? 1 : 3;
        }
        return (10 - sum % 10) % 10;
    }

    private static boolean hasValidLength(int length) {
        for (int valid : VALID_LENGTHS) {
            if (length == valid) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return value;
    }
}
