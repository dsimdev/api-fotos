package com.axum.fotos.core.sheet;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the client's product list (an .xlsx or a .csv) into rows of description, SKU and EAN.
 *
 * <p>The columns are found by the NAME in the header (ignoring case and accents), never by position: each
 * client's file will not be laid out the same. A missing SKU or EAN column is an error that says which.
 *
 * <p>The file comes from a client and is untrusted: see {@link XlsxTable} for how the XML is read.
 */
public final class SpreadsheetReader {

    /** More than this is not a product list somebody dropped by hand; it is refused instead of eating the machine. */
    public static final int MAX_ROWS = 50_000;
    private static final int HEADER_SEARCH_ROWS = 10;

    private static final Set<String> EAN_NAMES = Set.of("ean", "ean13", "ean 13", "gtin", "upc", "barcode",
            "codigo de barras", "codigo barras", "cod barras", "cod de barras", "codbarras",
            // Axum's article export names it "codbarra", singular, next to "codigo" for the SKU.
            "codbarra", "cod barra", "cod de barra", "codigo barra", "codigo de barra");
    private static final Set<String> SKU_NAMES = Set.of("sku", "codigo sku", "cod sku", "codigo articulo",
            "codigo de articulo", "cod articulo", "cod de articulo", "codigo interno", "codigo", "cod");
    private static final Set<String> DESCRIPTION_NAMES = Set.of("descripcion", "descripcion articulo",
            "descripcion del articulo", "nombre", "producto", "detalle");

    private SpreadsheetReader() {}

    public static List<ProductRow> read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        List<List<String>> table;
        if (name.endsWith(".xlsx")) {
            table = XlsxTable.read(file);
        } else if (name.endsWith(".csv") || name.endsWith(".txt")) {
            table = CsvTable.read(file);
        } else {
            throw new SheetFormatException("Formato no soportado: se espera una planilla .xlsx o .csv.");
        }
        return rows(table);
    }

    static List<ProductRow> rows(List<List<String>> table) throws SheetFormatException {
        int headerAt = -1;
        int ean = -1, sku = -1, description = -1;
        for (int r = 0; r < Math.min(HEADER_SEARCH_ROWS, table.size()); r++) {
            int e = -1, s = -1, d = -1;
            List<String> cells = table.get(r);
            for (int c = 0; c < cells.size(); c++) {
                String header = normalize(cells.get(c));
                if (e < 0 && EAN_NAMES.contains(header)) e = c;
                else if (s < 0 && SKU_NAMES.contains(header)) s = c;
                else if (d < 0 && DESCRIPTION_NAMES.contains(header)) d = c;
            }
            if (e >= 0 && s >= 0) {
                headerAt = r;
                ean = e;
                sku = s;
                description = d;
                break;
            }
        }
        if (headerAt < 0) throw new SheetFormatException(missingColumns(table));

        var rows = new ArrayList<ProductRow>();
        for (int r = headerAt + 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            String eanText = cell(cells, ean), skuText = cell(cells, sku);
            if (eanText.isEmpty() && skuText.isEmpty()) continue;
            rows.add(new ProductRow(r + 1, description < 0 ? "" : cell(cells, description), skuText, eanText));
            if (rows.size() > MAX_ROWS) {
                throw new SheetFormatException("La planilla tiene más de " + MAX_ROWS + " filas: no parece una lista de productos.");
            }
        }
        return rows;
    }

    private static String missingColumns(List<List<String>> table) {
        var found = table.isEmpty() ? List.<String>of() : table.get(0).stream().filter(h -> !h.isBlank()).toList();
        var message = new StringBuilder("No encontré las columnas SKU y EAN en el encabezado de la planilla.");
        message.append(" Tienen que llamarse, por ejemplo, \"SKU\" y \"EAN\" (la descripción es opcional).");
        if (!found.isEmpty()) message.append(" Encabezados que veo: ").append(String.join(", ", found)).append('.');
        return message.toString();
    }

    private static String cell(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index).trim() : "";
    }

    /** Lower case, no accents, punctuation and runs of spaces collapsed to one space. */
    static String normalize(String header) {
        String noAccents = Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
