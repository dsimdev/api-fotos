package com.axum.fotos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreProduct;
import com.axum.fotos.core.cache.LocalCache;
import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.delivery.RowProblem;
import com.axum.fotos.core.sheet.ProductRow;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchRunTest {

    private static final String EAN_A = "7791337010604";
    private static final String EAN_B = "7791337010710";

    @TempDir
    Path dir;

    private ExecutorService pool;
    private final AtomicInteger storeCalls = new AtomicInteger();

    @BeforeEach
    void start() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private PhotoFinder finder(boolean storeBlowsUp) {
        StoreCatalog store = new StoreCatalog() {
            @Override
            public String storeId() {
                return "jumbo";
            }

            @Override
            public Optional<StoreProduct> findByEan(Ean ean) {
                storeCalls.incrementAndGet();
                if (storeBlowsUp && ean.value().equals(EAN_B)) throw new IllegalStateException("bug");
                return Optional.of(new StoreProduct("jumbo", "N", null, "https://page", List.of("u1")));
            }
        };
        return new PhotoFinder(List.of(store), url -> Pics.clean(url), new LocalCache(dir.resolve("cache")), Clock.systemUTC(), pool);
    }

    private static ProductRow row(int n, String sku, String ean) {
        return new ProductRow(n, "Producto " + n, sku, ean);
    }

    @Test
    void eachRowBecomesALineInTheSameOrder() {
        var lines = BatchRun.run(List.of(row(2, "1", EAN_A), row(3, "2", EAN_B)), finder(false), false, (d, t, l, c) -> { });

        assertEquals(List.of("1", "2"), lines.stream().map(l -> l.sku()).toList());
        assertTrue(lines.stream().allMatch(l -> l.result() == Result.OK));
    }

    @Test
    void aBadRowDoesNotStopTheOthers() {
        var rows = List.of(row(2, "1", "123"), row(3, "", EAN_A), row(4, "3", EAN_B));

        var lines = BatchRun.run(rows, finder(false), false, (d, t, l, c) -> { });

        assertEquals(Result.DATOS_INVALIDOS, lines.get(0).result());
        assertTrue(lines.get(0).detail().contains("EAN"), lines.get(0).detail());
        assertEquals(Result.DATOS_INVALIDOS, lines.get(1).result());
        assertTrue(lines.get(1).detail().contains("SKU"), lines.get(1).detail());
        assertEquals(Result.OK, lines.get(2).result());
        assertEquals(1, storeCalls.get(), "only the valid row reached a store");
    }

    /** An export of a client whose articles have no barcode loaded in the source system: the row says so, not "invalid". */
    @Test
    void aRowWithoutAnEanSaysItIsMissingInsteadOfInvalid() {
        var lines = BatchRun.run(List.of(row(2, "1000", ""), row(3, "1001", "123")), finder(false), false, (d, t, l, c) -> { });

        assertEquals(Result.DATOS_INVALIDOS, lines.get(0).result());
        assertTrue(lines.get(0).detail().contains("No tiene código de barras"), lines.get(0).detail());
        assertTrue(lines.get(1).detail().contains("EAN inválido"), "a present but wrong EAN keeps the old message: " + lines.get(1).detail());
        assertEquals(0, storeCalls.get(), "neither reached a store");
    }

    @Test
    void theSameEanUnderTwoSkusIsLookedUpOnceAndBothGetThePhoto() {
        var lines = BatchRun.run(List.of(row(2, "1", EAN_A), row(3, "2", EAN_A)), finder(false), false, (d, t, l, c) -> { });

        assertEquals(1, storeCalls.get());
        assertEquals(Result.OK, lines.get(0).result());
        assertEquals(Result.OK, lines.get(1).result());
        assertEquals("1", lines.get(0).sku());
        assertEquals("2", lines.get(1).sku());
    }

    @Test
    void anUnexpectedFailureInOneRowIsALineNotACrash() {
        var lines = BatchRun.run(List.of(row(2, "1", EAN_B), row(3, "2", EAN_A)), finder(true), false, (d, t, l, c) -> { });

        assertEquals(Result.ERROR, lines.get(0).result());
        assertEquals(Result.OK, lines.get(1).result());
    }

    /** Rows with bad data are ignored: the person is never told about them one by one, and the total is what is really asked. */
    @Test
    void progressIsReportedOnlyForTheRowsThatAreLookedUp() {
        var seen = new ArrayList<String>();

        var lines = BatchRun.run(List.of(row(2, "1", "123"), row(3, "2", EAN_A), row(4, "", EAN_B), row(5, "3", ""), row(6, "4", EAN_B)), finder(false), false,
                (done, total, line, fromCache) -> seen.add(done + "/" + total + " " + line.result()));

        assertEquals(List.of("1/2 OK", "2/2 OK"), seen);
        assertEquals(5, lines.size(), "the ignored ones still come back, for the report");
        assertEquals(List.of(Result.DATOS_INVALIDOS, Result.OK, Result.DATOS_INVALIDOS, Result.DATOS_INVALIDOS, Result.OK),
                lines.stream().map(l -> l.result()).toList());
    }

    @Test
    void anIgnoredRowIsNeverAskedAboutAndSaysWhichKindOfProblemItHas() {
        var rows = List.of(row(2, "1", ""), row(3, "2", "123"), row(4, "", EAN_A), row(5, "a/b", EAN_A), row(6, "CON", EAN_B));

        var lines = BatchRun.run(rows, finder(false), false, 3, (d, t, l, c) -> { });

        assertEquals(0, storeCalls.get(), "not one store was asked: everything here is ignored");
        assertEquals(List.of(RowProblem.NO_EAN, RowProblem.BAD_EAN, RowProblem.NO_SKU, RowProblem.BAD_SKU, RowProblem.BAD_SKU),
                lines.stream().map(l -> l.problem()).toList());
        assertTrue(lines.stream().allMatch(l -> l.result() == Result.DATOS_INVALIDOS && l.detail() != null));
    }

    @Test
    void aSkuReusedWithAnotherEanIsIgnoredAndTheFirstOneKeepsIt() {
        var lines = BatchRun.run(List.of(row(2, "77", EAN_A), row(3, "77", EAN_B)), finder(false), false, (d, t, l, c) -> { });

        assertEquals(Result.OK, lines.get(0).result());
        assertEquals(RowProblem.SKU_REUSED, lines.get(1).problem());
        assertTrue(lines.get(1).detail().contains("fila 2"), lines.get(1).detail());
        assertEquals(1, storeCalls.get(), "the second product was never asked about");
    }

    @Test
    void aSheetWhereEverythingIsIgnoredStillReturnsItsLinesAndAsksNothing() {
        var seen = new ArrayList<String>();

        var lines = BatchRun.run(List.of(row(2, "1", ""), row(3, "2", "")), finder(false), false, 4,
                (d, t, l, c) -> seen.add("called"));

        assertEquals(2, lines.size());
        assertTrue(seen.isEmpty());
        assertEquals(0, storeCalls.get());
    }

    @Test
    void aSecondRunIsAnsweredFromTheCache() {
        var cacheHits = new ArrayList<Boolean>();
        var finder = finder(false);

        BatchRun.run(List.of(row(2, "1", EAN_A)), finder, false, (d, t, l, c) -> { });
        BatchRun.run(List.of(row(2, "1", EAN_A)), finder, false, (d, t, l, c) -> cacheHits.add(c));

        assertEquals(List.of(true), cacheHits);
        assertEquals(1, storeCalls.get());
    }
}
