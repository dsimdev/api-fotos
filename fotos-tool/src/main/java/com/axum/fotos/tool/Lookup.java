package com.axum.fotos.tool;

import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.Warning;
import java.util.List;

/**
 * The answer for one EAN.
 *
 * @param chosen   only for FOUND
 * @param detail   in Spanish, it goes to the report: why there is no photo, or a note about the one there is
 * @param fromCache true if no store was asked
 */
public record Lookup(Status status, Candidate chosen, List<Warning> warnings, String detail, boolean fromCache) {

    public enum Status {
        FOUND,
        /** Every store answered and there is no photo fit to deliver. */
        NO_IMAGE,
        /** Some store could not be asked, so nothing can be said yet; it is not remembered, running again may fix it. */
        ERROR
    }

    public Lookup {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    static Lookup found(Candidate chosen, String detail, boolean fromCache) {
        return new Lookup(Status.FOUND, chosen, Warning.of(chosen), detail, fromCache);
    }

    static Lookup noImage(String detail, boolean fromCache) {
        return new Lookup(Status.NO_IMAGE, null, List.of(), detail, fromCache);
    }

    static Lookup error(String detail) {
        return new Lookup(Status.ERROR, null, List.of(), detail, false);
    }
}
