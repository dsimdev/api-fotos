package com.axum.fotos.tool;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreException;
import com.axum.fotos.core.StoreProduct;
import com.axum.fotos.core.cache.CacheEntry;
import com.axum.fotos.core.cache.LocalCache;
import com.axum.fotos.core.image.BannerCropper;
import com.axum.fotos.core.image.BannerDetector;
import com.axum.fotos.core.image.ImageCodec;
import com.axum.fotos.core.image.ImageFingerprint;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.image.ImageQuality;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.CandidatePicker;
import com.axum.fotos.core.pick.Warning;
import com.axum.fotos.stores.ImageDownloadException;
import com.axum.fotos.stores.SafeImageDownloader.Downloaded;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the photo for ONE EAN: our cache first, the stores only if it is not there.
 *
 * <p>Every store is asked in parallel (each one is paced on its own, so this never speeds up any single store),
 * ONLY the first image of each store is looked at (the later ones can be the back of the pack), and each image
 * becomes a {@link Candidate} only if it is fit to deliver: clean, or cropped without its template panel. An image
 * that still has the panel, a background that is not white, a WebP the JDK cannot read: no candidate, never a
 * "closest one".
 *
 * <p>What is remembered:
 * <ul>
 *   <li>a photo, once found, for good (unless it is doubtful AND some store could not be asked: then the next run
 *       tries again, because the missing store may have a better one);
 *   <li>"not found" and "nothing usable" only when EVERY store answered. A store that was down, blocked or
 *       limited says nothing about the product, so that case is an error and is never stored.
 * </ul>
 */
public final class PhotoFinder {

    private static final Logger log = LoggerFactory.getLogger(PhotoFinder.class);

    /** What one store contributed. */
    private record StoreResult(String store, boolean answered, boolean sells, boolean incomplete, Candidate candidate, String note) {

        static StoreResult failed(String store, String note) {
            return new StoreResult(store, false, false, true, null, note);
        }

        static StoreResult doesNotSell(String store) {
            return new StoreResult(store, true, false, false, null, null);
        }

        static StoreResult sellsWithout(String store, String note, boolean incomplete) {
            return new StoreResult(store, true, true, incomplete, null, store + ": " + note);
        }

        static StoreResult sellsWith(String store, Candidate candidate) {
            return new StoreResult(store, true, true, false, candidate, null);
        }
    }

    private final List<StoreCatalog> stores;
    private final List<String> storeOrder;
    private final ImageFetcher fetcher;
    private final LocalCache cache;
    private final Clock clock;
    private final ExecutorService pool;
    /** How many photos are decoded and analysed at once: bounded by the cores, and capped so memory stays modest. */
    private final Semaphore imageWork = new Semaphore(Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors())));

    public PhotoFinder(List<StoreCatalog> stores, ImageFetcher fetcher, LocalCache cache, Clock clock, ExecutorService pool) {
        this.stores = List.copyOf(stores);
        this.storeOrder = stores.stream().map(StoreCatalog::storeId).toList();
        this.fetcher = fetcher;
        this.cache = cache;
        this.clock = clock;
        this.pool = pool;
    }

    /** @param refresh forget what the cache knows about this EAN and ask the stores again */
    public Lookup find(Ean ean, boolean refresh) throws IOException {
        if (refresh) cache.remove(ean);
        Optional<Lookup> cached = fromCache(ean);
        if (cached.isPresent()) return cached.get();

        List<Future<StoreResult>> pending = stores.stream().map(store -> pool.submit(() -> ask(store, ean))).toList();
        List<StoreResult> results = pending.stream().map(PhotoFinder::await).toList();

        List<Candidate> candidates = results.stream().map(StoreResult::candidate).filter(c -> c != null).toList();
        boolean incomplete = results.stream().anyMatch(StoreResult::incomplete);

        Optional<Candidate> best = CandidatePicker.pick(candidates, storeOrder);
        if (best.isPresent()) {
            Candidate chosen = best.get();
            if (incomplete && !Warning.of(chosen).isEmpty()) {
                return Lookup.found(chosen, "Algunas tiendas no respondieron: no se guardó para volver a probar la próxima vez.", false);
            }
            cache.saveReady(ean, chosen, clock.instant());
            return Lookup.found(chosen, null, false);
        }
        if (incomplete) {
            return Lookup.error("No se pudo consultar: " + notesOf(results, true));
        }
        if (results.stream().noneMatch(StoreResult::sells)) {
            String detail = "Ninguna tienda lo vende.";
            cache.saveEmpty(ean, CacheEntry.Status.NOT_FOUND, detail, clock.instant());
            return Lookup.noImage(detail, false);
        }
        String detail = "Hay tiendas que lo venden pero sin una foto utilizable. " + notesOf(results, false);
        cache.saveEmpty(ean, CacheEntry.Status.NO_USABLE, detail, clock.instant());
        return Lookup.noImage(detail, false);
    }

    private Optional<Lookup> fromCache(Ean ean) {
        Optional<CacheEntry> entry = cache.find(ean);
        if (entry.isEmpty()) return Optional.empty();
        CacheEntry e = entry.get();
        if (e.status() != CacheEntry.Status.READY) return Optional.of(Lookup.noImage(e.detail(), true));
        try {
            byte[] bytes = cache.readImage(ean, e.format());
            var candidate = new Candidate(e.store(), e.pageUrl(), e.imageUrl(), e.kind(), e.format(), e.side(),
                    e.edgeContact(), e.sharpness(), bytes);
            return Optional.of(Lookup.found(candidate, null, true));
        } catch (IOException unreadable) {
            log.warn("Cache entry for an EAN could not be read, looking it up again: {}", unreadable.getMessage());
            return Optional.empty();
        }
    }

    private StoreResult ask(StoreCatalog store, Ean ean) {
        String id = store.storeId();
        try {
            Optional<StoreProduct> product = store.findByEan(ean);
            if (product.isEmpty()) return StoreResult.doesNotSell(id);
            return judge(product.get());
        } catch (StoreException e) {
            return StoreResult.failed(id, id + ": " + describe(e.kind()));
        } catch (RuntimeException e) {
            log.warn("Unexpected failure asking {}", id, e);
            return StoreResult.failed(id, id + ": falló la consulta");
        }
    }

    private StoreResult judge(StoreProduct product) {
        String id = product.storeId();
        if (product.imageUrls().isEmpty()) return StoreResult.sellsWithout(id, "no tiene imágenes", false);
        // Position 0 only: the later ones can be the back of the pack or an ambiguous shot.
        String url = product.imageUrls().get(0);

        Downloaded downloaded;
        try {
            downloaded = fetcher.fetch(url);
        } catch (ImageDownloadException e) {
            boolean transientProblem = e.kind() == ImageDownloadException.Kind.NETWORK
                    || e.kind() == ImageDownloadException.Kind.HTTP_STATUS;
            return StoreResult.sellsWithout(id, "no se pudo bajar la imagen (" + describe(e.kind()) + ")", transientProblem);
        }
        if (downloaded.format() == ImageFormat.WEBP) {
            return StoreResult.sellsWithout(id, "la imagen está en WebP, que no se puede leer", false);
        }

        // Downloads wait on the network and can be many at once; decoding and analysing a 2000 px photo takes CPU and
        // tens of MB, so only a few of those run at the same time however many products are in flight.
        imageWork.acquireUninterruptibly();
        try {
            return analyze(id, product, downloaded);
        } finally {
            imageWork.release();
        }
    }

    private StoreResult analyze(String id, StoreProduct product, Downloaded downloaded) {
        BufferedImage image;
        try {
            image = ImageCodec.decode(downloaded.bytes());
        } catch (IOException e) {
            return StoreResult.sellsWithout(id, "la imagen no se pudo leer", false);
        }

        BannerDetector.Detection detection = BannerDetector.detect(image);
        switch (detection.kind()) {
            case UNKNOWN:
                return StoreResult.sellsWithout(id, "la foto no tiene fondo blanco", false);
            case NONE:
                return StoreResult.sellsWith(id, new Candidate(id, product.pageUrl(), downloaded.finalUrl(), Candidate.Kind.ORIGINAL,
                        downloaded.format(), Math.min(image.getWidth(), image.getHeight()), ImageQuality.productEdgeContact(image),
                        ImageQuality.sharpness(image), downloaded.bytes(), ImageFingerprint.of(image)));
            default:
                return cropped(id, product, downloaded, image);
        }
    }

    private StoreResult cropped(String id, StoreProduct product, Downloaded downloaded, BufferedImage image) {
        BannerCropper.Result result = BannerCropper.crop(image);
        if (result.status() != BannerCropper.Status.CROPPED) {
            return StoreResult.sellsWithout(id, "la foto trae el panel de la plantilla y no se pudo recortar", false);
        }
        try {
            byte[] bytes = ImageCodec.encode(result.image(), downloaded.format());
            return StoreResult.sellsWith(id, new Candidate(id, product.pageUrl(), downloaded.finalUrl(), Candidate.Kind.CROPPED,
                    downloaded.format(), result.image().getWidth(), result.edgeContact(),
                    ImageQuality.sharpness(result.image()), bytes, ImageFingerprint.of(result.image())));
        } catch (IOException e) {
            return StoreResult.sellsWithout(id, "no se pudo guardar la imagen recortada", false);
        }
    }

    private static StoreResult await(Future<StoreResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StoreResult.failed("?", "interrumpido");
        } catch (ExecutionException e) {
            log.warn("A store lookup crashed", e.getCause());
            return StoreResult.failed("?", "falló la consulta");
        }
    }

    private static String notesOf(List<StoreResult> results, boolean onlyIncomplete) {
        return results.stream().filter(r -> r.note() != null && (!onlyIncomplete || r.incomplete()))
                .map(StoreResult::note).collect(Collectors.joining("; ")) + ".";
    }

    private static String describe(StoreException.Kind kind) {
        return switch (kind) {
            case UNAVAILABLE -> "no respondió";
            case RATE_LIMITED -> "limitó las consultas";
            case BLOCKED -> "bloqueó la consulta";
            case UNEXPECTED_RESPONSE -> "respondió algo inesperado";
        };
    }

    private static String describe(ImageDownloadException.Kind kind) {
        return switch (kind) {
            case REJECTED_URL -> "dirección no permitida";
            case NETWORK -> "sin respuesta";
            case HTTP_STATUS -> "respuesta de error";
            case TOO_LARGE -> "demasiado grande";
            case NOT_AN_IMAGE -> "no es una imagen";
        };
    }

}
