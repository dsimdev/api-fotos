package com.axum.fotos.core.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Builds catalog-shot look-alikes: a white canvas with solid rectangles. No third-party photos in tests. */
final class Synthetic {

    static final Color RED = new Color(220, 30, 30);
    static final Color BLUE = new Color(30, 60, 200);
    static final Color PRODUCT = new Color(60, 140, 70);
    /** A white pack: light enough to be a pale grey on white, still non-white for the detector. */
    static final Color PALE = new Color(215, 215, 215);

    private final BufferedImage image;
    private final Graphics2D g;

    private Synthetic(int w, int h, Color background) {
        image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        g = image.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, w, h);
    }

    static Synthetic canvas(int size) {
        return new Synthetic(size, size, Color.WHITE);
    }

    static Synthetic canvas(int size, Color background) {
        return new Synthetic(size, size, background);
    }

    Synthetic rect(Color color, int x, int y, int w, int h) {
        g.setColor(color);
        g.fillRect(x, y, w, h);
        return this;
    }

    Synthetic oval(Color color, int x, int y, int w, int h) {
        g.setColor(color);
        g.fillOval(x, y, w, h);
        return this;
    }

    BufferedImage done() {
        g.dispose();
        return image;
    }

    /** Whether any pixel is close to the given colour. */
    static boolean contains(BufferedImage image, Color color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                var c = new Color(image.getRGB(x, y));
                if (Math.abs(c.getRed() - color.getRed()) < 25 && Math.abs(c.getGreen() - color.getGreen()) < 25
                        && Math.abs(c.getBlue() - color.getBlue()) < 25) {
                    return true;
                }
            }
        }
        return false;
    }
}
