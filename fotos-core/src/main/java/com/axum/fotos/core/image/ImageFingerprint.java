package com.axum.fotos.core.image;

import com.axum.fotos.core.image.WorkingCopy.Component;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A 64-bit visual fingerprint of the PRODUCT in a photo (a difference hash of the box around the non-white
 * content), to tell whether two stores show the same picture: the same shot at another size or with other
 * margins lands a few bits apart, a different pack or another angle lands far away.
 *
 * <p>The box is taken first, so margins and the size of the file do not matter. Call it on an image that has no
 * template panel (a clean original or a crop), or the panel becomes part of the "product".
 */
public final class ImageFingerprint {

    private static final int COLUMNS = 9, ROWS = 8;
    private static final int S = WorkingCopy.SIZE;

    private ImageFingerprint() {}

    public static long of(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        List<Component> blobs = WorkingCopy.of(image).components();
        int minX = 0, maxX = S - 1, minY = 0, maxY = S - 1;
        if (!blobs.isEmpty()) {
            minX = S; maxX = 0; minY = S; maxY = 0;
            for (Component k : blobs) {
                minX = Math.min(minX, k.minX());
                maxX = Math.max(maxX, k.maxX());
                minY = Math.min(minY, k.minY());
                maxY = Math.max(maxY, k.maxY());
            }
        }
        int x0 = (int) ((long) minX * w / S), x1 = Math.min(w, (int) (((long) (maxX + 1) * w + S - 1) / S));
        int y0 = (int) ((long) minY * h / S), y1 = Math.min(h, (int) (((long) (maxY + 1) * h + S - 1) / S));
        int bw = Math.max(1, x1 - x0), bh = Math.max(1, y1 - y0);

        double[][] grey = new double[ROWS][COLUMNS];
        for (int cy = 0; cy < ROWS; cy++) {
            int ya = y0 + cy * bh / ROWS, yb = Math.max(ya + 1, y0 + (cy + 1) * bh / ROWS);
            for (int cx = 0; cx < COLUMNS; cx++) {
                int xa = x0 + cx * bw / COLUMNS, xb = Math.max(xa + 1, x0 + (cx + 1) * bw / COLUMNS);
                double sum = 0;
                int n = 0;
                for (int y = ya; y < Math.min(yb, h); y++) {
                    for (int x = xa; x < Math.min(xb, w); x++) {
                        sum += greyOverWhite(image.getRGB(x, y));
                        n++;
                    }
                }
                grey[cy][cx] = n == 0 ? 255 : sum / n;
            }
        }
        long bits = 0;
        for (int cy = 0; cy < ROWS; cy++) {
            for (int cx = 0; cx < COLUMNS - 1; cx++) {
                bits = (bits << 1) | (grey[cy][cx] > grey[cy][cx + 1] ? 1 : 0);
            }
        }
        return bits;
    }

    /** How many of the 64 bits differ. */
    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static double greyOverWhite(int argb) {
        int a = argb >>> 24;
        double r = ((argb >> 16) & 0xff) * a / 255.0 + 255.0 * (255 - a) / 255.0;
        double g = ((argb >> 8) & 0xff) * a / 255.0 + 255.0 * (255 - a) / 255.0;
        double b = (argb & 0xff) * a / 255.0 + 255.0 * (255 - a) / 255.0;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }
}
