package com.axum.fotos.app.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The least time between two requests to the SAME store.
 *
 * <p>Stores are independent third parties: this paces each one on its own, so a slow store never
 * delays the others. It exists so that a batch of EANs does not become a burst: the stores publish no
 * limit for this endpoint and the CDNs in front of them (CloudFront, Cloudflare) decide on their own.
 *
 * <p>Each caller reserves the next free slot under the lock and then waits OUTSIDE it, so waiting
 * never blocks other stores.
 */
public class StoreRateLimiter {

    private final Duration minInterval;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Instant> nextFreeSlot = new HashMap<>();

    public StoreRateLimiter(Duration minInterval, Clock clock, Sleeper sleeper) {
        this.minInterval = minInterval;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Blocks until this store may be called again. */
    public void acquire(String storeId) {
        if (minInterval.isZero() || minInterval.isNegative()) return;
        Duration wait;
        synchronized (this) {
            var now = clock.instant();
            var next = nextFreeSlot.get(storeId);
            var slot = next != null && next.isAfter(now) ? next : now;
            nextFreeSlot.put(storeId, slot.plus(minInterval));
            wait = Duration.between(now, slot);
        }
        if (!wait.isZero() && !wait.isNegative()) {
            sleeper.sleep(wait);
        }
    }
}
