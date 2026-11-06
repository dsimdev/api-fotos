package com.axum.fotos.core.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class ImageFingerprintTest {

    /**
     * A "pack" drawn as a smooth diagonal wave of greys filling the box (x, y, w, h) of a square white canvas. Smooth
     * on purpose: flat bands leave neighbouring cells tied, and a tie is decided by rounding noise, which no real
     * photograph (it has texture) does. Greys stay under the white threshold so the whole box counts as the product.
     */
    private static BufferedImage pack(int canvas, int x, int y, int w, int h, boolean mirrored) {
        BufferedImage image = Synthetic.canvas(canvas).done();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double u = (double) i / w, v = (double) j / h;
                double phase = 2 * Math.PI * ((mirrored ? -1.5 : 1.5) * u + 0.5 * v);
                int grey = (int) Math.round(128 + 100 * Math.sin(phase));
                image.setRGB(x + i, y + j, new Color(grey, grey, grey).getRGB());
            }
        }
        return image;
    }

    @Test
    void theSameShotAtAnotherSizeAndWithOtherMarginsIsAlmostTheSame() {
        long small = ImageFingerprint.of(pack(600, 100, 60, 240, 400, false));
        long large = ImageFingerprint.of(pack(1500, 400, 300, 600, 1000, false));

        assertTrue(ImageFingerprint.distance(small, large) <= 6, "distance " + ImageFingerprint.distance(small, large));
    }

    @Test
    void aDifferentPackIsFarAway() {
        long one = ImageFingerprint.of(pack(1000, 200, 100, 400, 700, false));
        long other = ImageFingerprint.of(pack(1000, 200, 100, 400, 700, true));

        assertTrue(ImageFingerprint.distance(one, other) >= 17, "distance " + ImageFingerprint.distance(one, other));
    }

    @Test
    void theDistanceIsTheNumberOfDifferingBits() {
        assertEquals(0, ImageFingerprint.distance(0xABCDL, 0xABCDL));
        assertEquals(3, ImageFingerprint.distance(0b1010L, 0b0001L));
        assertEquals(64, ImageFingerprint.distance(0L, -1L));
    }

    @Test
    void aBlankImageDoesNotCrash() {
        ImageFingerprint.of(Synthetic.canvas(300).done());
    }
}
