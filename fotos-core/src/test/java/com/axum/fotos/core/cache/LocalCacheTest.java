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
import java.util.List;
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

    private List<String> filesOf(Ean ean) throws IOException {
        try (Stream<Path> files = Files.list(cache.root().resolve(ean.value()))) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void savingTheSamePhotoTwiceIsIdempotent() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW.plusSeconds(60));

        assertEquals(List.of("entry.properties", "image.jpg"), filesOf(EAN), "no temporary files and no pointless previous photo");
        assertEquals(NOW.plusSeconds(60), cache.find(EAN).orElseThrow().fetchedAt());
    }

    /** The bank has to outlive its sources: a different photo never replaces one without keeping it. */
    @Test
    void aDifferentPhotoReplacesTheOldOneAndKeepsItAsThePreviousOne() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{9, 9}), NOW.plusSeconds(60));

        var entry = cache.find(EAN).orElseThrow();
        assertArrayEquals(new byte[]{9, 9}, cache.readImage(EAN, entry.format()));
        assertEquals(NOW.plusSeconds(60), entry.fetchedAt());
        assertEquals(List.of("entry.properties", "image.jpg", "previa.jpg"), filesOf(EAN));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(cache.root().resolve(EAN.value()).resolve("previa.jpg")));
    }

    @Test
    void onlyTheLastReplacedPhotoIsKeptAsThePreviousOne() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{2}), NOW);
        cache.saveReady(EAN, candidate(ImageFormat.PNG, new byte[]{3}), NOW);

        assertEquals(List.of("entry.properties", "image.png", "previa.jpg"), filesOf(EAN), "one previous slot, of whatever format");
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(cache.root().resolve(EAN.value()).resolve("previa.jpg")));
    }

    /** What lets a better cropper redo the crop: the store's file, before the crop, next to the delivered photo. */
    @Test
    void theOriginalBeforeTheCropIsKeptNextToTheDeliveredPhoto() throws IOException {
        var cropped = new Candidate("carrefour", "https://page", "https://img", Kind.CROPPED, ImageFormat.JPEG, 900, 0.0, 200.0,
                new byte[]{1, 1}, 0L, new byte[]{7, 7, 7});

        cache.saveReady(EAN, cropped, NOW);

        var entry = cache.find(EAN).orElseThrow();
        assertTrue(entry.hasOriginal());
        assertArrayEquals(new byte[]{7, 7, 7}, cache.readOriginal(EAN, entry.format()));
        assertArrayEquals(new byte[]{1, 1}, cache.readImage(EAN, entry.format()));
        assertEquals(List.of("entry.properties", "image.jpg", "original.jpg"), filesOf(EAN));
    }

    @Test
    void aCleanPhotoHasNoSeparateOriginalBecauseItIsTheOriginal() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);

        assertFalse(cache.find(EAN).orElseThrow().hasOriginal());
    }

    @Test
    void anOriginalThatBelongedToAReplacedCropDoesNotStayBehind() throws IOException {
        cache.saveReady(EAN, new Candidate("carrefour", "p", "i", Kind.CROPPED, ImageFormat.JPEG, 900, 0.0, 200.0,
                new byte[]{1}, 0L, new byte[]{7}), NOW);

        cache.saveReady(EAN, candidate(ImageFormat.PNG, new byte[]{5}), NOW);

        assertFalse(cache.find(EAN).orElseThrow().hasOriginal());
        assertFalse(Files.exists(cache.root().resolve(EAN.value()).resolve("original.jpg")));
    }

    @Test
    void aPhotoPutInByHandIsMarkedAsSuchAndHasTheOwnStore() throws IOException {
        cache.saveManual(EAN, new byte[]{4, 4}, ImageFormat.PNG, 1200, NOW);

        var entry = cache.find(EAN).orElseThrow();
        assertEquals(CacheEntry.Source.MANUAL, entry.source());
        assertEquals(Candidate.OWN_STORE, entry.store());
        assertEquals(Kind.ORIGINAL, entry.kind());
        assertEquals(1200, entry.side());
        assertArrayEquals(new byte[]{4, 4}, cache.readImage(EAN, ImageFormat.PNG));
    }

    @Test
    void aPhotoFromAStoreIsMarkedAsFromAStore() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);

        assertEquals(CacheEntry.Source.STORE, cache.find(EAN).orElseThrow().source());
    }

    /** Entries written before the bank had a source or an original still read fine. */
    @Test
    void anEntryWrittenByAnEarlierVersionIsStillReadAsFromAStoreWithoutAnOriginal() throws IOException {
        Path folder = Files.createDirectories(cache.root().resolve(EAN.value()));
        Files.write(folder.resolve("image.jpg"), new byte[]{1});
        Files.writeString(folder.resolve("entry.properties"), String.join("\n", "status=READY", "fetchedAt=2026-11-06T15:00:00Z",
                "store=carrefour", "kind=CROPPED", "format=JPEG", "side=1052", "edgeContact=0.0", "sharpness=210.5"));

        var entry = cache.find(EAN).orElseThrow();

        assertEquals(CacheEntry.Source.STORE, entry.source());
        assertFalse(entry.hasOriginal());
    }

    @Test
    void theEansInTheBankAreListedInOrderAndAnythingElseIsNot() throws IOException {
        cache.saveReady(Ean.parse("7791337010710"), candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);
        Files.createDirectories(cache.root().resolve(".git"));
        Files.createDirectories(cache.root().resolve("no es un ean"));
        Files.writeString(cache.root().resolve("indice.csv"), "x");

        assertEquals(List.of(EAN, Ean.parse("7791337010710")), cache.eans());
    }

    @Test
    void anEmptyAnswerOverAPhotoNeverLosesThePhoto() throws IOException {
        cache.saveReady(EAN, candidate(ImageFormat.JPEG, new byte[]{1}), NOW);

        cache.saveEmpty(EAN, Status.NOT_FOUND, "No store sells it", NOW);

        assertEquals(Status.NOT_FOUND, cache.find(EAN).orElseThrow().status());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(cache.root().resolve(EAN.value()).resolve("previa.jpg")));
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
