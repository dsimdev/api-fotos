package com.axum.fotos.tool;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.delivery.DeliveryLine;
import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.sheet.ProductRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the whole product list through the finder and turns each row into a line of the delivery.
 *
 * <p>Several products are looked up at the same time ({@code parallelism}): one product at a time left the
 * program waiting for the slowest of the seven stores on every product. How hard the stores are hit does NOT grow
 * with it without limit, because each store is paced on its own (a minimum time between two requests to it).
 *
 * <p>The lines come back in the order of the rows, whatever order they finished in. One bad row never stops the
 * others: an invalid EAN, a missing SKU or an unexpected failure becomes a line that says so. An EAN that appears in
 * several rows (the same product under two SKUs) is looked up once, even when those rows run at the same time.
 */
public final class BatchRun {

    private static final Logger log = LoggerFactory.getLogger(BatchRun.class);

    /** Called after each row, one at a time (never two at once), so the person sees it moving. */
    @FunctionalInterface
    public interface Progress {
        void row(int done, int total, DeliveryLine line, boolean fromCache);
    }

    private BatchRun() {}

    /** One product at a time. */
    public static List<DeliveryLine> run(List<ProductRow> rows, PhotoFinder finder, boolean refresh, Progress progress) {
        return run(rows, finder, refresh, 1, progress);
    }

    public static List<DeliveryLine> run(List<ProductRow> rows, PhotoFinder finder, boolean refresh, int parallelism, Progress progress) {
        int total = rows.size();
        DeliveryLine[] lines = new DeliveryLine[total];
        Map<String, CompletableFuture<Lookup>> lookedUp = new ConcurrentHashMap<>();
        int[] done = {0};
        Object progressLock = new Object();

        ExecutorService workers = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, Math.max(1, total))));
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                int index = i;
                pending.add(workers.submit(() -> {
                    boolean[] fromCache = {false};
                    DeliveryLine line = lineFor(rows.get(index), finder, refresh, lookedUp, fromCache);
                    lines[index] = line;
                    synchronized (progressLock) {
                        progress.row(++done[0], total, line, fromCache[0]);
                    }
                }));
            }
            for (Future<?> f : pending) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    // lineFor never throws, so this is the progress callback failing: not worth losing the run.
                    log.warn("The progress callback failed", e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            workers.shutdownNow();
        }
        return Arrays.stream(lines).map(l -> l != null ? l : interrupted()).toList();
    }

    private static DeliveryLine interrupted() {
        return DeliveryLine.without(0, "", "", "", Result.ERROR, "Se interrumpió antes de terminar. Volver a correr.");
    }

    private static DeliveryLine lineFor(ProductRow row, PhotoFinder finder, boolean refresh,
                                        Map<String, CompletableFuture<Lookup>> lookedUp, boolean[] fromCache) {
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
            var mine = new CompletableFuture<Lookup>();
            CompletableFuture<Lookup> existing = lookedUp.putIfAbsent(ean.value(), mine);
            Lookup lookup;
            if (existing == null) {
                try {
                    lookup = finder.find(ean, refresh);
                    mine.complete(lookup);
                } catch (Exception e) {
                    mine.completeExceptionally(e);
                    throw e;
                }
                fromCache[0] = lookup.fromCache();
            } else {
                // The owner of this EAN is running (it put the future), so waiting on it cannot deadlock.
                lookup = existing.join();
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
