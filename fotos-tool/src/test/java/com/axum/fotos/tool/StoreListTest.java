package com.axum.fotos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreListTest {

    private static List<StoreList.Entry> parse(String text) throws IOException {
        return StoreList.parse(new BufferedReader(new StringReader(text)));
    }

    @Test
    void readsTheStoresInOrderIgnoringCommentsAndBlankLines() throws IOException {
        var stores = parse("# the stores\n\njumbo https://www.jumbo.com.ar\n  carrefour   https://www.carrefour.com.ar/  \n");

        assertEquals(List.of(new StoreList.Entry("jumbo", "https://www.jumbo.com.ar"),
                new StoreList.Entry("carrefour", "https://www.carrefour.com.ar")), stores, "the trailing slash is dropped");
    }

    @Test
    void aLineWithoutAnAddressIsRefused() {
        var e = assertThrows(IOException.class, () -> parse("jumbo\n"));
        assertTrue(e.getMessage().contains("jumbo"), e.getMessage());
    }

    @Test
    void anAddressThatIsNotHttpIsRefused() {
        assertThrows(IOException.class, () -> parse("jumbo file:///etc/passwd\n"));
        assertThrows(IOException.class, () -> parse("jumbo not-a-url\n"));
    }

    @Test
    void anEmptyListIsRefused() {
        assertThrows(IOException.class, () -> parse("# nothing\n"));
    }

    @Test
    void theBundledListHasTheSevenStoresInTheMeasuredOrder() throws IOException {
        assertEquals(List.of("jumbo", "carrefour", "masonline", "josimar", "cordiez", "disco", "vea"),
                StoreList.load().stream().map(StoreList.Entry::id).toList());
    }
}
