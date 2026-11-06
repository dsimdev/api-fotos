package com.axum.fotos.core.delivery;

/**
 * Why a row of the client's sheet is not looked up at all. Such a row is IGNORED: no store is asked about it, it is not
 * printed line by line and it has no card in the gallery. It is only counted by kind in the summary and explained in the
 * report, so a sheet with thousands of articles without a barcode does not bury the ones that matter.
 *
 * <p>The texts are in Spanish: they are shown as they are to the person who has to fix the source.
 */
public enum RowProblem {
    NO_EAN("sin código de barras (EAN)",
            "No tiene código de barras (EAN) en la planilla: hay que cargarlo en el sistema de origen."),
    BAD_EAN("con EAN inválido",
            "EAN inválido: revisar el largo, que sean solo dígitos y el dígito verificador."),
    NO_SKU("sin SKU", "Falta el SKU."),
    BAD_SKU("con un SKU que no sirve de nombre de archivo",
            "El SKU no sirve como nombre de archivo: solo letras, números, punto, guion y guion bajo."),
    SKU_REUSED("con un SKU repetido en otra fila con otro EAN", "SKU repetido con otro EAN.");

    private final String summary;
    private final String message;

    RowProblem(String summary, String message) {
        this.summary = summary;
        this.message = message;
    }

    /** How the summary counts them: "239 sin código de barras (EAN)". */
    public String summary() {
        return summary;
    }

    /** What the report says about the row. */
    public String message() {
        return message;
    }

    /** A repeated SKU also says where the first one is. */
    public static String skuReused(int firstRow) {
        return "SKU repetido con otro EAN (ya está en la fila " + firstRow + ").";
    }
}
