package com.axum.fotos.core.delivery;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.sheet.ProductRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decides, BEFORE any store is asked, which rows of the sheet can be worked on and which are ignored. The batch run
 * uses it, and the delivery writer takes its messages. The writer keeps its own file-name and repeated-SKU check on
 * purpose, as a last line of defence: a photo must never be written under a name that is not a file name.
 *
 * <p>A row is ignored when it has no EAN, an invalid EAN, no SKU, a SKU that cannot be a file name, or a SKU that an
 * earlier row already uses for ANOTHER EAN (the first one keeps it). The same SKU with the same EAN is just a repeated
 * line of the same product and is fine. Rows are checked in order, and the EAN goes first: a row that lacks both reports
 * the barcode, which is what has to be fixed at the source.
 */
public final class RowChecker {

    /**
     * @param ean     the validated barcode; present only when the row is usable
     * @param problem null when the row is usable
     * @param detail  null when the row is usable
     */
    public record Checked(ProductRow row, Optional<Ean> ean, RowProblem problem, String detail) {
        public boolean usable() {
            return problem == null;
        }
    }

    private RowChecker() {}

    public static List<Checked> check(List<ProductRow> rows) {
        Map<String, ProductRow> skuOwner = new HashMap<>();
        var out = new ArrayList<Checked>(rows.size());
        for (ProductRow row : rows) {
            out.add(checkOne(row, skuOwner));
        }
        return out;
    }

    private static Checked checkOne(ProductRow row, Map<String, ProductRow> skuOwner) {
        Optional<Ean> ean = Ean.tryParse(row.ean());
        if (ean.isEmpty()) {
            RowProblem p = row.ean().isBlank() ? RowProblem.NO_EAN : RowProblem.BAD_EAN;
            return new Checked(row, Optional.empty(), p, p.message());
        }
        if (row.sku().isBlank()) return rejected(row, RowProblem.NO_SKU, RowProblem.NO_SKU.message());
        if (SkuFileName.of(row.sku()).isEmpty()) return rejected(row, RowProblem.BAD_SKU, RowProblem.BAD_SKU.message());

        String key = row.sku().trim().toLowerCase(Locale.ROOT);
        ProductRow first = skuOwner.putIfAbsent(key, row);
        if (first != null && !Ean.tryParse(first.ean()).equals(ean)) {
            return rejected(row, RowProblem.SKU_REUSED, RowProblem.skuReused(first.row()));
        }
        return new Checked(row, ean, null, null);
    }

    private static Checked rejected(ProductRow row, RowProblem problem, String detail) {
        return new Checked(row, Optional.empty(), problem, detail);
    }
}
