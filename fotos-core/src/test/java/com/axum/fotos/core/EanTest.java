package com.axum.fotos.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EanTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "7790070337016",   // EAN-13, the dyssa Matarazzo lasagna
            "7790310985502",   // EAN-13
            "73513537",        // EAN-8
            "036000291452",    // UPC-A (12)
            "10012345678902"   // GTIN-14
    })
    void acceptsValidCodes(String code) {
        assertTrue(Ean.isValid(code));
        assertEquals(code, Ean.parse(code).value());
    }

    @Test
    void rejectsAWrongCheckDigit() {
        assertFalse(Ean.isValid("7790070337017"));
        assertFalse(Ean.isValid("73513538"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"779007033701", "779007033701600", "1234567", "123456789", "abcdefghijklm", "77900703370 6", "-7790070337016"})
    void rejectsWrongLengthOrNonDigits(String code) {
        assertFalse(Ean.isValid(code));
    }

    @Test
    void rejectsDigitsFromOtherScripts() {
        // Fullwidth and Arabic-Indic digits pass Character.isDigit but are not an EAN.
        assertFalse(Ean.isValid("７７９００７０３３７０１６"));
        assertFalse(Ean.isValid("٧٧٩٠٠٧٠٣٣٧٠١٦"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsNullAndBlank(String code) {
        assertFalse(Ean.isValid(code));
        assertThrows(IllegalArgumentException.class, () -> Ean.parse(code));
    }

    @Test
    void parseTrimsSurroundingWhitespace() {
        assertEquals("7790070337016", Ean.parse("  7790070337016\n").value());
    }

    @Test
    void tryParseDoesNotThrow() {
        assertTrue(Ean.tryParse("7790070337016").isPresent());
        assertTrue(Ean.tryParse("not an ean").isEmpty());
        assertTrue(Ean.tryParse(null).isEmpty());
    }

    @Test
    void theErrorDoesNotEchoTheInput() {
        var e = assertThrows(IllegalArgumentException.class, () -> Ean.parse("<script>alert(1)</script>"));
        assertFalse(e.getMessage().contains("script"));
    }
}
