package com.axum.fotos.core.pick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidatePickerTest {

    private static final List<String> ORDER = List.of("jumbo", "carrefour", "masonline", "cordiez");

    private static Candidate c(String store, Kind kind, int side) {
        return new Candidate(store, "page", "img", kind, ImageFormat.JPEG, side, 0.0, 200.0, new byte[0]);
    }

    private static Candidate with(Candidate base, double edgeContact, double sharpness) {
        return new Candidate(base.store(), base.pageUrl(), base.imageUrl(), base.kind(), base.format(), base.side(),
                edgeContact, sharpness, base.bytes());
    }

    private static String pick(Candidate... candidates) {
        return CandidatePicker.pick(List.of(candidates), ORDER).orElseThrow().store();
    }

    @Test
    void noCandidatesMeansNoPick() {
        assertTrue(CandidatePicker.pick(List.of(), ORDER).isEmpty());
    }

    @Test
    void anUntouchedOriginalBeatsACroppedOne() {
        assertEquals("cordiez", pick(c("jumbo", Kind.CROPPED, 1000), c("cordiez", Kind.ORIGINAL, 1000)));
    }

    @Test
    void aLowResolutionOriginalDoesNotBeatAGoodCrop() {
        assertEquals("jumbo", pick(c("jumbo", Kind.CROPPED, 1000), c("cordiez", Kind.ORIGINAL, 300)));
    }

    @Test
    void theLargerOneWinsWhenEverythingElseIsEqual() {
        assertEquals("carrefour", pick(c("jumbo", Kind.CROPPED, 1000), c("carrefour", Kind.CROPPED, 2000)));
    }

    @Test
    void theConfiguredStoreOrderBreaksTheLastTie() {
        assertEquals("jumbo", pick(c("carrefour", Kind.CROPPED, 1000), c("jumbo", Kind.CROPPED, 1000)));
    }

    /** The case of 3 of the 124 delivered photos: the store's original was sliced flat at the base. */
    @Test
    void aProductTouchingTheEdgeGoesBehindOneWithMargins() {
        var sliced = with(c("cordiez", Kind.ORIGINAL, 1000), 0.42, 900);

        assertEquals("jumbo", pick(sliced, c("jumbo", Kind.CROPPED, 849)));
    }

    /** The case of 640017: a thumbnail enlarged to 2000 px. */
    @Test
    void anEnlargedThumbnailGoesBehindASharperOne() {
        var blurry = with(c("carrefour", Kind.CROPPED, 2197), 0.0, 6);

        assertEquals("masonline", pick(blurry, c("masonline", Kind.CROPPED, 1105)));
    }

    @Test
    void whenEveryCandidateTouchesTheEdgeOneIsStillDelivered() {
        var a = with(c("jumbo", Kind.CROPPED, 800), 0.4, 200);
        var b = with(c("carrefour", Kind.CROPPED, 1200), 0.4, 200);

        assertEquals("carrefour", pick(a, b));
    }

    @Test
    void sharpnessIsNotComparedBetweenCandidates() {
        // A 2000 px image scores lower than a 1000 px one of the same photo once shrunk for analysis: that is not blur.
        var big = with(c("carrefour", Kind.CROPPED, 2000), 0.0, 230);
        var small = with(c("jumbo", Kind.CROPPED, 1000), 0.0, 1100);

        assertEquals("carrefour", pick(big, small));
    }

    private static Candidate shot(String store, Kind kind, long fingerprint, int fileId) {
        return new Candidate(store, "page", "img", kind, ImageFormat.JPEG, 1000, 0.0, 200.0,
                new byte[]{(byte) fileId}, fingerprint);
    }

    /** 964107: Cordiez shows an older 946 ml pack from another angle; Jumbo and Carrefour agree on the current 1 L. */
    @Test
    void theStoresThatAgreeBeatTheOneThatShowsAnotherPicture() {
        var agreeA = shot("jumbo", Kind.CROPPED, 0b1111_0000L, 1);
        var agreeB = shot("carrefour", Kind.CROPPED, 0b1111_0011L, 2);
        var other = shot("cordiez", Kind.ORIGINAL, 0xFFFF_FFFF_0000_0000L, 3);

        assertEquals("jumbo", pick(agreeA, agreeB, other), "the original no longer wins just for being an original");
    }

    /** Jumbo, Disco and Vea serve one and the same file: that is one opinion, not three. */
    @Test
    void threeCopiesOfOneFileAreOneVote() {
        var a = shot("jumbo", Kind.CROPPED, 0L, 1);
        var b = shot("disco", Kind.CROPPED, 0L, 1);
        var c = shot("vea", Kind.CROPPED, 0L, 1);
        var other = shot("cordiez", Kind.ORIGINAL, -1L, 2);

        assertEquals("cordiez", pick(a, b, c, other), "1 file against 1 file is no majority: the usual rule decides");
    }

    @Test
    void whenEveryStoreShowsADifferentPictureThereIsNoMajorityAndTheUsualRuleDecides() {
        var a = shot("jumbo", Kind.CROPPED, 0L, 1);
        var b = shot("carrefour", Kind.CROPPED, 0xFFFF_0000_0000_0000L, 2);
        var c = shot("cordiez", Kind.ORIGINAL, 0x0000_0000_FFFF_0000L, 3);

        assertEquals("cordiez", pick(a, b, c));
    }

    /** The layers decide who can be chosen, but everybody votes: a too-tight crop still shows the same picture. */
    @Test
    void aCandidateThatTouchesTheEdgeStillVotesButCannotBeChosen() {
        var tooTight = new Candidate("carrefour", "p", "i", Kind.CROPPED, ImageFormat.JPEG, 2300, 0.38, 300.0, new byte[]{9}, 0b0011L);
        var agrees = shot("jumbo", Kind.CROPPED, 0b0001L, 1);
        var other = shot("cordiez", Kind.ORIGINAL, 0xFFFF_FFFF_0000_0000L, 3);

        assertEquals("jumbo", pick(tooTight, agrees, other));
    }

    /** 610021: the sliced original that two stores share must not outvote the intact crop of the same photo. */
    @Test
    void aMajorityOfSlicedCopiesDoesNotOutvoteTheIntactCrop() {
        var intact = shot("jumbo", Kind.CROPPED, 0L, 1);
        var slicedA = new Candidate("masonline", "p", "i", Kind.ORIGINAL, ImageFormat.JPEG, 1000, 0.42, 900.0, new byte[]{2}, 0xFFFF_0000_0000_0000L);
        var slicedB = new Candidate("cordiez", "p", "i", Kind.ORIGINAL, ImageFormat.JPEG, 1000, 0.42, 900.0, new byte[]{3}, 0xFFFF_0000_0000_0000L);

        assertEquals("jumbo", pick(intact, slicedA, slicedB));
    }

    @Test
    void theMajorityLayerCanBeSwitchedOff() {
        var agreeA = shot("jumbo", Kind.CROPPED, 0b1111_0000L, 1);
        var agreeB = shot("carrefour", Kind.CROPPED, 0b1111_0011L, 2);
        var other = shot("cordiez", Kind.ORIGINAL, 0xFFFF_FFFF_0000_0000L, 3);

        var picked = CandidatePicker.pick(List.of(agreeA, agreeB, other), ORDER, -1).orElseThrow();

        assertEquals("cordiez", picked.store());
    }

    @Test
    void aStoreNotInTheOrderComesLast() {
        assertEquals("jumbo", pick(c("unknown", Kind.CROPPED, 1000), c("jumbo", Kind.CROPPED, 1000)));
    }

    @Test
    void warningsTellWhatToLookAt() {
        assertEquals(List.of(), Warning.of(c("jumbo", Kind.CROPPED, 1000)));
        assertEquals(List.of(Warning.TOUCHES_EDGE), Warning.of(with(c("jumbo", Kind.CROPPED, 1000), 0.3, 200)));
        assertEquals(List.of(Warning.SOFT), Warning.of(with(c("jumbo", Kind.CROPPED, 1000), 0.0, 30)));
        assertEquals(List.of(Warning.LOW_RESOLUTION), Warning.of(c("jumbo", Kind.CROPPED, 450)));
    }
}
