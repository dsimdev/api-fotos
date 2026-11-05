package com.axum.fotos.app.store;

import com.axum.fotos.core.StoreCatalog;
import java.util.List;

/**
 * The stores to ask, in the order to ask them (see {@code fotos.catalog.stores}).
 *
 * <p>The order matters: callers stop at the first store that gives a usable image, so the stores
 * that cover the most come first.
 */
public record StoreCatalogs(List<StoreCatalog> all) {

    public StoreCatalogs {
        all = List.copyOf(all);
    }
}
