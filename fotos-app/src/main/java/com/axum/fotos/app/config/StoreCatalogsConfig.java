package com.axum.fotos.app.config;

import com.axum.fotos.app.store.CatalogProperties;
import com.axum.fotos.stores.ResilientStoreCatalog;
import com.axum.fotos.stores.Sleeper;
import com.axum.fotos.stores.StoreCatalogs;
import com.axum.fotos.stores.StoreRateLimiter;
import com.axum.fotos.stores.StoreResilience;
import com.axum.fotos.stores.VtexStoreCatalog;
import com.axum.fotos.core.StoreCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Builds the configured stores, each wrapped with pacing and resilience. */
@Configuration
public class StoreCatalogsConfig {

    @Bean
    Sleeper sleeper() {
        return Sleeper.REAL;
    }

    @Bean
    StoreRateLimiter storeRateLimiter(CatalogProperties properties, Clock clock, Sleeper sleeper) {
        return new StoreRateLimiter(properties.minInterval(), clock, sleeper);
    }

    @Bean
    StoreResilience storeResilience(CatalogProperties p, Clock clock, Sleeper sleeper) {
        return new StoreResilience(p.attemptsToOpen(), p.openFor(), p.retryWait(), p.maxRetryAfter(),
                p.maxOpenOnRateLimit(), clock, sleeper, () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    StoreCatalogs storeCatalogs(CatalogProperties properties, RestClient client, ObjectMapper mapper,
                                StoreResilience resilience, StoreRateLimiter limiter) {
        List<StoreCatalog> stores = properties.stores().stream()
                .<StoreCatalog>map(s -> new ResilientStoreCatalog(
                        new VtexStoreCatalog(s.id(), s.baseUrl(), client, mapper), resilience, limiter))
                .toList();
        return new StoreCatalogs(stores);
    }
}
