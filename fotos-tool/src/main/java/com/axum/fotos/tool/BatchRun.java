package com.axum.fotos.tool;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.delivery.DeliveryLine;
import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.delivery.RowChecker;
import com.axum.fotos.core.sheet.ProductRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 * <p>Rows with bad data (no EAN, invalid EAN, no SKU, a SKU that is not a file name, a SKU repeated with another EAN) are
 * IGNORED: {@link RowChecker} sorts them out first, so no store is asked about them and the progress callback never hears of
 * them; they come back as lines that explain themselves, for the report. The progress total is the number of rows that
 * are really looked up.
 *
 * <p>The lines come back in the order of the rows, whatever order they finished in. One bad row never stops the
 * others: an unexpected failure becomes a line that says so. An EAN that appears in
 * several rows (the same product under two SKUs) is looked up once, even when those rows run at the same time.
 */
public final class BatchRun {

    private static final Logger log = LoggerFactory.getLogger(BatchRun.class);

    /** Called after each row that is looked up, one at a time (never two at once), so the person sees it moving. */
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
        List<RowChecker.Checked> checked = RowChecker.check(rows);
        int total = (int) checked.stream().filter(RowChecker.Checked::usable).count();
        DeliveryLine[] lines = new DeliveryLine[rows.size()];
        for (int i = 0; i < lines.length; i++) {
            RowChecker.Checked c = checked.get(i);
            if (!c.usable()) {
                ProductRow r = c.row();
                lines[i] = DeliveryLine.invalid(r.row(), r.sku(), r.ean(), r.description(), c.problem(), c.detail());
            }
        }
        Map<String, CompletableFuture<Lookup>> lookedUp = new ConcurrentHashMap<>();
        int[] done = {0};
        Object progressLock = new Object();

        ExecutorService workers = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelism, Math.max(1, total))));
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (!checked.get(i).usable()) continue;
                int index = i;
                Ean ean = checked.get(i).ean().orElseThrow();
                pending.add(workers.submit(() -> {
                    boolean[] fromCache = {false};
                    DeliveryLine line = lineFor(rows.get(index), ean, finder, refresh, lookedUp, fromCache);
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

    /** A row that passed {@link RowChecker}: its EAN is valid and its SKU is usable. */
    private static DeliveryLine lineFor(ProductRow row, Ean ean, PhotoFinder finder, boolean refresh,
                                        Map<String, CompletableFuture<Lookup>> lookedUp, boolean[] fromCache) {
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
