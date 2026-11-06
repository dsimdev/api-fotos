package com.axum.fotos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreProduct;
import com.axum.fotos.core.cache.LocalCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole run, from the dropped file to the delivery folder, with the stores and the network replaced. */
class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final List<Path> opened = new ArrayList<>();

    private String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int run(Main.FinderFactory factory, String... args) {
        return Main.run(args, new PrintStream(captured, true, StandardCharsets.UTF_8), factory, opened::add);
    }

    private static PhotoFinder stubFinder(Path cacheDir, ExecutorService pool) {
        StoreCatalog store = new StoreCatalog() {
            @Override
            public String storeId() {
                return "jumbo";
            }

            @Override
            public Optional<StoreProduct> findByEan(Ean ean) {
                return ean.value().equals("7791337010710") ? Optional.empty()
                        : Optional.of(new StoreProduct("jumbo", "N", null, "https://page", List.of("u1")));
            }
        };
        return new PhotoFinder(List.of(store), url -> Pics.withPanel(url), new LocalCache(cacheDir), Clock.systemUTC(), pool);
    }

    private static final Main.FinderFactory STUBS = MainTest::stubFinder;

    private Path sheet(String content) throws IOException {
        Path file = dir.resolve("lista.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void withoutAFileItExplainsHowToUseIt() {
        assertEquals(1, run(STUBS));
        assertTrue(out().contains("arrastrá la planilla"), out());
    }

    @Test
    void helpIsNotAnError() {
        assertEquals(0, run(STUBS, "--help"));
        assertTrue(out().contains("--refresh"));
    }

    @Test
    void aMissingFileIsSaidPlainly() {
        assertEquals(1, run(STUBS, dir.resolve("nada.xlsx").toString()));
        assertTrue(out().contains("No encuentro la planilla"), out());
    }

    @Test
    void aSheetWithoutTheRightColumnsSaysWhichOnes() throws IOException {
        Path file = sheet("Nombre,Precio\nFlan,10\n");

        assertEquals(1, run(STUBS, file.toString()));
        assertTrue(out().contains("SKU") && out().contains("EAN"), out());
        assertTrue(out().contains("Precio"), "it says what it did find: " + out());
    }

    @Test
    void anUnknownOptionIsRefusedInsteadOfTreatedAsAFile() throws IOException {
        assertEquals(1, run(STUBS, "--rapido", sheet("SKU,EAN\n1,7791337010604\n").toString()));
        assertTrue(out().contains("--rapido"), out());
    }

    @Test
    void aWholeRunLeavesTheDeliveryNextToTheSheetAndOpensIt() throws IOException {
        Path file = sheet("SKU,EAN,Descripcion\n640008,7791337010604,Flan\n640012,7791337010710,Yogur\n77,123,Roto\n");
        Path cache = dir.resolve("cache");

        int code = run(STUBS, file.toString(), "--cache", cache.toString());

        assertEquals(0, code, out());
        Path folder = dir.resolve("lista - fotos");
        assertTrue(Files.isRegularFile(folder.resolve("fotos").resolve("640008.jpg")), out());
        assertTrue(Files.isRegularFile(folder.resolve("fotos.zip")));
        assertTrue(Files.isRegularFile(folder.resolve("reporte.csv")));
        assertTrue(Files.isRegularFile(folder.resolve("galeria.html")));
        assertFalse(Files.exists(folder.resolve("fotos").resolve("640012.jpg")), "no store sells that EAN");
        assertEquals(List.of(folder), opened);
        assertTrue(out().contains("1 con foto"), out());
        assertTrue(out().contains("1 sin imagen"), out());
        assertTrue(out().contains("1 con datos inválidos"), out());
        String report = Files.readString(folder.resolve("reporte.csv"), StandardCharsets.UTF_8);
        assertTrue(report.contains("640008") && report.contains("OK_RECORTADA"), report);
    }

    @Test
    void theNoOpenFlagLeavesTheFolderClosed() throws IOException {
        run(STUBS, sheet("SKU,EAN\n1,7791337010604\n").toString(), "--no-abrir", "--cache", dir.resolve("c").toString());

        assertTrue(opened.isEmpty());
    }

    @Test
    void aSecondRunReusesThePhotosAndNeverOverwritesTheFirstDelivery() throws IOException {
        Path file = sheet("SKU,EAN\n1,7791337010604\n");
        String cache = dir.resolve("cache").toString();
        run(STUBS, file.toString(), "--cache", cache);
        Files.writeString(dir.resolve("lista - fotos").resolve("marca-de-la-persona.txt"), "mi cambio");

        run(STUBS, file.toString(), "--cache", cache);

        assertTrue(Files.exists(dir.resolve("lista - fotos (2)").resolve("fotos").resolve("1.jpg")));
        assertTrue(Files.exists(dir.resolve("lista - fotos").resolve("marca-de-la-persona.txt")), "the first folder is untouched");
        assertTrue(out().contains("(ya estaba)"), out());
    }
}
