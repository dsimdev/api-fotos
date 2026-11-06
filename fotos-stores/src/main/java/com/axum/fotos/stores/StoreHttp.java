package com.axum.fotos.stores;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one place that builds the client used to talk to the stores, so the service, the local tool and
 * the tests all use the same configuration.
 *
 * <p>Timeouts are explicit and short: a store that takes 30 seconds is as useless as one that is down,
 * and it leaves the caller hanging.
 *
 * <p><b>Redirects are not followed</b> (the JDK client does not by default, and it is left that way on
 * purpose): a store that redirects is not answering like a catalog, and following it would let a
 * response choose where the next request goes.
 *
 * <p>HTTP/1.1 fixed, as measured: it is what the lookups were validated with.
 *
 * <p>The User-Agent names the tool and its version and nothing else: a generic client name, not a
 * browser's and without any personal contact detail.
 */
public final class StoreHttp {

    private StoreHttp() {}

    public static RestClient client(RestClient.Builder builder, Duration connectTimeout, Duration readTimeout,
                                    String version) {
        var http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, "api-fotos/" + version)
                .build();
    }
}
