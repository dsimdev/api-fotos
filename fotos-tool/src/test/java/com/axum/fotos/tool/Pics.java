package com.axum.fotos.tool;

import com.axum.fotos.core.image.ImageCodec;
import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.stores.SafeImageDownloader.Downloaded;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/** Catalog-shot look-alikes drawn in memory: no third-party photos in tests. */
final class Pics {

    static final Color PANEL = new Color(220, 30, 30);
    private static final Color GREEN = new Color(60, 140, 70);
    private static final Color DARK = new Color(30, 90, 40);

    private Pics() {}

    /** A product with margins, with texture so it is not "soft", and no panel. */
    static Downloaded clean(String url) {
        return jpeg(url, product(white(), 150, 100, 450, 550));
    }

    /** The same product with the supplier's panel along the right edge and its weight badge. */
    static Downloaded withPanel(String url) {
        var image = product(white(), 150, 100, 450, 550);
        var g = image.createGraphics();
        g.setColor(PANEL);
        g.fillRect(700, 60, 200, 640);
        g.fillRect(700, 720, 200, 180);
        g.dispose();
        return jpeg(url, image);
    }

    /** Nothing but the panel: the detector sees a banner and the cropper finds no product to keep. */
    static Downloaded onlyPanel(String url) {
        var image = white();
        var g = image.createGraphics();
        g.setColor(PANEL);
        g.fillRect(700, 60, 200, 640);
        g.fillRect(700, 720, 200, 180);
        g.dispose();
        return jpeg(url, image);
    }

    /** The source sliced the product flat at the bottom edge. */
    static Downloaded sliced(String url) {
        return jpeg(url, product(white(), 150, 100, 400, 900));
    }

    static Downloaded greyBackground(String url) {
        var image = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(new Color(120, 120, 120));
        g.fillRect(0, 0, 1000, 1000);
        g.dispose();
        return jpeg(url, product(image, 150, 100, 450, 550));
    }

    static Downloaded webp(String url) {
        return new Downloaded("RIFF\0\0\0\0WEBPVP8 ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), ImageFormat.WEBP, url);
    }

    static boolean hasPanelColour(byte[] jpeg) throws IOException {
        var image = ImageCodec.decode(jpeg);
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                var c = new Color(image.getRGB(x, y));
                if (c.getRed() > 190 && c.getGreen() < 80 && c.getBlue() < 80) return true;
            }
        }
        return false;
    }

    private static BufferedImage white() {
        var image = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1000, 1000);
        g.dispose();
        return image;
    }

    private static BufferedImage product(BufferedImage image, int x, int y, int w, int h) {
        Graphics2D g = image.createGraphics();
        for (int yy = y; yy < y + h; yy += 8) {
            for (int xx = x; xx < x + w; xx += 8) {
                g.setColor(((xx / 8) + (yy / 8)) % 2 == 0 ? GREEN : DARK);
                g.fillRect(xx, yy, Math.min(8, x + w - xx), Math.min(8, y + h - yy));
            }
        }
        g.dispose();
        return image;
    }

    private static Downloaded jpeg(String url, BufferedImage image) {
        try {
            return new Downloaded(ImageCodec.encode(image, ImageFormat.JPEG), ImageFormat.JPEG, url);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
