package com.axum.fotos.core.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.image.BannerCropper.Status;
import org.junit.jupiter.api.Test;

class BannerCropperTest {

    /** One cell of the 400x400 working copy is 2.5 px on a 1000 px image, and the box carries one cell of slack on each side. */
    private static final int TOLERANCE = 8;

    private static void assertNear(int expected, int actual, String what) {
        assertTrue(Math.abs(expected - actual) <= TOLERANCE, what + ": expected ~" + expected + " but was " + actual);
    }

    @Test
    void cutsASidePanelAndItsBadgeAndKeepsTheWholeProduct() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 100, 150, 460, 650)
                .rect(Synthetic.RED, 700, 60, 200, 640)
                .rect(Synthetic.RED, 700, 720, 200, 180)
                .done();

        var result = BannerCropper.crop(image);

        assertEquals(Status.CROPPED, result.status());
        assertNear(100, result.productBox().x(), "left edge");
        assertNear(150, result.productBox().y(), "top edge");
        assertNear(460, result.productBox().width(), "width");
        assertNear(650, result.productBox().height(), "height");
        assertFalse(Synthetic.contains(result.image(), Synthetic.RED), "no panel colour is left");
        assertTrue(Synthetic.contains(result.image(), Synthetic.PRODUCT), "the product is there");
        assertEquals(result.image().getWidth(), result.image().getHeight(), "a square, so the shop's thumbnail does not distort it");
    }

    @Test
    void cutsABottomPanel() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 200, 50, 600, 550)
                .rect(Synthetic.BLUE, 100, 700, 800, 250)
                .done();

        var result = BannerCropper.crop(image);

        assertEquals(Status.CROPPED, result.status());
        assertFalse(Synthetic.contains(result.image(), Synthetic.BLUE));
        assertTrue(Synthetic.contains(result.image(), Synthetic.PRODUCT));
    }

    /**
     * The bug that put a panel in 8 delivered photos: a white pack breaks into several pale pieces, each
     * smaller than the solid panel, and the first version took "the biggest blob" as the product.
     */
    @Test
    void aProductMadeOfSeveralSmallerPiecesIsKeptWholeEvenWhenThePanelIsTheBiggestBlob() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PALE, 100, 100, 200, 200)
                .rect(Synthetic.PALE, 100, 330, 200, 200)
                .rect(Synthetic.PALE, 100, 560, 200, 190)
                .rect(Synthetic.RED, 700, 60, 200, 640)
                .rect(Synthetic.RED, 700, 720, 200, 180)
                .done();

        var result = BannerCropper.crop(image);

        assertEquals(Status.CROPPED, result.status());
        assertNear(100, result.productBox().x(), "left edge");
        assertNear(100, result.productBox().y(), "top edge");
        assertNear(200, result.productBox().width(), "width");
        assertNear(650, result.productBox().height(), "height: the three pieces, not only one");
        assertFalse(Synthetic.contains(result.image(), Synthetic.RED));
    }

    /** The other half of the same bug: a thin piece at the foot of the product was cut as if it were a bottom panel. */
    @Test
    void aThinWidePieceAtTheFootOfTheProductIsNotAPanel() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 250, 80, 500, 520)
                .rect(Synthetic.PALE, 200, 640, 600, 50)
                .done();

        var result = BannerCropper.crop(image);

        assertEquals(Status.NO_SEPARATE_PANEL, result.status());
        assertNull(result.image());
    }

    @Test
    void aShotWithoutAPanelIsLeftAlone() {
        var image = Synthetic.canvas(1000).rect(Synthetic.PRODUCT, 250, 100, 500, 800).done();

        var result = BannerCropper.crop(image);

        assertEquals(Status.NO_SEPARATE_PANEL, result.status());
        assertNull(result.productBox());
        assertNull(result.image());
    }

    @Test
    void aBlankImageHasNothingToCrop() {
        assertEquals(Status.EMPTY, BannerCropper.crop(Synthetic.canvas(1000).done()).status());
    }

    @Test
    void theProductIsNeverScaled() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 100, 150, 400, 600)
                .rect(Synthetic.RED, 700, 60, 200, 640)
                .rect(Synthetic.RED, 700, 720, 200, 180)
                .done();

        var result = BannerCropper.crop(image);

        // The product is copied pixel for pixel: its box in the output is as big as in the original.
        int side = result.image().getWidth();
        int expectedSide = (int) Math.ceil(Math.max(result.productBox().width(), result.productBox().height()) / 0.84);
        assertEquals(expectedSide, side);
        assertNotNull(result.image());
    }

    @Test
    void aTransparentBackgroundCountsAsWhite() {
        var argb = new java.awt.image.BufferedImage(1000, 1000, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = argb.createGraphics();
        g.setColor(Synthetic.PRODUCT);
        g.fillRect(100, 150, 460, 650);
        g.setColor(Synthetic.RED);
        g.fillRect(700, 60, 200, 640);
        g.fillRect(700, 720, 200, 180);
        g.dispose();

        var result = BannerCropper.crop(argb);

        assertEquals(Status.CROPPED, result.status());
        assertNear(100, result.productBox().x(), "left edge");
    }
}
