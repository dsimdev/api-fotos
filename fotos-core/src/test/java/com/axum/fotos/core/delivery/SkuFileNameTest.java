package com.axum.fotos.core.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SkuFileNameTest {

    @Test
    void ordinarySkusAreFileNames() {
        for (String ok : new String[]{"640008", "0609400", "A-12", "ls_900.v2", "x"}) {
            assertEquals(Optional.of(ok), SkuFileName.of(ok), ok);
        }
    }

    @Test
    void surroundingSpacesAreTrimmedButNothingElseIsChanged() {
        assertEquals(Optional.of("640008"), SkuFileName.of("  640008 "));
    }

    @Test
    void anythingThatCouldEscapeTheFolderOrIsNotAWindowsNameIsRefused() {
        for (String bad : new String[]{"", " ", "../x", "..", ".", "a/b", "a\\b", "C:x", "a b", "a:b", "x.", ".hidden", "-x", "a*b",
                "a?b", "a\"b", "a<b", "CON", "con", "nul.txt", "COM1", "LPT9.jpg", "ñandú", "a\u0000b", "a\nb"}) {
            assertTrue(SkuFileName.of(bad).isEmpty(), "must be refused: [" + bad + "]");
        }
        assertTrue(SkuFileName.of(null).isEmpty());
        assertTrue(SkuFileName.of("a".repeat(101)).isEmpty());
        assertTrue(SkuFileName.of("a".repeat(100)).isPresent());
    }
}
