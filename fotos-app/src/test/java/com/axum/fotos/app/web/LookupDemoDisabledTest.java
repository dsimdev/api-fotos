package com.axum.fotos.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The demo endpoint has no authentication, so by default it must simply not exist. */
@SpringBootTest
@AutoConfigureMockMvc
class LookupDemoDisabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void theLookupEndpointDoesNotExistUnlessDemoIsEnabled() throws Exception {
        mvc.perform(get("/admin/v1/lookup").param("ean", "7790070337016"))
                .andExpect(status().isNotFound());
    }
}
