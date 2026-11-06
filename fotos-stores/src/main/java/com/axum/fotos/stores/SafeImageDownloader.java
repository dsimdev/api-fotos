package com.axum.fotos.stores;

import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.stores.ImageDownloadException.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

/**
 * Downloads a product image from a URL that a STORE gave us, which is to say a URL chosen by a third party.
 *
 * <ul>
 *   <li>Only http and https, and the host must resolve ONLY to public addresses (the {@link AddressPolicy}):
 *       otherwise a store's answer could point us at a service inside the machine's own network.
 *   <li>Redirects are followed by hand, a few at most, and every hop goes through the same checks. (The JDK
 *       client would follow one to a private address without asking.)
 *   <li>A size cap and a time cap. The body is read as a stream and abandoned at the cap, whatever the server
 *       claims in Content-Length.
 *   <li>The type comes from the first bytes, never from the Content-Type: one store answers
 *       {@code application/octet-stream} with a perfectly good image.
 *   <li>It asks for JPEG and PNG, because six of the seven stores then serve JPEG and the JDK cannot read WebP.
 *       The seventh ignores the header, so a WebP can still arrive: it is returned as such and the caller
 *       decides (it skips it and uses another candidate).
 * </ul>
 *
 * <p>The address check happens before the request and the JDK client resolves the name again when it connects,
 * so a server that answers differently the second time (DNS rebinding) could slip through. For a tool that
 * a person runs on their own machine on a handful of CDN hosts that is an accepted residual risk.
 */
public final class SafeImageDownloader {

    public record Downloaded(byte[] bytes, ImageFormat format, String finalUrl) {}

    /** What addresses a download is allowed to reach. */
    @FunctionalInterface
    public interface AddressPolicy {
        boolean allows(InetAddress address);

        /** Production: anything that is not on this machine, a private network or otherwise not the public internet. */
        AddressPolicy PUBLIC_ONLY = SafeImageDownloader::isPublic;
    }

    /** Turns a host name into its addresses; behind an interface so a test does not need DNS. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;

        Resolver SYSTEM = InetAddress::getAllByName;
    }

    public static final long DEFAULT_MAX_BYTES = 15L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;

    private final HttpClient client;
    private final AddressPolicy policy;
    private final Resolver resolver;
    private final StoreRateLimiter limiter;
    private final long maxBytes;
    private final Duration timeout;
    private final String userAgent;

    public SafeImageDownloader(HttpClient client, AddressPolicy policy, Resolver resolver, StoreRateLimiter limiter,
                               long maxBytes, Duration timeout, String version) {
        this.client = client;
        this.policy = policy;
        this.resolver = resolver;
        this.limiter = limiter;
        this.maxBytes = maxBytes;
        this.timeout = timeout;
        this.userAgent = "api-fotos/" + version;
    }

    /** The client the downloader needs: redirects OFF (they are followed by hand), HTTP/1.1 like the store client. */
    public static HttpClient newHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
    }

    public Downloaded download(String url) throws ImageDownloadException {
        URI uri = parse(url);
        for (int hop = 0; ; hop++) {
            checkAddress(uri);
            limiter.acquire(uri.getHost().toLowerCase(Locale.ROOT));
            HttpResponse<InputStream> response = send(uri);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) throw new ImageDownloadException(Kind.HTTP_STATUS, "Redirect without a Location");
                if (hop >= MAX_REDIRECTS) throw new ImageDownloadException(Kind.HTTP_STATUS, "Too many redirects");
                uri = parse(uri.resolve(location).toString());
                continue;
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new ImageDownloadException(Kind.HTTP_STATUS, "HTTP " + status);
            }
            byte[] bytes = readCapped(response);
            ImageFormat format = ImageFormat.sniff(Arrays.copyOf(bytes, Math.min(bytes.length, 16)));
            if (format == ImageFormat.UNKNOWN) throw new ImageDownloadException(Kind.NOT_AN_IMAGE, "Not an image");
            return new Downloaded(bytes, format, uri.toString());
        }
    }

    private static URI parse(String url) throws ImageDownloadException {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.trim());
        } catch (java.net.URISyntaxException e) {
            throw new ImageDownloadException(Kind.REJECTED_URL, "Malformed URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ImageDownloadException(Kind.REJECTED_URL, "Only http and https are allowed");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw new ImageDownloadException(Kind.REJECTED_URL, "No host, or credentials in the URL");
        }
        return uri;
    }

    private void checkAddress(URI uri) throws ImageDownloadException {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            throw new ImageDownloadException(Kind.NETWORK, "Unknown host", e);
        }
        if (addresses.length == 0) throw new ImageDownloadException(Kind.NETWORK, "Host does not resolve");
        // ALL of them: a name that has one public and one private address is not trusted.
        for (InetAddress address : addresses) {
            if (!policy.allows(address)) {
                throw new ImageDownloadException(Kind.REJECTED_URL, "The host resolves to a non-public address");
            }
        }
    }

    private HttpResponse<InputStream> send(URI uri) throws ImageDownloadException {
        var request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "image/jpeg,image/png")
                .header("User-Agent", userAgent)
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ImageDownloadException(Kind.NETWORK, "Request failed: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImageDownloadException(Kind.NETWORK, "Interrupted", e);
        }
    }

    private byte[] readCapped(HttpResponse<InputStream> response) throws ImageDownloadException {
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
            closeQuietly(response.body());
            throw new ImageDownloadException(Kind.TOO_LARGE, "Content-Length over the cap");
        }
        try (InputStream in = response.body()) {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > maxBytes) throw new ImageDownloadException(Kind.TOO_LARGE, "Body over the cap");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new ImageDownloadException(Kind.NETWORK, "Reading the body failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing to do: the response is being discarded.
        }
    }

    /** Not loopback, link-local, private, multicast, unspecified, carrier-grade NAT, benchmarking or unique-local. */
    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            if (first == 0) return false;
            if (first == 100 && second >= 64 && second <= 127) return false;
            if (first == 198 && (second == 18 || second == 19)) return false;
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return false;
            return first < 240;
        }
        return (b[0] & 0xfe) != 0xfc;
    }
}
