package com.axum.fotos.core.image;

import java.awt.image.BufferedImage;

/**
 * Tells whether a catalog shot carries the supplier's template panel: a near-solid strip along the right
 * or the bottom edge with the product name and weight. Measured against hand labels in
 * docs/proyecto/mediciones.md; the numbers below are the tuned ones from that work.
 *
 * <p>ASSUMPTION: the product does not reach the right 35% or the bottom 30% of the image. True for the
 * catalog shots (they have margins), FALSE for a crop that re-centres the product at full size, so this must
 * not be used to verify a crop: the cropper verifies its own output.
 */
public final class BannerDetector {

    public enum Kind {
        NONE,
        SIDE,
        BOTTOM,
        /** The frame of the image is not white: not a white-background catalog shot, the heuristic does not apply. */
        UNKNOWN
    }

    public record Detection(Kind kind, double frameWhite, double sideRun, double bottomRun) {}

    private static final int S = WorkingCopy.SIZE;
    private static final double FRAME = 0.03;
    private static final double FRAME_WHITE_MIN = 0.8;
    private static final double SIDE_FROM = 0.65;
    private static final double BOTTOM_FROM = 0.7;
    private static final double FILL = 0.6;
    private static final double MIN_RUN = 0.06;
    /** White runs shorter than this fraction of the width, with colour on BOTH sides, count as colour (the white text inside a panel). */
    private static final double GAP = 0.08;

    private BannerDetector() {}

    public static Detection detect(BufferedImage image) {
        return detect(WorkingCopy.of(image));
    }

    static Detection detect(WorkingCopy copy) {
        boolean[] non = copy.nonWhite.clone();
        int maxGap = (int) Math.round(S * GAP);
        for (int y = 0; y < S; y++) {
            int x = 0;
            while (x < S) {
                if (non[y * S + x]) { x++; continue; }
                int end = x;
                while (end < S && !non[y * S + end]) end++;
                if (x > 0 && end < S && end - x <= maxGap) {
                    for (int k = x; k < end; k++) non[y * S + k] = true;
                }
                x = end;
            }
        }

        // Only the LEFT and TOP edges: the panels touch the right and bottom edges, which would make a
        // genuine banner image look like it has a non-white background.
        int frame = Math.max(1, (int) Math.round(S * FRAME));
        int frameCells = 0, frameWhite = 0;
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                if (x < frame || y < frame) {
                    frameCells++;
                    if (!non[y * S + x]) frameWhite++;
                }
            }
        }
        double frameWhiteShare = (double) frameWhite / frameCells;

        double[] column = new double[S], row = new double[S];
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                if (non[y * S + x]) { column[x]++; row[y]++; }
            }
        }
        for (int i = 0; i < S; i++) { column[i] /= S; row[i] /= S; }

        double side = longestRun(column, SIDE_FROM);
        double bottom = longestRun(row, BOTTOM_FROM);
        Kind kind = frameWhiteShare < FRAME_WHITE_MIN ? Kind.UNKNOWN
                : side >= MIN_RUN ? Kind.SIDE
                : bottom >= MIN_RUN ? Kind.BOTTOM
                : Kind.NONE;
        return new Detection(kind, frameWhiteShare, side, bottom);
    }

    /** Longest stretch, from {@code from} of the way across to the end, where at least FILL of the line is coloured. */
    private static double longestRun(double[] share, double from) {
        int best = 0, current = 0;
        for (int i = (int) Math.floor(from * S); i < S; i++) {
            if (share[i] >= FILL) {
                current++;
                if (current > best) best = current;
            } else {
                current = 0;
            }
        }
        return (double) best / S;
    }
}
