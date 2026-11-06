package com.axum.fotos.core.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.cache.CacheEntry.Status;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.Candidate.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalCacheTest {

    private static final Ean EAN = Ean.parse("7791337010604");
    private static final Instant NOW = Instant.parse("2026-11-06T15:00:00Z");

    @TempDir
    Path dir;

    private LocalCache cache;

    @BeforeEach
    void open() {
        cache = new LocalCache(dir.resolve("cache"));
    }

    private static Candidate candidate(ImageFormat format, byte[] bytes) {
        return new Candidate("carrefour", "https://www.carrefour.com.ar/p", "https://img.example/a.jpg", Kind.CROPPED,
                format, 1052, 0.0, 210.5, bytes);
    }

    @Test
    void anUnknownEanIsNotFoundInTheCache() {
        assertTrue(cache.find(EAN).isEmpty());
    }

    @Test
    void aReadyEntryKeepsThePhotoAndWhereItCameFrom() throws IOException {
        byte[] photo = {1, 2, 3, 4};

        cache.saveReady(EAN, candidate(ImageFormat.JPEG, photo), NOW);

        var entry = cache.find(EAN).orElseThrow();
        assertEquals(Status.READY, entry.status());
        assertEquals("carrefour", entry.store());
        assertEquals("https://www.carrefour.com.ar/p", entry.pageUrl());
        assertEquals(Kind.CROPPED, entry.kind());
        assertEquals(1052, entry.side());
        assertEquals(210.5, entry.sharpness(), 0.0001);
        assertEquals(NOW, entry.fetchedAt());
        assertArrayEquals(photo, cache.readImage(EAN, entry.format()));
    }

    @Test
    void aNotFoundAnswerIsRememberedToo() throws IOException {
        cache.saveEmpty(EAN, Status.NOT_FOUND, "No store sells it", NOW);

        var entry = cache.find(EAN).orElseThrow();
        assertEquals(Status.NOT_FOUND, entry.status());
        assertEquals("No store sells it", entry.detail());
    }

    @Test
    void savingTwiceIsIdempotentAndTheLatestWins() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{9, 9}), NOW.plusSeconds(60));

        var entry = cache.find(EAN).orElseThrow();
        assertArrayEquals(new byte[]{9, 9}, cache.readImage(EAN, entry.format()));
        assertEquals(NOW.plusSeconds(60), entry.fetchedAt());
        try (Stream<Path> files = Files.list(cache.root().resolve(EAN.value()))) {
            assertEquals(2, files.count(), "one entry and one image, no temporary files");
        }
    }

    @Test
    void changingTheFormatLeavesNoOrphanImage() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        cache.saveReady(EAN, candidate(ImageFormat.PNG, new byte[]{2}), NOW);

        assertEquals(ImageFormat.PNG, cache.find(EAN).orElseThrow().format());
        assertFalse(Files.exists(cache.root().resolve(EAN.value()).resolve("image.jpg")));
    }

    @Test
    void aLaterEmptyAnswerReplacesAPhoto() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);

        cache.saveEmpty(EAN, Status.NO_USABLE, "Only with the panel", NOW);

        assertEquals(Status.NO_USABLE, cache.find(EAN).orElseThrow().status());
        assertFalse(Files.exists(cache.root().resolve(EAN.value()).resolve("image.jpg")));
    }

    @Test
    void anEntryWhoseImageWasDeletedIsLookedUpAgain() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        Files.delete(cache.root().resolve(EAN.value()).resolve("image.jpg"));

        assertTrue(cache.find(EAN).isEmpty());
    }

    @Test
    void aDamagedEntryIsLookedUpAgainInsteadOfTrusted() throws IOException {
        Path folder = Files.createDirectories(cache.root().resolve(EAN.value()));
        Files.writeString(folder.resolve("entry.properties"), "status=SOMETHING_ELSE\nfetchedAt=yesterday\n");

        assertTrue(cache.find(EAN).isEmpty());
    }

    @Test
    void removingAnEanMakesTheNextRunLookItUpAgain() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);

        cache.remove(EAN);

        assertTrue(cache.find(EAN).isEmpty());
        assertFalse(Files.exists(cache.root().resolve(EAN.value())));
        cache.remove(EAN);
    }

    @Test
    void aReadyEntryCannotBeSavedWithoutAPhoto() {
        assertThrows(IllegalArgumentException.class, () -> cache.saveEmpty(EAN, Status.READY, "x", NOW));
    }
}
