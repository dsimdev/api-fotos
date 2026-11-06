package com.axum.fotos.stores;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.stores.ImageDownloadException.Kind;
import com.axum.fotos.stores.SafeImageDownloader.AddressPolicy;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SafeImageDownloaderTest {

    private static final AddressPolicy ANY = a -> true;

    private WireMockServer server;
    private byte[] jpeg;

    @BeforeEach
    void start() throws IOException {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        var image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        jpeg = out.toByteArray();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private SafeImageDownloader downloader(AddressPolicy policy, long maxBytes, Duration timeout) {
        return new SafeImageDownloader(SafeImageDownloader.newHttpClient(Duration.ofSeconds(2)), policy,
                SafeImageDownloader.Resolver.SYSTEM,
                new StoreRateLimiter(Duration.ZERO, Clock.systemUTC(), Sleeper.REAL), maxBytes, timeout, "9.9.9");
    }

    private SafeImageDownloader downloader() {
        return downloader(ANY, SafeImageDownloader.DEFAULT_MAX_BYTES, Duration.ofSeconds(5));
    }

    private String url(String path) {
        return "http://localhost:" + server.port() + path;
    }

    private static ImageDownloadException refused(Kind expected, org.junit.jupiter.api.function.Executable call) {
        var e = assertThrows(ImageDownloadException.class, call);
        assertEquals(expected, e.kind(), e.getMessage());
        return e;
    }

    @Test
    void downloadsAJpegAndTellsItsTypeFromTheBytes() throws Exception {
        server.stubFor(get(urlEqualTo("/a.jpg")).willReturn(aResponse().withHeader("Content-Type", "image/jpeg").withBody(jpeg)));

        var result = downloader().download(url("/a.jpg"));

        assertEquals(ImageFormat.JPEG, result.format());
        assertEquals(jpeg.length, result.bytes().length);
    }

    /** One store answers application/octet-stream with a perfectly good image: the Content-Type must not decide. */
    @Test
    void anOctetStreamResponseThatIsReallyAnImageIsAccepted() throws Exception {
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withHeader("Content-Type", "application/octet-stream").withBody(jpeg)));

        assertEquals(ImageFormat.JPEG, downloader().download(url("/a")).format());
    }

    @Test
    void aResponseSayingImageJpegThatIsHtmlIsNotAnImage() {
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withHeader("Content-Type", "image/jpeg")
                .withBody("<html>Access denied</html>")));

        refused(Kind.NOT_AN_IMAGE, () -> downloader().download(url("/a")));
    }

    @Test
    void aWebpIsReturnedAsSuchSoTheCallerCanSkipIt() throws Exception {
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse()
                .withBody("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))));

        assertEquals(ImageFormat.WEBP, downloader().download(url("/a")).format());
    }

    @Test
    void asksForJpegAndPngAndNamesItself() throws Exception {
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withBody(jpeg)));

        downloader().download(url("/a"));

        server.verify(getRequestedFor(urlEqualTo("/a")).withHeader("Accept", equalTo("image/jpeg,image/png"))
                .withHeader("User-Agent", equalTo("api-fotos/9.9.9")));
    }

    @Test
    void aNon200IsAFailureEvenWithAnImageBody() {
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withStatus(404).withBody(jpeg)));

        refused(Kind.HTTP_STATUS, () -> downloader().download(url("/a")));
    }

    @Test
    void aBodyAboveTheCapIsRefusedWhateverTheServerClaims() {
        server.stubFor(get(urlEqualTo("/declared")).willReturn(aResponse().withBody(new byte[5000])));
        server.stubFor(get(urlEqualTo("/chunked")).willReturn(aResponse().withBody(new byte[5000]).withChunkedDribbleDelay(5, 5)));
        var small = downloader(ANY, 1000, Duration.ofSeconds(5));

        refused(Kind.TOO_LARGE, () -> small.download(url("/declared")));
        refused(Kind.TOO_LARGE, () -> small.download(url("/chunked")));
    }

    @Test
    void followsARedirectByHand() throws Exception {
        server.stubFor(get(urlEqualTo("/old")).willReturn(aResponse().withStatus(302).withHeader("Location", "/new.jpg")));
        server.stubFor(get(urlEqualTo("/new.jpg")).willReturn(aResponse().withBody(jpeg)));

        var result = downloader().download(url("/old"));

        assertTrue(result.finalUrl().endsWith("/new.jpg"));
    }

    @Test
    void aRedirectToANonPublicAddressIsRefusedAfterTheFirstHop() {
        server.stubFor(get(urlEqualTo("/old")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://127.0.0.2:" + server.port() + "/secret")));
        AddressPolicy blocksTheSecondHost = a -> !a.getHostAddress().equals("127.0.0.2");

        refused(Kind.REJECTED_URL, () -> downloader(blocksTheSecondHost, SafeImageDownloader.DEFAULT_MAX_BYTES, Duration.ofSeconds(5))
                .download(url("/old")));

        server.verify(0, getRequestedFor(urlEqualTo("/secret")));
    }

    @Test
    void aRedirectLoopGivesUp() {
        server.stubFor(get(urlEqualTo("/loop")).willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));

        refused(Kind.HTTP_STATUS, () -> downloader().download(url("/loop")));
    }

    @Test
    void onlyHttpAndHttpsAreFollowed() {
        for (String bad : new String[]{"file:///etc/passwd", "ftp://example.com/a.jpg", "javascript:alert(1)", "//example.com/a.jpg",
                "http://user:pass@example.com/a.jpg", "not a url", ""}) {
            refused(Kind.REJECTED_URL, () -> downloader().download(bad));
        }
    }

    @Test
    void thePublicOnlyPolicyNeverReachesThisMachineEvenWhenTheUrlIsAnIpLiteral() {
        server.stubFor(get(urlEqualTo("/a")).willReturn(aResponse().withBody(jpeg)));
        var strict = downloader(AddressPolicy.PUBLIC_ONLY, SafeImageDownloader.DEFAULT_MAX_BYTES, Duration.ofSeconds(5));

        refused(Kind.REJECTED_URL, () -> strict.download("http://127.0.0.1:" + server.port() + "/a"));
        refused(Kind.REJECTED_URL, () -> strict.download("http://localhost:" + server.port() + "/a"));

        server.verify(0, getRequestedFor(urlEqualTo("/a")));
    }

    @Test
    void addressesThatAreNotThePublicInternetAreBlocked() throws Exception {
        for (String blocked : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "fc00::1", "fd12:3456::1", "fe80::1"}) {
            assertFalse(SafeImageDownloader.isPublic(InetAddress.getByName(blocked)), blocked + " must be blocked");
        }
        for (String open : new String[]{"8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111"}) {
            assertTrue(SafeImageDownloader.isPublic(InetAddress.getByName(open)), open + " must be allowed");
        }
    }

    @Test
    void aSlowServerIsAbandoned() {
        server.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withFixedDelay(1500).withBody(jpeg)));

        refused(Kind.NETWORK, () -> downloader(ANY, SafeImageDownloader.DEFAULT_MAX_BYTES, Duration.ofMillis(300))
                .download(url("/slow")));
    }
}
