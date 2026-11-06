package com.axum.fotos.tool;

import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.cache.LocalCache;
import com.axum.fotos.stores.ResilientStoreCatalog;
import com.axum.fotos.stores.SafeImageDownloader;
import com.axum.fotos.stores.Sleeper;
import com.axum.fotos.stores.StoreHttp;
import com.axum.fotos.stores.StoreRateLimiter;
import com.axum.fotos.stores.StoreResilience;
import com.axum.fotos.stores.VtexStoreCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.client.RestClient;

/**
 * Assembles the real finder: the stores with pacing, retry and circuit breaker (the same pieces and numbers as
 * the service), the safe image downloader and the cache folder. The numbers are the ones measured in
 * docs/proyecto/mediciones.md: no store limited anything at one request per 250 ms.
 */
final class ToolWiring {

    private ToolWiring() {}

    static PhotoFinder finder(String version, Path cacheDir, ExecutorService pool) throws IOException {
        Clock clock = Clock.systemUTC();
        RestClient client = StoreHttp.client(RestClient.builder(), Duration.ofSeconds(5), Duration.ofSeconds(10), version);
        var mapper = new ObjectMapper();
        var limiter = new StoreRateLimiter(Duration.ofMillis(250), clock, Sleeper.REAL);
        var resilience = new StoreResilience(4, Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofSeconds(5),
                Duration.ofMinutes(5), clock, Sleeper.REAL, () -> ThreadLocalRandom.current().nextDouble());

        List<StoreCatalog> stores = StoreList.load().stream()
                .<StoreCatalog>map(s -> new ResilientStoreCatalog(new VtexStoreCatalog(s.id(), s.baseUrl(), client, mapper), resilience, limiter))
                .toList();

        var downloader = new SafeImageDownloader(SafeImageDownloader.newHttpClient(Duration.ofSeconds(5)),
                SafeImageDownloader.AddressPolicy.PUBLIC_ONLY, SafeImageDownloader.Resolver.SYSTEM,
                new StoreRateLimiter(Duration.ofMillis(250), clock, Sleeper.REAL), SafeImageDownloader.DEFAULT_MAX_BYTES,
                Duration.ofSeconds(20), version);
        return new PhotoFinder(stores, downloader::download, new LocalCache(cacheDir), clock, pool);
    }

    static int storeCount() throws IOException {
        return StoreList.load().size();
    }
}
