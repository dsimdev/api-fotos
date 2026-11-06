package com.axum.fotos.core.pick;

import com.axum.fotos.core.image.ImageQuality;
import java.util.ArrayList;
import java.util.List;

/**
 * Something about the chosen photo that the person should look at before handing it over. A warning never
 * blocks a delivery: the photo is the best one found, and the report says what to check.
 */
public enum Warning {
    /** The product lies along the edge of the photo: either tightly framed or sliced by the source. */
    TOUCHES_EDGE,
    /** The photo looks soft, like an enlarged thumbnail. */
    SOFT,
    /** Smaller than the usual 500 px. */
    LOW_RESOLUTION;

    public static final int MIN_SIDE = 500;

    public static List<Warning> of(Candidate chosen) {
        var warnings = new ArrayList<Warning>();
        if (chosen.touchesEdge()) warnings.add(TOUCHES_EDGE);
        if (chosen.sharpness() < ImageQuality.SOFT_WARNING) warnings.add(SOFT);
        if (chosen.side() < MIN_SIDE) warnings.add(LOW_RESOLUTION);
        return warnings;
    }
}
