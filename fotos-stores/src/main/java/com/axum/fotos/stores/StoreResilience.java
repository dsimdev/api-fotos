package com.axum.fotos.stores;

import com.axum.fotos.core.StoreException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What to do when a store fails: retry ONCE what is worth retrying, and stop insisting with a store
 * that is down, rate-limiting us or blocking us.
 *
 * <p><b>Retrying here is safe</b>: the lookup is a GET with no effects, so the worst case of a retry
 * is one more request.
 *
 * <p><b>Once, not three times.</b> Someone is waiting for the answer: the retry is for the one-off
 * network failure, not for waiting out a store that is down.
 *
 * <p><b>The circuit is per store.</b> One store being down must not cost the others, nor every later
 * request to that same store, the full timeout.
 *
 * <p>Each kind of failure is treated differently, because they mean different things:
 * <ul>
 *   <li>{@code UNEXPECTED_RESPONSE}: the store is up but is not answering like a VTEX catalog. Not a
 *       failure of the store, so it is neither retried nor counted toward the circuit.</li>
 *   <li>{@code BLOCKED} (401/403, usually a bot challenge): NO retry, and the circuit opens at once.
 *       Insisting against a challenge only makes it worse.</li>
 *   <li>{@code RATE_LIMITED} (429): the store said when to come back. If that is short we wait it out
 *       and retry once; if it is long, or it did not say, we leave the store alone instead of
 *       holding a request.</li>
 *   <li>{@code UNAVAILABLE}: network or 5xx. One retry after a short wait.</li>
 * </ul>
 *
 * <p>Waits get a +/-20% jitter so several requests that failed together do not retry together.
 */
public class StoreResilience {

    private static final Logger log = LoggerFactory.getLogger(StoreResilience.class);

    private final int attemptsToOpen;
    private final Duration openFor;
    private final Duration retryWait;
    private final Duration maxRetryAfter;
    private final Duration maxOpenOnRateLimit;
    private final Clock clock;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public StoreResilience(int attemptsToOpen, Duration openFor, Duration retryWait, Duration maxRetryAfter,
                           Duration maxOpenOnRateLimit, Clock clock, Sleeper sleeper, DoubleSupplier random) {
        this.attemptsToOpen = attemptsToOpen;
        this.openFor = openFor;
        this.retryWait = retryWait;
        this.maxRetryAfter = maxRetryAfter;
        this.maxOpenOnRateLimit = maxOpenOnRateLimit;
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
    }

    public <T> T call(String storeId, Supplier<T> call) {
        var breaker = breakers.computeIfAbsent(storeId, id -> new Breaker());
        failFastIfOpen(storeId, breaker);
        try {
            T result = call.get();
            breaker.reset();
            return result;
        } catch (StoreException first) {
            return afterFirstFailure(storeId, breaker, call, first);
        }
    }

    private <T> T afterFirstFailure(String storeId, Breaker breaker, Supplier<T> call, StoreException first) {
        switch (first.kind()) {
            case UNEXPECTED_RESPONSE -> throw first;
            case BLOCKED -> {
                open(storeId, breaker, openFor, "blocked");
                throw first;
            }
            case RATE_LIMITED -> {
                var wait = first.retryAfter();
                if (wait == null || wait.compareTo(maxRetryAfter) > 0) {
                    open(storeId, breaker, pauseAfterRateLimit(first), "rate limited");
                    throw first;
                }
                log.warn("Store {} rate-limited us; waiting {} before one retry", storeId, wait);
                // If this very failure opened the circuit, the retry is one more request to a store
                // we just decided to leave alone.
                if (breaker.failedAttempt(clock.instant(), openFor, attemptsToOpen, storeId)) throw first;
                sleeper.sleep(jitter(wait));
            }
            case UNAVAILABLE -> {
                log.warn("Store {} failed ({}); retrying once", storeId, first.getMessage());
                if (breaker.failedAttempt(clock.instant(), openFor, attemptsToOpen, storeId)) throw first;
                sleeper.sleep(jitter(retryWait));
            }
        }
        return retryOnce(storeId, breaker, call);
    }

    private <T> T retryOnce(String storeId, Breaker breaker, Supplier<T> call) {
        try {
            T result = call.get();
            breaker.reset();
            return result;
        } catch (StoreException second) {
            switch (second.kind()) {
                case UNEXPECTED_RESPONSE -> { }
                case BLOCKED -> open(storeId, breaker, openFor, "blocked");
                case RATE_LIMITED -> open(storeId, breaker, pauseAfterRateLimit(second), "rate limited");
                case UNAVAILABLE -> breaker.failedAttempt(clock.instant(), openFor, attemptsToOpen, storeId);
            }
            // The SECOND error is the one that propagates: it is the most recent and describes the
            // store's current state.
            throw second;
        }
    }

    private void failFastIfOpen(String storeId, Breaker breaker) {
        var remaining = breaker.remainingOpenTime(clock.instant(), attemptsToOpen);
        if (remaining != null) {
            // UNAVAILABLE with the remaining time: for the caller it is "the store is not available",
            // the same as if the timeout had expired, without paying for it.
            throw new StoreException(StoreException.Kind.UNAVAILABLE,
                    "Store " + storeId + " is being left alone for " + remaining.toSeconds() + " s",
                    remaining, null);
        }
    }

    private void open(String storeId, Breaker breaker, Duration pause, String reason) {
        breaker.open(clock.instant(), pause);
        log.warn("Store {}: {}; not calling it for {} s", storeId, reason, pause.toSeconds());
    }

    /** What the store asked for, capped; if it did not say, the normal pause. */
    private Duration pauseAfterRateLimit(StoreException e) {
        var asked = e.retryAfter();
        if (asked == null) return openFor;
        return asked.compareTo(maxOpenOnRateLimit) > 0 ? maxOpenOnRateLimit : asked;
    }

    private Duration jitter(Duration base) {
        long permille = 800 + (long) (400 * random.getAsDouble());
        return base.multipliedBy(permille).dividedBy(1000);
    }

    /** The state of one store. Everything is under the monitor of the instance. */
    private static final class Breaker {
        private int failedAttempts;
        private Instant lastFailureAt;
        private Instant openUntil;

        synchronized void reset() {
            failedAttempts = 0;
            lastFailureAt = null;
            openUntil = null;
        }

        /**
         * Counts a failed attempt, forgetting old ones: failures that are far apart are not a
         * trend, so the counter restarts when the last one is older than the pause.
         */
        /** @return true if this attempt opened the circuit */
        synchronized boolean failedAttempt(Instant now, Duration openFor, int attemptsToOpen, String storeId) {
            if (lastFailureAt != null && Duration.between(lastFailureAt, now).compareTo(openFor) > 0) {
                failedAttempts = 0;
            }
            failedAttempts++;
            lastFailureAt = now;
            if (failedAttempts >= attemptsToOpen) {
                openUntil = now.plus(openFor);
                log.warn("Store {}: {} failed attempts in a row; not calling it for {} s",
                        storeId, failedAttempts, openFor.toSeconds());
                return true;
            }
            return false;
        }

        synchronized void open(Instant now, Duration pause) {
            openUntil = now.plus(pause);
            lastFailureAt = now;
        }

        /**
         * @return how long the circuit stays open, or null if calls may go through. When the pause
         *         ends the circuit is half open: ONE more failure closes the door again, one success
         *         clears everything.
         */
        synchronized Duration remainingOpenTime(Instant now, int attemptsToOpen) {
            if (openUntil == null) return null;
            if (now.isBefore(openUntil)) return Duration.between(now, openUntil);
            openUntil = null;
            failedAttempts = Math.max(0, attemptsToOpen - 1);
            // The half-open state starts NOW. Without this the last failure would already be older
            // than the pause and the "forget old failures" rule would wipe the count we just set.
            lastFailureAt = now;
            return null;
        }
    }
}
