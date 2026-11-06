package com.axum.fotos.app.config;

import com.axum.fotos.app.store.CatalogProperties;
import com.axum.fotos.stores.StoreHttp;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpConfig {

    /** The client used to talk to the stores: see {@link StoreHttp} for why it is configured the way it is. */
    @Bean
    public RestClient restClient(RestClient.Builder builder, CatalogProperties properties,
                                 @Value("${fotos.version:dev}") String version) {
        return StoreHttp.client(builder, properties.connectTimeout(), properties.readTimeout(), version);
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
