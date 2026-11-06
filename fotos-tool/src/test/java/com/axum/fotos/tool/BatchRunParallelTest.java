package com.axum.fotos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreProduct;
import com.axum.fotos.core.cache.LocalCache;
import com.axum.fotos.core.delivery.DeliveryLine;
import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.sheet.ProductRow;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several products at a time: the same results as one by one, in the same order, and nothing looked up twice. */
class BatchRunParallelTest {

    /** Valid EANs (GS1 check digit), taken from the real client sheet. */
    private static final List<String> EANS = List.of("7791337010604", "7791337010710", "7791337009608", "7791337010802",
            "7791337010857", "7791337010833", "7791337010864", "7791337060944");

    @TempDir
    Path dir;

    private ExecutorService storePool;

    @BeforeEach
    void start() {
        storePool = Executors.newFixedThreadPool(32);
    }

    @AfterEach
    void stop() {
        storePool.shutdownNow();
    }

    /** One store whose answer takes {@code delayMillis} per EAN and which counts how many asks are in flight together. */
    private static final class SlowStore implements StoreCatalog {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final ToLongFunction<Ean> delayMillis;
        final String explodesOn;

        SlowStore(ToLongFunction<Ean> delayMillis, String explodesOn) {
            this.delayMillis = delayMillis;
            this.explodesOn = explodesOn;
        }

        @Override
        public String storeId() {
            return "jumbo";
        }

        @Override
        public Optional<StoreProduct> findByEan(Ean ean) {
            calls.incrementAndGet();
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(delayMillis.applyAsLong(ean));
                if (ean.value().equals(explodesOn)) throw new IllegalStateException("bug");
                return Optional.of(new StoreProduct("jumbo", "N", null, "https://page", List.of("u-" + ean.value())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private PhotoFinder finder(SlowStore store) {
        return new PhotoFinder(List.of(store), Pics::clean, new LocalCache(dir.resolve("cache")), Clock.systemUTC(), storePool);
    }

    private static List<ProductRow> rows(int n) {
        var rows = new ArrayList<ProductRow>();
        for (int i = 0; i < n; i++) rows.add(new ProductRow(i + 2, "Producto " + i, "SKU" + i, EANS.get(i)));
        return rows;
    }

    @Test
    void severalProductsReallyAreAskedAtTheSameTime() {
        var store = new SlowStore(ean -> 150, null);

        var lines = BatchRun.run(rows(6), finder(store), false, 3, (d, t, l, c) -> { });

        assertTrue(store.maxInFlight.get() >= 2, "never more than one at a time: " + store.maxInFlight.get());
        assertTrue(store.maxInFlight.get() <= 3, "more at once than asked for: " + store.maxInFlight.get());
        assertTrue(lines.stream().allMatch(l -> l.result() == Result.OK));
    }

    @Test
    void oneAtATimeStaysOneAtATime() {
        var store = new SlowStore(ean -> 30, null);

        BatchRun.run(rows(4), finder(store), false, 1, (d, t, l, c) -> { });

        assertEquals(1, store.maxInFlight.get());
    }

    /** The first product is the slowest, so it finishes last: the lines must still come back in the order of the rows. */
    @Test
    void theLinesComeBackInTheOrderOfTheRowsWhateverFinishesFirst() {
        var store = new SlowStore(ean -> ean.value().equals(EANS.get(0)) ? 400 : 20, null);

        var lines = BatchRun.run(rows(5), finder(store), false, 5, (d, t, l, c) -> { });

        assertEquals(List.of("SKU0", "SKU1", "SKU2", "SKU3", "SKU4"), lines.stream().map(DeliveryLine::sku).toList());
        assertTrue(lines.stream().allMatch(l -> l.result() == Result.OK));
    }

    @Test
    void theSameEanInSeveralRowsIsLookedUpOnceEvenWhenTheyRunTogether() {
        var store = new SlowStore(ean -> 200, null);
        var rows = new ArrayList<ProductRow>();
        for (int i = 0; i < 6; i++) rows.add(new ProductRow(i + 2, "Producto", "SKU" + i, EANS.get(0)));

        var lines = BatchRun.run(rows, finder(store), false, 6, (d, t, l, c) -> { });

        assertEquals(1, store.calls.get(), "one lookup for six rows of the same product");
        assertTrue(lines.stream().allMatch(l -> l.result() == Result.OK));
        assertEquals(6, lines.stream().map(DeliveryLine::sku).distinct().count(), "each row keeps its own SKU");
    }

    @Test
    void progressCountsUpToTheTotalAndIsNeverReportedByTwoThreadsAtOnce() {
        var store = new SlowStore(ean -> 40, null);
        var counts = Collections.synchronizedList(new ArrayList<Integer>());
        var inside = new AtomicInteger();
        var overlapped = new AtomicInteger();

        BatchRun.run(rows(8), finder(store), false, 4, (done, total, line, fromCache) -> {
            if (inside.incrementAndGet() > 1) overlapped.incrementAndGet();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            counts.add(done);
            assertEquals(8, total);
            inside.decrementAndGet();
        });

        assertEquals(0, overlapped.get(), "two progress lines were printed at once");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), counts, "the counter goes 1..n without gaps or repeats");
    }

    @Test
    void aRowThatBlowsUpDoesNotStopTheOthers() {
        var store = new SlowStore(ean -> 30, EANS.get(2));

        var lines = BatchRun.run(rows(5), finder(store), false, 3, (d, t, l, c) -> { });

        assertEquals(Result.ERROR, lines.get(2).result());
        assertFalse(lines.get(2).detail().isBlank());
        for (int i : new int[]{0, 1, 3, 4}) assertEquals(Result.OK, lines.get(i).result(), "row " + i);
    }

    @Test
    void theResultIsTheSameAsOneByOne() {
        var sequential = BatchRun.run(rows(6), finder(new SlowStore(ean -> 10, null)), false, 1, (d, t, l, c) -> { });
        // A fresh cache for the parallel run, or it would just read what the first one stored.
        var parallelFinder = new PhotoFinder(List.of(new SlowStore(ean -> 10, null)), Pics::clean,
                new LocalCache(dir.resolve("cache-2")), Clock.systemUTC(), storePool);

        var parallel = BatchRun.run(rows(6), parallelFinder, false, 4, (d, t, l, c) -> { });

        assertEquals(sequential.stream().map(l -> l.sku() + l.stateName() + l.store() + l.side()).toList(),
                parallel.stream().map(l -> l.sku() + l.stateName() + l.store() + l.side()).toList());
    }
}
