package com.axum.fotos.core.pick;

import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.image.ImageQuality;

/**
 * An image of the product that is fit to be delivered: a clean store original, or a store image whose template
 * panel was cut off. An image that still carries a panel is never a candidate.
 *
 * <p>Each one keeps where it came from (store, product page, image URL). The report shows the store, whether the panel
 * was cut, the size and the warnings; the product page and the image URL are only kept in the cache entry, and nothing
 * says why this candidate was chosen over the others.
 *
 * @param side        the shorter side, in pixels, of the image in {@code bytes}
 * @param edgeContact see {@link ImageQuality#productEdgeContact}
 * @param sharpness   see {@link ImageQuality#sharpness}
 * @param bytes       exactly what would be written to the delivery, in {@code format}
 * @param fingerprint see {@link com.axum.fotos.core.image.ImageFingerprint}; 0 when unknown (a photo read back from the cache)
 */
public record Candidate(String store, String pageUrl, String imageUrl, Kind kind, ImageFormat format, int side,
                        double edgeContact, double sharpness, byte[] bytes, long fingerprint) {

    public Candidate(String store, String pageUrl, String imageUrl, Kind kind, ImageFormat format, int side,
                     double edgeContact, double sharpness, byte[] bytes) {
        this(store, pageUrl, imageUrl, kind, format, side, edgeContact, sharpness, bytes, 0L);
    }

    public enum Kind {
        /** The store's own file, untouched. */
        ORIGINAL,
        /** The store's file with the template panel removed and the product re-centred. */
        CROPPED
    }

    public boolean touchesEdge() {
        return edgeContact >= ImageQuality.TOUCHING_EDGE;
    }

    public boolean soft() {
        return sharpness < ImageQuality.SOFT;
    }
}
