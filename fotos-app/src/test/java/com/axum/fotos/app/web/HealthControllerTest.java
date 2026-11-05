package com.axum.fotos.app.web;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void reportsTheBuildThatIsRunning() throws Exception {
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                // The build replaces the @tokens@ of application.yml: a raw token or "dev" would mean it did not.
                .andExpect(jsonPath("$.version").value(matchesPattern("\\d+\\.\\d+\\.\\d+")))
                .andExpect(jsonPath("$.commit").value(matchesPattern("[0-9a-f]{7,40}(\\+cambios)?|unknown")))
                // In Argentina's zone, not the server clock's (the store server runs in UTC-12).
                .andExpect(jsonPath("$.startedAt").value(endsWith("-03:00")))
                .andExpect(jsonPath("$.upFor").exists());
    }

    @Test
    void upForSpeaksInWords() {
        var t0 = Instant.parse("2026-11-05T12:00:00Z");
        assertEquals("30 s", HealthController.upFor(t0, t0.plusSeconds(30)));
        assertEquals("6 min", HealthController.upFor(t0, t0.plusSeconds(6 * 60 + 10)));
        assertEquals("19 h 23 min", HealthController.upFor(t0, t0.plusSeconds(19 * 3600 + 23 * 60)));
        assertEquals("2 d 5 h", HealthController.upFor(t0, t0.plusSeconds(2 * 86400 + 5 * 3600)));
    }
}
