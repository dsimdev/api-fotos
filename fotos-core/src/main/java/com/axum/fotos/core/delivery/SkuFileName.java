package com.axum.fotos.core.delivery;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A SKU as the name of a file. The SKU is text from the client's spreadsheet and ends up in a path, so it is
 * validated, never cleaned up: changing "A/1" into "A_1" would make the photo stop matching the store's SKU,
 * and the person has to fix the sheet instead.
 *
 * <p>Letters, digits, dot, dash and underscore; it starts with a letter or digit and does not end with a dot
 * (Windows drops it). Windows device names (CON, NUL, COM1...) are not file names there, with or without extension.
 */
public final class SkuFileName {

    public static final int MAX_LENGTH = 100;
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9_-])?");
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SkuFileName() {}

    public static Optional<String> of(String sku) {
        if (sku == null) return Optional.empty();
        String s = sku.trim();
        if (s.isEmpty() || s.length() > MAX_LENGTH || !SAFE.matcher(s).matches()) return Optional.empty();
        String base = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (RESERVED.contains(base.toUpperCase(Locale.ROOT))) return Optional.empty();
        return Optional.of(s);
    }
}
