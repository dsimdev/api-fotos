package com.axum.fotos.app.store;

import com.axum.fotos.core.StoreException;
import java.time.Duration;

/** Waiting, behind an interface so the tests do not really sleep. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    Sleeper REAL = duration -> {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            // Someone cancelled the request: stop insisting with the store.
            Thread.currentThread().interrupt();
            throw new StoreException(StoreException.Kind.UNAVAILABLE, "Interrupted while waiting");
        }
    };
}
