package com.axum.fotos.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreException;
import com.axum.fotos.core.StoreProduct;
import com.axum.fotos.core.cache.CacheEntry;
import com.axum.fotos.core.cache.LocalCache;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.Warning;
import com.axum.fotos.stores.ImageDownloadException;
import com.axum.fotos.stores.ImageDownloadException.Kind;
import com.axum.fotos.stores.SafeImageDownloader.Downloaded;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The service that orchestrates ask -> download -> filter -> pick -> cache, with the stores and the network replaced. */
class PhotoFinderTest {

    private static final Ean EAN = Ean.parse("7791337010604");

    @TempDir
    Path dir;

    private LocalCache cache;
    private ExecutorService pool;
    private final Map<String, Object> images = new HashMap<>();
    private final List<String> requested = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() {
        cache = new LocalCache(dir.resolve("cache"));
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private static final class Store implements StoreCatalog {
        final String id;
        final Supplier<Optional<StoreProduct>> answer;
        final AtomicInteger calls = new AtomicInteger();

        Store(String id, Supplier<Optional<StoreProduct>> answer) {
            this.id = id;
            this.answer = answer;
        }

        @Override
        public String storeId() {
            return id;
        }

        @Override
        public Optional<StoreProduct> findByEan(Ean ean) {
            calls.incrementAndGet();
            return answer.get();
        }
    }

    private static Store sells(String id, String... urls) {
        return new Store(id, () -> Optional.of(new StoreProduct(id, "Name", null, "https://page/" + id, List.of(urls))));
    }

    private static Store doesNotSell(String id) {
        return new Store(id, () -> Optional.<StoreProduct>empty());
    }

    private static Store down(String id) {
        return new Store(id, () -> {
            throw new StoreException(StoreException.Kind.UNAVAILABLE, "down");
        });
    }

    private void serves(String url, Downloaded image) {
        images.put(url, image);
    }

    private void failsWith(String url, Kind kind) {
        images.put(url, new ImageDownloadException(kind, "failed"));
    }

    private PhotoFinder finder(Store... stores) {
        ImageFetcher fetcher = url -> {
            requested.add(url);
            Object what = images.get(url);
            if (what instanceof ImageDownloadException e) throw e;
            if (what == null) throw new AssertionError("Unexpected download of " + url);
            return (Downloaded) what;
        };
        return new PhotoFinder(List.<StoreCatalog>of(stores), fetcher, cache, Clock.fixed(Instant.parse("2026-11-06T15:00:00Z"), ZoneOffset.UTC), pool);
    }

    @Test
    void aCleanOriginalIsChosenAndRememberedForGood() throws IOException {
        var jumbo = sells("jumbo", "u1");
        serves("u1", Pics.clean("u1"));
        var finder = finder(jumbo);

        var first = finder.find(EAN, false);
        var second = finder.find(EAN, false);

        assertEquals(Lookup.Status.FOUND, first.status());
        assertEquals(Candidate.Kind.ORIGINAL, first.chosen().kind());
        assertEquals("jumbo", first.chosen().store());
        assertEquals("https://page/jumbo", first.chosen().pageUrl());
        assertFalse(first.fromCache());
        assertTrue(second.fromCache(), "the second time the cache answers");
        assertEquals(1, jumbo.calls.get(), "and the store is not asked again");
        assertEquals(CacheEntry.Status.READY, cache.find(EAN).orElseThrow().status());
    }

    @Test
    void thePanelIsCutOffAndTheDeliveredBytesHaveNoneOfIt() throws IOException {
        serves("u1", Pics.withPanel("u1"));

        var lookup = finder(sells("jumbo", "u1")).find(EAN, false);

        assertEquals(Candidate.Kind.CROPPED, lookup.chosen().kind());
        assertFalse(Pics.hasPanelColour(lookup.chosen().bytes()), "the panel colour is not in what would be delivered");
    }

    @Test
    void aCleanOriginalFromAnotherStoreBeatsACroppedOne() throws IOException {
        serves("u1", Pics.withPanel("u1"));
        serves("u2", Pics.clean("u2"));

        var lookup = finder(sells("jumbo", "u1"), sells("cordiez", "u2")).find(EAN, false);

        assertEquals("cordiez", lookup.chosen().store());
        assertEquals(Candidate.Kind.ORIGINAL, lookup.chosen().kind());
    }

    @Test
    void onlyTheFirstImageOfAStoreIsLookedAt() throws IOException {
        serves("front", Pics.clean("front"));
        serves("back", Pics.clean("back"));

        finder(sells("jumbo", "front", "back")).find(EAN, false);

        assertEquals(List.of("front"), requested, "the later images can be the back of the pack");
    }

    @Test
    void whenNoStoreSellsItItIsRememberedAsNotFoundAndNotAskedAgain() throws IOException {
        var a = doesNotSell("jumbo");
        var b = doesNotSell("carrefour");
        var finder = finder(a, b);

        var first = finder.find(EAN, false);
        var second = finder.find(EAN, false);

        assertEquals(Lookup.Status.NO_IMAGE, first.status());
        assertEquals(CacheEntry.Status.NOT_FOUND, cache.find(EAN).orElseThrow().status());
        assertTrue(second.fromCache());
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
    }

    @Test
    void whenEveryStoreIsDownItIsAnErrorAndNothingIsRemembered() throws IOException {
        var lookup = finder(down("jumbo"), down("carrefour")).find(EAN, false);

        assertEquals(Lookup.Status.ERROR, lookup.status());
        assertTrue(cache.find(EAN).isEmpty(), "an error says nothing about the product");
    }

    @Test
    void oneStoreDownAndTheOthersEmptyCannotBeCalledNotFound() throws IOException {
        var lookup = finder(down("jumbo"), doesNotSell("carrefour")).find(EAN, false);

        assertEquals(Lookup.Status.ERROR, lookup.status());
        assertTrue(lookup.detail().contains("jumbo"), lookup.detail());
        assertTrue(cache.find(EAN).isEmpty());
    }

    @Test
    void aPhotoFromOneStoreIsKeptEvenIfAnotherStoreWasDown() throws IOException {
        serves("u1", Pics.clean("u1"));

        var lookup = finder(down("jumbo"), sells("carrefour", "u1")).find(EAN, false);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertEquals(CacheEntry.Status.READY, cache.find(EAN).orElseThrow().status(), "a clean photo needs no better one");
    }

    @Test
    void aDoubtfulPhotoIsNotRememberedWhileAStoreIsMissingSoTheNextRunTriesAgain() throws IOException {
        serves("u1", Pics.sliced("u1"));

        var lookup = finder(down("jumbo"), sells("carrefour", "u1")).find(EAN, false);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertTrue(lookup.warnings().contains(Warning.TOUCHES_EDGE), lookup.warnings().toString());
        assertTrue(lookup.detail().contains("no se guardó"), lookup.detail());
        assertTrue(cache.find(EAN).isEmpty(), "the store that was down may have a better one");
    }

    @Test
    void aDownloadThatFailsInOneStoreDoesNotStopAnotherStoresPhoto() throws IOException {
        failsWith("u1", Kind.NETWORK);
        serves("u2", Pics.clean("u2"));

        var lookup = finder(sells("jumbo", "u1"), sells("carrefour", "u2")).find(EAN, false);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertEquals("carrefour", lookup.chosen().store());
    }

    @Test
    void ifTheOnlyStoreThatSellsItFailsToServeTheImageItIsAnErrorNotANegative() throws IOException {
        failsWith("u1", Kind.NETWORK);

        var lookup = finder(sells("jumbo", "u1"), doesNotSell("carrefour")).find(EAN, false);

        assertEquals(Lookup.Status.ERROR, lookup.status());
        assertTrue(cache.find(EAN).isEmpty());
    }

    @Test
    void anImageThatHasOnlyThePanelIsNeverDelivered() throws IOException {
        serves("u1", Pics.onlyPanel("u1"));

        var lookup = finder(sells("jumbo", "u1")).find(EAN, false);

        assertEquals(Lookup.Status.NO_IMAGE, lookup.status());
        assertTrue(lookup.detail().contains("panel"), lookup.detail());
        assertEquals(CacheEntry.Status.NO_USABLE, cache.find(EAN).orElseThrow().status());
    }

    @Test
    void aWebpIsSkippedAndSaidSo() throws IOException {
        serves("u1", Pics.webp("u1"));

        var lookup = finder(sells("josimar", "u1")).find(EAN, false);

        assertEquals(Lookup.Status.NO_IMAGE, lookup.status());
        assertTrue(lookup.detail().contains("WebP"), lookup.detail());
    }

    @Test
    void aWebpDoesNotHideAnotherStoresJpeg() throws IOException {
        serves("u1", Pics.webp("u1"));
        serves("u2", Pics.clean("u2"));

        var lookup = finder(sells("josimar", "u1"), sells("carrefour", "u2")).find(EAN, false);

        assertEquals("carrefour", lookup.chosen().store());
    }

    @Test
    void aPhotoWithoutAWhiteBackgroundIsNotAClosestMatch() throws IOException {
        serves("u1", Pics.greyBackground("u1"));

        var lookup = finder(sells("jumbo", "u1")).find(EAN, false);

        assertEquals(Lookup.Status.NO_IMAGE, lookup.status());
        assertTrue(lookup.detail().contains("fondo"), lookup.detail());
    }

    @Test
    void aStoreThatSellsItWithoutImagesGivesNoPhoto() throws IOException {
        var lookup = finder(sells("jumbo")).find(EAN, false);

        assertEquals(Lookup.Status.NO_IMAGE, lookup.status());
        assertEquals(CacheEntry.Status.NO_USABLE, cache.find(EAN).orElseThrow().status());
    }

    @Test
    void aBodyThatIsNotAnImageIsAFinalAnswerForThatStore() throws IOException {
        failsWith("u1", Kind.NOT_AN_IMAGE);

        var lookup = finder(sells("jumbo", "u1")).find(EAN, false);

        assertEquals(Lookup.Status.NO_IMAGE, lookup.status());
        assertEquals(CacheEntry.Status.NO_USABLE, cache.find(EAN).orElseThrow().status());
    }

    @Test
    void aBugInOneStoreIsAFailureOfThatStoreNotACrashOfTheRun() throws IOException {
        var broken = new Store("jumbo", () -> {
            throw new IllegalStateException("bug");
        });
        serves("u1", Pics.clean("u1"));

        var lookup = finder(broken, sells("carrefour", "u1")).find(EAN, false);

        assertEquals(Lookup.Status.FOUND, lookup.status());
    }

    @Test
    void aRefreshAsksTheStoresAgainEvenThoughTheBankHasTheEan() throws IOException {
        var jumbo = sells("jumbo", "u1");
        serves("u1", Pics.clean("u1"));
        var finder = finder(jumbo);
        finder.find(EAN, false);

        var again = finder.find(EAN, true);

        assertFalse(again.fromCache());
        assertEquals(2, jumbo.calls.get());
    }

    // ---- the bank: a refresh never loses a photo, a person's photo is never refreshed, the original is kept ----

    private byte[] storedPhoto() throws IOException {
        return cache.readImage(EAN, cache.find(EAN).orElseThrow().format());
    }

    /** The bank has to outlive the stores: asking again and getting nothing must NOT cost the photo that is already there. */
    @Test
    void aRefreshWhileEveryStoreIsDownKeepsTheStoredPhoto() throws IOException {
        serves("u1", Pics.clean("u1"));
        finder(sells("jumbo", "u1")).find(EAN, false);
        byte[] before = storedPhoto();

        var lookup = finder(down("jumbo")).find(EAN, true);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertTrue(lookup.detail().contains("Se conservó la foto guardada"), lookup.detail());
        assertArrayEquals(before, lookup.chosen().bytes());
        assertEquals(CacheEntry.Status.READY, cache.find(EAN).orElseThrow().status());
        assertArrayEquals(before, storedPhoto());
    }

    @Test
    void aRefreshWhenNoStoreSellsItAnymoreKeepsTheStoredPhoto() throws IOException {
        serves("u1", Pics.clean("u1"));
        finder(sells("jumbo", "u1")).find(EAN, false);

        var lookup = finder(doesNotSell("jumbo"), doesNotSell("carrefour")).find(EAN, true);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertTrue(lookup.detail().contains("Ninguna tienda la vende hoy") && lookup.detail().contains("Se conservó"), lookup.detail());
        assertEquals(CacheEntry.Status.READY, cache.find(EAN).orElseThrow().status(), "the photo is not replaced by 'not found'");
    }

    @Test
    void aRefreshWhenTheStoresOnlyHaveUnusablePhotosKeepsTheStoredPhoto() throws IOException {
        serves("u1", Pics.clean("u1"));
        finder(sells("jumbo", "u1")).find(EAN, false);
        serves("u2", Pics.onlyPanel("u2"));

        var lookup = finder(sells("jumbo", "u2")).find(EAN, true);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertTrue(lookup.detail().contains("Se conservó"), lookup.detail());
        assertEquals(CacheEntry.Status.READY, cache.find(EAN).orElseThrow().status());
    }

    @Test
    void aRefreshThatFindsAnotherPhotoReplacesItAndKeepsTheOldOneAsThePreviousOne() throws IOException {
        serves("u1", Pics.clean("u1"));
        finder(sells("jumbo", "u1")).find(EAN, false);
        byte[] old = storedPhoto();
        serves("u2", Pics.withPanel("u2"));

        var lookup = finder(sells("carrefour", "u2")).find(EAN, true);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertFalse(lookup.fromCache(), "this one is new");
        assertEquals("carrefour", cache.find(EAN).orElseThrow().store());
        assertFalse(java.util.Arrays.equals(old, storedPhoto()));
        assertArrayEquals(old, java.nio.file.Files.readAllBytes(dir.resolve("cache").resolve(EAN.value()).resolve("previa.jpg")));
    }

    @Test
    void aRefreshOfAnEanThatHadNoPhotoCanFindOne() throws IOException {
        finder(doesNotSell("jumbo")).find(EAN, false);
        assertEquals(CacheEntry.Status.NOT_FOUND, cache.find(EAN).orElseThrow().status());
        serves("u1", Pics.clean("u1"));

        var lookup = finder(sells("jumbo", "u1")).find(EAN, true);

        assertEquals(Lookup.Status.FOUND, lookup.status());
        assertEquals(CacheEntry.Status.READY, cache.find(EAN).orElseThrow().status());
    }

    @Test
    void aPhotoAPersonPutInIsUsedAsItIsAndNeverRefreshedNorAskedAboutAtAll() throws IOException {
        cache.saveManual(EAN, Pics.clean("mine").bytes(), ImageFormat.JPEG, 1000, Instant.parse("2026-11-06T15:00:00Z"));
        var jumbo = sells("jumbo", "u1");
        serves("u1", Pics.withPanel("u1"));

        var plain = finder(jumbo).find(EAN, false);
        var refreshed = finder(jumbo).find(EAN, true);

        assertTrue(plain.chosen().own() && refreshed.chosen().own());
        assertEquals("propia", refreshed.chosen().store());
        assertEquals(0, jumbo.calls.get(), "not even a refresh asks a store about a photo a person chose");
        assertEquals(CacheEntry.Source.MANUAL, cache.find(EAN).orElseThrow().source());
    }

    @Test
    void aPhotoAPersonPutInOnlyWarnsAboutItsSize() throws IOException {
        cache.saveManual(EAN, Pics.clean("mine").bytes(), ImageFormat.JPEG, 300, Instant.parse("2026-11-06T15:00:00Z"));

        var lookup = finder(doesNotSell("jumbo")).find(EAN, false);

        assertEquals(List.of(Warning.LOW_RESOLUTION), lookup.warnings(), "no 'touches the edge' or 'soft' about a photo you chose");
    }

    /** What lets a better cropper redo the crop later: the store's file before the crop is saved next to the cropped one. */
    @Test
    void whenThePanelIsCutTheStoresFileBeforeTheCropIsSavedInTheBank() throws IOException {
        var downloaded = Pics.withPanel("u1");
        serves("u1", downloaded);

        var lookup = finder(sells("jumbo", "u1")).find(EAN, false);

        var entry = cache.find(EAN).orElseThrow();
        assertEquals(Candidate.Kind.CROPPED, lookup.chosen().kind());
        assertTrue(entry.hasOriginal());
        assertArrayEquals(downloaded.bytes(), cache.readOriginal(EAN, entry.format()), "exactly the bytes the store served");
        assertFalse(java.util.Arrays.equals(downloaded.bytes(), storedPhoto()), "and the delivered one is the cropped one");
    }

    @Test
    void aCleanPhotoIsNotSavedTwiceBecauseItIsItsOwnOriginal() throws IOException {
        serves("u1", Pics.clean("u1"));

        finder(sells("jumbo", "u1")).find(EAN, false);

        assertFalse(cache.find(EAN).orElseThrow().hasOriginal());
    }

    @Test
    void everyStoreIsAskedForEachNewEan() throws IOException {
        var a = sells("jumbo", "u1");
        var b = doesNotSell("carrefour");
        var c = doesNotSell("masonline");
        serves("u1", Pics.clean("u1"));

        finder(a, b, c).find(EAN, false);

        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
        assertEquals(1, c.calls.get());
    }
}
