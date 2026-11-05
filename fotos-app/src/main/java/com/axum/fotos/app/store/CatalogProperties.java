package com.axum.fotos.app.store;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the store catalogs are queried. Every value has a default so a missing key never turns into
 * a zero timeout or an endless retry loop.
 *
 * @param stores          the stores to ask, IN THE ORDER to ask them
 * @param connectTimeout  short on purpose: a store that takes 30 s is as useless as one that is down
 * @param attemptsToOpen  FAILED ATTEMPTS (not requests) in a row before a store is left alone for a
 *                        while; a request that fails and is retried burns two
 * @param openFor         how long a store is left alone once the circuit opens
 * @param retryWait       wait before the single retry of a network failure
 * @param maxRetryAfter   the longest Retry-After we are willing to sleep through inside a request;
 *                        a longer one opens the circuit instead
 * @param maxOpenOnRateLimit the longest we stay away from a store that rate-limited us
 * @param minInterval     the least time between two requests to the SAME store, so the service never
 *                        becomes the thing that trips a store's rate limit
 */
@ConfigurationProperties(prefix = "fotos.catalog")
public record CatalogProperties(
        List<Store> stores,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("4") int attemptsToOpen,
        @DefaultValue("30s") Duration openFor,
        @DefaultValue("200ms") Duration retryWait,
        @DefaultValue("5s") Duration maxRetryAfter,
        @DefaultValue("5m") Duration maxOpenOnRateLimit,
        @DefaultValue("250ms") Duration minInterval) {

    public CatalogProperties {
        stores = stores == null ? List.of() : List.copyOf(stores);
    }

    /** @param baseUrl scheme and host, no trailing slash, e.g. https://www.jumbo.com.ar */
    public record Store(String id, String baseUrl) {}
}
