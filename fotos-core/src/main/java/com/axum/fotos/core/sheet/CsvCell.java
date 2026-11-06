package com.axum.fotos.core.sheet;

/** One cell of a CSV that this tool WRITES (the report and the bank index). One copy, used by both. */
public final class CsvCell {

    private CsvCell() {}

    /**
     * @param freeText text that came from outside (a description, a URL, a store's answer): a cell that starts with
     *                 {@code = + - @} would be a formula for a spreadsheet program, so it gets a leading quote that makes it text
     */
    public static String of(String value, boolean freeText) {
        String v = value == null ? "" : value.trim();
        if (freeText && !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
