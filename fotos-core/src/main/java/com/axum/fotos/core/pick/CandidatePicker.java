package com.axum.fotos.core.pick;

import com.axum.fotos.core.image.ImageFingerprint;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Chooses the photo to recommend among the candidates of ONE product, in layers. Each of the first three only
 * narrows the field when it leaves somebody standing, so nothing is ever thrown away for good:
 *
 * <ol>
 *   <li>not touching the edge of the photo (a sliced product is the one thing a crop cannot repair);
 *   <li>not soft (an enlarged thumbnail);
 *   <li>the MAJORITY picture: when stores show different pictures of the product, the one that most of them agree
 *       on. This is what catches a store that carries another variant or an old pack (Cordiez's 946 ml Silk against
 *       the 1 L that Jumbo and Carrefour show). Everybody votes, but only what is left after the two layers above
 *       can be chosen: a sliced original shared by two stores must not win over the intact crop of the same photo;
 * </ol>
 *
 * <p>and then, among what remains, the first difference wins: at least {@link Warning#MIN_SIDE} px, then the
 * store's untouched original over a cropped one (no re-encoding, no re-centring), then the larger one, then the
 * store that comes first in the configured order.
 *
 * <p>Votes are counted per DISTINCT FILE: Jumbo, Disco and Vea serve the very same file, and three copies of one
 * picture are one opinion, not three. Two pictures agree when their fingerprints are within
 * {@value #CONSENSUS_DISTANCE} bits of 64 (measured: the same shot at another size or crop is 0 to 6 bits apart,
 * a different pack or angle 17 to 31).
 *
 * <p>Sharpness is NOT compared between candidates: a 2000 px image shrunk to the analysis size smooths out and
 * scores lower than a 1000 px one of the same photo, so "the sharper" would just mean "the smaller".
 */
public final class CandidatePicker {

    public static final int CONSENSUS_DISTANCE = 8;

    private CandidatePicker() {}

    public static Optional<Candidate> pick(List<Candidate> candidates, List<String> storeOrder) {
        return pick(candidates, storeOrder, CONSENSUS_DISTANCE);
    }

    /** @param consensusDistance bits of difference within which two pictures agree; negative turns the majority layer off */
    public static Optional<Candidate> pick(List<Candidate> candidates, List<String> storeOrder, int consensusDistance) {
        List<Candidate> pool = keepIfAny(candidates, c -> !c.touchesEdge());
        pool = keepIfAny(pool, c -> !c.soft());
        if (consensusDistance >= 0) pool = majority(pool, candidates, consensusDistance);
        return pool.stream().min(comparator(storeOrder));
    }

    private static List<Candidate> keepIfAny(List<Candidate> pool, Predicate<Candidate> keep) {
        List<Candidate> kept = pool.stream().filter(keep).toList();
        return kept.isEmpty() ? pool : kept;
    }

    /**
     * Among {@code pool}, those whose picture is shared by the most distinct files, if at least two files agree on
     * anything. EVERY candidate votes ({@code voters}), also the ones the layers above kept out of the pool: a store
     * whose crop is too tight still shows the same picture as the others, and its vote counts.
     */
    private static List<Candidate> majority(List<Candidate> pool, List<Candidate> voters, int distance) {
        int[] support = new int[pool.size()];
        int best = 0;
        for (int i = 0; i < pool.size(); i++) {
            Set<ByteBuffer> files = new HashSet<>();
            for (Candidate other : voters) {
                if (ImageFingerprint.distance(pool.get(i).fingerprint(), other.fingerprint()) <= distance) {
                    files.add(ByteBuffer.wrap(other.bytes()));
                }
            }
            support[i] = files.size();
            best = Math.max(best, support[i]);
        }
        if (best < 2) return pool;
        var winners = new java.util.ArrayList<Candidate>();
        for (int i = 0; i < pool.size(); i++) if (support[i] == best) winners.add(pool.get(i));
        return winners;
    }

    static Comparator<Candidate> comparator(List<String> storeOrder) {
        return Comparator.<Candidate, Boolean>comparing(c -> c.side() < Warning.MIN_SIDE)
                .thenComparing(c -> c.kind() != Candidate.Kind.ORIGINAL)
                .thenComparing(Comparator.comparingInt(Candidate::side).reversed())
                .thenComparingInt(c -> {
                    int index = storeOrder.indexOf(c.store());
                    return index < 0 ? Integer.MAX_VALUE : index;
                });
    }
}
