package com.axum.fotos.app.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axum.fotos.core.StoreCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@AutoConfigureMockMvc
class StoreCatalogsWiringTest {

    @Autowired
    StoreCatalogs storeCatalogs;

    @Test
    void theConfiguredStoresAreBuiltInTheConfiguredOrder() {
        // The order matters: callers stop at the first store that gives a usable image.
        assertEquals(List.of("jumbo", "carrefour", "masonline", "josimar", "cordiez", "disco", "vea"),
                storeCatalogs.all().stream().map(StoreCatalog::storeId).toList());
    }
}
