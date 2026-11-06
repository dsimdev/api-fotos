package com.axum.fotos.core.delivery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.delivery.DeliveryLine.Result;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.Candidate.Kind;
import com.axum.fotos.core.pick.Warning;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeliveryWriterTest {

    @TempDir
    Path dir;

    private static Candidate photo(Kind kind, ImageFormat format, byte... bytes) {
        return new Candidate("carrefour", "https://x/p", "https://x/i", kind, format, 1000, 0.0, 200.0, bytes);
    }

    private static DeliveryLine ok(int row, String sku, String ean, String description, Candidate c, Warning... warnings) {
        return DeliveryLine.ok(row, sku, ean, description, c, List.of(warnings));
    }

    private DeliveryWriter.Written write(DeliveryLine... lines) throws IOException {
        return DeliveryWriter.write(Files.createDirectory(dir.resolve("out" + System.nanoTime())), List.of(lines));
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void eachPhotoIsNamedAfterItsSkuWithItsRealFormat() throws IOException {
        var written = write(
                ok(2, "640008", "7791337010604", "Flan", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                ok(3, "A-12", "7791337010710", "Yogur", photo(Kind.CROPPED, ImageFormat.PNG, (byte) 2)));

        assertEquals(List.of("640008.jpg", "A-12.png"), names(written.photos()));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(written.photos().resolve("640008.jpg")));
        assertEquals(2, written.photoCount());
    }

    @Test
    void theZipHoldsTheSamePhotosAtTheTopLevelAndNothingElse() throws IOException {
        var written = write(ok(2, "640008", "7791337010604", "Flan", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)));

        try (var zip = new ZipFile(written.zip().toFile())) {
            Set<String> entries = new HashSet<>();
            zip.stream().forEach(e -> entries.add(e.getName()));
            assertEquals(Set.of("640008.jpg"), entries);
        }
    }

    @Test
    void theReportHasOneLinePerRowWithItsState() throws IOException {
        var written = write(
                ok(2, "1", "7791337010604", "Flan", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                ok(3, "2", "7791337010710", "Yogur", photo(Kind.CROPPED, ImageFormat.JPEG, (byte) 1), Warning.TOUCHES_EDGE),
                DeliveryLine.without(4, "3", "7791337009608", "Postre", Result.SIN_IMAGEN, "Ninguna tienda lo vende."),
                DeliveryLine.without(5, "4", "123", "Raro", Result.DATOS_INVALIDOS, "EAN inválido."),
                DeliveryLine.without(6, "5", "7791337010123", "Leche", Result.ERROR, "Tienda bloqueada."));

        String report = Files.readString(written.report(), StandardCharsets.UTF_8);

        List<String> lines = report.replace("﻿", "").lines().toList();
        assertEquals(6, lines.size(), report);
        assertTrue(lines.get(1).contains(",OK,"), lines.get(1));
        assertTrue(lines.get(2).contains(",OK_RECORTADA,"), lines.get(2));
        assertTrue(lines.get(2).contains("toca el borde"), "the warning is spelled out: " + lines.get(2));
        assertTrue(lines.get(3).contains(",SIN_IMAGEN,"), lines.get(3));
        assertTrue(lines.get(4).contains(",DATOS_INVALIDOS,"), lines.get(4));
        assertTrue(lines.get(5).contains(",ERROR,"), lines.get(5));
    }

    @Test
    void aSkuThatCannotBeAFileNameIsReportedAndNothingIsWrittenOutsideTheFolder() throws IOException {
        var written = write(
                ok(2, "../evil", "7791337010604", "A", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                ok(3, "a/b", "7791337010710", "B", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                ok(4, "CON", "7791337009608", "C", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)));

        assertEquals(0, written.photoCount());
        assertEquals(List.of(), names(written.photos()));
        assertFalse(Files.exists(written.folder().resolveSibling("evil.jpg")));
        assertTrue(written.lines().stream().allMatch(l -> l.result() == Result.DATOS_INVALIDOS));
    }

    @Test
    void aRepeatedSkuWithAnotherEanDoesNotOverwriteTheFirstPhoto() throws IOException {
        var written = write(
                ok(2, "77", "7791337010604", "A", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                ok(3, "77", "7791337010710", "B", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 2)));

        assertArrayEquals(new byte[]{1}, Files.readAllBytes(written.photos().resolve("77.jpg")));
        assertEquals(Result.DATOS_INVALIDOS, written.lines().get(1).result());
        assertTrue(written.lines().get(1).detail().contains("fila 2"), written.lines().get(1).detail());
    }

    @Test
    void aRepeatedSkuWithTheSameEanIsTheSamePhoto() throws IOException {
        var written = write(
                ok(2, "77", "7791337010604", "A", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                ok(3, "77", "7791337010604", "A", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)));

        assertEquals(1, written.photoCount());
        assertEquals(Result.OK, written.lines().get(1).result());
    }

    @Test
    void textFromTheSheetCannotInjectHtmlOrAFormula() throws IOException {
        var written = write(
                ok(2, "1", "7791337010604", "<script>alert(1)</script>", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                DeliveryLine.without(3, "2", "7791337010710", "=HYPERLINK(\"http://evil\")", Result.SIN_IMAGEN, "+cmd"));

        String html = Files.readString(written.gallery(), StandardCharsets.UTF_8);
        String report = Files.readString(written.report(), StandardCharsets.UTF_8);

        assertFalse(html.contains("<script>"), "the description is escaped in the gallery");
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(report.contains("\"'=HYPERLINK("), "a formula is turned into text: " + report);
        assertTrue(report.contains("\"'+cmd\""), report);
    }

    /** Rows with bad data are ignored: counted in the gallery's header and explained in the report, but with no card. */
    @Test
    void anIgnoredRowHasNoCardInTheGalleryButStaysInTheReport() throws IOException {
        var written = write(
                ok(2, "640008", "7791337010604", "Flan", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)),
                DeliveryLine.invalid(3, "S1", "", "Articulo sin barras", RowProblem.NO_EAN, RowProblem.NO_EAN.message()),
                DeliveryLine.invalid(4, "S2", "123", "Articulo roto", RowProblem.BAD_EAN, RowProblem.BAD_EAN.message()),
                DeliveryLine.without(5, "S3", "7791337009608", "Postre", Result.SIN_IMAGEN, "Ninguna tienda lo vende."));

        String html = Files.readString(written.gallery(), StandardCharsets.UTF_8);
        String report = Files.readString(written.report(), StandardCharsets.UTF_8);

        assertFalse(html.contains("Articulo sin barras") || html.contains("Articulo roto"), "no card for an ignored row");
        assertTrue(html.contains("Postre"), "a product that was looked up and has no image still has its card");
        assertTrue(html.contains("2 productos consultados"), html.substring(html.indexOf("<h1>"), html.indexOf("<h1>") + 60));
        assertTrue(html.contains("Se ignoraron 2 filas"), "the header says how many were left out");
        assertTrue(report.contains("Articulo sin barras") && report.contains("Articulo roto"), "but the report explains each one");
        assertTrue(report.contains("No tiene código de barras") && report.contains("EAN inválido"), report);
    }

    @Test
    void theGalleryDoesNotMentionIgnoredRowsWhenThereAreNone() throws IOException {
        var written = write(ok(2, "640008", "7791337010604", "Flan", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)));

        assertFalse(Files.readString(written.gallery(), StandardCharsets.UTF_8).contains("Se ignoraron"));
    }

    @Test
    void theGalleryPointsAtThePhotosByRelativePath() throws IOException {
        var written = write(ok(2, "640008", "7791337010604", "Flan", photo(Kind.ORIGINAL, ImageFormat.JPEG, (byte) 1)));

        assertTrue(Files.readString(written.gallery(), StandardCharsets.UTF_8).contains("src=\"fotos/640008.jpg\""));
    }

    @Test
    void theDeliveryFolderIsNeverReused() throws IOException {
        Path root = dir.resolve("Fotos Tienda");

        var first = DeliveryWriter.newFolder(root, "Fotos 2026-11-06");
        var second = DeliveryWriter.newFolder(root, "Fotos 2026-11-06");
        var third = DeliveryWriter.newFolder(root, "Fotos 2026-11-06");

        assertEquals("Fotos 2026-11-06", first.getFileName().toString());
        assertEquals("Fotos 2026-11-06 (2)", second.getFileName().toString());
        assertEquals("Fotos 2026-11-06 (3)", third.getFileName().toString());
        assertTrue(Files.isDirectory(first) && Files.isDirectory(second) && Files.isDirectory(third));
    }

    @Test
    void theRootIsCreatedWhenItDoesNotExistYet() throws IOException {
        Path root = dir.resolve("no").resolve("existe").resolve("todavia");

        var folder = DeliveryWriter.newFolder(root, "Fotos 2026-11-06");

        assertEquals(root, folder.getParent());
        assertTrue(Files.isDirectory(folder));
    }

    @Test
    void anEmptyRunStillWritesAReport() throws IOException {
        var written = write();

        assertEquals(0, written.photoCount());
        assertTrue(Files.exists(written.report()));
        assertTrue(Files.exists(written.zip()));
        assertEquals(new ArrayList<>(), names(written.photos()));
    }
}
