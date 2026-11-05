package com.axum.fotos.core;

import java.util.Optional;

/**
 * A store whose catalog can be asked for a product by barcode.
 *
 * <p>Every source of images sits behind this port so one can be replaced or switched off without
 * touching the rest: stores change or cut their API, and some EANs exist in only one of them.
 */
public interface StoreCatalog {

    /** The id from the configuration. Used in logs, in the origin of a result and as a metric key. */
    String storeId();

    /**
     * @return the product the store sells under EXACTLY this EAN, or empty if it does not sell it.
     *         A product that merely looks similar is not a match: an empty answer is always better
     *         than an image of another variant.
     * @throws StoreException when the store could not answer; the kind says what to do about it
     */
    Optional<StoreProduct> findByEan(Ean ean);
}
