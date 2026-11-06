package com.axum.fotos.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final List<Duration> sleeps = new ArrayList<>();

    @Test
    void theFirstCallDoesNotWaitAndTheNextOnesQueueUp() {
        var limiter = new StoreRateLimiter(Duration.ofMillis(250), clock, sleeps::add);

        limiter.acquire("jumbo");
        limiter.acquire("jumbo");
        limiter.acquire("jumbo");

        // The clock does not move, so each caller reserved the next free slot.
        assertEquals(List.of(Duration.ofMillis(250), Duration.ofMillis(500)), sleeps);
    }

    @Test
    void noWaitOnceTheIntervalHasPassed() {
        var limiter = new StoreRateLimiter(Duration.ofMillis(250), clock, sleeps::add);

        limiter.acquire("jumbo");
        clock.advance(Duration.ofMillis(300));
        limiter.acquire("jumbo");

        assertTrue(sleeps.isEmpty());
    }

    @Test
    void storesArePacedIndependently() {
        var limiter = new StoreRateLimiter(Duration.ofMillis(250), clock, sleeps::add);

        limiter.acquire("jumbo");
        limiter.acquire("carrefour");
        limiter.acquire("masonline");

        assertTrue(sleeps.isEmpty(), "a slow store must never delay the others");
    }

    @Test
    void aZeroIntervalTurnsItOff() {
        var limiter = new StoreRateLimiter(Duration.ZERO, clock, sleeps::add);

        limiter.acquire("jumbo");
        limiter.acquire("jumbo");

        assertTrue(sleeps.isEmpty());
    }
}
