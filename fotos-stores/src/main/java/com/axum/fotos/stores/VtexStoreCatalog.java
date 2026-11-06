package com.axum.fotos.stores;

import com.axum.fotos.core.Ean;
import com.axum.fotos.core.StoreCatalog;
import com.axum.fotos.core.StoreException;
import com.axum.fotos.core.StoreException.Kind;
import com.axum.fotos.core.StoreProduct;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Asks a VTEX store for the product it sells under an exact EAN.
 *
 * <p>It uses the public storefront search, {@code /api/catalog_system/pub/products/search} with
 * {@code fq=alternateIds_Ean:<ean>}. <b>That filter is not in the documentation we found</b>: it works
 * today (measured on 7 Argentine stores, 2026-11-05) but it can change without notice, so it lives in
 * this one class and a contract test against the real store (tag {@code live}) tells when it breaks.
 *
 * <p>A match is the store's item whose {@code ean} equals the one asked, nothing else. The product
 * name is never used to decide: three of the six wrong images in the first Google test had the right
 * title and the wrong picture.
 *
 * <p>HTTP statuses are translated to {@link StoreException} kinds, because each one calls for a
 * different reaction (see StoreResilience). Redirects are NOT followed (the HTTP client is built that
 * way): a store answering with a redirect is not answering like a catalog.
 */
public class VtexStoreCatalog implements StoreCatalog {

    static final String SEARCH_PATH = "/api/catalog_system/pub/products/search";
    /** A real answer for one EAN is ~30 KB. Anything near this is not a catalog answer. */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final long MAX_RETRY_AFTER_SECONDS = 24 * 3600;

    private final String storeId;
    private final String baseUrl;
    private final RestClient client;
    private final ObjectMapper mapper;

    public VtexStoreCatalog(String storeId, String baseUrl, RestClient client, ObjectMapper mapper) {
        this.storeId = storeId;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public String storeId() {
        return storeId;
    }

    @Override
    public Optional<StoreProduct> findByEan(Ean ean) {
        // The EAN is validated digits only, so it is safe to put straight into the URL.
        var uri = URI.create(baseUrl + SEARCH_PATH + "?fq=alternateIds_Ean:" + ean.value());
        try {
            return client.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> interpret(ean, response));
        } catch (ResourceAccessException e) {
            // Timeouts, refused connections, TLS failures. The cause class is enough for the log.
            var cause = e.getCause() != null ? e.getCause() : e;
            throw new StoreException(Kind.UNAVAILABLE,
                    "Store " + storeId + " unreachable (" + cause.getClass().getSimpleName() + ")",
                    null, e);
        }
    }

    private Optional<StoreProduct> interpret(Ean ean, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 200) {
            return parse(ean, readBody(response));
        }
        var retryAfter = parseRetryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        if (status == 429) {
            throw new StoreException(Kind.RATE_LIMITED, "Store " + storeId + " answered 429", retryAfter, null);
        }
        if (status == 401 || status == 403) {
            throw new StoreException(Kind.BLOCKED, "Store " + storeId + " answered " + status, null, null);
        }
        if (status >= 500) {
            throw new StoreException(Kind.UNAVAILABLE, "Store " + storeId + " answered " + status, retryAfter, null);
        }
        // 404 on a host that is not VTEX, redirects, any other 4xx.
        throw new StoreException(Kind.UNEXPECTED_RESPONSE, "Store " + storeId + " answered " + status);
    }

    private String readBody(ClientHttpResponse response) throws IOException {
        byte[] bytes = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new StoreException(Kind.UNEXPECTED_RESPONSE, "Store " + storeId + " answered a body over the limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Optional<StoreProduct> parse(Ean ean, String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new StoreException(Kind.UNEXPECTED_RESPONSE, "Store " + storeId + " did not answer JSON");
        }
        if (root == null || !root.isArray()) {
            throw new StoreException(Kind.UNEXPECTED_RESPONSE, "Store " + storeId + " did not answer a JSON array");
        }
        for (JsonNode product : root) {
            for (JsonNode item : product.path("items")) {
                if (ean.value().equals(item.path("ean").asText(null))) {
                    return Optional.of(toProduct(product, item));
                }
            }
        }
        return Optional.empty();
    }

    private StoreProduct toProduct(JsonNode product, JsonNode item) {
        var images = new ArrayList<String>();
        for (JsonNode image : item.path("images")) {
            var url = image.path("imageUrl").asText("").trim();
            if (!url.isEmpty()) images.add(url);
        }
        var link = product.path("link").asText(null);
        if (link != null && link.startsWith("/")) link = baseUrl + link;
        return new StoreProduct(storeId, product.path("productName").asText(null),
                product.path("brand").asText(null), link, images);
    }

    /** Retry-After as seconds. The HTTP-date form is not used by these stores and is ignored. */
    static Duration parseRetryAfter(String header) {
        if (header == null) return null;
        var value = header.trim();
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9') || value.length() > 9) return null;
        return Duration.ofSeconds(Math.min(Long.parseLong(value), MAX_RETRY_AFTER_SECONDS));
    }
}
