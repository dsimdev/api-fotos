package com.axum.fotos.app.config;

import com.axum.fotos.app.store.CatalogProperties;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpConfig {

    /**
     * The client used to talk to the stores.
     *
     * <p>Timeouts are explicit and short: a store that takes 30 seconds is as useless as one that is
     * down, and it leaves the caller hanging.
     *
     * <p><b>Redirects are not followed</b> (the JDK client does not by default, and it is left that
     * way on purpose): a store that redirects is not answering like a catalog, and following it
     * would let a response choose where the next request goes.
     *
     * <p>HTTP/1.1 fixed, as measured: it is what the lookups were validated with.
     *
     * <p>The User-Agent names the service and its version and nothing else: a generic client name,
     * not a browser's and without any personal contact detail.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder, CatalogProperties properties,
                                 @Value("${fotos.version:dev}") String version) {
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return builder.requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, "api-fotos/" + version)
                .build();
    }

    /**
     * The clock is injected instead of calling {@code now()} loose, so a test can fix it.
     *
     * <p>In ARGENTINA's zone and not the server's: the store server runs in UTC-12, nine hours behind,
     * and anything built from its local time comes out shifted.
     */
    @Bean
    public Clock clock(@Value("${fotos.time-zone:America/Argentina/Buenos_Aires}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
