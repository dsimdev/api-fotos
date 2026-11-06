package com.axum.fotos.app.web;

import com.axum.fotos.app.store.StoreCatalogs;
import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreException;
import com.axum.fotos.core.StoreProduct;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * What EVERY store answers for one EAN, side by side. It exists so a person can look at the VTEX
 * source and judge whether it is useful, before the real API and the image pipeline are built.
 *
 * <p><b>OFF by default</b> ({@code fotos.demo.enabled}). It has no authentication and it makes
 * requests to third parties on demand, so anyone who could reach it could use this service to hammer
 * the stores from our IP. It is turned on on purpose, on a machine where nobody else can reach it,
 * and it is replaced by the authenticated panel API: it must not be deployed enabled.
 *
 * <p>Stores are asked in parallel (each one is independent and paced on its own), and the answer keeps
 * the configured order. A failing store shows up as an ERROR line with its kind; it never breaks the others.
 */
@RestController
@ConditionalOnProperty(name = "fotos.demo.enabled", havingValue = "true")
public class LookupDemoController {

    private static final Logger log = LoggerFactory.getLogger(LookupDemoController.class);

    private final StoreCatalogs stores;

    LookupDemoController(StoreCatalogs stores, @Value("${server.address:}") String serverAddress) {
        requireLoopback(serverAddress);
        this.stores = stores;
        log.warn("The EAN lookup demo is ENABLED (fotos.demo.enabled=true): it has no authentication. "
                + "Never leave it on in a deployed instance.");
    }

    /**
     * The demo has no authentication and makes requests to third parties, so it REFUSES TO START unless
     * the server listens on this machine alone. A warning in the log is not enough: nobody reads the
     * startup log of a service that came up fine. Without {@code server.address} Tomcat listens on every
     * interface, which is exactly what this guard exists to stop.
     */
    static void requireLoopback(String serverAddress) {
        var address = serverAddress == null ? "" : serverAddress.trim();
        boolean loopback = address.equals("127.0.0.1") || address.equalsIgnoreCase("localhost")
                || address.equals("::1") || address.equals("[::1]");
        if (!loopback) {
            throw new IllegalStateException(
                    "fotos.demo.enabled=true has no authentication, so the server must listen on this machine "
                            + "only. Set server.address=127.0.0.1 (SERVER_ADDRESS=127.0.0.1), or turn the demo off.");
        }
    }

    @GetMapping("/admin/v1/lookup")
    public LookupResponse lookup(@RequestParam("ean") String rawEan) {
        // The message does not echo the input: it came from outside.
        var ean = Ean.tryParse(rawEan)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid EAN"));

        List<Future<StoreResult>> pending;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            pending = stores.all().stream().map(store -> executor.submit(() -> ask(store, ean))).toList();
        } // close() waits for every task
        return new LookupResponse(ean.value(), pending.stream().map(LookupDemoController::result).toList());
    }

    private StoreResult ask(StoreCatalog store, Ean ean) {
        long start = System.nanoTime();
        try {
            return store.findByEan(ean)
                    .map(product -> found(store.storeId(), product, start))
                    .orElseGet(() -> new StoreResult(store.storeId(), "NOT_FOUND", null, null, null, null,
                            List.of(), millisSince(start)));
        } catch (StoreException e) {
            return error(store.storeId(), e.kind().name(), start);
        } catch (RuntimeException e) {
            log.warn("Unexpected failure asking store {}: {}", store.storeId(), e.getClass().getSimpleName());
            return error(store.storeId(), "UNEXPECTED", start);
        }
    }

    private static StoreResult found(String storeId, StoreProduct product, long start) {
        return new StoreResult(storeId, "FOUND", null, product.productName(), product.brand(), product.pageUrl(),
                product.imageUrls(), millisSince(start));
    }

    private static StoreResult error(String storeId, String error, long start) {
        return new StoreResult(storeId, "ERROR", error, null, null, null, List.of(), millisSince(start));
    }

    private static StoreResult result(Future<StoreResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted");
        } catch (ExecutionException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Lookup failed");
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * @param status FOUND, NOT_FOUND (the store does not sell it) or ERROR (it could not answer)
     * @param error  for ERROR, the kind: UNAVAILABLE, RATE_LIMITED, BLOCKED, UNEXPECTED_RESPONSE
     */
    public record StoreResult(String store, String status, String error, String productName, String brand,
                              String pageUrl, List<String> imageUrls, long millis) {}

    public record LookupResponse(String ean, List<StoreResult> stores) {}
}
