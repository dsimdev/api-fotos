package com.axum.fotos.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.Candidate.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankIndexTest {

    private static final Instant NOW = Instant.parse("2026-11-06T15:00:00Z");

    @TempDir
    Path dir;

    private LocalCache bank;

    @BeforeEach
    void open() {
        bank = new LocalCache(dir.resolve("banco"));
    }

    private List<String> lines() throws IOException {
        String text = Files.readString(bank.root().resolve("indice.csv"), StandardCharsets.UTF_8).replace("﻿", "");
        return text.lines().toList();
    }

    private static Candidate store(String store, Kind kind, byte[] original) {
        return new Candidate(store, "https://www.carrefour.com.ar/p", "https://img.example/a.jpg", kind, ImageFormat.JPEG, 1052, 0.0, 200.0,
                new byte[]{1}, 0L, original);
    }

    @Test
    void anEmptyBankStillHasAnIndexWithItsHeader() throws IOException {
        assertEquals(0, BankIndex.write(bank));

        assertEquals(1, lines().size());
        assertTrue(lines().get(0).startsWith("EAN,Estado,Origen,Tienda"));
    }

    @Test
    void eachEanIsOneLineInEanOrderWithWhereItsPhotoCameFrom() throws IOException {
        bank.saveReady(Ean.parse("7791337010710"), store("jumbo", Kind.ORIGINAL, null), NOW);
        bank.saveReady(Ean.parse("7791337010604"), store("carrefour", Kind.CROPPED, new byte[]{7}), NOW);

        assertEquals(2, BankIndex.write(bank));

        var lines = lines();
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("\"7791337010604\",READY,tienda,\"carrefour\",recortada,jpg,1052,si,"), lines.get(1));
        assertTrue(lines.get(2).startsWith("\"7791337010710\",READY,tienda,\"jumbo\",original,jpg,1052,no,"), lines.get(2));
        assertTrue(lines.get(1).contains("https://www.carrefour.com.ar/p") && lines.get(1).contains("https://img.example/a.jpg"));
    }

    @Test
    void anEanWithoutAPhotoIsListedToo_thatIsWhereYouSeeWhatIsMissing() throws IOException {
        bank.saveEmpty(Ean.parse("7791337010604"), CacheEntry.Status.NOT_FOUND, "Ninguna tienda lo vende.", NOW);

        BankIndex.write(bank);

        assertTrue(lines().get(1).startsWith("\"7791337010604\",NOT_FOUND,,\"\",,,,,"), lines().get(1));
        assertTrue(lines().get(1).contains("Ninguna tienda lo vende."));
    }

    @Test
    void aPhotoPutInByHandSaysSo() throws IOException {
        bank.saveManual(Ean.parse("7791337010604"), new byte[]{1}, ImageFormat.PNG, 1200, NOW);

        BankIndex.write(bank);

        assertTrue(lines().get(1).startsWith("\"7791337010604\",READY,propia,\"propia\",original,png,1200,no,"), lines().get(1));
    }

    @Test
    void textFromOutsideCannotBecomeAFormula() throws IOException {
        var evil = new Candidate("jumbo", "=HYPERLINK(\"http://evil\")", "+cmd", Kind.ORIGINAL, ImageFormat.JPEG, 900, 0.0, 200.0, new byte[]{1});
        bank.saveReady(Ean.parse("7791337010604"), evil, NOW);

        BankIndex.write(bank);

        assertTrue(lines().get(1).contains("\"'=HYPERLINK("), lines().get(1));
        assertTrue(lines().get(1).contains("\"'+cmd\""), lines().get(1));
    }

    /** The index is what goes to git: the same bank must give the same bytes, or every run would be a commit. */
    @Test
    void theSameBankGivesTheSameIndexByteForByte() throws IOException {
        bank.saveReady(Ean.parse("7791337010604"), store("carrefour", Kind.CROPPED, new byte[]{7}), NOW);
        bank.saveEmpty(Ean.parse("7791337009608"), CacheEntry.Status.NO_USABLE, "Solo con panel.", NOW);

        BankIndex.write(bank);
        byte[] first = Files.readAllBytes(bank.root().resolve("indice.csv"));
        BankIndex.write(bank);

        assertEquals(new String(first, StandardCharsets.UTF_8), Files.readString(bank.root().resolve("indice.csv"), StandardCharsets.UTF_8));
    }

    @Test
    void noTemporaryFileIsLeftBehind() throws IOException {
        bank.saveReady(Ean.parse("7791337010604"), store("jumbo", Kind.ORIGINAL, null), NOW);

        BankIndex.write(bank);

        assertFalse(Files.exists(bank.root().resolve("indice.csv.tmp")));
    }

    @Test
    void theIndexDoesNotListTheSkuBecauseTheBankDoesNotKnowIt() throws IOException {
        bank.saveReady(Ean.parse("7791337010604"), store("jumbo", Kind.ORIGINAL, null), NOW);

        BankIndex.write(bank);

        assertFalse(lines().get(0).toLowerCase().contains("sku"));
    }
}
