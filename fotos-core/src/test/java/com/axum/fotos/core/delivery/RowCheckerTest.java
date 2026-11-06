package com.axum.fotos.core.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axum.fotos.core.delivery.RowChecker.Checked;
import com.axum.fotos.core.sheet.ProductRow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RowCheckerTest {

    private static final String EAN_A = "7791337010604";
    private static final String EAN_B = "7791337010710";

    private static ProductRow row(int n, String sku, String ean) {
        return new ProductRow(n, "Producto " + n, sku, ean);
    }

    private static List<RowProblem> problems(ProductRow... rows) {
        var out = new ArrayList<RowProblem>();
        for (Checked c : RowChecker.check(List.of(rows))) out.add(c.problem());
        return out;
    }

    @Test
    void aRowWithAValidEanAndAUsableSkuIsUsableAndCarriesItsEan() {
        var checked = RowChecker.check(List.of(row(2, "640008", EAN_A))).get(0);

        assertTrue(checked.usable());
        assertEquals(EAN_A, checked.ean().orElseThrow().value());
        assertEquals(null, checked.problem());
        assertEquals(null, checked.detail());
    }

    @Test
    void eachKindOfProblemIsTold() {
        var result = problems(
                row(2, "1", ""),
                row(3, "2", "123"),
                row(4, "3", "7791337010605"),
                row(5, "", EAN_A),
                row(6, "a/b", EAN_A),
                row(7, "..", EAN_A),
                row(8, "NUL", EAN_B));

        assertEquals(List.of(RowProblem.NO_EAN, RowProblem.BAD_EAN, RowProblem.BAD_EAN, RowProblem.NO_SKU, RowProblem.BAD_SKU,
                RowProblem.BAD_SKU, RowProblem.BAD_SKU), result);
    }

    /** The barcode is what has to be fixed at the source, so it is the one reported when a row lacks both. */
    @Test
    void aRowThatLacksTheBarcodeAndTheSkuReportsTheBarcode() {
        assertEquals(List.of(RowProblem.NO_EAN), problems(row(2, "", "")));
    }

    @Test
    void aSkuAlreadyUsedByAnotherEanIsIgnoredAndTheFirstRowKeepsIt() {
        var checked = RowChecker.check(List.of(row(2, "77", EAN_A), row(3, "77", EAN_B), row(4, "78", EAN_B)));

        assertTrue(checked.get(0).usable());
        assertEquals(RowProblem.SKU_REUSED, checked.get(1).problem());
        assertTrue(checked.get(1).detail().contains("fila 2"), checked.get(1).detail());
        assertTrue(checked.get(2).usable(), "the product that lost its SKU keeps its other rows");
    }

    @Test
    void theSameSkuWithTheSameEanIsJustARepeatedLine() {
        var checked = RowChecker.check(List.of(row(2, "77", EAN_A), row(3, "77", EAN_A)));

        assertTrue(checked.get(0).usable() && checked.get(1).usable());
    }

    /** Windows file names do not tell "a1" from "A1", so neither does the check. */
    @Test
    void theSkuIsComparedWithoutCaseOrSurroundingSpaces() {
        var checked = RowChecker.check(List.of(row(2, "a1", EAN_A), row(3, " A1 ", EAN_B)));

        assertEquals(RowProblem.SKU_REUSED, checked.get(1).problem());
    }

    /** A row that is ignored for another reason does not take the SKU away from a good one. */
    @Test
    void anIgnoredRowDoesNotClaimItsSku() {
        var checked = RowChecker.check(List.of(row(2, "55", "123"), row(3, "55", EAN_A)));

        assertEquals(RowProblem.BAD_EAN, checked.get(0).problem());
        assertTrue(checked.get(1).usable());
    }

    @Test
    void theResultKeepsTheOrderAndTheSizeOfTheInput() {
        var rows = new ArrayList<ProductRow>();
        for (int i = 0; i < 50; i++) rows.add(row(i + 2, "S" + i, i % 2 == 0 ? EAN_A.substring(0, 12) + "4" : ""));

        var checked = RowChecker.check(rows);

        assertEquals(50, checked.size());
        for (int i = 0; i < 50; i++) assertEquals(i + 2, checked.get(i).row().row());
        assertFalse(checked.get(1).usable());
    }

    @Test
    void everyProblemHasAMessageAndASummaryForThePerson() {
        for (RowProblem p : RowProblem.values()) {
            assertFalse(p.message().isBlank(), p.name());
            assertFalse(p.summary().isBlank(), p.name());
        }
    }
}
