package com.axum.fotos.app.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * How the embedded panel is served. These only run when the panel was built into the classpath
 * (node_modules present): the build does not require Node, so without it they are skipped, not failed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PanelServingTest {

    private static final Path PANEL = Path.of("build", "resources", "main", "static", "admin");

    @Autowired
    MockMvc mvc;

    private static void assumePanelBuilt() {
        assumeTrue(Files.exists(PANEL.resolve("index.html")), "panel not built into the classpath, skipping");
    }

    @Test
    void adminIsAForwardNeverARedirect() throws Exception {
        assumePanelBuilt();
        // A redirect would send the browser a Location built with the path THIS process sees, which
        // behind a nested proxy lands outside the app. It reached production three times in api-impuestos.
        mvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/index.html"))
                .andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/admin/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/admin/index.html"))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void indexIsRevalidatedOnEveryLoad() throws Exception {
        assumePanelBuilt();
        mvc.perform(get("/admin/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Fotos")));
    }

    @Test
    void hashedChunksAreCachedForAYearAndImmutable() throws Exception {
        assumePanelBuilt();
        var chunk = firstChunk();
        var url = "/admin/" + PANEL.relativize(chunk).toString().replace('\\', '/');
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("max-age=31536000"),
                                org.hamcrest.Matchers.containsString("immutable"))));
    }

    private static Path firstChunk() throws IOException {
        try (Stream<Path> files = Files.walk(PANEL.resolve("_next").resolve("static"))) {
            var chunk = files.filter(p -> p.toString().endsWith(".js")).findFirst();
            assertTrue(chunk.isPresent(), "the built panel has no JS chunk under _next/static");
            return chunk.get();
        }
    }
}
