package com.axum.fotos.core.image;

import com.axum.fotos.core.image.WorkingCopy.Component;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes the supplier's template panel from a catalog shot and re-centres the product on a white square.
 *
 * <p>The PANEL is found by what it IS, not by being "the smallest thing": a solid rectangle, thick enough,
 * tall at the right (a side strip) or wide at the bottom (a bottom strip), with ALL the rest of the content on
 * one side of it, plus the small badge ("900g") in the same column or row. The product is every other blob: a
 * white pack breaks into several pieces, so it is neither one blob nor the largest one. (A first version assumed
 * both and picked the panel as the product on 8 of 130 photos.)
 *
 * <p>Only the bounding box of the product is copied, from the ORIGINAL pixels and without scaling, so the product
 * is never cut. The result is then VERIFIED: the same panel search runs on the output, and a surviving panel
 * means the crop is reported as failed instead of being delivered.
 */
public final class BannerCropper {

    public enum Status {
        CROPPED,
        /** Nothing qualified as a panel; the image is left alone. */
        NO_SEPARATE_PANEL,
        EMPTY,
        /** The panel was cut but another one is still there in the output. */
        LEFTOVER_PANEL
    }

    /** Top-left corner and size, in pixels of the original image. */
    public record Box(int x, int y, int width, int height) {}

    /**
     * {@code image} is only set when the status is CROPPED. {@code edgeContact} is how much of the ORIGINAL image's border the
     * product lies along (see {@link ImageQuality#productEdgeContact}): a large value means the supplier's photo already had
     * the product sliced, which no crop can repair.
     */
    public record Result(Status status, Box productBox, BufferedImage image, double edgeContact) {}

    private static final int S = WorkingCopy.SIZE;
    private static final double MARGIN = 0.08;
    private static final int PAD_CELLS = 1;

    private BannerCropper() {}

    public static Result crop(BufferedImage image) {
        WorkingCopy copy = WorkingCopy.of(image);
        List<Component> big = copy.components();
        if (big.isEmpty()) return new Result(Status.EMPTY, null, null, 0);
        Set<Component> panel = findPanel(big);
        if (panel.isEmpty()) return new Result(Status.NO_SEPARATE_PANEL, null, null, 0);
        List<Component> keep = big.stream().filter(k -> !panel.contains(k)).toList();
        if (keep.isEmpty()) return new Result(Status.NO_SEPARATE_PANEL, null, null, 0);

        int minX = S, maxX = 0, minY = S, maxY = 0;
        for (Component k : keep) {
            minX = Math.min(minX, k.minX());
            maxX = Math.max(maxX, k.maxX());
            minY = Math.min(minY, k.minY());
            maxY = Math.max(maxY, k.maxY());
        }
        double edgeContact = copy.edgeContact(keep);
        // One cell of slack on each side: the pale edge of a white pack averages to "white" at the threshold and
        // the box would shave it. The white margin added below makes the extra cell free.
        minX = Math.max(0, minX - PAD_CELLS);
        minY = Math.max(0, minY - PAD_CELLS);
        maxX = Math.min(S - 1, maxX + PAD_CELLS);
        maxY = Math.min(S - 1, maxY + PAD_CELLS);
        int w = image.getWidth(), h = image.getHeight();
        int bx = (int) ((long) minX * w / S), by = (int) ((long) minY * h / S);
        // Integer arithmetic: (maxX + 1) * (w / 400.0) can come out as w + epsilon and ceil past the image.
        int bw = Math.min(w, (int) (((long) (maxX + 1) * w + S - 1) / S)) - bx;
        int bh = Math.min(h, (int) (((long) (maxY + 1) * h + S - 1) / S)) - by;
        var box = new Box(bx, by, bw, bh);

        int side = (int) Math.ceil(Math.max(bw, bh) / (1 - 2 * MARGIN));
        var out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, side, side);
            g.drawImage(image.getSubimage(bx, by, bw, bh), (side - bw) / 2, (side - bh) / 2, null);
        } finally {
            g.dispose();
        }

        if (!findPanel(WorkingCopy.of(out).components()).isEmpty()) {
            return new Result(Status.LEFTOVER_PANEL, box, null, edgeContact);
        }
        return new Result(Status.CROPPED, box, out, edgeContact);
    }

    /** The panel (the strip plus whatever sits in its column or row), or an empty set when nothing qualifies. */
    static Set<Component> findPanel(List<Component> big) {
        Set<Component> group = new HashSet<>();

        // Side strip: tall, narrow-ish, solid, in the right part, with the rest of the content to its left.
        List<Component> sides = big.stream()
                .filter(k -> k.height() >= 0.4 * S && k.width() >= 0.06 * S && k.width() <= 0.3 * S
                        && k.minX() >= 0.55 * S && k.fill() >= 0.5)
                .sorted((a, b) -> Integer.compare(b.area(), a.area()))
                .toList();
        for (Component strip : sides) {
            List<Component> inColumn = big.stream().filter(k -> k.minX() >= strip.minX() - 0.04 * S).toList();
            List<Component> rest = big.stream().filter(k -> !inColumn.contains(k)).toList();
            if (rest.isEmpty() || rest.stream().mapToInt(Component::maxX).max().getAsInt() > strip.minX() + 0.02 * S) {
                continue;
            }
            group.addAll(inColumn);
            break;
        }

        // Bottom strip: wide, thick, solid, in the lower part, with the rest of the content above it.
        List<Component> bottoms = big.stream()
                .filter(k -> k.width() >= 0.4 * S && k.height() >= 0.1 * S && k.minY() >= 0.6 * S
                        && k.fill() >= 0.5 && !group.contains(k))
                .sorted((a, b) -> Integer.compare(b.area(), a.area()))
                .toList();
        for (Component strip : bottoms) {
            List<Component> inRow = big.stream().filter(k -> k.minY() >= strip.minY() - 0.04 * S).toList();
            List<Component> rest = big.stream().filter(k -> !inRow.contains(k) && !group.contains(k)).toList();
            if (rest.isEmpty() || rest.stream().mapToInt(Component::maxY).max().getAsInt() > strip.minY() + 0.02 * S) {
                continue;
            }
            group.addAll(inRow);
            break;
        }
        return group;
    }
}
