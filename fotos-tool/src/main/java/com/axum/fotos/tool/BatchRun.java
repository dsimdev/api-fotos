package com.axum.fotos.tool;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.delivery.DeliveryLine;
import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.sheet.ProductRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the whole product list through the finder, one row at a time, and turns each into a line of the delivery.
 *
 * <p>One bad row never stops the others: an invalid EAN, a missing SKU or an unexpected failure becomes a line
 * that says so. An EAN that appears in several rows (the same product under two SKUs) is looked up once.
 */
public final class BatchRun {

    private static final Logger log = LoggerFactory.getLogger(BatchRun.class);

    /** Called after each row, so the person sees it moving. */
    @FunctionalInterface
    public interface Progress {
        void row(int done, int total, DeliveryLine line, boolean fromCache);
    }

    private BatchRun() {}

    public static List<DeliveryLine> run(List<ProductRow> rows, PhotoFinder finder, boolean refresh, Progress progress) {
        var lines = new ArrayList<DeliveryLine>();
        Map<String, Lookup> lookedUp = new HashMap<>();
        for (ProductRow row : rows) {
            boolean[] fromCache = {false};
            DeliveryLine line = lineFor(row, finder, refresh, lookedUp, fromCache);
            lines.add(line);
            progress.row(lines.size(), rows.size(), line, fromCache[0]);
        }
        return lines;
    }

    private static DeliveryLine lineFor(ProductRow row, PhotoFinder finder, boolean refresh, Map<String, Lookup> lookedUp,
                                        boolean[] fromCache) {
        Optional<Ean> parsed = Ean.tryParse(row.ean());
        if (parsed.isEmpty()) {
            String why = row.ean().isBlank()
                    ? "No tiene código de barras (EAN) en la planilla: hay que cargarlo en el sistema de origen."
                    : "EAN inválido: revisar el largo, que sean solo dígitos y el dígito verificador.";
            return DeliveryLine.without(row.row(), row.sku(), row.ean(), row.description(), Result.DATOS_INVALIDOS, why);
        }
        if (row.sku().isBlank()) {
            return DeliveryLine.without(row.row(), row.sku(), parsed.get().value(), row.description(), Result.DATOS_INVALIDOS,
                    "Falta el SKU.");
        }
        Ean ean = parsed.get();
        try {
            Lookup lookup = lookedUp.get(ean.value());
            if (lookup == null) {
                lookup = finder.find(ean, refresh);
                lookedUp.put(ean.value(), lookup);
                fromCache[0] = lookup.fromCache();
            } else {
                fromCache[0] = true;
            }
            return switch (lookup.status()) {
                case FOUND -> DeliveryLine.ok(row.row(), row.sku(), ean.value(), row.description(), lookup.chosen(),
                        lookup.warnings(), lookup.detail());
                case NO_IMAGE -> DeliveryLine.without(row.row(), row.sku(), ean.value(), row.description(), Result.SIN_IMAGEN,
                        lookup.detail());
                case ERROR -> DeliveryLine.without(row.row(), row.sku(), ean.value(), row.description(), Result.ERROR,
                        lookup.detail());
            };
        } catch (Exception e) {
            log.warn("Row {} failed unexpectedly", row.row(), e);
            return DeliveryLine.without(row.row(), row.sku(), ean.value(), row.description(), Result.ERROR,
                    "Error inesperado en esta fila: " + e.getClass().getSimpleName() + ". Volver a correr.");
        }
    }
}
