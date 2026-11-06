package com.axum.fotos.core.pick;

import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.image.ImageQuality;

/**
 * An image of the product that is fit to be delivered: a clean store original, or a store image whose template
 * panel was cut off. An image that still carries a panel is never a candidate.
 *
 * <p>Each one keeps where it came from (store, product page, image URL) so the report can say why a photo was
 * chosen and the person can judge it.
 *
 * @param side        the shorter side, in pixels, of the image in {@code bytes}
 * @param edgeContact see {@link ImageQuality#productEdgeContact}
 * @param sharpness   see {@link ImageQuality#sharpness}
 * @param bytes       exactly what would be written to the delivery, in {@code format}
 */
public record Candidate(String store, String pageUrl, String imageUrl, Kind kind, ImageFormat format, int side,
                        double edgeContact, double sharpness, byte[] bytes) {

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
