package com.axum.fotos.core.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;

/** Decodes and encodes the formats the tool delivers (JPEG and PNG). WebP is not readable by the JDK. */
public final class ImageCodec {

    /** The images come from third parties: a small file can declare a huge canvas, so the size is checked BEFORE decoding. */
    public static final long MAX_PIXELS = 40_000_000L;
    private static final float JPEG_QUALITY = 0.95f;

    private ImageCodec() {}

    /** @throws IOException if the bytes are not a readable JPEG or PNG, or the declared size is too large */
    public static BufferedImage decode(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Unreadable image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) throw new IOException("Image too large: " + pixels + " pixels");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            // ImageIO signals some broken files with unchecked exceptions (CMYK JPEGs, truncated data).
            throw new IOException("Unreadable image: " + e.getMessage(), e);
        }
    }

    /** Re-encodes. PNG stays lossless; JPEG is written at a high quality. */
    public static byte[] encode(BufferedImage image, ImageFormat format) throws IOException {
        var out = new ByteArrayOutputStream();
        if (format == ImageFormat.PNG) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("No PNG writer");
            return out.toByteArray();
        }
        if (format != ImageFormat.JPEG) throw new IOException("Cannot write " + format);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(toRgb(image), null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** JPEG has no alpha channel: a PNG with transparency is flattened over white before it is written. */
    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_3BYTE_BGR) return image;
        var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = rgb.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }
}
