package com.axum.fotos.core.pick;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the photo to recommend among the candidates of ONE product. In this order, the first difference wins:
 *
 * <ol>
 *   <li>not touching the edge of the photo (a sliced product is the one thing a crop cannot repair);
 *   <li>not soft (an enlarged thumbnail);
 *   <li>at least {@link Warning#MIN_SIDE} px;
 *   <li>the store's untouched original over a cropped one (no re-encoding, no re-centring);
 *   <li>the larger one;
 *   <li>the store that comes first in the configured order.
 * </ol>
 *
 * <p>The first two only move a candidate to the back: they never discard it, because a tight complete carton
 * and a sliced one measure the same (see {@code ImageQuality}).
 *
 * <p>Sharpness is NOT compared between candidates: a 2000 px image shrunk to the analysis size smooths out and
 * scores lower than a 1000 px one of the same photo, so "the sharper" would just mean "the smaller".
 */
public final class CandidatePicker {

    private CandidatePicker() {}

    public static Optional<Candidate> pick(List<Candidate> candidates, List<String> storeOrder) {
        return candidates.stream().min(comparator(storeOrder));
    }

    static Comparator<Candidate> comparator(List<String> storeOrder) {
        return Comparator.<Candidate, Boolean>comparing(Candidate::touchesEdge)
                .thenComparing(Candidate::soft)
                .thenComparing(c -> c.side() < Warning.MIN_SIDE)
                .thenComparing(c -> c.kind() != Candidate.Kind.ORIGINAL)
                .thenComparing(Comparator.comparingInt(Candidate::side).reversed())
                .thenComparingInt(c -> {
                    int index = storeOrder.indexOf(c.store());
                    return index < 0 ? Integer.MAX_VALUE : index;
                });
    }
}
