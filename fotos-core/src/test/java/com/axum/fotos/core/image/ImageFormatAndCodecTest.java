package com.axum.fotos.core.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class ImageFormatAndCodecTest {

    private static byte[] bytes(int... values) {
        var b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    @Test
    void theTypeComesFromTheBytes() {
        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0x10)));
        assertEquals(ImageFormat.PNG, ImageFormat.sniff(bytes(0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0)));
        assertEquals(ImageFormat.WEBP, ImageFormat.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void anythingElseIsUnknownEvenIfTheServerSaidItWasAnImage() {
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff("<html>Access denied</html>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff(bytes(0xff, 0xd8)));
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.sniff(new byte[0]));
    }

    @Test
    void pngRoundTripsWithoutLoss() throws IOException {
        var image = Synthetic.canvas(120).rect(Synthetic.PRODUCT, 10, 10, 50, 50).done();

        var back = ImageCodec.decode(ImageCodec.encode(image, ImageFormat.PNG));

        assertEquals(image.getRGB(30, 30), back.getRGB(30, 30));
        assertEquals(120, back.getWidth());
    }

    @Test
    void jpegIsWrittenAndReadBack() throws IOException {
        var image = Synthetic.canvas(120).rect(Synthetic.PRODUCT, 10, 10, 50, 50).done();

        var encoded = ImageCodec.encode(image, ImageFormat.JPEG);

        assertEquals(ImageFormat.JPEG, ImageFormat.sniff(encoded));
        assertEquals(120, ImageCodec.decode(encoded).getHeight());
    }

    @Test
    void bytesThatAreNotAnImageAreRejected() {
        assertThrows(IOException.class, () -> ImageCodec.decode("not an image".getBytes(StandardCharsets.UTF_8)));
    }

    /** A tiny file can declare a canvas of hundreds of megapixels: it is refused before the decoder allocates it. */
    @Test
    void aFileDeclaringAHugeCanvasIsRefusedBeforeDecoding() throws IOException {
        var png = ImageCodec.encode(Synthetic.canvas(4).done(), ImageFormat.PNG);
        var patched = png.clone();
        ByteBuffer.wrap(patched).putInt(16, 20_000).putInt(20, 20_000);
        var crc = new CRC32();
        crc.update(patched, 12, 17);
        ByteBuffer.wrap(patched).putInt(29, (int) crc.getValue());

        var e = assertThrows(IOException.class, () -> ImageCodec.decode(patched));
        assertEquals(true, e.getMessage().contains("too large"), e.getMessage());
    }
}
