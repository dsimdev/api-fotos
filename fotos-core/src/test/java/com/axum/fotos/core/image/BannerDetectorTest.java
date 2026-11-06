package com.axum.fotos.core.image;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axum.fotos.core.image.BannerDetector.Kind;
import java.awt.Color;
import org.junit.jupiter.api.Test;

class BannerDetectorTest {

    @Test
    void findsASidePanel() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 100, 150, 460, 650)
                .rect(Synthetic.RED, 700, 60, 200, 640)
                .rect(Synthetic.RED, 700, 720, 200, 180)
                .done();

        assertEquals(Kind.SIDE, BannerDetector.detect(image).kind());
    }

    @Test
    void findsABottomPanel() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 100, 50, 500, 550)
                .rect(Synthetic.BLUE, 100, 760, 800, 200)
                .done();

        assertEquals(Kind.BOTTOM, BannerDetector.detect(image).kind());
    }

    /** The product keeps to the left 60% and the top 65%, as the catalog shots do: that is what the detector assumes. */
    @Test
    void aCleanShotHasNoPanel() {
        var image = Synthetic.canvas(1000).rect(Synthetic.PRODUCT, 150, 100, 450, 550).done();

        assertEquals(Kind.NONE, BannerDetector.detect(image).kind());
    }

    @Test
    void whiteTextInsideThePanelDoesNotSplitIt() {
        var image = Synthetic.canvas(1000)
                .rect(Synthetic.PRODUCT, 100, 150, 460, 650)
                .rect(Synthetic.RED, 700, 60, 200, 840)
                .rect(Color.WHITE, 760, 200, 60, 400)
                .done();

        assertEquals(Kind.SIDE, BannerDetector.detect(image).kind());
    }

    @Test
    void aNonWhiteBackgroundIsNotACatalogShot() {
        var image = Synthetic.canvas(1000, new Color(120, 120, 120)).rect(Synthetic.PRODUCT, 250, 100, 500, 800).done();

        assertEquals(Kind.UNKNOWN, BannerDetector.detect(image).kind());
    }
}
