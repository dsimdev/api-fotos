package com.axum.fotos.core.sheet;

/**
 * One product line of the client's spreadsheet, as written: nothing is validated here, so a bad EAN is
 * reported against its row instead of silently dropping the line.
 *
 * @param row         1-based row number in the file, to point the person at the line
 * @param description may be empty: it is only used in the report
 */
public record ProductRow(int row, String description, String sku, String ean) {}
