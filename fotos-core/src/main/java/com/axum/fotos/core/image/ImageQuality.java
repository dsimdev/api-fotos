package com.axum.fotos.core.image;

import java.awt.image.BufferedImage;

/** Checks on a delivered-to-be image that the banner logic does not cover: a product cut by the source, a soft image. */
public final class ImageQuality {

    private static final int ANALYSIS_SIDE = 1000;

    /**
     * PROVISIONAL thresholds, fitted on one client's catalog (124 photos reviewed by eye, see mediciones.md), and
     * used only to put a candidate behind the others, never to throw it away: a tight but complete product and a
     * sliced one look the same to this measure (a carton's flat base reaches the frame either way).
     *
     * <p>Edge contact from which the product is treated as touching the frame: the 3 sliced photos measured 0.33 to
     * 0.42, and the tight-but-complete bottles and cups measured 0.2 or less.
     */
    public static final double TOUCHING_EDGE = 0.25;

    /** Sharpness below which an image is treated as an enlarged thumbnail: 1 of 124 (6; the next lowest was 30). */
    public static final double SOFT = 15;

    /** Sharpness below which the report warns that the photo looks soft: the three that the reviewer found soft were under 50. */
    public static final double SOFT_WARNING = 50;

    private ImageQuality() {}

    /**
     * How much of the border of the image the product lies along, as a share of the side (0 to 1). A product that
     * only grazes the edge scores near 0; one that the source sliced (a flat base or top, a side cut off) lies along
     * it. Call it on a clean original; for a crop the cropper reports it, because the panel strips of a banner shot
     * also reach the border and are not the product.
     */
    public static double productEdgeContact(BufferedImage image) {
        var copy = WorkingCopy.of(image);
        return copy.edgeContact(copy.components());
    }

    /**
     * Variance of the Laplacian of the grey image, analysed at no more than 1000 px on the long side: an
     * enlarged thumbnail is soft at that size too, a sharp photo is not. Only meaningful to compare images of
     * the same product, because the value also depends on how much detail the pack has.
     */
    public static double sharpness(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        double scale = Math.min(1.0, (double) ANALYSIS_SIDE / Math.max(w, h));
        int aw = Math.max(3, (int) Math.round(w * scale)), ah = Math.max(3, (int) Math.round(h * scale));
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        double[] grey = new double[aw * ah];
        for (int y = 0; y < ah; y++) {
            int y0 = (int) ((long) y * h / ah), y1 = Math.min(h, Math.max(y0 + 1, (int) (((long) (y + 1) * h + ah - 1) / ah)));
            for (int x = 0; x < aw; x++) {
                int x0 = (int) ((long) x * w / aw), x1 = Math.min(w, Math.max(x0 + 1, (int) (((long) (x + 1) * w + aw - 1) / aw)));
                double sum = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = pixels[yy * w + xx];
                        int a = p >>> 24;
                        double r = ((p >> 16) & 0xff) * a / 255.0 + 255.0 * (255 - a) / 255.0;
                        double g = ((p >> 8) & 0xff) * a / 255.0 + 255.0 * (255 - a) / 255.0;
                        double b = (p & 0xff) * a / 255.0 + 255.0 * (255 - a) / 255.0;
                        sum += 0.299 * r + 0.587 * g + 0.114 * b;
                    }
                }
                grey[y * aw + x] = sum / ((y1 - y0) * (x1 - x0));
            }
        }
        double sum = 0, sumSquares = 0;
        long n = 0;
        for (int y = 1; y < ah - 1; y++) {
            for (int x = 1; x < aw - 1; x++) {
                double l = 4 * grey[y * aw + x] - grey[(y - 1) * aw + x] - grey[(y + 1) * aw + x]
                        - grey[y * aw + x - 1] - grey[y * aw + x + 1];
                sum += l;
                sumSquares += l * l;
                n++;
            }
        }
        double mean = sum / n;
        return sumSquares / n - mean * mean;
    }
}
