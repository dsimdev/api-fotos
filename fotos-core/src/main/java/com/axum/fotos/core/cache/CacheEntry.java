package com.axum.fotos.core.cache;

import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import java.time.Instant;

/**
 * What the local cache remembers about one EAN. Entries NEVER expire (decided 2026-11-06): the picture of a
 * product does not change under the same EAN, and a lookup is a request to a third party. Refreshing is an
 * explicit act.
 *
 * <p>An error is never an entry: only an answer is. A store that was down says nothing about the product.
 *
 * @param store      for READY: the store the image came from. Null otherwise
 * @param detail     for NOT_FOUND and NO_USABLE: what was found, for the report. Null for READY
 */
public record CacheEntry(Status status, Instant fetchedAt, String store, String pageUrl, String imageUrl,
                         Candidate.Kind kind, ImageFormat format, int side, double edgeContact, double sharpness,
                         String detail) {

    public enum Status {
        /** There is a photo, kept next to the entry. */
        READY,
        /** EVERY store answered and none sells this EAN. Not looked up again unless a refresh is asked for. */
        NOT_FOUND,
        /** Some store sells it but none has an image fit to deliver (only with the template panel, only WebP...). */
        NO_USABLE
    }
}
