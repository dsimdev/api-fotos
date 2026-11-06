package com.axum.fotos.core;

import java.util.List;

/**
 * What one store says about a product it sells under an exact EAN.
 *
 * @param storeId     which store answered (the id from the configuration, e.g. "jumbo")
 * @param productName the store's own name for it; it is NOT used to decide a match, the EAN is
 * @param brand       may be null
 * @param pageUrl     the product page in the store: it is the origin of the image. The hosted service
 *                    returned it so a result could be audited; the local tool keeps it in the cache entry.
 *                    May be null
 * @param imageUrls   in the store's order. <b>Position 0 is the main picture; later ones can be the
 *                    back of the pack or an ambiguous shot</b> (Hesperidina's second image is its
 *                    back label), so a caller must not treat them as equivalent
 */
public record StoreProduct(String storeId, String productName, String brand, String pageUrl,
                           List<String> imageUrls) {

    public StoreProduct {
        imageUrls = List.copyOf(imageUrls);
    }
}
