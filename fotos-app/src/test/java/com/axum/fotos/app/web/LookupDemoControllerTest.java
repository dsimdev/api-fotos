package com.axum.fotos.app.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.axum.fotos.app.store.StoreCatalogs;
import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreException;
import com.axum.fotos.core.StoreProduct;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "fotos.demo.enabled=true")
@AutoConfigureMockMvc
class LookupDemoControllerTest {

    @Autowired
    MockMvc mvc;

    /** Three fake stores, one per outcome, so no test touches a real store. */
    @TestConfiguration
    static class FakeStores {
        @Bean
        @Primary
        StoreCatalogs fakeStores() {
            return new StoreCatalogs(List.of(
                    store("alpha", ean -> Optional.of(new StoreProduct("alpha", "Lasagna 250 g", "MATARAZZO",
                            "https://alpha.example/p", List.of("https://cdn.example/front.jpg",
                            "https://cdn.example/back.jpg")))),
                    store("beta", ean -> Optional.empty()),
                    store("gamma", ean -> {
                        throw new StoreException(StoreException.Kind.UNAVAILABLE, "gamma is down");
                    })));
        }

        private static StoreCatalog store(String id, java.util.function.Function<Ean, Optional<StoreProduct>> answer) {
            return new StoreCatalog() {
                @Override
                public String storeId() {
                    return id;
                }

                @Override
                public Optional<StoreProduct> findByEan(Ean ean) {
                    return answer.apply(ean);
                }
            };
        }
    }

    @Test
    void showsWhatEachStoreSaidInTheConfiguredOrderAndAFailureDoesNotBreakTheOthers() throws Exception {
        mvc.perform(get("/admin/v1/lookup").param("ean", "7790070337016"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ean").value("7790070337016"))
                .andExpect(jsonPath("$.stores.length()").value(3))
                .andExpect(jsonPath("$.stores[0].store").value("alpha"))
                .andExpect(jsonPath("$.stores[0].status").value("FOUND"))
                .andExpect(jsonPath("$.stores[0].brand").value("MATARAZZO"))
                .andExpect(jsonPath("$.stores[0].imageUrls[0]").value("https://cdn.example/front.jpg"))
                .andExpect(jsonPath("$.stores[1].store").value("beta"))
                .andExpect(jsonPath("$.stores[1].status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.stores[2].store").value("gamma"))
                .andExpect(jsonPath("$.stores[2].status").value("ERROR"))
                .andExpect(jsonPath("$.stores[2].error").value("UNAVAILABLE"));
    }

    @Test
    void anInvalidEanIsA400AndTheInputIsNotEchoed() throws Exception {
        mvc.perform(get("/admin/v1/lookup").param("ean", "<script>alert(1)</script>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("script"))));
        mvc.perform(get("/admin/v1/lookup").param("ean", "7790070337017")) // wrong check digit
                .andExpect(status().isBadRequest());
    }
}
