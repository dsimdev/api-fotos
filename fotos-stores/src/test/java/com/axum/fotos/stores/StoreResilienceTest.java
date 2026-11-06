package com.axum.fotos.stores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.StoreException;
import com.axum.fotos.core.StoreException.Kind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoreResilienceTest {

    private final MutableClock clock = new MutableClock();
    private final List<Duration> sleeps = new ArrayList<>();
    private StoreResilience resilience;

    @BeforeEach
    void setUp() {
        // 4 failed attempts open the circuit for 30 s; one retry after 200 ms; a Retry-After up to 5 s
        // is waited out; the pause after a 429 is capped at 5 min. random 0.5 = no jitter at all.
        resilience = new StoreResilience(4, Duration.ofSeconds(30), Duration.ofMillis(200),
                Duration.ofSeconds(5), Duration.ofMinutes(5), clock, sleeps::add, () -> 0.5);
    }

    /** Plays a script of results: a String is a success, a StoreException is thrown. The last step repeats. */
    private static final class Script implements Supplier<String> {
        private final Object[] steps;
        int calls;

        Script(Object... steps) {
            this.steps = steps;
        }

        @Override
        public String get() {
            var step = steps[Math.min(calls, steps.length - 1)];
            calls++;
            if (step instanceof StoreException e) throw e;
            return (String) step;
        }
    }

    private static StoreException unavailable(String message) {
        return new StoreException(Kind.UNAVAILABLE, message);
    }

    private static StoreException rateLimited(Duration retryAfter) {
        return new StoreException(Kind.RATE_LIMITED, "429", retryAfter, null);
    }

    private static StoreException blocked() {
        return new StoreException(Kind.BLOCKED, "403");
    }

    private static StoreException unexpected() {
        return new StoreException(Kind.UNEXPECTED_RESPONSE, "not a catalog");
    }

    @Test
    void successPassesThroughWithoutWaiting() {
        var script = new Script("ok");
        assertEquals("ok", resilience.call("jumbo", script));
        assertEquals(1, script.calls);
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void aNetworkFailureIsRetriedOnceAfterAShortWait() {
        var script = new Script(unavailable("reset"), "ok");
        assertEquals("ok", resilience.call("jumbo", script));
        assertEquals(2, script.calls);
        assertEquals(List.of(Duration.ofMillis(200)), sleeps);
    }

    @Test
    void twoFailuresInARowPropagateTheSecondError() {
        var script = new Script(unavailable("first"), unavailable("second"));
        var e = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals("second", e.getMessage());
        assertEquals(2, script.calls);
    }

    @Test
    void theCircuitOpensAfterFourFailedAttemptsAndThenFailsFast() {
        var script = new Script(unavailable("down"));
        assertThrows(StoreException.class, () -> resilience.call("jumbo", script)); // attempts 1 and 2
        assertThrows(StoreException.class, () -> resilience.call("jumbo", script)); // attempts 3 and 4: opens
        assertEquals(4, script.calls);

        var e = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(4, script.calls, "an open circuit must not call the store");
        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertEquals(Duration.ofSeconds(30), e.retryAfter());
    }

    @Test
    void anUnexpectedResponseIsNeitherRetriedNorCounted() {
        var script = new Script(unexpected());
        for (int i = 0; i < 10; i++) {
            var e = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
            assertEquals(Kind.UNEXPECTED_RESPONSE, e.kind());
        }
        assertEquals(10, script.calls, "one attempt per call, and the circuit never opens");
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void blockedOpensTheCircuitAtOnceWithoutRetry() {
        var script = new Script(blocked());
        var first = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(Kind.BLOCKED, first.kind());
        assertEquals(1, script.calls);
        assertTrue(sleeps.isEmpty());

        var fast = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(1, script.calls);
        assertEquals(Duration.ofSeconds(30), fast.retryAfter());

        clock.advance(Duration.ofSeconds(31));
        assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(2, script.calls, "after the pause the store is tried again");
    }

    @Test
    void aShortRetryAfterIsHonoredWithOneRetry() {
        var script = new Script(rateLimited(Duration.ofSeconds(2)), "ok");
        assertEquals("ok", resilience.call("jumbo", script));
        assertEquals(2, script.calls);
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
    }

    @Test
    void aLongRetryAfterOpensTheCircuitInsteadOfHoldingTheRequest() {
        var script = new Script(rateLimited(Duration.ofSeconds(60)));
        var first = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(Kind.RATE_LIMITED, first.kind());
        assertEquals(1, script.calls);
        assertTrue(sleeps.isEmpty(), "never sleep a minute inside a request");

        var fast = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(1, script.calls);
        assertEquals(Duration.ofSeconds(60), fast.retryAfter());

        clock.advance(Duration.ofSeconds(61));
        assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(2, script.calls);
    }

    @Test
    void aRateLimitWithoutRetryAfterLeavesTheStoreAloneForTheNormalPause() {
        var script = new Script(rateLimited(null));
        assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(1, script.calls);
        assertTrue(sleeps.isEmpty());
        var fast = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(Duration.ofSeconds(30), fast.retryAfter());
    }

    @Test
    void aHugeRetryAfterIsCapped() {
        var script = new Script(rateLimited(Duration.ofHours(1)));
        assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        var fast = assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(Duration.ofMinutes(5), fast.retryAfter());
    }

    @Test
    void storesAreIndependent() {
        var blockedStore = new Script(blocked());
        assertThrows(StoreException.class, () -> resilience.call("jumbo", blockedStore));

        assertEquals("fine", resilience.call("carrefour", new Script("fine")),
                "one store being left alone must not cost the others");
    }

    @Test
    void afterThePauseOneMoreFailureClosesTheDoorAgainWithoutARetry() {
        assertThrows(StoreException.class, () -> resilience.call("jumbo", new Script(blocked())));
        clock.advance(Duration.ofSeconds(31));

        var script = new Script(unavailable("still down"));
        assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(1, script.calls, "half open: the failure reopens the circuit, no retry");
        assertTrue(sleeps.isEmpty());

        assertThrows(StoreException.class, () -> resilience.call("jumbo", script));
        assertEquals(1, script.calls, "and it is open again");
    }

    @Test
    void aSuccessClearsTheCount() {
        assertThrows(StoreException.class,
                () -> resilience.call("jumbo", new Script(unavailable("a"), unavailable("b")))); // count 2
        assertEquals("ok", resilience.call("jumbo", new Script("ok")));                           // reset
        assertThrows(StoreException.class,
                () -> resilience.call("jumbo", new Script(unavailable("a"), unavailable("b")))); // count 2, not 4

        assertEquals("ok", resilience.call("jumbo", new Script("ok")), "the circuit must not be open");
    }

    @Test
    void oldFailuresAreForgotten() {
        assertThrows(StoreException.class,
                () -> resilience.call("jumbo", new Script(unavailable("a"), unavailable("b")))); // count 2
        clock.advance(Duration.ofSeconds(31));
        assertThrows(StoreException.class,
                () -> resilience.call("jumbo", new Script(unavailable("a"), unavailable("b")))); // 0 again, then 2

        assertEquals("ok", resilience.call("jumbo", new Script("ok")),
                "failures far apart are not a trend: the circuit must not be open");
    }

    @Test
    void jitterStaysWithinPlusOrMinusTwentyPercent() {
        var low = new StoreResilience(4, Duration.ofSeconds(30), Duration.ofMillis(1000), Duration.ofSeconds(5),
                Duration.ofMinutes(5), clock, sleeps::add, () -> 0.0);
        var high = new StoreResilience(4, Duration.ofSeconds(30), Duration.ofMillis(1000), Duration.ofSeconds(5),
                Duration.ofMinutes(5), clock, sleeps::add, () -> 0.999999);
        low.call("a", new Script(unavailable("x"), "ok"));
        high.call("b", new Script(unavailable("x"), "ok"));
        assertEquals(Duration.ofMillis(800), sleeps.get(0));
        assertTrue(sleeps.get(1).toMillis() <= 1200 && sleeps.get(1).toMillis() >= 1199);
    }
}
