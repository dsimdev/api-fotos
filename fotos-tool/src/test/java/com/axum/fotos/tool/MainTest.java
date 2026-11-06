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
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole run, from the dropped file to the delivery folder, with the stores and the network replaced. */
class MainTest {

    private static final Clock NOVEMBER_6 = Clock.fixed(Instant.parse("2026-11-06T15:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));

    @TempDir
    Path dir;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final List<Path> opened = new ArrayList<>();

    private String out() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** As if the tool were installed in {@code installDir} (null: running from a build). */
    private int runInstalledIn(Path installDir, String... args) {
        return Main.run(args, new PrintStream(captured, true, StandardCharsets.UTF_8), MainTest::stubFinder, opened::add, installDir, NOVEMBER_6);
    }

    private int run(String... args) {
        return runInstalledIn(null, args);
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

    private Path sheet(String content) throws IOException {
        Path downloads = Files.createDirectories(dir.resolve("Descargas"));
        Path file = downloads.resolve("lista.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void withoutAFileItExplainsHowToUseIt() {
        assertEquals(1, run());
        assertTrue(out().contains("arrastrá la planilla"), out());
    }

    @Test
    void helpIsNotAnError() {
        assertEquals(0, run("--help"));
        assertTrue(out().contains("--refresh") && out().contains("--salida"), out());
    }

    @Test
    void aMissingFileIsSaidPlainly() {
        assertEquals(1, run(dir.resolve("nada.xlsx").toString()));
        assertTrue(out().contains("No encuentro la planilla"), out());
    }

    @Test
    void aSheetWithoutTheRightColumnsSaysWhichOnes() throws IOException {
        Path file = sheet("Nombre,Precio\nFlan,10\n");

        assertEquals(1, run(file.toString()));
        assertTrue(out().contains("SKU") && out().contains("EAN"), out());
        assertTrue(out().contains("Precio"), "it says what it did find: " + out());
    }

    @Test
    void anUnknownOptionIsRefusedInsteadOfTreatedAsAFile() throws IOException {
        assertEquals(1, run("--rapido", sheet("SKU,EAN\n1,7791337010604\n").toString()));
        assertTrue(out().contains("--rapido"), out());
    }

    @Test
    void anOptionThatNeedsAFolderSaysSoWhenItIsMissing() throws IOException {
        assertEquals(1, run(sheet("SKU,EAN\n1,7791337010604\n").toString(), "--salida"));
        assertTrue(out().contains("--salida"), out());
    }

    @Test
    void theParallelOptionMustBeANumberBetweenOneAndEight() throws IOException {
        String file = sheet("SKU,EAN\n1,7791337010604\n").toString();

        for (String bad : new String[]{"abc", "0", "9", "-2"}) {
            captured.reset();
            assertEquals(1, run(file, "--paralelo", bad), bad);
            assertTrue(out().contains("--paralelo"), bad + ": " + out());
        }
        captured.reset();
        assertEquals(1, run(file, "--paralelo"));
        assertTrue(out().contains("--paralelo"), out());
    }

    @Test
    void aRunWithSeveralProductsAtOnceGivesTheSameDelivery() throws IOException {
        Path file = sheet("SKU,EAN\n640008,7791337010604\n640012,7791337010710\n77,7791337009608\n");

        int code = runInstalledIn(dir.resolve("Fotos Tienda"), file.toString(), "--paralelo", "4", "--cache", dir.resolve("c").toString());

        assertEquals(0, code, out());
        assertTrue(out().contains("Productos que se consultan a la vez: 4"), out());
        assertTrue(Files.isRegularFile(dir.resolve("Fotos Tienda").resolve("Fotos 2026-11-06").resolve("fotos").resolve("640008.jpg")));
        assertTrue(Files.isRegularFile(dir.resolve("Fotos Tienda").resolve("Fotos 2026-11-06").resolve("fotos").resolve("77.jpg")));
    }

    /** The sheet is dropped from Downloads; the deliveries must pile up in the tool's own folder, not there. */
    @Test
    void aWholeRunWritesFotosAndTheDateInsideTheProgramFolderNotNextToTheSheet() throws IOException {
        Path file = sheet("SKU,EAN,Descripcion\n640008,7791337010604,Flan\n640012,7791337010710,Yogur\n77,123,Roto\n");
        Path program = dir.resolve("Fotos Tienda");
        Path cache = dir.resolve("cache");

        int code = runInstalledIn(program, file.toString(), "--cache", cache.toString());

        assertEquals(0, code, out());
        Path folder = program.resolve("Fotos 2026-11-06");
        assertTrue(Files.isRegularFile(folder.resolve("fotos").resolve("640008.jpg")), out());
        assertTrue(Files.isRegularFile(folder.resolve("fotos.zip")));
        assertTrue(Files.isRegularFile(folder.resolve("reporte.csv")));
        assertTrue(Files.isRegularFile(folder.resolve("galeria.html")));
        assertFalse(Files.exists(folder.resolve("fotos").resolve("640012.jpg")), "no store sells that EAN");
        try (var downloads = Files.list(file.getParent())) {
            assertEquals(List.of(file), downloads.toList(), "nothing was written next to the sheet");
        }
        assertEquals(List.of(folder), opened);
        assertTrue(out().contains("1 con foto"), out());
        assertTrue(out().contains("1 sin imagen"), out());
        assertTrue(out().contains("1 con datos inválidos"), out());
        assertTrue(out().contains("Las entregas se guardan en: " + program), out());
        String report = Files.readString(folder.resolve("reporte.csv"), StandardCharsets.UTF_8);
        assertTrue(report.contains("640008") && report.contains("OK_RECORTADA"), report);
    }

    @Test
    void theOutputOptionPutsTheDeliveryElsewhereAndCreatesTheFolderIfNeeded() throws IOException {
        Path file = sheet("SKU,EAN\n1,7791337010604\n");
        Path elsewhere = dir.resolve("otra").resolve("carpeta");

        int code = runInstalledIn(dir.resolve("Fotos Tienda"), file.toString(), "--salida", elsewhere.toString(), "--cache", dir.resolve("c").toString());

        assertEquals(0, code, out());
        assertTrue(Files.isRegularFile(elsewhere.resolve("Fotos 2026-11-06").resolve("fotos").resolve("1.jpg")));
        assertFalse(Files.exists(dir.resolve("Fotos Tienda")), "the default was not used");
    }

    @Test
    void whenNotRunningFromAnInstallItWritesNextToTheSheet() throws IOException {
        Path file = sheet("SKU,EAN\n1,7791337010604\n");

        int code = run(file.toString(), "--cache", dir.resolve("c").toString());

        assertEquals(0, code, out());
        assertTrue(Files.isRegularFile(file.getParent().resolve("Fotos 2026-11-06").resolve("fotos").resolve("1.jpg")), out());
    }

    @Test
    void theNoOpenFlagLeavesTheFolderClosed() throws IOException {
        runInstalledIn(dir.resolve("Fotos Tienda"), sheet("SKU,EAN\n1,7791337010604\n").toString(), "--no-abrir", "--cache", dir.resolve("c").toString());

        assertTrue(opened.isEmpty());
    }

    @Test
    void aSecondRunTheSameDayReusesThePhotosAndNeverOverwritesTheFirstDelivery() throws IOException {
        Path file = sheet("SKU,EAN\n1,7791337010604\n");
        Path program = dir.resolve("Fotos Tienda");
        String cache = dir.resolve("cache").toString();
        runInstalledIn(program, file.toString(), "--cache", cache);
        Files.writeString(program.resolve("Fotos 2026-11-06").resolve("marca-de-la-persona.txt"), "mi cambio");

        runInstalledIn(program, file.toString(), "--cache", cache);

        assertTrue(Files.exists(program.resolve("Fotos 2026-11-06 (2)").resolve("fotos").resolve("1.jpg")));
        assertTrue(Files.exists(program.resolve("Fotos 2026-11-06").resolve("marca-de-la-persona.txt")), "the first folder is untouched");
        assertTrue(out().contains("(ya estaba)"), out());
    }

    @Test
    void aRunFromABuildIsNotMistakenForAnInstall() {
        // The test classes live in build/classes, not in <install>/lib/*.jar with a bin next to lib.
        assertEquals(null, Main.installDir());
    }
}
