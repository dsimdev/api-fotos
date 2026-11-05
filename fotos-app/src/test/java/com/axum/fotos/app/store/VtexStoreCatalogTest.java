package com.axum.fotos.app.store;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.app.config.HttpConfig;
import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreException;
import com.axum.fotos.core.StoreException.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class VtexStoreCatalogTest {

    private static final String EAN = "7790070337016";
    private static final String PATH = VtexStoreCatalog.SEARCH_PATH;

    private WireMockServer server;
    private VtexStoreCatalog catalog;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        catalog = catalogFor("http://localhost:" + server.port(), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    /** The REAL client configuration (HttpConfig), not a copy of it, so its headers and redirect policy are tested. */
    private VtexStoreCatalog catalogFor(String baseUrl, Duration readTimeout) {
        var props = new CatalogProperties(List.of(), Duration.ofSeconds(2), readTimeout, 4, Duration.ofSeconds(30),
                Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ZERO);
        RestClient client = new HttpConfig().restClient(RestClient.builder(), props, "9.9.9");
        return new VtexStoreCatalog("jumbo", baseUrl, client, new ObjectMapper());
    }

    private void stubSearch(ResponseDefinitionBuilder response) {
        server.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("fq", equalTo("alternateIds_Ean:" + EAN))
                .willReturn(response));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private StoreException failure() {
        return assertThrows(StoreException.class, () -> catalog.findByEan(Ean.parse(EAN)));
    }

    // ---- what a match is -------------------------------------------------------------------

    @Test
    void findsTheProductByExactEan() throws IOException {
        stubSearch(json(200, fixture("vtex-jumbo-7790070337016.json")));

        var found = catalog.findByEan(Ean.parse(EAN));

        assertTrue(found.isPresent());
        var product = found.get();
        assertEquals("jumbo", product.storeId());
        assertEquals("MATARAZZO", product.brand());
        assertEquals("https://www.jumbo.com.ar/lasagna-matarazzo-fort-x-250-gr/p", product.pageUrl());
        assertEquals(1, product.imageUrls().size());
        assertTrue(product.imageUrls().get(0)
                .startsWith("https://jumboargentina.vteximg.com.br/arquivos/ids/929069/7790070337016.jpg"));
    }

    @Test
    void anEmptyArrayMeansTheStoreDoesNotSellIt() {
        stubSearch(json(200, "[]"));
        assertTrue(catalog.findByEan(Ean.parse(EAN)).isEmpty());
    }

    @Test
    void aProductWhoseItemsHaveAnotherEanIsNotAMatch() {
        // A search can answer similar products. Only an item with EXACTLY this EAN counts: an empty
        // answer is always better than the picture of another variant.
        stubSearch(json(200, """
                [{"productName":"Lasagna Matarazzo 500 g","brand":"Matarazzo","link":"https://x/p",
                  "items":[{"ean":"7790070337023","images":[{"imageUrl":"https://cdn/other.jpg"}]}]}]
                """));
        assertTrue(catalog.findByEan(Ean.parse(EAN)).isEmpty());
    }

    @Test
    void picksTheItemWithTheEanAmongSeveralAndKeepsTheImageOrder() {
        stubSearch(json(200, """
                [{"productName":"Pack","brand":"B","link":"https://x/p","items":[
                   {"ean":"7790070337023","images":[{"imageUrl":"https://cdn/other.jpg"}]},
                   {"ean":"7790070337016","images":[
                      {"imageUrl":"https://cdn/front.jpg"},{"imageUrl":"  "},{"imageUrl":"https://cdn/back.jpg"}]}]}]
                """));

        var product = catalog.findByEan(Ean.parse(EAN)).orElseThrow();

        // Position 0 is the main picture; a blank URL is dropped, the rest keep the store's order.
        assertEquals(List.of("https://cdn/front.jpg", "https://cdn/back.jpg"), product.imageUrls());
    }

    @Test
    void aRelativeLinkIsMadeAbsolute() {
        stubSearch(json(200, """
                [{"productName":"P","brand":"B","link":"/lasagna/p",
                  "items":[{"ean":"7790070337016","images":[{"imageUrl":"https://cdn/a.jpg"}]}]}]
                """));
        var product = catalog.findByEan(Ean.parse(EAN)).orElseThrow();
        assertEquals("http://localhost:" + server.port() + "/lasagna/p", product.pageUrl());
    }

    // ---- what we send ----------------------------------------------------------------------

    @Test
    void identifiesItselfWithAGenericUserAgentAndNoContactDetail() {
        stubSearch(json(200, "[]"));

        catalog.findByEan(Ean.parse(EAN));

        server.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("User-Agent", matching("api-fotos/9\\.9\\.9"))
                .withHeader("Accept", containing("application/json")));
        var userAgent = server.getAllServeEvents().get(0).getRequest().getHeader("User-Agent");
        assertFalse(userAgent.contains("@"), "no personal contact detail goes to third parties");
    }

    // ---- status codes become the failure each one calls for ----------------------------------

    @Test
    void a429CarriesTheRetryAfterTheStoreAsked() {
        stubSearch(aResponse().withStatus(429).withHeader("Retry-After", "7"));
        var e = failure();
        assertEquals(Kind.RATE_LIMITED, e.kind());
        assertEquals(Duration.ofSeconds(7), e.retryAfter());
    }

    @Test
    void a429WithoutRetryAfterHasNone() {
        stubSearch(aResponse().withStatus(429));
        var e = failure();
        assertEquals(Kind.RATE_LIMITED, e.kind());
        assertNull(e.retryAfter());
    }

    @Test
    void a5xxIsUnavailable() {
        stubSearch(aResponse().withStatus(503).withHeader("Retry-After", "3"));
        var e = failure();
        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertEquals(Duration.ofSeconds(3), e.retryAfter());
    }

    @Test
    void a401And403AreBlocked() {
        stubSearch(aResponse().withStatus(403));
        assertEquals(Kind.BLOCKED, failure().kind());
        server.resetAll();
        stubSearch(aResponse().withStatus(401));
        assertEquals(Kind.BLOCKED, failure().kind());
    }

    @Test
    void a404MeansItIsNotAVtexCatalog() {
        stubSearch(aResponse().withStatus(404).withBody("<html>not found</html>"));
        assertEquals(Kind.UNEXPECTED_RESPONSE, failure().kind());
    }

    @Test
    void aRedirectIsNotFollowed() {
        server.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://localhost:" + server.port() + "/elsewhere")));
        server.stubFor(get(urlEqualTo("/elsewhere")).willReturn(json(200, "[]")));

        assertEquals(Kind.UNEXPECTED_RESPONSE, failure().kind());
        server.verify(0, getRequestedFor(urlEqualTo("/elsewhere")));
    }

    @Test
    void htmlWithA200IsNotACatalogAnswer() {
        stubSearch(aResponse().withStatus(200).withHeader("Content-Type", "text/html")
                .withBody("<html><body>Just a moment...</body></html>"));
        assertEquals(Kind.UNEXPECTED_RESPONSE, failure().kind());
    }

    @Test
    void aJsonObjectInsteadOfAnArrayIsUnexpected() {
        stubSearch(json(200, "{\"error\":\"nope\"}"));
        assertEquals(Kind.UNEXPECTED_RESPONSE, failure().kind());
    }

    @Test
    void anOversizedBodyIsRejected() {
        stubSearch(json(200, "[" + "0,".repeat(1_100_000) + "0]"));
        var e = failure();
        assertEquals(Kind.UNEXPECTED_RESPONSE, e.kind());
        assertTrue(e.getMessage().contains("limit"));
    }

    @Test
    void aSlowStoreIsUnavailableNotAHangingRequest() {
        stubSearch(json(200, "[]").withFixedDelay(2000));
        catalog = catalogFor("http://localhost:" + server.port(), Duration.ofMillis(300));
        assertEquals(Kind.UNAVAILABLE, failure().kind());
    }

    @Test
    void aStoreThatRefusesTheConnectionIsUnavailable() {
        server.stop();
        assertEquals(Kind.UNAVAILABLE, failure().kind());
    }

    // ---- Retry-After parsing -----------------------------------------------------------------

    @Test
    void parsesRetryAfterOnlyAsSeconds() {
        assertEquals(Duration.ofSeconds(7), VtexStoreCatalog.parseRetryAfter("7"));
        assertEquals(Duration.ofSeconds(120), VtexStoreCatalog.parseRetryAfter(" 120 "));
        assertEquals(Duration.ofHours(24), VtexStoreCatalog.parseRetryAfter("999999"), "capped at a day");
        assertNull(VtexStoreCatalog.parseRetryAfter(null));
        assertNull(VtexStoreCatalog.parseRetryAfter(""));
        assertNull(VtexStoreCatalog.parseRetryAfter("-5"));
        assertNull(VtexStoreCatalog.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertNull(VtexStoreCatalog.parseRetryAfter("99999999999"), "absurdly long numbers are ignored");
    }
}
