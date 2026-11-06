package com.axum.fotos.core.image;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The 400x400 black-and-white copy of an image that the banner detector and the cropper look at.
 *
 * <p>Each cell is the AVERAGE of the source pixels it covers (a plain bilinear shrink of a 2000 px image
 * samples four pixels per cell and leaves noise right at the white threshold). Transparency is composited
 * over white first: a catalog shot with a transparent background is a white-background shot.
 */
final class WorkingCopy {

    static final int SIZE = 400;
    static final int WHITE = 235;
    private static final double MIN_AREA = 0.002;

    /** A blob of non-white cells (4-connected). */
    record Component(int id, int area, int minX, int maxX, int minY, int maxY) {
        int width() { return maxX - minX + 1; }
        int height() { return maxY - minY + 1; }
        double fill() { return (double) area / ((double) width() * height()); }
    }

    /** Row-major: {@code nonWhite[y * SIZE + x]}. */
    final boolean[] nonWhite = new boolean[SIZE * SIZE];

    private int[] labels;
    private int labelCount;
    private List<Component> blobs;

    static WorkingCopy of(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
        var copy = new WorkingCopy();
        for (int y = 0; y < SIZE; y++) {
            int y0 = (int) ((long) y * h / SIZE);
            int y1 = Math.min(h, Math.max(y0 + 1, (int) (((long) (y + 1) * h + SIZE - 1) / SIZE)));
            for (int x = 0; x < SIZE; x++) {
                int x0 = (int) ((long) x * w / SIZE);
                int x1 = Math.min(w, Math.max(x0 + 1, (int) (((long) (x + 1) * w + SIZE - 1) / SIZE)));
                long r = 0, g = 0, b = 0;
                int n = (y1 - y0) * (x1 - x0);
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int p = pixels[yy * w + xx];
                        int a = p >>> 24;
                        r += overWhite((p >> 16) & 0xff, a);
                        g += overWhite((p >> 8) & 0xff, a);
                        b += overWhite(p & 0xff, a);
                    }
                }
                copy.nonWhite[y * SIZE + x] = !(r / n >= WHITE && g / n >= WHITE && b / n >= WHITE);
            }
        }
        return copy;
    }

    private static int overWhite(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha)) / 255;
    }

    /**
     * How much of the border the given blobs occupy, as a share of the side: on each of the four sides, the number of
     * positions where one of them sits in the outermost two rows or columns; the largest of the four. A product that
     * merely grazes the edge touches a few positions; one that the source sliced off lies along it (a flat base is a
     * third of the side or more). Call components() first.
     */
    double edgeContact(Collection<Component> of) {
        boolean[] wanted = new boolean[labelCount];
        for (Component k : of) wanted[k.id()] = true;
        int best = 0;
        for (int side = 0; side < 4; side++) {
            int count = 0;
            for (int i = 0; i < SIZE; i++) {
                boolean hit = false;
                for (int depth = 0; depth < 2 && !hit; depth++) {
                    int x = side == 0 ? depth : side == 1 ? SIZE - 1 - depth : i;
                    int y = side == 2 ? depth : side == 3 ? SIZE - 1 - depth : i;
                    int id = labels[y * SIZE + x];
                    hit = id > 0 && wanted[id];
                }
                if (hit) count++;
            }
            best = Math.max(best, count);
        }
        return (double) best / SIZE;
    }

    /** The blobs big enough to matter (0.2% of the image), in scan order. */
    List<Component> components() {
        if (blobs != null) return blobs;
        int[] label = new int[SIZE * SIZE];
        int[] stack = new int[SIZE * SIZE];
        List<Component> found = new ArrayList<>();
        int nextId = 1;
        for (int start = 0; start < SIZE * SIZE; start++) {
            if (!nonWhite[start] || label[start] != 0) continue;
            int id = nextId++;
            int sp = 0, area = 0, minX = SIZE, maxX = 0, minY = SIZE, maxY = 0;
            stack[sp++] = start;
            label[start] = id;
            while (sp > 0) {
                int q = stack[--sp];
                int x = q % SIZE, y = q / SIZE;
                area++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (x > 0 && nonWhite[q - 1] && label[q - 1] == 0) { label[q - 1] = id; stack[sp++] = q - 1; }
                if (x < SIZE - 1 && nonWhite[q + 1] && label[q + 1] == 0) { label[q + 1] = id; stack[sp++] = q + 1; }
                if (y > 0 && nonWhite[q - SIZE] && label[q - SIZE] == 0) { label[q - SIZE] = id; stack[sp++] = q - SIZE; }
                if (y < SIZE - 1 && nonWhite[q + SIZE] && label[q + SIZE] == 0) { label[q + SIZE] = id; stack[sp++] = q + SIZE; }
            }
            if (area >= MIN_AREA * SIZE * SIZE) found.add(new Component(id, area, minX, maxX, minY, maxY));
        }
        labels = label;
        labelCount = nextId;
        blobs = found;
        return found;
    }
}
