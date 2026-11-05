package com.axum.fotos.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.app.config.HttpConfig;
import com.axum.fotos.app.store.CatalogProperties;
import com.axum.fotos.app.store.VtexStoreCatalog;
import com.axum.fotos.core.Ean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The contract against the REAL stores: it is what tells us if the {@code alternateIds_Ean} filter
 * (not documented) stops working. Excluded from the normal build so it never hits third parties by
 * surprise; run it on purpose with {@code -PincludeLiveTests}. It makes a handful of requests.
 */
@Tag("live")
class VtexLiveTest {

    /** Matarazzo lasagna 250 g: the same EAN the measurements started with. */
    private static final String SOLD = "7790070337016";
    /** A valid EAN that no store sells. */
    private static final String NOT_SOLD = "7790000000003";

    private VtexStoreCatalog store(String id, String baseUrl) {
        var props = new CatalogProperties(List.of(), Duration.ofSeconds(5), Duration.ofSeconds(15), 4,
                Duration.ofSeconds(30), Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMinutes(5),
                Duration.ZERO);
        return new VtexStoreCatalog(id, baseUrl, new HttpConfig().restClient(RestClient.builder(), props, "live-test"),
                new ObjectMapper());
    }

    @Test
    void jumboAndCarrefourStillAnswerByExactEan() {
        for (var store : List.of(store("jumbo", "https://www.jumbo.com.ar"),
                store("carrefour", "https://www.carrefour.com.ar"))) {
            var product = store.findByEan(Ean.parse(SOLD));

            assertTrue(product.isPresent(), store.storeId() + " no longer answers by EAN");
            assertFalse(product.get().imageUrls().isEmpty(), store.storeId() + " answered without images");
            assertTrue(product.get().imageUrls().get(0).startsWith("https://"));
            System.out.println(store.storeId() + " -> " + product.get().productName() + " | "
                    + product.get().imageUrls().size() + " image(s)");
        }
    }

    @Test
    void anEanNobodySellsComesBackEmpty() {
        assertTrue(Ean.isValid(NOT_SOLD), "the test EAN itself must be valid");
        assertTrue(store("jumbo", "https://www.jumbo.com.ar").findByEan(Ean.parse(NOT_SOLD)).isEmpty());
    }
}
