package com.axum.fotos.app.store;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreProduct;
import java.util.Optional;

/**
 * A store catalog with pacing and resilience around it.
 *
 * <p>The pacing is acquired INSIDE each attempt, so the retry is paced too: a retry is a request
 * like any other and must not skip the line.
 */
public class ResilientStoreCatalog implements StoreCatalog {

    private final StoreCatalog delegate;
    private final StoreResilience resilience;
    private final StoreRateLimiter limiter;

    public ResilientStoreCatalog(StoreCatalog delegate, StoreResilience resilience, StoreRateLimiter limiter) {
        this.delegate = delegate;
        this.resilience = resilience;
        this.limiter = limiter;
    }

    @Override
    public String storeId() {
        return delegate.storeId();
    }

    @Override
    public Optional<StoreProduct> findByEan(Ean ean) {
        return resilience.call(storeId(), () -> {
            limiter.acquire(storeId());
            return delegate.findByEan(ean);
        });
    }
}
