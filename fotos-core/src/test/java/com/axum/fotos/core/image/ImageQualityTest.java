package com.axum.fotos.core.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import org.junit.jupiter.api.Test;

class ImageQualityTest {

    @Test
    void aProductWithMarginsDoesNotTouchTheEdge() {
        var image = Synthetic.canvas(1000).rect(Synthetic.PRODUCT, 200, 100, 500, 800).done();

        assertEquals(0.0, ImageQuality.productEdgeContact(image), 0.001);
    }

    @Test
    void aProductSlicedFlatByTheBottomEdgeLiesAlongIt() {
        // The base of a carton reaches the last row over 40% of the width.
        var image = Synthetic.canvas(1000).rect(Synthetic.PRODUCT, 300, 100, 400, 900).done();

        assertEquals(0.4, ImageQuality.productEdgeContact(image), 0.03);
    }

    /** A round pack tangent to the frame is not sliced: it stays under the 0.25 that the pick treats as touching. */
    @Test
    void aRoundProductTangentToTheEdgeScoresLow() {
        var image = Synthetic.canvas(1000).oval(Synthetic.PRODUCT, 0, 100, 500, 800).done();

        double contact = ImageQuality.productEdgeContact(image);

        assertTrue(contact > 0.0 && contact < ImageQuality.TOUCHING_EDGE, "contact was " + contact);
    }

    /** The panel strips of a banner shot run from the top edge to the bottom edge: they are not the product. */
    @Test
    void thePanelThatTouchesTheEdgeDoesNotCountAsACutProduct() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 100, 150, 460, 650)
                .rect(Synthetic.RED, 700, 0, 200, 700)
                .rect(Synthetic.RED, 700, 720, 200, 280)
                .done();

        var result = BannerCropper.crop(image);

        assertEquals(BannerCropper.Status.CROPPED, result.status());
        assertEquals(0.0, result.edgeContact(), 0.001);
    }

    @Test
    void theCropperReportsAProductThatTheSourceAlreadySliced() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 100, 150, 460, 850)
                .rect(Synthetic.RED, 700, 60, 200, 640)
                .rect(Synthetic.RED, 700, 720, 200, 180)
                .done();

        var result = BannerCropper.crop(image);

        assertEquals(BannerCropper.Status.CROPPED, result.status());
        assertTrue(result.edgeContact() > 0.25, "contact was " + result.edgeContact());
    }

    @Test
    void aBlurredImageScoresFarBelowTheSharpOne() {
        var sharp = checker(1000);
        var blurred = blur(sharp, 15);

        double sharpScore = ImageQuality.sharpness(sharp);
        double blurredScore = ImageQuality.sharpness(blurred);

        assertTrue(sharpScore > 10 * blurredScore, "sharp " + sharpScore + " vs blurred " + blurredScore);
    }

    @Test
    void aBlankImageHasNoDetailAtAll() {
        assertEquals(0.0, ImageQuality.sharpness(Synthetic.canvas(600).done()), 0.0001);
    }

    private static BufferedImage checker(int size) {
        var s = Synthetic.canvas(size);
        for (int y = 200; y < 800; y += 6) {
            for (int x = 200; x < 800; x += 6) {
                if (((x / 6) + (y / 6)) % 2 == 0) s.rect(Color.DARK_GRAY, x, y, 6, 6);
            }
        }
        return s.done();
    }

    private static BufferedImage blur(BufferedImage image, int radius) {
        int n = radius * radius;
        float[] data = new float[n];
        java.util.Arrays.fill(data, 1f / n);
        return new ConvolveOp(new Kernel(radius, radius, data), ConvolveOp.EDGE_NO_OP, null).filter(image, null);
    }
}
