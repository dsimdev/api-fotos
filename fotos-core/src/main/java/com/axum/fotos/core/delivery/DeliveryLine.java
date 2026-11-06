package com.axum.fotos.core.delivery;

import com.axum.fotos.core.image.ImageFormat;
import com.axum.fotos.core.pick.Candidate;
import com.axum.fotos.core.pick.Warning;
import java.util.List;

/**
 * What came out for one row of the client's sheet.
 *
 * @param row     the row in the file, to point the person at it
 * @param image   only for OK: the bytes of the photo; null otherwise
 * @param detail  why there is no photo (or a note about the one there is); free text for the report
 * @param problem only for DATOS_INVALIDOS: which kind of problem the row has, so the summary can count them by kind
 */
public record DeliveryLine(int row, String sku, String ean, String description, Result result, String store,
                           Candidate.Kind kind, ImageFormat format, int side, List<Warning> warnings,
                           byte[] image, String detail, RowProblem problem) {

    public enum Result {
        /** There is a photo for this SKU. */
        OK,
        /** The EAN is valid but there is no image fit to deliver. */
        SIN_IMAGEN,
        /** The stores could not be asked (network, blocked, limited): running again may fix it. */
        ERROR,
        /** The row itself cannot be used: bad EAN, a SKU that is not a file name, a repeated SKU. */
        DATOS_INVALIDOS
    }

    public DeliveryLine {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** The state as the report spells it: a photo that had its panel cut off says so. */
    public String stateName() {
        return result == Result.OK && kind == Candidate.Kind.CROPPED ? "OK_RECORTADA" : result.name();
    }

    public static DeliveryLine ok(int row, String sku, String ean, String description, Candidate chosen, List<Warning> warnings) {
        return ok(row, sku, ean, description, chosen, warnings, null);
    }

    public static DeliveryLine ok(int row, String sku, String ean, String description, Candidate chosen, List<Warning> warnings,
                                  String note) {
        return new DeliveryLine(row, sku, ean, description, Result.OK, chosen.store(), chosen.kind(), chosen.format(),
                chosen.side(), warnings, chosen.bytes(), note, null);
    }

    public static DeliveryLine without(int row, String sku, String ean, String description, Result result, String detail) {
        return new DeliveryLine(row, sku, ean, description, result, null, null, null, 0, List.of(), null, detail, null);
    }

    /** A row that is ignored (see {@link RowProblem}): not looked up, only counted and explained in the report. */
    public static DeliveryLine invalid(int row, String sku, String ean, String description, RowProblem problem, String detail) {
        return new DeliveryLine(row, sku, ean, description, Result.DATOS_INVALIDOS, null, null, null, 0, List.of(), null, detail, problem);
    }
}
