package com.axum.fotos.core;

import java.time.Duration;

/**
 * A store could not answer. The {@link Kind} decides what the caller does next, which is why a
 * single "it failed" is not enough.
 */
public class StoreException extends RuntimeException {

    public enum Kind {
        /** Network failure, timeout or 5xx. Retrying once makes sense. */
        UNAVAILABLE,
        /** The store (or the CDN in front of it) said 429. Honor {@code retryAfter} if present. */
        RATE_LIMITED,
        /** 401 or 403, typically a bot challenge. Do NOT insist: more attempts make it worse. */
        BLOCKED,
        /** Not what a VTEX catalog answers (HTML, 404, 3xx, bad JSON, oversized). Retrying will not help. */
        UNEXPECTED_RESPONSE
    }

    private final Kind kind;
    private final Duration retryAfter;

    public StoreException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    public StoreException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    /** What the store asked us to wait, or null if it did not say. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
