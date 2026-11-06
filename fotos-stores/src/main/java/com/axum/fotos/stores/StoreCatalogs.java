package com.axum.fotos.stores;

import com.axum.fotos.core.StoreCatalog;
import java.util.List;

/**
 * The stores of the hosted service, in the order configured in {@code fotos.catalog.stores} (a Spring property of
 * {@code fotos-app}, which is the only user of this class; the local tool reads its own list, {@code stores.txt}).
 *
 * <p>The order is kept as configured. The demo lookup asks all of them at once, so it is not a "stop at the first one"
 * list.
 */
public record StoreCatalogs(List<StoreCatalog> all) {

    public StoreCatalogs {
        all = List.copyOf(all);
    }
}
