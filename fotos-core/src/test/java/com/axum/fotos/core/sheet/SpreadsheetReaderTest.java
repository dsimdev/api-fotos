package com.axum.fotos.core.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpreadsheetReaderTest {

    @TempDir
    Path dir;

    private static final String NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";

    private Path xlsx(String sharedStringsXml, String sheetData) throws IOException {
        Path file = dir.resolve("planilla.xlsx");
        try (OutputStream out = Files.newOutputStream(file); var zip = new ZipOutputStream(out)) {
            put(zip, "xl/workbook.xml", "<workbook " + NS + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Hoja1\" sheetId=\"1\" r:id=\"rId7\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId7\" Type=\"worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            if (sharedStringsXml != null) put(zip, "xl/sharedStrings.xml", sharedStringsXml);
            put(zip, "xl/worksheets/sheet1.xml", "<worksheet " + NS + "><sheetData>" + sheetData + "</sheetData></worksheet>");
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sst(String... strings) {
        var sb = new StringBuilder("<sst " + NS + ">");
        for (String s : strings) sb.append("<si><t xml:space=\"preserve\">").append(s).append("</t></si>");
        return sb.append("</sst>").toString();
    }

    @Test
    void readsTextAndNumberCellsTheWayAPersonWouldReadThem() throws IOException {
        // Like the real client file: the SKU is a number in some rows and text in others, the EAN is a number.
        var file = xlsx(sst("Descripción", "SKU", "EAN", "LS SACHET 900GR", "0609400", "ROTO"),
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\"><v>640008</v></c><c r=\"C2\"><v>7791337010604</v></c></row>"
                        + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"B3\" t=\"s\"><v>4</v></c><c r=\"C3\"><v>7.791337010604E12</v></c></row>"
                        + "<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>Inline</t></is></c><c r=\"B4\"><v>12.0</v></c><c r=\"C4\"><v>7791337010604</v></c></row>");

        var rows = SpreadsheetReader.read(file);

        assertEquals(3, rows.size());
        assertEquals(new ProductRow(2, "LS SACHET 900GR", "640008", "7791337010604"), rows.get(0));
        assertEquals("0609400", rows.get(1).sku(), "a text SKU keeps its leading zero");
        assertEquals("7791337010604", rows.get(1).ean(), "an exponent is not what the person sees in Excel");
        assertEquals("Inline", rows.get(2).description());
        assertEquals("12", rows.get(2).sku(), "12.0 is 12");
    }

    @Test
    void columnsAreFoundByTheirHeaderNotByPosition() throws IOException {
        var file = xlsx(sst("Código de Barras", "descripción", "SKU", "7791337010604", "Yogur", "A-1"),
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\" t=\"s\"><v>4</v></c><c r=\"C2\" t=\"s\"><v>5</v></c></row>");

        var rows = SpreadsheetReader.read(file);

        assertEquals(List.of(new ProductRow(2, "Yogur", "A-1", "7791337010604")), rows);
    }

    /** The article export of Axum: "codigo" is the SKU and "codbarra" the EAN, with other columns around them. */
    @Test
    void readsTheArticleExportOfAxumWhereCodigoIsTheSkuAndCodbarraTheEan() throws IOException {
        var file = xlsx(sst("codigo", "descripcion", "linea", "codbarra", "rubro", "1000", "POLVO DESODORANTE X 180 G", "DESODORANTES", "DESODORANTE EN POLVO"),
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c>"
                        + "<c r=\"D1\" t=\"s\"><v>3</v></c><c r=\"E1\" t=\"s\"><v>4</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>5</v></c><c r=\"B2\" t=\"s\"><v>6</v></c><c r=\"C2\" t=\"s\"><v>7</v></c>"
                        + "<c r=\"D2\"><v>7791337010604</v></c><c r=\"E2\" t=\"s\"><v>8</v></c></row>");

        var rows = SpreadsheetReader.read(file);

        assertEquals(List.of(new ProductRow(2, "POLVO DESODORANTE X 180 G", "1000", "7791337010604")), rows);
    }

    @Test
    void theAxumHeadersAreRecognisedInUpperCaseAndInACsvToo() throws IOException {
        var file = dir.resolve("articulos.csv");
        Files.writeString(file, "CODIGO;DESCRIPCION;CODBARRA\r\n1000;Polvo;7791337010604\r\n1001;Sin codigo de barras;\r\n", StandardCharsets.UTF_8);

        var rows = SpreadsheetReader.read(file);

        assertEquals(List.of(new ProductRow(2, "Polvo", "1000", "7791337010604"), new ProductRow(3, "Sin codigo de barras", "1001", "")), rows);
    }

    /**
     * Axum's export has barcodes with non-breaking spaces (U+00A0) before or after the digits: seven real EANs were being
     * rejected as invalid. String.trim() does not remove them. Only whitespace goes: no digit is ever guessed.
     */
    @Test
    void nonBreakingSpacesAroundACellAreWhitespaceToo() throws IOException {
        var file = dir.resolve("articulos.csv");
        Files.writeString(file, "codigo,descripcion,codbarra\n"
                + "\"7000016587\",\"JUGO\",7798085681490   \n"
                + "\"9000004087\",\"AGUA\", 7790250040873\n"
                + "​1001﻿,\"UN ESPACIO FINO \",7791337010604\n", StandardCharsets.UTF_8);

        var rows = SpreadsheetReader.read(file);

        assertEquals("7798085681490", rows.get(0).ean());
        assertEquals("7790250040873", rows.get(1).ean());
        assertEquals("1001", rows.get(2).sku(), "zero-width characters and a BOM in the middle of a file go too");
        assertEquals("UN ESPACIO FINO", rows.get(2).description());
    }

    @Test
    void aMissingColumnIsReportedByNameTogetherWithWhatWasFound() throws IOException {
        var file = xlsx(sst("Descripción", "Precio", "EAN"),
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row>");

        var e = assertThrows(SheetFormatException.class, () -> SpreadsheetReader.read(file));

        assertTrue(e.getMessage().contains("SKU"), e.getMessage());
        assertTrue(e.getMessage().contains("Precio"), "says what it did see: " + e.getMessage());
    }

    @Test
    void blankRowsAreSkippedAndRowNumbersPointAtTheRealLine() throws IOException {
        var file = xlsx(sst("SKU", "EAN"),
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"/>"
                        + "<row r=\"5\"><c r=\"A5\"><v>10</v></c><c r=\"B5\"><v>7791337010604</v></c></row>");

        var rows = SpreadsheetReader.read(file);

        assertEquals(1, rows.size());
        assertEquals("10", rows.get(0).sku());
        assertEquals("", rows.get(0).description(), "the description column is optional");
    }

    /** A client file can carry an XML entity that points at a local file. It must never end up in the rows. */
    @Test
    void aFileThatTriesToReadALocalFileThroughAnXmlEntityLeaksNothing() throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "SECRET-MARKER");
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE sst [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
                + "<sst " + NS + "><si><t>SKU</t></si><si><t>EAN</t></si><si><t>&xxe;</t></si></sst>";
        var file = xlsx(evil,
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>7791337010604</v></c></row>");

        String seen;
        try {
            seen = SpreadsheetReader.read(file).toString();
        } catch (IOException refused) {
            seen = String.valueOf(refused.getMessage());
        }

        assertFalse(seen.contains("SECRET-MARKER"), seen);
    }

    @Test
    void somethingThatIsNotAZipIsRefusedWithAReadableMessage() throws IOException {
        var file = dir.resolve("falso.xlsx");
        Files.writeString(file, "esto no es una planilla");

        var e = assertThrows(SheetFormatException.class, () -> SpreadsheetReader.read(file));

        assertTrue(e.getMessage().contains("xlsx"), e.getMessage());
    }

    @Test
    void anUnknownExtensionIsRefused() {
        assertThrows(SheetFormatException.class, () -> SpreadsheetReader.read(dir.resolve("lista.pdf")));
    }

    @Test
    void readsASemicolonCsvWrittenByASpanishExcelInWindows1252() throws IOException {
        var file = dir.resolve("lista.csv");
        Files.write(file, "Descripción;SKU;EAN\r\nJabón ñandú;123;7791337010604\r\n".getBytes(Charset.forName("windows-1252")));

        var rows = SpreadsheetReader.read(file);

        assertEquals(List.of(new ProductRow(2, "Jabón ñandú", "123", "7791337010604")), rows);
    }

    @Test
    void readsAQuotedCommaCsvWithAByteOrderMark() throws IOException {
        var file = dir.resolve("lista.csv");
        Files.write(file, ("﻿EAN,SKU,Producto\n7791337010604,\"A,1\",\"Yogur \"\"entero\"\"\"\n").getBytes(StandardCharsets.UTF_8));

        var rows = SpreadsheetReader.read(file);

        assertEquals(List.of(new ProductRow(2, "Yogur \"entero\"", "A,1", "7791337010604")), rows);
    }

    @Test
    void theHeaderMayNotBeOnTheFirstLine() throws IOException {
        var file = dir.resolve("lista.csv");
        Files.writeString(file, "Pedido de fotos\n\nSKU,EAN\n77,7791337010604\n");

        var rows = SpreadsheetReader.read(file);

        assertEquals(List.of(new ProductRow(4, "", "77", "7791337010604")), rows);
    }
}
