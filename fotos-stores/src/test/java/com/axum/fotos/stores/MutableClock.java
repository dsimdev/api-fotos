package com.axum.fotos.stores;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock the test moves by hand, so nothing in a test really waits. */
final class MutableClock extends Clock {

    private final ZoneId zone = ZoneId.of("America/Argentina/Buenos_Aires");
    private Instant now = Instant.parse("2026-11-05T12:00:00Z");

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }
}
